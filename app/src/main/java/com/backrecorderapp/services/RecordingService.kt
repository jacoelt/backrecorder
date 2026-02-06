package com.backrecorderapp.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.backrecorderapp.MAX_MINUTES_TOTAL
import com.backrecorderapp.MainActivity
import com.backrecorderapp.R
import com.backrecorderapp.helpers.GDriveHelper
import com.backrecorderapp.helpers.SettingsDataStore
import com.backrecorderapp.helpers.SubscriptionManager
import com.backrecorderapp.helpers.SubscriptionMode
import com.backrecorderapp.helpers.TimeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class RecordingService : Service() {

    companion object {
        private const val TAG = "AudioRecordingService"
        const val CHANNELS = 1
        const val SAMPLING_RATE = 16000
        const val BIT_RATE = 32000
        const val AUDIO_RECORDINGS_DIR = "audio_recordings"
        const val DEFAULT_DURATION = MAX_MINUTES_TOTAL
        const val NOTIFICATION_CHANNEL_ID = "AudioRecordingChannel"
        const val NOTIFICATION_ID = 42
    }

    private var gDriveHelper: GDriveHelper? = null
    private var mediaRecorder: MediaRecorder? = null
    private lateinit var settings: SettingsDataStore
    private lateinit var subscriptionManager: SubscriptionManager


    private lateinit var outputDir: File
    private var maxDurationMinutes = DEFAULT_DURATION
    private val fileList = mutableListOf<File>()

    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    private val binder = LocalBinder()
    private var isRecording = false  // Track recording status
    private var currentDurationCallback: ((duration: Int) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onCreate() {
        super.onCreate()
        RecordingServiceHolder.set(this)
        Log.d("AudioRecordingService", "Created")
        outputDir = File(externalCacheDir, AUDIO_RECORDINGS_DIR).apply { mkdirs() }
        settings = SettingsDataStore.getInstance(this.applicationContext)
        subscriptionManager = SubscriptionManager(settings)
        runBlocking { subscriptionManager.load() }

        // Dedicated thread for recurring recording tasks
        workerThread = HandlerThread("AudioRecorderWorker")
        workerThread.start()
        workerHandler = Handler(workerThread.looper)

        restoreListOfExistingFiles()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("AudioRecordingService", "Started")
        startForegroundService()
        serviceScope.launch {
            startNewRecording()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.audio_recording_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)


        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBigContentId =
            when (subscriptionManager.mode.value) {
                SubscriptionMode.FREE -> R.string.notification_bigtext_free
                SubscriptionMode.TRIAL -> R.string.notification_bigtext_trial
                SubscriptionMode.PREMIUM -> R.string.notification_bigtext_premium
            }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.icon)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, TimeHelper.formatDuration(getDuration())))
            .setStyle(
                NotificationCompat
                    .BigTextStyle()
                    .bigText(getString(
                        notificationBigContentId,
                        TimeHelper.formatDuration(getDuration())
                    ))
            )
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun restoreListOfExistingFiles() {
        val existingFiles = outputDir.listFiles()?.toList()
        if (existingFiles != null) {
            fileList.addAll(existingFiles)
        }
    }

    private fun startNewRecording() {
        Log.d("AudioRecordingService", "Start new recording")

        if (fileList.size > 0) {
            serviceScope.launch {
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
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val audioFile = File(outputDir, "record_$timeStamp.m4a")
        fileList.add(audioFile)

        cleanOldFiles()

        if (currentDurationCallback != null) {
            currentDurationCallback?.let { it(fileList.size) }
        }

        Log.d(TAG, "Writing staging file ${audioFile.absolutePath}")

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
    }

    private fun cleanOldFiles() {
        Log.d(TAG, "Clean up: currently have ${fileList.size} files")
        while (fileList.size > maxDurationMinutes) {
            fileList.removeAt(0).delete()
        }
        gDriveHelper?.deleteOldestFromStaging(maxDurationMinutes)
        Log.d(TAG, "Clean up done: ${fileList.size} files left")
    }

    override fun onDestroy() {
        Log.d(TAG, "Destroy")
        stopRecording()
        workerThread.quitSafely()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun isRecording(): Boolean {
        Log.d(TAG, "isRecording: $isRecording")
        return isRecording
    }

    fun getFileList(): List<File> {
        return fileList.toList() // Make a readonly copy
    }

    fun interruptCurrentRecording() {
        Log.d(TAG, "interruptCurrentRecording")
        workerHandler.removeCallbacksAndMessages(null)
        startNewRecording()
    }

    fun stopRecording() {
        Log.d(TAG, "stopRecording")
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
        stopForeground(STOP_FOREGROUND_REMOVE)
        fileList.clear()
    }

    fun registerCallbackCurrentDuration(callback: (duration: Int) -> Unit) {
        currentDurationCallback = callback
        currentDurationCallback?.let { it(getDuration()) }
    }

    private fun getDuration(): Int {
        return fileList.size
    }

    fun registerGDriveHelper(gDriveHelper: GDriveHelper) {
        this.gDriveHelper = gDriveHelper
    }
}
