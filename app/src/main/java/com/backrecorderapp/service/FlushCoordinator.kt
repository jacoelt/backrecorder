package com.backrecorderapp.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/**
 * Thrown when a flush request can never be served — the recording service was torn down
 * while the request was queued or in flight (issue #47).
 */
class FlushAbandonedException : IllegalStateException(
    "Recording stopped before the current segment could be finalized"
)

/**
 * The handshake between [RecordingService.flushCurrentSegment] and the service's flush
 * collector.
 *
 * Requests go through an unlimited channel rather than a `SharedFlow`, because a channel
 * buffers with no receiver: a request made before [serveRequests] starts is held and served
 * instead of being dropped, leaving its caller suspended forever (issue #47).
 *
 * The coordinator is process-global (a field of `RecordingService`'s companion), so it
 * outlives any one service instance. Two invariants keep that safe: a request is never
 * served after its caller gave up, and a request is never stranded by teardown.
 */
class FlushCoordinator {

    private val requests = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
    private val outstanding = ConcurrentHashMap.newKeySet<CompletableDeferred<Unit>>()

    /** Set by [abandonAll] before it drains, cleared when a collector takes over. */
    @Volatile
    private var closed = false

    /** Suspends until the flush is served, fails, or is abandoned. */
    suspend fun requestFlush() {
        val deferred = CompletableDeferred<Unit>()
        outstanding.add(deferred)
        try {
            requests.send(deferred)
            // `abandonAll` sets `closed` before draining, so seeing it set here means this
            // request may have arrived after the drain and after the completion pass — with
            // nothing left to serve or fail it. Fail it here instead of waiting for nobody.
            if (closed) deferred.completeExceptionally(FlushAbandonedException())
            deferred.await()
        } finally {
            outstanding.remove(deferred)
            // A caller that gave up — the use case's timeout, or a cancelled scope — cannot
            // take its entry back out of the channel. Cancelling the deferred marks it dead
            // so a collector skips it rather than rotating for a save that is already gone.
            deferred.cancel()
        }
    }

    /**
     * Receives requests forever, running [flush] for each one still being waited on.
     * Cancelling the calling coroutine ends the loop; whatever is left queued is the
     * caller's to [abandonAll].
     */
    suspend fun serveRequests(flush: suspend () -> Unit) {
        closed = false
        for (deferred in requests) {
            if (deferred.isCompleted) continue
            try {
                flush()
                deferred.complete(Unit)
            } catch (e: CancellationException) {
                deferred.completeExceptionally(FlushAbandonedException())
                throw e
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            }
        }
    }

    /**
     * Fails every queued and in-flight request with [cause] and drains the queue, so no
     * request outlives the service instance that was meant to serve it.
     *
     * Removing each request from [outstanding] before completing it, rather than clearing
     * the set afterwards, means a request added concurrently is either completed here or
     * still tracked — never silently dropped.
     */
    fun abandonAll(cause: Throwable) {
        closed = true
        while (requests.tryReceive().isSuccess) {
            // Drained.
        }
        while (true) {
            val deferred = outstanding.firstOrNull() ?: break
            outstanding.remove(deferred)
            deferred.completeExceptionally(cause)
        }
    }

    /** Visible for tests. */
    val outstandingCount: Int get() = outstanding.size
}
