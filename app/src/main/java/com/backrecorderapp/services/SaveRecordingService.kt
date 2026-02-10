package com.backrecorderapp.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.backrecorderapp.R
import com.backrecorderapp.helpers.GDriveHelper
import com.backrecorderapp.helpers.SettingsDataStore
import com.backrecorderapp.helpers.concatAudioFiles
import com.backrecorderapp.services.RecordingService.Companion.AUDIO_RECORDINGS_DIR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SaveRecordingService : Service() {

    companion object {
        const val TAG = "SaveRecordingService"

        const val ACTION_START = "com.backrecorderapp.action.START_SAVE"
        const val EXTRA_OUTPUT_URI = "extra_output_uri"
        const val EXTRA_DURATION_START = "extra_duration_start"
        const val EXTRA_DURATION_END = "extra_duration_end"

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
        startForeground(NOTIFICATION_ID, buildNotification("Preparing save…"))

        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }

        val outputUri: Uri? =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_OUTPUT_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_OUTPUT_URI)
            }

        if (outputUri == null) {
            Log.e(TAG, "Missing output uri")
            stopSelf()
            return START_NOT_STICKY
        }

        val start = intent.getIntExtra(EXTRA_DURATION_START, 0)
        val end = intent.getIntExtra(EXTRA_DURATION_END, RecordingService.DEFAULT_DURATION)

        Log.d(TAG, "Requested start: $start, end: $end")

        startSave(outputUri, start, end)

        return START_NOT_STICKY
    }

    private fun startSave(outputUri: Uri, start: Int, end: Int) {
        val context = this.applicationContext
        val settings = SettingsDataStore.getInstance(this.applicationContext)
        val gDriveHelper = GDriveHelper(this.applicationContext)

        serviceScope.launch {
            val mergedFile = File.createTempFile(
                "merged_audio_",
                ".m4a",
                cacheDir
            )

            try {
                updateNotification(getString(R.string.saving_status_stop_recording))

                val allAudioFiles = getFileList()

                val boundStart = start.coerceIn(0, allAudioFiles.size)
                val boundEnd = end.coerceIn(0, allAudioFiles.size)

                if (boundStart >= boundEnd) {
                    Log.e(TAG, "Invalid slice range: start=$start, end=$end")
                    throw IllegalArgumentException("Invalid recording slice range")
                }

                val audioFiles = allAudioFiles.subList(boundStart, boundEnd)

                Log.d(TAG, "All audio files size: ${allAudioFiles.size}, kept files ${audioFiles.size}")

                if (audioFiles.isEmpty()) {
                    return@launch
                }

                if (end == allAudioFiles.size) {
                    // If last file is included, we need to cut it where it is
                    Log.d(TAG, "Need to interrupt current file")

                    val intent = Intent(context, RecordingService::class.java).apply {
                        action = RecordingService.ACTION_FLUSH_CURRENT_FILE
                    }
                    context.startService(intent)

                    delay(500)
                }

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

                Log.d(TAG, "Uploading file: ${mergedFile.path} (${mergedFile.exists()})")
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
                updateNotification(getString(R.string.saving_status_error), false)
                toastError(context, R.string.error_on_recording_save)

                delay(5_000)
            } finally {
                Log.d(TAG, "Clean up after merge and save")
                mergedFile.delete()
                delay(1_000)
                stopForeground(STOP_FOREGROUND_REMOVE)
                cancelNotification()
                stopSelf()
            }
        }
    }

    private fun getFileList(): List<File> {
        val recordingsDir = File(externalCacheDir, AUDIO_RECORDINGS_DIR)

        val allAudioFiles = recordingsDir
            .listFiles { file -> file.extension == "m4a" }
            ?.sortedBy { it.name }
            ?: emptyList()

        return allAudioFiles
    }

    private fun cancelNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
    }


    private fun updateNotification(message: String, ongoing: Boolean = true) {
        val notification = buildNotification(message, ongoing)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String, ongoing: Boolean = true) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon)
            .setContentTitle(getString(R.string.saving_recording))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
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

    private suspend fun toastError(context: Context, errorCode: Int) {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                errorCode,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
