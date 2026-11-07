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
 * `MediaRecorder` is a final platform class that cannot be constructed on the JVM, so the
 * *ordering* rules that matter here — release even when `stop()` throws, release a recorder whose
 * `prepare()`/`start()` failed, never read the amplitude of a recorder that is being released —
 * could not be covered by a test without this seam (#48). The implementation below holds every
 * platform call and no decisions; the decisions live in [MediaRecorderAudioRecorder] and are unit
 * tested.
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
     * Written by the service's rotation coroutine and read by its amplitude coroutine, on
     * different `Dispatchers.Default` threads. `@Volatile` supplies the happens-before edge those
     * two would otherwise lack, so [maxAmplitude] cannot see a stale reference to a recorder that
     * has already been released — a native use-after-release no `catch` can contain (#48).
     */
    @Volatile
    private var recorder: PlatformRecorder? = null

    override fun start(outputFile: File) {
        val platform = newPlatformRecorder()
        try {
            platform.prepareAndStart(outputFile)
        } catch (e: Throwable) {
            // A recorder that failed to start still holds a native object and, depending on how
            // far it got, the microphone. Nothing else has a reference to it, so releasing here is
            // the only chance there will ever be. `runCatching` so a failing release cannot bury
            // the failure the caller needs to see.
            runCatching { platform.release() }
            throw e
        }
        recorder = platform
    }

    override fun stop() {
        val platform = recorder ?: return
        // Clear the field *before* stopping, so a concurrent amplitude read gets 0 rather than a
        // recorder that is in the middle of being torn down.
        recorder = null
        try {
            platform.stop()
        } finally {
            // `MediaRecorder.stop()` throws on a segment that captured no data — a flush right
            // after a segment opens, or mic contention — and before this `finally` that threw past
            // `release()`, leaking the native recorder and the microphone once per occurrence.
            platform.release()
        }
    }

    override fun maxAmplitude(): Int = recorder?.maxAmplitude() ?: 0

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
