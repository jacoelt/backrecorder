package com.backrecorderapp.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.backrecorderapp.GDriveHelper
import com.backrecorderapp.R
import com.backrecorderapp.SecurePreferences
import com.backrecorderapp.SettingsDataStore
import com.backrecorderapp.concatAudioFiles
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*

class SaveRecordingService : Service() {

    private var securePreferences = SecurePreferences(this)

    companion object {
        const val TAG = "SaveRecordingService"

        const val ACTION_START = "com.backrecorderapp.action.START_SAVE"
        const val EXTRA_OUTPUT_URI = "extra_output_uri"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "save_recording_channel"
    }

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }

        val outputUri = Uri.parse(intent.getStringExtra(EXTRA_OUTPUT_URI))
        if (outputUri == null) {
            Log.e(TAG, "Missing output uri")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Preparing save…"))

        startSave(outputUri)

        return START_NOT_STICKY
    }

    private fun startSave(outputUri: Uri) {
        val context = this.applicationContext
        val settings = SettingsDataStore.getInstance(this.applicationContext)
        val gDriveHelper = GDriveHelper(this.applicationContext, securePreferences)

        serviceScope.launch {
            val mergedFile = File(cacheDir, "merged_audio.m4a")

            try {
                updateNotification(getString(R.string.saving_status_stop_recording))

                val recordingService = RecordingServiceHolder.get()
                val audioFiles = recordingService?.getFileList()?.toList()
                    ?: throw IllegalStateException("RecordingService not available")

                recordingService.pauseRecording()

                if (mergedFile.exists()) mergedFile.delete()

                updateNotification(getString(R.string.saving_status_mergin_audio))

                concatAudioFiles(
                    inputFiles = audioFiles,
                    outputFile = mergedFile
                )

                updateNotification(getString(R.string.saving_status_writing_file))

                contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                    FileInputStream(mergedFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: throw IllegalStateException("Cannot open output uri")

                updateNotification(getString(R.string.saving_status_completed))

                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                if (settings.getUseGDrive()) {
                    gDriveHelper.uploadFile(
                        mergedFile,
                        GDriveHelper.FolderType.FINAL,
                        fileName = "record_$timeStamp.m4a"
                    ) { isSuccess ->
                        if (!isSuccess) {
                            Toast.makeText(
                                context,
                                R.string.error_save_gdrive,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Save failed", e)
                updateNotification(getString(R.string.saving_status_error))
                Toast.makeText(
                    context,
                    R.string.error_on_recording_save,
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                mergedFile.delete()
                delay(1_000)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun updateNotification(message: String) {
        val notification = buildNotification(message)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.saving_recording))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.save_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.save_channel_description)
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
