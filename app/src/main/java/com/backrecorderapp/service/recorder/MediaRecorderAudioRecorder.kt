package com.backrecorderapp.service.recorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
     * Guards [recorder] for every read and write.
     *
     * A `@Volatile` field is not enough, and neither is an `AtomicReference`. Three threads reach
     * this object: the rotation coroutine starts and stops segments, `onDestroy` stops on the main
     * thread, and the amplitude coroutine reads every 200 ms, both coroutines on
     * `Dispatchers.Default`. Atomically *claiming* the recorder in [stop] would still leave
     * [maxAmplitude] reading the reference and then calling through it in two steps, with the
     * release free to land in between — a native use-after-release no `catch` can contain. One
     * monitor over the reference *and* its use is what actually closes that.
     */
    private val lock = Any()

    private var recorder: PlatformRecorder? = null

    override fun start(outputFile: File) = synchronized(lock) {
        // Refuse before constructing anything: one instance records one segment (see
        // [AudioRecorder]), and a second start would otherwise open the microphone, truncate the
        // output file and only then be rejected — having stolen the mic from the live recorder.
        check(recorder == null) {
            "MediaRecorderAudioRecorder is single-use; start() was already called"
        }

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
        recorder = platform
    }

    override fun stop() = synchronized(lock) {
        // Claim the recorder first: whichever caller wins owns the teardown and any other returns,
        // so `onDestroy` and a rotation cannot both release the same native recorder.
        val platform = recorder ?: return
        recorder = null
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

    override fun maxAmplitude(): Int = synchronized(lock) { recorder?.maxAmplitude() ?: 0 }

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
