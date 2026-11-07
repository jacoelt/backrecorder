package com.backrecorderapp.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

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
 * outlives any one service instance. Three invariants keep that safe: a request is never
 * served after its caller gave up, never stranded by teardown, and never attributed to the
 * wrong service instance.
 */
class FlushCoordinator {

    /**
     * Which service instance the coordinator is serving, and whether it is accepting.
     * A plain flag would not do: [requestFlush] has to check *after* it enqueues, and
     * between its read and that check a whole teardown-and-restart can happen, which a
     * boolean would have flipped back to its original value.
     */
    private data class Epoch(val id: Long, val accepting: Boolean)

    private val requests = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
    private val outstanding = ConcurrentHashMap.newKeySet<CompletableDeferred<Unit>>()
    private val epoch = AtomicReference(Epoch(id = 0, accepting = true))

    /**
     * Starts accepting requests for a new service instance.
     *
     * The service must call this as it starts, not when [serveRequests] begins, or the very
     * window #47 closes — between the service reporting itself running and its collector
     * launching — would reject requests for every recording session after the first.
     */
    fun reopen() {
        epoch.updateAndGet { Epoch(it.id + 1, accepting = true) }
    }

    /** Suspends until the flush is served, fails, or is abandoned. */
    suspend fun requestFlush() {
        val sentAt = epoch.get()
        val deferred = CompletableDeferred<Unit>()
        outstanding.add(deferred)
        try {
            requests.send(deferred)
            // Nothing will serve this request if the coordinator was not accepting when we
            // read, or if teardown ran while we were enqueueing — `abandonAll` may already
            // have drained past us. Fail it here rather than wait for a collector that is
            // gone, or let the next service instance rotate for a save that is over.
            if (!sentAt.accepting || epoch.get() != sentAt) {
                deferred.completeExceptionally(FlushAbandonedException())
            }
            deferred.await()
        } finally {
            outstanding.remove(deferred)
            // A caller that gave up — the use case's timeout, or a cancelled save screen —
            // cannot take its entry back out of the channel. Cancelling the deferred marks
            // it dead so a collector skips it rather than rotating for a save already gone.
            deferred.cancel()
        }
    }

    /**
     * Receives requests forever, running [flush] for each one still being waited on.
     * Cancelling the calling coroutine ends the loop; whatever is left queued is the
     * caller's to [abandonAll].
     */
    suspend fun serveRequests(flush: suspend () -> Unit) {
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
        epoch.updateAndGet { Epoch(it.id + 1, accepting = false) }
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
