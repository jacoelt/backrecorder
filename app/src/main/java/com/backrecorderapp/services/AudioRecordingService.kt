package com.backrecorderapp.services

import android.app.*
import android.content.Intent
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.app.NotificationCompat
import com.backrecorderapp.GDriveHelper
import com.backrecorderapp.MainActivity
import com.backrecorderapp.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.properties.Delegates


class AudioRecordingService : Service() {

    private var gDriveHelper: GDriveHelper? = null
    private var mediaRecorder: MediaRecorder? = null
    private lateinit var outputDir: File
    private var maxDurationMinutes by Delegates.notNull<Int>()
    private val fileList = mutableListOf<File>()
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler

    private val binder = LocalBinder()
    private var isRecording = false  // Track recording status
    private var currentDurationCallback: ((duration: Int) -> Unit)? = null

    companion object {
        private const val TAG = "AudioRecordingService"
        const val CHANNELS = 1
        const val SAMPLING_RATE = 16000
        const val BIT_RATE = 32000
        const val AUDIO_RECORDINGS_DIR = "audio_recordings"
        const val DURATION_INTENT_KEY = "duration"
        const val DEFAULT_DURATION = 10
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioRecordingService = this@AudioRecordingService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("AudioRecordingService", "Created")
        outputDir = File(externalCacheDir, AUDIO_RECORDINGS_DIR).apply { mkdirs() }

        // Dedicated thread for recurring recording tasks
        workerThread = HandlerThread("AudioRecorderWorker")
        workerThread.start()
        workerHandler = Handler(workerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("AudioRecordingService", "Started")
        maxDurationMinutes = (intent?.getIntExtra(DURATION_INTENT_KEY, DEFAULT_DURATION) ?: DEFAULT_DURATION).coerceAtLeast(1)
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

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)
    }

    fun startNewRecording() {
        Log.d("AudioRecordingService", "Start new recording")

        if (fileList.size > 0) {
            try {
                val lastFile = fileList.last()
                gDriveHelper?.uploadFile(
                    lastFile,
                    GDriveHelper.FolderType.STAGING,
                    fileName = null,
                    callback = null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload to Drive", e)
            }
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val audioFile = File(outputDir, "record_$timeStamp.m4a")
        fileList.add(audioFile)

        Log.d(TAG, "Writing staging file ${audioFile.absolutePath}")

        cleanOldFiles()

        if (mediaRecorder == null) {
            mediaRecorder = MediaRecorder(this)
        }

        try {
            mediaRecorder?.apply {
                reset()
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(CHANNELS)
                setAudioSamplingRate(SAMPLING_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
            isRecording = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaRecorder", e)
            stopSelf()
            return
        }

        // Restart recording after 1 minute
        workerHandler.postDelayed({ startNewRecording() }, 60_000)

        if (currentDurationCallback != null) {
            currentDurationCallback?.let { it((fileList.size - 1).coerceAtLeast(0)) }
        }
    }

    private fun cleanOldFiles() {
        while (fileList.size > maxDurationMinutes) {
            fileList.removeAt(0).delete()
        }
        gDriveHelper?.deleteOldestFromStaging(maxDurationMinutes)
    }

    override fun onDestroy() {
        Log.d("AudioRecordingService", "Destroy")
        cleanupTemporaryFiles()
        stopRecording()
        workerThread.quitSafely()
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

    fun getFileList(): List<File> {
        return fileList.toList() // Make a readonly copy
    }

    fun pauseRecording() {
        Log.d(TAG, "pauseRecording")
        workerHandler.removeCallbacksAndMessages(null)
        mediaRecorder?.apply {
            try {
                stop()
            } catch (_: Exception) {
            } finally {
                try {
                    reset()
                } catch (_: Exception) {
                }
                try {
                    release()
                } catch (_: Exception) {
                }
            }
        }
        mediaRecorder = null
        isRecording = false
    }

    fun stopRecording() {
        Log.d(TAG, "stopRecording")
        pauseRecording()
        stopForeground(STOP_FOREGROUND_REMOVE)
        fileList.clear()
    }

    fun registerCallbackCurrentDuration(callback: (duration: Int) -> Unit) {
        currentDurationCallback = callback
        if (isRecording) {
            currentDurationCallback?.let { it((fileList.size - 1).coerceAtLeast(0)) }
        }
    }

    fun registerGDriveHelper(gDriveHelper: GDriveHelper) {
        this.gDriveHelper = gDriveHelper
    }
}
