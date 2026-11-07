package com.backrecorderapp.service.recorder

import java.io.File
import java.util.concurrent.CountDownLatch
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
    private var failure: Throwable? = null
    private var nextStartFailure: Throwable? = null
    private var startGate: CountDownLatch? = null
    private var held = 0

    /**
     * Makes the next [create] hand back a recorder whose `start()` throws, the way a real
     * `MediaRecorder` does when another app holds the microphone. One-shot: the recorder after it
     * behaves normally, so a test can prove what the service does with a single failure.
     */
    fun failNextStart(cause: Throwable = IllegalStateException("start failed")) =
        synchronized(lock) { nextStartFailure = cause }

    override fun create(): AudioRecorder {
        val startFailure = synchronized(lock) { nextStartFailure.also { nextStartFailure = null } }
        if (startFailure != null) return FailingAudioRecorder(startFailure)
        return createWorking()
    }

    /** An [AudioRecorder] that refuses to open. It never reaches the started/finalized books. */
    private class FailingAudioRecorder(private val cause: Throwable) : AudioRecorder {
        override fun start(outputFile: File): Unit = throw cause
        override fun stop() = Unit
        override fun maxAmplitude(): Int = 0
    }

    /**
     * Holds every later [create]d recorder inside `start()` until the returned latch is counted
     * down, and counts [startsHeld] as they arrive.
     *
     * This is what makes a teardown that lands mid-rotation a *deterministic* test rather than a
     * race a test can only hope to hit: the rotation parks in a known place while the service is
     * destroyed. Always release the latch in a `finally`, or the service never stops.
     */
    fun holdStarts(): CountDownLatch = CountDownLatch(1).also {
        synchronized(lock) { startGate = it }
    }

    /** How many recorders are parked in `start()` by [holdStarts]. */
    fun startsHeld(): Int = synchronized(lock) { held }

    private fun awaitStartGate() {
        val gate = synchronized(lock) {
            startGate ?: return
        }
        synchronized(lock) {
            held++
            lock.notifyAll()
        }
        try {
            check(gate.await(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "A held start was never released; the test must count the latch down"
            }
        } finally {
            synchronized(lock) { held = (held - 1).coerceAtLeast(0) }
        }
    }

    private fun createWorking(): AudioRecorder = FakeAudioRecorder(
        onStarting = ::awaitStartGate,
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
        },
        onFailed = { error ->
            synchronized(lock) {
                if (failure == null) failure = error
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

    /** True once every segment the service opened has also been closed. */
    fun isSettled(): Boolean = synchronized(lock) { started.size == finalized.size }

    /** Drops the recorded history, so one test's segments cannot satisfy another's await. */
    fun reset() = synchronized(lock) {
        started.clear()
        nextStartFailure = null
        startGate?.countDown()
        startGate = null

        finalized.clear()
        failure = null
    }

    private fun awaitLocked(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            // The service swallows whatever a recorder throws, so surface it here instead of
            // letting the real failure arrive as an unexplained timeout.
            failure?.let { throw AssertionError("A segment failed to finalize", it) }
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
