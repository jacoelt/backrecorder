package com.backrecorder.services

import android.app.*
import android.content.Intent
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import android.os.Handler
import android.util.Log
import androidx.core.app.NotificationCompat
import com.backrecorder.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.properties.Delegates


class AudioRecordingService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private lateinit var outputDir: File
    private var maxDurationMinutes by Delegates.notNull<Int>()
    private val fileList = mutableListOf<File>()
    private lateinit var looper: Handler

    private val binder = LocalBinder()
    private var isRecording = false  // Track recording status
    private var currentDurationCallback: ((duration: Int) -> Unit)? = null

    companion object {
        const val CHANNELS = 1
        const val SAMPLING_RATE = 16000
        const val BIT_RATE = 32000
        const val AUDIO_RECORDINGS_DIR = "audio_recordings"
        const val DURATION_KEY = "duration"
        const val DEFAULT_DURATION = 10
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioRecordingService = this@AudioRecordingService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("AudioRecordingService", "Created")
        outputDir = File(externalCacheDir, AUDIO_RECORDINGS_DIR).apply { mkdirs() }
        looper = Handler(mainLooper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("AudioRecordingService", "Started")
        maxDurationMinutes = intent?.getIntExtra(DURATION_KEY, DEFAULT_DURATION)!!
        startForegroundService()
        startNewRecording()
        return START_STICKY
    }

    private fun startForegroundService() {
        val notificationChannelId = "AudioRecordingChannel"
        val channel = NotificationChannel(
            notificationChannelId,
            getString(R.string.audio_recording_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(1, notification)
    }

    private fun startNewRecording() {
        Log.d("AudioRecordingService", "Start new recording")

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val audioFile = File(outputDir, "record_$timeStamp.ogg")
        fileList.add(audioFile)

        cleanOldFiles()

        if (mediaRecorder == null) {
            mediaRecorder = MediaRecorder(this)
        }

        mediaRecorder?.apply {
            reset()
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setAudioChannels(CHANNELS)
            setAudioSamplingRate(SAMPLING_RATE)
            setAudioEncodingBitRate(BIT_RATE)
            setOutputFile(audioFile.absolutePath)
            prepare()
            start()
        }
        isRecording = true

        // Restart recording after 1 minute
        looper.postDelayed({ startNewRecording() }, 60_000)

        if (currentDurationCallback != null) {
            currentDurationCallback?.let { it((fileList.size - 1).coerceAtLeast(0)) }
        }
    }

    private fun cleanOldFiles() {
        while (fileList.size > maxDurationMinutes) {
            fileList.removeAt(0).delete()
        }
    }

    override fun onDestroy() {
        Log.d("AudioRecordingService", "Destroy")
        cleanupTemporaryFiles()
        stopRecording()
        super.onDestroy()
    }

    private fun cleanupTemporaryFiles() {
        outputDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.delete()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun isRecording(): Boolean {
        return isRecording
    }

    fun getFileList(): MutableList<File> {
        return fileList
    }

    fun stopRecording() {
        Log.d("AudioRecordingService", "stopRecording")
        looper.removeCallbacksAndMessages(null)
        mediaRecorder?.apply {
            reset()
            release()
        }
        mediaRecorder = null
        isRecording = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        fileList.removeAll(fileList)
    }

    fun registerCallbackCurrentDuration(callback: (duration: Int) -> Unit) {
        currentDurationCallback = callback
        if (isRecording) {
            currentDurationCallback?.let { it((fileList.size - 1).coerceAtLeast(0)) }
        }
    }
}
