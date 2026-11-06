package com.backrecorderapp.service.recorder

import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands [RecordingService][com.backrecorderapp.service.RecordingService] a [FakeAudioRecorder] per
 * segment and lets the test observe the recorder lifecycle from outside the service.
 *
 * The service creates and finalizes recorders on its own coroutine scope, so a test has no
 * synchronous hook into either event. The await helpers here supply one: [awaitRecorder] blocks
 * until the service has opened a segment (so the test knows what to advance), and
 * [awaitFinalizedFiles] blocks until a given number of segments have been closed — which is what
 * makes the `RecordingFlusher.flushCurrentSegment()` handshake observable rather than inferred.
 */
@Singleton
class FakeAudioRecorderFactory @Inject constructor() : AudioRecorderFactory {

    private val lock = Object()
    private val created = mutableListOf<FakeAudioRecorder>()
    private val finalized = mutableListOf<File>()

    override fun create(): AudioRecorder {
        val recorder = FakeAudioRecorder(onFinalized = { file ->
            synchronized(lock) {
                finalized += file
                lock.notifyAll()
            }
        })
        synchronized(lock) {
            created += recorder
            lock.notifyAll()
        }
        return recorder
    }

    /** The recorder for the [index]-th segment the service opened, waiting for it if necessary. */
    fun awaitRecorder(index: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): FakeAudioRecorder =
        synchronized(lock) {
            awaitLocked(timeoutMs) { created.size > index }
            created[index]
        }

    /** The files of the first [count] segments the service finalized, waiting for them. */
    fun awaitFinalizedFiles(count: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<File> =
        synchronized(lock) {
            awaitLocked(timeoutMs) { finalized.size >= count }
            finalized.take(count)
        }

    /** Segments opened so far. Only a snapshot — use [awaitRecorder] to wait for one. */
    fun createdCount(): Int = synchronized(lock) { created.size }

    /** Segments finalized so far. Only a snapshot — use [awaitFinalizedFiles] to wait for them. */
    fun finalizedCount(): Int = synchronized(lock) { finalized.size }

    /** Drops the recorded history, so one test's segments cannot satisfy another's await. */
    fun reset() = synchronized(lock) {
        created.clear()
        finalized.clear()
    }

    private fun awaitLocked(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            check(remainingMs > 0) {
                "Timed out after ${timeoutMs}ms waiting on the recording service " +
                    "(segments opened=${created.size}, finalized=${finalized.size})"
            }
            lock.wait(remainingMs)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
    }
}
