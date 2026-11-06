package com.backrecorderapp.service.recorder

import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands [RecordingService][com.backrecorderapp.service.RecordingService] a [FakeAudioRecorder] per
 * segment and lets a test observe the recorder lifecycle from outside the service.
 *
 * The service opens and finalizes recorders on its own coroutine scope, so a test has no
 * synchronous hook into either event. The await helpers supply one: [awaitRecorder] blocks until
 * the service has a segment open and ready to accept audio, and [awaitFinalizedFiles] blocks until
 * a given number of segments have been closed — which is what makes the
 * `RecordingFlusher.flushCurrentSegment()` handshake observable rather than inferred.
 */
@Singleton
class FakeAudioRecorderFactory @Inject constructor() : AudioRecorderFactory {

    private val lock = Object()
    private val started = mutableListOf<FakeAudioRecorder>()
    private val finalized = mutableListOf<File>()

    override fun create(): AudioRecorder = FakeAudioRecorder(
        onStarted = { recorder ->
            synchronized(lock) {
                started += recorder
                lock.notifyAll()
            }
        },
        onFinalized = { file ->
            synchronized(lock) {
                finalized += file
                lock.notifyAll()
            }
        }
    )

    /**
     * The recorder for the [index]-th segment the service opened, waiting for it if necessary. It
     * has already been started, so [FakeAudioRecorder.feed] is safe to call on it.
     */
    fun awaitRecorder(index: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): FakeAudioRecorder =
        synchronized(lock) {
            awaitLocked(timeoutMs) { started.size > index }
            started[index]
        }

    /** The files of the first [count] segments the service finalized, waiting for them. */
    fun awaitFinalizedFiles(count: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<File> =
        synchronized(lock) {
            awaitLocked(timeoutMs) { finalized.size >= count }
            finalized.take(count)
        }

    /** Segments started so far. Only a snapshot — use [awaitRecorder] to wait for one. */
    fun startedCount(): Int = synchronized(lock) { started.size }

    /** Segments finalized so far. Only a snapshot — use [awaitFinalizedFiles] to wait for them. */
    fun finalizedCount(): Int = synchronized(lock) { finalized.size }

    /** Drops the recorded history, so one test's segments cannot satisfy another's await. */
    fun reset() = synchronized(lock) {
        started.clear()
        finalized.clear()
    }

    private fun awaitLocked(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            check(remainingMs > 0) {
                "Timed out after ${timeoutMs}ms waiting on the recording service " +
                    "(segments started=${started.size}, finalized=${finalized.size})"
            }
            lock.wait(remainingMs)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
    }
}
