package com.backrecorderapp.service.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The lifecycle contract of [MediaRecorderAudioRecorder]: whatever happens, the native recorder
 * and the microphone it holds are released exactly once, and nothing reads the amplitude of a
 * recorder that is going away (#48).
 *
 * These run against [PlatformRecorder] rather than `MediaRecorder`, which cannot be constructed on
 * the JVM. What that leaves uncovered, stated plainly: the `MediaRecorderPlatformRecorder` wrapper
 * (one delegating call per method, no decisions), and the *memory visibility* the
 * `AtomicReference` provides — a JVM test cannot deterministically produce a stale read, so
 * `concurrent stops release the recorder exactly once` pins the atomicity half and visibility
 * rests on review.
 */
class MediaRecorderAudioRecorderTest {

    private val outputFile = File("segment.m4a")

    @Test
    fun `stop releases the recorder`() {
        val platform = FakePlatformRecorder()
        val recorder = MediaRecorderAudioRecorder { platform }

        recorder.start(outputFile)
        recorder.stop()

        assertEquals(listOf("prepareAndStart", "stop", "release"), platform.calls)
    }

    /**
     * Regression guard for #48: `stop()` throwing must not skip `release()`. `MediaRecorder.stop()`
     * throws on a segment that captured no data, and with both calls in one `apply` the throw
     * stranded the native recorder and the microphone for the life of the process.
     */
    @Test
    fun `stop releases the recorder even when the platform stop throws`() {
        val platform = FakePlatformRecorder(stopFailure = RuntimeException("stop failed: -1007"))
        val recorder = MediaRecorderAudioRecorder { platform }
        recorder.start(outputFile)

        val thrown = assertThrows(RuntimeException::class.java) { recorder.stop() }

        assertEquals("stop failed: -1007", thrown.message)
        assertTrue("stop() threw past release()", platform.released)
        assertEquals(listOf("prepareAndStart", "stop", "release"), platform.calls)
    }

    /**
     * The empty-segment diagnostic is the one the caller needs, so a release that also fails is
     * attached to it rather than thrown in its place.
     */
    @Test
    fun `stop reports the stop failure and suppresses a release failure`() {
        val releaseFailure = RuntimeException("release failed")
        val platform = FakePlatformRecorder(
            stopFailure = RuntimeException("stop failed: -1007"),
            releaseFailure = releaseFailure
        )
        val recorder = MediaRecorderAudioRecorder { platform }
        recorder.start(outputFile)

        val thrown = assertThrows(RuntimeException::class.java) { recorder.stop() }

        assertEquals("stop failed: -1007", thrown.message)
        assertSame(releaseFailure, thrown.suppressed.single())
    }

    /** A failed `prepare()`/`start()` must still release the recorder it constructed (#48). */
    @Test
    fun `a failed start releases the recorder it constructed`() {
        val platform = FakePlatformRecorder(startFailure = IOException("prepare failed"))
        val recorder = MediaRecorderAudioRecorder { platform }

        assertThrows(IOException::class.java) { recorder.start(outputFile) }

        assertTrue("A recorder that failed to start was never released", platform.released)
        assertEquals(listOf("prepareAndStart", "release"), platform.calls)
    }

    @Test
    fun `a failed start reports the start failure and suppresses a release failure`() {
        val releaseFailure = RuntimeException("release failed")
        val platform = FakePlatformRecorder(
            startFailure = IOException("prepare failed"),
            releaseFailure = releaseFailure
        )
        val recorder = MediaRecorderAudioRecorder { platform }

        val thrown = assertThrows(IOException::class.java) { recorder.start(outputFile) }

        assertEquals("prepare failed", thrown.message)
        assertSame(releaseFailure, thrown.suppressed.single())
    }

    @Test
    fun `a failed start leaves no recorder behind to be read`() {
        val platform = FakePlatformRecorder(startFailure = IOException("prepare failed"))
        val recorder = MediaRecorderAudioRecorder { platform }

        assertThrows(IOException::class.java) { recorder.start(outputFile) }

        assertEquals(0, recorder.maxAmplitude())
    }

    @Test
    fun `maxAmplitude is zero before a start and after a stop`() {
        val platform = FakePlatformRecorder()
        val recorder = MediaRecorderAudioRecorder { platform }

        assertEquals(0, recorder.maxAmplitude())
        recorder.start(outputFile)
        assertEquals(FakePlatformRecorder.AMPLITUDE, recorder.maxAmplitude())
        recorder.stop()
        assertEquals(0, recorder.maxAmplitude())
    }

    /** `maxAmplitude()` mid-teardown must not reach the recorder. */
    @Test
    fun `maxAmplitude reads nothing once teardown has begun`() {
        lateinit var recorder: MediaRecorderAudioRecorder
        var amplitudeDuringStop = -1
        val platform =
            FakePlatformRecorder(onStop = { amplitudeDuringStop = recorder.maxAmplitude() })
        recorder = MediaRecorderAudioRecorder { platform }

        recorder.start(outputFile)
        recorder.stop()

        assertEquals(0, amplitudeDuringStop)
    }

    /** `onDestroy` and a rotation can both reach `stop()`; the second must not double-release. */
    @Test
    fun `a second stop is a no-op`() {
        val platform = FakePlatformRecorder()
        val recorder = MediaRecorderAudioRecorder { platform }

        recorder.start(outputFile)
        recorder.stop()
        recorder.stop()

        assertEquals(1, platform.calls.count { it == "release" })
    }

    /**
     * The guard the sequential test above cannot give: `onDestroy` stops the recorder on the main
     * thread while a rotation may be inside its own stop on `Dispatchers.Default`. A read-then-
     * clear lets both see the same recorder and release it twice, which aborts natively — so the
     * claim has to be one atomic step.
     *
     * Repeated, because one interleaving proves nothing: a racing pair that happened to serialize
     * would pass against the broken version too.
     */
    @Test
    fun `concurrent stops release the recorder exactly once`() {
        repeat(CONCURRENT_ATTEMPTS) {
            val releases = AtomicInteger()
            val platform = FakePlatformRecorder(onRelease = { releases.incrementAndGet() })
            val recorder = MediaRecorderAudioRecorder { platform }
            recorder.start(outputFile)

            val startLine = CyclicBarrier(2)
            val stoppers = List(2) {
                Thread {
                    startLine.await(AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
                    recorder.stop()
                }.apply { start() }
            }
            stoppers.forEach { it.join(TimeUnit.SECONDS.toMillis(AWAIT_TIMEOUT_S)) }

            assertEquals("Attempt $it released more than once", 1, releases.get())
        }
    }

    /** Rotation starts a fresh recorder after a stop that threw, so the stop must not wedge it. */
    @Test
    fun `a recorder is reusable after a stop that threw`() {
        val first = FakePlatformRecorder(stopFailure = RuntimeException("stop failed: -1007"))
        val second = FakePlatformRecorder()
        val platforms = ArrayDeque(listOf<PlatformRecorder>(first, second))
        val recorder = MediaRecorderAudioRecorder { platforms.removeFirst() }

        recorder.start(outputFile)
        assertThrows(RuntimeException::class.java) { recorder.stop() }

        recorder.start(outputFile)
        recorder.stop()

        assertEquals(listOf("prepareAndStart", "stop", "release"), second.calls)
    }

    /**
     * One instance records one segment ([AudioRecorder]). Overwriting the field would strand the
     * first recorder with nothing left to release it, so a second start is refused and the
     * recorder it had just constructed is released on the way out.
     */
    @Test
    fun `a second start is refused and releases what it constructed`() {
        val first = FakePlatformRecorder()
        val second = FakePlatformRecorder()
        val platforms = ArrayDeque(listOf<PlatformRecorder>(first, second))
        val recorder = MediaRecorderAudioRecorder { platforms.removeFirst() }
        recorder.start(outputFile)

        assertThrows(IllegalStateException::class.java) { recorder.start(outputFile) }

        assertTrue("The refused recorder was left unreleased", second.released)
        assertTrue("The live recorder was released by the refusal", !first.released)
        assertEquals(FakePlatformRecorder.AMPLITUDE, recorder.maxAmplitude())
    }

    @Test
    fun `stop without a start does nothing`() {
        val platform = FakePlatformRecorder()
        val recorder = MediaRecorderAudioRecorder { platform }

        recorder.stop()

        assertEquals(emptyList<String>(), platform.calls)
    }

    private class FakePlatformRecorder(
        private val startFailure: Throwable? = null,
        private val stopFailure: Throwable? = null,
        private val releaseFailure: Throwable? = null,
        private val onStop: () -> Unit = {},
        private val onRelease: () -> Unit = {}
    ) : PlatformRecorder {

        /** Synchronized because the concurrent-stop test drives one instance from two threads. */
        val calls: List<String> get() = synchronized(recorded) { recorded.toList() }

        @Volatile
        var released = false
            private set

        private val recorded = mutableListOf<String>()

        override fun prepareAndStart(outputFile: File) {
            record("prepareAndStart")
            startFailure?.let { throw it }
        }

        override fun stop() {
            record("stop")
            onStop()
            stopFailure?.let { throw it }
        }

        override fun release() {
            record("release")
            released = true
            onRelease()
            releaseFailure?.let { throw it }
        }

        override fun maxAmplitude(): Int = AMPLITUDE

        private fun record(call: String) = synchronized(recorded) { recorded += call }

        companion object {
            const val AMPLITUDE = 12_345
        }
    }

    private companion object {
        /** Enough interleavings that a read-then-clear loses the race at least once. */
        const val CONCURRENT_ATTEMPTS = 2_000
        const val AWAIT_TIMEOUT_S = 10L
    }
}
