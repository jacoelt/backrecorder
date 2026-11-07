package com.backrecorderapp.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression tests for issue #47 — a flush request must never be silently dropped
 * or silently abandoned, because the caller ([com.backrecorderapp.domain.SaveRecordingUseCase])
 * suspends on it and the save UI hangs if it never completes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlushCoordinatorTest {

    @Test
    fun `request made before the collector starts is still served`() = runTest {
        val coordinator = FlushCoordinator()
        var flushes = 0

        // The window that used to lose requests: the service reports itself running
        // and a save asks for a flush before `serveRequests` is reached.
        val requester = launch { coordinator.requestFlush() }
        advanceUntilIdle()
        assertEquals("request should be queued, not served yet", 1, coordinator.outstandingCount)

        val server = launch { coordinator.serveRequests { flushes++ } }
        advanceUntilIdle()

        assertTrue("the late collector should have served the queued request", requester.isCompleted)
        assertEquals(1, flushes)
        assertEquals(0, coordinator.outstandingCount)
        server.cancel()
    }

    @Test
    fun `requests queued while no collector runs are all served in order`() = runTest {
        val coordinator = FlushCoordinator()
        var flushes = 0

        val requesters = List(3) { launch { coordinator.requestFlush() } }
        advanceUntilIdle()
        assertEquals(3, coordinator.outstandingCount)

        val server = launch { coordinator.serveRequests { flushes++ } }
        advanceUntilIdle()

        assertTrue(requesters.all { it.isCompleted })
        assertEquals(3, flushes)
        server.cancel()
    }

    @Test
    fun `abandonAll fails queued requests instead of leaving them suspended`() = runTest {
        val coordinator = FlushCoordinator()
        val thrown = CompletableDeferred<Throwable>()

        val requester = launch {
            try {
                coordinator.requestFlush()
                fail("requestFlush should not return normally after abandonAll")
            } catch (e: Throwable) {
                thrown.complete(e)
            }
        }
        advanceUntilIdle()
        assertEquals(1, coordinator.outstandingCount)

        coordinator.abandonAll(FlushAbandonedException())
        advanceUntilIdle()

        assertTrue("requester must not stay suspended", requester.isCompleted)
        assertTrue(thrown.getCompleted() is FlushAbandonedException)
        assertEquals(0, coordinator.outstandingCount)
    }

    @Test
    fun `abandoned requests are drained so a later collector does not serve them`() = runTest {
        val coordinator = FlushCoordinator()
        var flushes = 0

        val requester = launch { runCatching { coordinator.requestFlush() } }
        advanceUntilIdle()
        coordinator.abandonAll(FlushAbandonedException())
        advanceUntilIdle()
        assertTrue(requester.isCompleted)

        val server = launch { coordinator.serveRequests { flushes++ } }
        advanceUntilIdle()

        assertEquals("the abandoned request should not be replayed", 0, flushes)
        server.cancel()
    }

    @Test
    fun `a failing flush fails its request rather than dropping it`() = runTest {
        val coordinator = FlushCoordinator()
        val thrown = CompletableDeferred<Throwable>()

        val server = launch { coordinator.serveRequests { throw IllegalStateException("boom") } }
        val requester = launch {
            try {
                coordinator.requestFlush()
                fail("requestFlush should propagate the flush failure")
            } catch (e: Throwable) {
                thrown.complete(e)
            }
        }
        advanceUntilIdle()

        assertTrue(requester.isCompleted)
        assertEquals("boom", thrown.getCompleted().message)
        assertEquals(0, coordinator.outstandingCount)
        server.cancel()
    }

    @Test
    fun `a served request completes well within the use case flush timeout`() = runTest {
        val coordinator = FlushCoordinator()
        val server = launch { coordinator.serveRequests { /* instant */ } }

        withTimeout(1_000) { coordinator.requestFlush() }

        server.cancel()
    }
}
