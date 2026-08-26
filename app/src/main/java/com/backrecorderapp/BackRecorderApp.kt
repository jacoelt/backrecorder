package com.backrecorderapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.cloud.CloudSyncManager
import com.backrecorderapp.data.cloud.DriveAuthManager
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class BackRecorderApp : Application() {

    @Inject lateinit var billingRepository: BillingRepository
    @Inject lateinit var driveAuthManager: DriveAuthManager
    @Inject lateinit var cloudSyncManager: CloudSyncManager
    @Inject lateinit var appPreferences: AppPreferences

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val RECORDING_CHANNEL_ID = "recording_channel"
        const val CLOUD_CHANNEL_ID = "cloud_channel"
        const val RECOVERY_CHANNEL_ID = "recovery_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        billingRepository.connect()
        resumeCloudSync()
    }

    /**
     * Re-establishes the Drive session and resumes uploading on launch. Without this the
     * sync loop only ever started from the settings toggle, so uploads stopped at the first
     * app restart and never resumed on their own.
     */
    private fun resumeCloudSync() {
        appScope.launch {
            if (appPreferences.cloudUploadMode.first() == CloudUploadMode.OFF) return@launch
            driveAuthManager.restoreSession()
            cloudSyncManager.startSync()
        }
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val recordingChannel = NotificationChannel(
            RECORDING_CHANNEL_ID,
            "Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing recording notification"
            setShowBadge(false)
        }

        val cloudChannel = NotificationChannel(
            CLOUD_CHANNEL_ID,
            "Cloud Sync",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Cloud storage sync status"
            setShowBadge(false)
        }

        val recoveryChannel = NotificationChannel(
            RECOVERY_CHANNEL_ID,
            "Recovery",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Important notifications about recovered recordings"
        }

        notificationManager.createNotificationChannels(
            listOf(recordingChannel, cloudChannel, recoveryChannel)
        )
    }
}
