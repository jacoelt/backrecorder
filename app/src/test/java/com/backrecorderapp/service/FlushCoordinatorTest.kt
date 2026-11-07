package com.backrecorderapp.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for issue #47 — a flush request must never be silently dropped or
 * silently abandoned, because the caller ([com.backrecorderapp.domain.SaveRecordingUseCase])
 * suspends on it and the save UI hangs if it never completes. The coordinator is
 * process-global, so the inverse matters too: a request its caller gave up on must not be
 * served later.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlushCoordinatorTest {

    @Test
    fun `request made before the collector starts is still served`() = runTest {
        val coordinator = FlushCoordinator()
        var flushes = 0

        // The window that used to lose requests: the service reports itself running and a
        // save asks for a flush before `serveRequests` is reached.
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
    fun `requests queued while no collector runs are all served`() = runTest {
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
    fun `abandonAll fails a queued request instead of leaving it suspended`() = runTest {
        val coordinator = FlushCoordinator()
        var thrown: Throwable? = null

        val requester = launch { thrown = runCatching { coordinator.requestFlush() }.exceptionOrNull() }
        advanceUntilIdle()
        assertEquals(1, coordinator.outstandingCount)

        coordinator.abandonAll(FlushAbandonedException())
        advanceUntilIdle()

        assertTrue("requester must not stay suspended", requester.isCompleted)
        assertTrue("expected FlushAbandonedException, got $thrown", thrown is FlushAbandonedException)
        assertEquals(0, coordinator.outstandingCount)
    }

    @Test
    fun `abandonAll fails an in-flight request the collector already picked up`() = runTest {
        val coordinator = FlushCoordinator()
        var thrown: Throwable? = null

        // The flush never finishes, so the request is dequeued and in flight — the case the
        // channel drain alone cannot reach, and the reason `outstanding` exists.
        val server = launch { coordinator.serveRequests { awaitCancellation() } }
        val requester = launch { thrown = runCatching { coordinator.requestFlush() }.exceptionOrNull() }
        advanceUntilIdle()
        assertEquals("request should be in flight", 1, coordinator.outstandingCount)

        coordinator.abandonAll(FlushAbandonedException())
        advanceUntilIdle()

        assertTrue("in-flight requester must not stay suspended", requester.isCompleted)
        assertTrue("expected FlushAbandonedException, got $thrown", thrown is FlushAbandonedException)
        assertEquals(0, coordinator.outstandingCount)
        server.cancel()
    }

    @Test
    fun `a request arriving after abandonAll fails instead of being stranded`() = runTest {
        val coordinator = FlushCoordinator()
        var thrown: Throwable? = null

        // Teardown has already drained; without the closed flag this request would sit in
        // the process-global channel with nothing left to serve or fail it.
        coordinator.abandonAll(FlushAbandonedException())
        val requester = launch { thrown = runCatching { coordinator.requestFlush() }.exceptionOrNull() }
        advanceUntilIdle()

        assertTrue("requester must not stay suspended", requester.isCompleted)
        assertTrue("expected FlushAbandonedException, got $thrown", thrown is FlushAbandonedException)
        assertEquals(0, coordinator.outstandingCount)
    }

    @Test
    fun `abandoned requests are not replayed to a later collector`() = runTest {
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
    fun `a request whose caller gave up is skipped rather than flushed`() = runTest {
        val coordinator = FlushCoordinator()
        var flushes = 0

        // What the use case's withTimeout does, and what cancelling the save screen does.
        // The entry stays in the channel, so the collector must recognise it as dead —
        // otherwise the next recording is rotated for a save that already failed.
        val requester = launch { coordinator.requestFlush() }
        advanceUntilIdle()
        requester.cancel()
        advanceUntilIdle()

        val server = launch { coordinator.serveRequests { flushes++ } }
        advanceUntilIdle()

        assertEquals("a request nobody waits on must not cause a rotation", 0, flushes)
        assertEquals(0, coordinator.outstandingCount)
        server.cancel()
    }

    @Test
    fun `the coordinator still works after a teardown and restart`() = runTest {
        val coordinator = FlushCoordinator()
        var flushes = 0

        coordinator.abandonAll(FlushAbandonedException())

        // A second recording session in the same process gets a fresh collector but the same
        // coordinator, and must behave exactly like the first.
        val server = launch { coordinator.serveRequests { flushes++ } }
        advanceUntilIdle()
        coordinator.requestFlush()

        assertEquals(1, flushes)
        assertEquals(0, coordinator.outstandingCount)
        server.cancel()
    }

    @Test
    fun `a failing flush fails its request rather than dropping it`() = runTest {
        val coordinator = FlushCoordinator()
        var thrown: Throwable? = null

        val server = launch { coordinator.serveRequests { throw IllegalStateException("boom") } }
        val requester = launch { thrown = runCatching { coordinator.requestFlush() }.exceptionOrNull() }
        advanceUntilIdle()

        assertTrue(requester.isCompleted)
        assertEquals("boom", thrown?.message)
        assertEquals(0, coordinator.outstandingCount)
        server.cancel()
    }
}
