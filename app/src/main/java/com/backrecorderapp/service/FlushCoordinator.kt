package com.backrecorderapp.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/**
 * Thrown when a flush request can never be served — the recording service was
 * torn down while the request was queued or in flight. Surfaces to the user as
 * a save error rather than a hang (issue #47).
 */
class FlushAbandonedException : IllegalStateException(
    "Recording stopped before the current segment could be finalized"
)

/**
 * The handshake between [RecordingService.flushCurrentSegment] and the service's
 * flush collector.
 *
 * Requests go through an **unlimited channel**, not a `SharedFlow`: a channel buffers
 * regardless of whether anyone is receiving yet, so a request made in the window
 * between the service reporting itself running and [serveRequests] actually starting
 * is held and served, instead of being dropped into a subscriber-less flow and leaving
 * its caller suspended forever (issue #47, window 1).
 *
 * Every request is also tracked in [outstanding] so that teardown can complete the
 * queued and in-flight ones exceptionally via [abandonAll] (issue #47, window 2).
 */
class FlushCoordinator {

    private val requests = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
    private val outstanding = ConcurrentHashMap.newKeySet<CompletableDeferred<Unit>>()

    /** Requests a flush and suspends until it is served, fails, or is abandoned. */
    suspend fun requestFlush() {
        val deferred = CompletableDeferred<Unit>()
        outstanding.add(deferred)
        try {
            requests.send(deferred)
            deferred.await()
        } finally {
            outstanding.remove(deferred)
        }
    }

    /**
     * Receives requests forever, running [flush] for each. Cancellation of the calling
     * coroutine ends the loop; whatever is left queued is the caller's to [abandonAll].
     */
    suspend fun serveRequests(flush: suspend () -> Unit) {
        for (deferred in requests) {
            try {
                flush()
                deferred.complete(Unit)
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            }
        }
    }

    /**
     * Completes every queued and in-flight request exceptionally with [cause] and drains
     * the queue, so no request outlives the service instance that was meant to serve it.
     *
     * Completing an already-completed request is a no-op, so this is safe to call
     * alongside a flush that happens to finish at the same moment.
     */
    fun abandonAll(cause: Throwable) {
        while (requests.tryReceive().isSuccess) {
            // Drained; the deferred is completed below via `outstanding`.
        }
        outstanding.forEach { it.completeExceptionally(cause) }
        outstanding.clear()
    }

    /** Number of requests currently queued or in flight. Visible for tests. */
    val outstandingCount: Int get() = outstanding.size
}
