package com.backrecorderapp.service.recorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * The slice of `MediaRecorder` that [MediaRecorderAudioRecorder] drives.
 *
 * `MediaRecorder` cannot be constructed on the JVM, so this seam is what lets the release ordering
 * be unit tested (#48). Implementations hold platform calls only, no decisions.
 */
internal interface PlatformRecorder {
    /** Configures the recorder for [outputFile] and begins capturing. */
    fun prepareAndStart(outputFile: File)

    /** Finalizes the output file. May throw — `MediaRecorder.stop()` does, on an empty segment. */
    fun stop()

    /** Frees the native recorder and the microphone. Must run even after [stop] threw. */
    fun release()

    fun maxAmplitude(): Int
}

/**
 * Production [AudioRecorder], backed by `MediaRecorder`: MPEG-4 container, AAC, 16 kHz mono at
 * 32 kbps.
 */
class MediaRecorderAudioRecorder internal constructor(
    private val newPlatformRecorder: () -> PlatformRecorder
) : AudioRecorder {

    constructor(context: Context) : this({ MediaRecorderPlatformRecorder(context) })

    /**
     * Written by the service's rotation coroutine and read by its amplitude coroutine on another
     * `Dispatchers.Default` thread, so [maxAmplitude] would otherwise have no happens-before edge
     * against the write and could reach a released recorder (#48).
     *
     * An `AtomicReference` rather than a `@Volatile` field: visibility is not enough, because
     * [stop] has to read and clear as one step. Two threads can reach [stop] at once — `onDestroy`
     * on the main thread and a rotation on `Dispatchers.Default` — and a plain read-then-clear
     * lets both see the same recorder and release it twice, which aborts natively.
     */
    private val recorder = AtomicReference<PlatformRecorder?>(null)

    override fun start(outputFile: File) {
        val platform = newPlatformRecorder()
        try {
            platform.prepareAndStart(outputFile)
        } catch (e: Throwable) {
            // A recorder that failed to start still holds a native object and, depending on how
            // far it got, the microphone. Nothing else has a reference to it, so releasing here is
            // the only chance there will ever be. The release failure is attached rather than
            // thrown, so it cannot bury the start failure the caller needs to see, and rather than
            // logged, because `android.util.Log` is not mocked in a JVM unit test.
            runCatching { platform.release() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        if (!recorder.compareAndSet(null, platform)) {
            // One instance records one segment (see [AudioRecorder]). Overwriting the field would
            // strand the previous recorder with no path to a release, so refuse instead — and
            // release the one just started, since the caller will not get a reference to it.
            runCatching { platform.release() }
            throw IllegalStateException("MediaRecorderAudioRecorder is single-use; start() was already called")
        }
    }

    override fun stop() {
        // Claim the recorder: whichever caller wins owns the teardown, and any other gets null and
        // returns. This is also what makes a concurrent `maxAmplitude()` read 0 rather than reach
        // a recorder that is about to be released.
        val platform = recorder.getAndSet(null) ?: return
        try {
            platform.stop()
        } catch (e: Throwable) {
            // `MediaRecorder.stop()` throws on a segment that captured no data — a flush right
            // after a segment opens, or mic contention. Before this, `stop()` and `release()` sat
            // in one `apply`, so the throw went past `release()` and leaked the native recorder
            // and the microphone once per occurrence.
            runCatching { platform.release() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        platform.release()
    }

    override fun maxAmplitude(): Int = recorder.get()?.maxAmplitude() ?: 0

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val BIT_RATE = 32_000
        const val CHANNEL_COUNT = 1
    }
}

/** The only part of the recorder that touches the platform. See [PlatformRecorder]. */
private class MediaRecorderPlatformRecorder(context: Context) : PlatformRecorder {

    private val mediaRecorder: MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    override fun prepareAndStart(outputFile: File) {
        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(MediaRecorderAudioRecorder.SAMPLE_RATE_HZ)
            setAudioEncodingBitRate(MediaRecorderAudioRecorder.BIT_RATE)
            setAudioChannels(MediaRecorderAudioRecorder.CHANNEL_COUNT)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
    }

    override fun stop() = mediaRecorder.stop()

    override fun release() = mediaRecorder.release()

    override fun maxAmplitude(): Int = mediaRecorder.maxAmplitude
}

class MediaRecorderAudioRecorderFactory @Inject constructor(
    @ApplicationContext private val context: Context
) : AudioRecorderFactory {
    override fun create(): AudioRecorder = MediaRecorderAudioRecorder(context)
}
