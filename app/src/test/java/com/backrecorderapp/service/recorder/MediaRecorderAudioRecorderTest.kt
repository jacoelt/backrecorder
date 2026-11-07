package com.backrecorderapp.service.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The lifecycle contract of [MediaRecorderAudioRecorder]: whatever happens, the native recorder
 * and the microphone it holds are released exactly once, and nothing reads the amplitude of a
 * recorder that is going away (#48).
 *
 * These run against [PlatformRecorder] rather than `MediaRecorder`, which cannot be constructed on
 * the JVM. The platform wrapper that this seam leaves untested holds no decisions — only the
 * `MediaRecorder` calls themselves.
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
     * The regression guard for the leak: `MediaRecorder.stop()` throws `RuntimeException` on a
     * segment that captured no data — a flush right after a segment opens, or mic contention — and
     * with `stop()` and `release()` in one `apply` the throw went straight past `release()`,
     * stranding the native recorder and the microphone for the life of the process.
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
     * A recorder whose `prepare()` or `start()` failed is already constructed and may already hold
     * the microphone, and nothing else ever gets a reference to it — so `start()` is the only
     * place it can be released.
     */
    @Test
    fun `a failed start releases the recorder it constructed`() {
        val platform = FakePlatformRecorder(startFailure = IOException("prepare failed"))
        val recorder = MediaRecorderAudioRecorder { platform }

        assertThrows(IOException::class.java) { recorder.start(outputFile) }

        assertTrue("A recorder that failed to start was never released", platform.released)
        assertEquals(listOf("prepareAndStart", "release"), platform.calls)
    }

    /** A release that fails on the way out must not replace the failure the caller needs. */
    @Test
    fun `a failed start reports the start failure, not a release failure`() {
        val platform = FakePlatformRecorder(
            startFailure = IOException("prepare failed"),
            releaseFailure = RuntimeException("release failed")
        )
        val recorder = MediaRecorderAudioRecorder { platform }

        val thrown = assertThrows(IOException::class.java) { recorder.start(outputFile) }

        assertEquals("prepare failed", thrown.message)
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

    /**
     * The service's amplitude coroutine runs on its own thread and can read while rotation is
     * stopping the segment. The read must not reach a recorder that is mid-teardown, which is why
     * the field is cleared before `stop()` rather than after `release()`.
     */
    @Test
    fun `maxAmplitude reads nothing once teardown has begun`() {
        lateinit var recorder: MediaRecorderAudioRecorder
        var amplitudeDuringStop = -1
        val platform = FakePlatformRecorder(onStop = { amplitudeDuringStop = recorder.maxAmplitude() })
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
        private val onStop: () -> Unit = {}
    ) : PlatformRecorder {

        val calls = mutableListOf<String>()
        var released = false
            private set

        override fun prepareAndStart(outputFile: File) {
            calls += "prepareAndStart"
            startFailure?.let { throw it }
        }

        override fun stop() {
            calls += "stop"
            onStop()
            stopFailure?.let { throw it }
        }

        override fun release() {
            calls += "release"
            released = true
            releaseFailure?.let { throw it }
        }

        override fun maxAmplitude(): Int = AMPLITUDE

        companion object {
            const val AMPLITUDE = 12_345
        }
    }
}
