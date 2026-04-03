package com.backrecorderapp.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.backrecorderapp.MainActivity
import com.backrecorderapp.R
import com.backrecorderapp.helpers.GDriveHelper
import com.backrecorderapp.helpers.RecordingConfig
import com.backrecorderapp.helpers.SettingsDataStore
import com.backrecorderapp.helpers.SubscriptionManager
import com.backrecorderapp.helpers.SubscriptionMode
import com.backrecorderapp.helpers.TimeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        const val CHANNELS = 1
        const val SAMPLING_RATE = 16000
        const val BIT_RATE = 32000
        const val AUDIO_RECORDINGS_DIR = "audio_recordings"
        const val NOTIFICATION_CHANNEL_ID = "AudioRecordingChannel"
        const val NOTIFICATION_ID = 42

        const val ACTION_START_RECORDING = "action_start_recording"
        const val ACTION_STOP_RECORDING = "action_stop_recording"
        const val ACTION_FLUSH_CURRENT_FILE = "action_flush_current_file"
    }

    private var mediaRecorder: MediaRecorder? = null
    private lateinit var gDriveHelper: GDriveHelper
    private lateinit var settings: SettingsDataStore
    private lateinit var subscriptionManager: SubscriptionManager


    private lateinit var outputDir: File
    private val fileList = mutableListOf<File>()

    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    enum class RecordingStatus {
        STOPPED,
        RECORDING,
        PAUSED_BY_CALL,
    }

    private val binder = LocalBinder()
    private var recordingStatus = RecordingStatus.STOPPED
    private var recordingStateCallback: ((recordingStatus: RecordingStatus) -> Unit)? = null
    private var currentDurationCallback: ((duration: Int) -> Unit)? = null

    @Volatile
    private var isTransitioning = false

    @Volatile
    private var isServiceActive = false

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Created")
        outputDir = File(externalCacheDir, AUDIO_RECORDINGS_DIR).apply { mkdirs() }
        settings = SettingsDataStore.getInstance(this.applicationContext)
        subscriptionManager = SubscriptionManager(settings)
        gDriveHelper = GDriveHelper(this)

        serviceScope.launch {
            subscriptionManager.load()
            updateNotification()
        }

        // Dedicated thread for recurring recording tasks
        workerThread = HandlerThread("AudioRecorderWorker")
        workerThread.start()
        workerHandler = Handler(workerThread.looper)

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        serviceScope.launch {
                            onCallStarted()
                        }
                    }

                    AudioManager.AUDIOFOCUS_GAIN -> {
                        serviceScope.launch {
                            onCallEnded()
                        }
                    }
                }
            }
            .build()

        audioManager.requestAudioFocus(focusRequest)

        restoreListOfExistingFiles()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Started")

        when(intent?.action) {
            ACTION_START_RECORDING -> {
                if (!isServiceActive) {
                    isServiceActive = true
                    startForegroundService()
                    serviceScope.launch {
                        startNewRecording()
                    }
                }
                return START_STICKY
            }

            ACTION_STOP_RECORDING -> {
                isServiceActive = false
                stopRecording()
                return START_NOT_STICKY
            }

            ACTION_FLUSH_CURRENT_FILE -> {
                if (isServiceActive) {
                    interruptCurrentRecording()
                    startNewRecording()
                }
                return START_NOT_STICKY
            }

            else -> {
                return START_NOT_STICKY
            }
        }
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

        val notificationContentId =
            when (recordingStatus) {
                RecordingStatus.RECORDING -> R.string.notification_title_recording
                RecordingStatus.STOPPED -> R.string.notification_title_stopped
                RecordingStatus.PAUSED_BY_CALL -> R.string.notification_title_paused_by_call
            }

        val notificationBigContentId =
            when (subscriptionManager.mode.value) {
                SubscriptionMode.FREE -> R.string.notification_bigtext_free
                SubscriptionMode.TRIAL -> R.string.notification_bigtext_trial
                SubscriptionMode.PREMIUM -> R.string.notification_bigtext_premium
                else -> R.string.notification_bigtext_free
            }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.icon)
            .setContentTitle(getString(notificationContentId))
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

    private suspend fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        subscriptionManager.load()

        if (isServiceActive) {
            val notification = buildNotification()
            manager.notify(NOTIFICATION_ID, notification)
        } else {
            manager.cancel(NOTIFICATION_ID)
        }
    }

    private fun restoreListOfExistingFiles() {
        val existingFiles = outputDir.listFiles()?.toList()
        fileList.clear()
        if (existingFiles != null) {
            fileList.addAll(existingFiles)
        }
    }

    private fun startNewRecording() {
        if (isTransitioning) {
            Log.w(TAG, "startNewRecording ignored: transition already in progress")
            return
        }
        isTransitioning = true

        try {
            internalStartNewRecording()
        } finally {
            isTransitioning = false
        }
    }

    private fun internalStartNewRecording() {
        Log.d(TAG, "Start new recording")

        stopCurrentRecorderSafely()

        if (fileList.isNotEmpty()) {
            serviceScope.launch {
                try {
                    val lastFile = fileList.last()
                    gDriveHelper.uploadFile(
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
        startRecorder(audioFile)

        scheduleNextRotation()

        serviceScope.launch {
            updateNotification()
        }
        notifyCurrentDurationChanged()
    }

    private fun startRecorder(outputFile: File) {
        Log.d(TAG, "Writing staging file ${outputFile.absolutePath}")

        mediaRecorder = MediaRecorder(this).apply {
            try {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(CHANNELS)
                setAudioSamplingRate(SAMPLING_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()

                changeRecordingStatus(RecordingStatus.RECORDING)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MediaRecorder", e)
                stopCurrentRecorderSafely()
                stopSelf()
            }
        }
    }

    private fun scheduleNextRotation() {
        workerHandler.removeCallbacksAndMessages(null)
        workerHandler.postDelayed({
            if (isServiceActive) {
                startNewRecording()
            }
        }, 60_000)
    }

    private fun stopRecording() {
        Log.d(TAG, "stopRecording")
        isServiceActive = false
        workerHandler.removeCallbacksAndMessages(null)
        stopCurrentRecorderSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun stopCurrentRecorderSafely() {
        Log.d(TAG, "stopCurrentRecorderSafely")
        mediaRecorder?.apply {
            try {
                stop()
            } catch (_: IllegalStateException) {
            } finally {
                try {
                    reset()
                } catch (_: Exception) {}
                try {
                    release()
                } catch (_: Exception) {}
            }
        }
        mediaRecorder = null
        changeRecordingStatus(RecordingStatus.STOPPED)
    }

    private suspend fun onCallStarted() {
        Log.d(TAG, "onCallStarted")
        if (isServiceActive) {
            interruptCurrentRecording()
        }
        changeRecordingStatus(RecordingStatus.PAUSED_BY_CALL)
        updateNotification()
    }

    private suspend fun onCallEnded() {
        Log.d(TAG, "onCallEnded")
        changeRecordingStatus(RecordingStatus.RECORDING)
        updateNotification()
        startNewRecording()
    }

    private fun cleanOldFiles() {
        Log.d(TAG, "Clean up: currently have ${fileList.size} files")
        while (fileList.size > RecordingConfig.currentMaxDuration) {
            fileList.removeAt(0).delete()
        }
        gDriveHelper.deleteOldestFromStaging(RecordingConfig.currentMaxDuration)
        Log.d(TAG, "Clean up done: ${fileList.size} files left")
    }

    override fun onDestroy() {
        Log.d(TAG, "Destroy")
        stopRecording()
        workerThread.quitSafely()
        serviceScope.cancel()
        gDriveHelper.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun interruptCurrentRecording() {
        Log.d(TAG, "interruptCurrentRecording")
        workerHandler.removeCallbacksAndMessages(null)
        isTransitioning = false
    }

    fun registerRecordingStateCallback(callback: (recordingStatus: RecordingStatus) -> Unit) {
        recordingStateCallback = callback
        changeRecordingStatus()
    }

    private fun changeRecordingStatus(newRecordingStatus: RecordingStatus? = null) {
        if (recordingStatus == newRecordingStatus) return

        if (newRecordingStatus != null) {
            recordingStatus = newRecordingStatus
        }

        recordingStateCallback?.invoke(recordingStatus)

        if (recordingStatus === RecordingStatus.PAUSED_BY_CALL) {
            serviceScope.launch {
                onEnterPausedByCall()
            }
        }
    }

    private suspend fun onEnterPausedByCall() {
        updateNotification()
        schedulePauseRecoveryCheck()
    }

    private fun schedulePauseRecoveryCheck() {
        workerHandler.postDelayed({
            if (recordingStatus != RecordingStatus.PAUSED_BY_CALL) return@postDelayed
            if (!isServiceActive) return@postDelayed

            tryResumeFromPausedByCall()
        }, 5_000)
    }

    private fun tryResumeFromPausedByCall() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val canResume =
            audioManager.mode == AudioManager.MODE_NORMAL &&
                    !audioManager.isMicrophoneMute

        if (canResume) {
            startNewRecording()
        } else {
            schedulePauseRecoveryCheck()
        }
    }

    fun registerCallbackCurrentDuration(callback: (duration: Int) -> Unit) {
        currentDurationCallback = callback
        notifyCurrentDurationChanged()
    }

    private fun notifyCurrentDurationChanged() {
        currentDurationCallback?.invoke(getDuration())
    }

    private fun getDuration(): Int {
        return fileList.size
    }
}
