package com.backrecorderapp.data.cloud

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.app.NotificationCompat
import com.backrecorderapp.BackRecorderApp
import com.backrecorderapp.MainActivity
import com.backrecorderapp.R
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.data.segment.SegmentManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val driveCloudStorage: DriveCloudStorage,
    private val segmentManager: SegmentManager,
    private val appPreferences: AppPreferences
) {
    companion object {
        private const val TAG = "CloudSyncManager"
        private const val SYNC_INTERVAL_MS = 30_000L // Check every 30 seconds
        private const val RECOVERY_NOTIFICATION_ID = 100
        private const val STALE_THRESHOLD_HOURS = 1L
        private const val STALE_GAP_THRESHOLD_HOURS = 12L
        private val RECOVERY_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null

    private val _recoveryMessage = MutableStateFlow<String?>(null)
    val recoveryMessage: StateFlow<String?> = _recoveryMessage.asStateFlow()

    fun startSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            // Ensure folder structure exists
            driveCloudStorage.ensureFolderStructure()

            // Check for stale files on startup
            handleStaleFiles()

            // Continuous sync loop
            while (isActive) {
                try {
                    syncPendingSegments()
                    cleanupOldCloudSegments()
                } catch (e: Exception) {
                    Log.e(TAG, "Sync error", e)
                }
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    fun stopSync() {
        syncJob?.cancel()
        syncJob = null
    }

    private suspend fun syncPendingSegments() {
        val mode = appPreferences.cloudUploadMode.first()
        if (mode == CloudUploadMode.OFF) return
        if (!isNetworkAvailable(mode)) return

        val pending = segmentManager.getSegmentsPendingUpload()
        for (segment in pending) {
            val file = File(segment.filePath)
            if (!file.exists()) continue

            try {
                val cloudFileId = driveCloudStorage.uploadSegment(file)
                if (cloudFileId != null) {
                    segmentManager.markAsUploaded(segment.id, cloudFileId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload segment ${segment.id}", e)
                // Will retry on next cycle
            }
        }
    }

    private suspend fun cleanupOldCloudSegments() {
        val bufferCutoff = System.currentTimeMillis() - SegmentManager.BUFFER_DURATION_MS
        val cloudFiles = driveCloudStorage.listStagingFiles()

        val filesToDelete = cloudFiles.filter { it.createdTime < bufferCutoff }
        for (file in filesToDelete) {
            try {
                driveCloudStorage.deleteFile(file.id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete cloud file ${file.id}", e)
            }
        }
    }

    private suspend fun handleStaleFiles() {
        if (!driveCloudStorage.isInitialized) return

        val cloudFiles = driveCloudStorage.listStagingFiles()
        if (cloudFiles.isEmpty()) return

        val now = System.currentTimeMillis()
        val bufferStart = now - SegmentManager.BUFFER_DURATION_MS

        // Find files outside the 24h window
        val staleFiles = cloudFiles.filter { it.createdTime < bufferStart }
        if (staleFiles.isEmpty()) return

        // Check thresholds:
        // 1. More than 1 hour of audio outside the window
        val staleSpanMs = staleFiles.last().createdTime - staleFiles.first().createdTime
        val staleSpanHours = staleSpanMs / (1000 * 60 * 60)

        // 2. Latest stale file is more than 12h before the buffer window start
        val latestStaleFile = staleFiles.maxByOrNull { it.createdTime }
        val gapHours = (bufferStart - (latestStaleFile?.createdTime ?: bufferStart)) / (1000 * 60 * 60)

        val needsRecovery = staleSpanHours >= STALE_THRESHOLD_HOURS || gapHours >= STALE_GAP_THRESHOLD_HOURS

        if (needsRecovery) {
            // Move to recovery folder
            val recoveryFolderName = "recovery_${RECOVERY_DATE_FORMAT.format(Date(now))}"
            val staleFileIds = staleFiles.map { it.id }

            try {
                driveCloudStorage.moveToRecoveryFolder(staleFileIds, recoveryFolderName)

                val message = context.getString(
                    R.string.recovery_banner,
                    recoveryFolderName
                )
                _recoveryMessage.value = message

                showRecoveryNotification(recoveryFolderName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move stale files to recovery", e)
            }
        } else {
            // Few stale files, just delete them
            for (file in staleFiles) {
                try {
                    driveCloudStorage.deleteFile(file.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete stale file ${file.id}", e)
                }
            }
        }
    }

    fun dismissRecoveryMessage() {
        _recoveryMessage.value = null
    }

    private fun showRecoveryNotification(recoveryFolderName: String) {
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, BackRecorderApp.RECOVERY_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.recovery_notification_title))
            .setContentText(context.getString(R.string.recovery_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(RECOVERY_NOTIFICATION_ID, notification)
    }

    private fun isNetworkAvailable(mode: CloudUploadMode): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return when (mode) {
            CloudUploadMode.WIFI_ONLY -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            CloudUploadMode.ALWAYS_ON -> capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            CloudUploadMode.OFF -> false
        }
    }
}
