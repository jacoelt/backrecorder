package com.backrecorderapp.service.recorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * The production [AudioRecorder]: the `MediaRecorder` configuration the service has always used,
 * moved behind the interface unchanged (MPEG-4 container, AAC, 16 kHz mono at 32 kbps).
 */
class MediaRecorderAudioRecorder(private val context: Context) : AudioRecorder {

    private var recorder: MediaRecorder? = null

    override fun start(outputFile: File) {
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(SAMPLE_RATE_HZ)
            setAudioEncodingBitRate(BIT_RATE)
            setAudioChannels(CHANNEL_COUNT)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
        recorder = mediaRecorder
    }

    override fun stop() {
        recorder?.apply {
            stop()
            release()
        }
        recorder = null
    }

    override fun maxAmplitude(): Int = recorder?.maxAmplitude ?: 0

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val BIT_RATE = 32_000
        const val CHANNEL_COUNT = 1
    }
}

class MediaRecorderAudioRecorderFactory @Inject constructor(
    @ApplicationContext private val context: Context
) : AudioRecorderFactory {
    override fun create(): AudioRecorder = MediaRecorderAudioRecorder(context)
}
