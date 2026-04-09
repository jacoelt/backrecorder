package com.backrecorderapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BackRecorderApp : Application() {

    companion object {
        const val RECORDING_CHANNEL_ID = "recording_channel"
        const val CLOUD_CHANNEL_ID = "cloud_channel"
        const val RECOVERY_CHANNEL_ID = "recovery_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
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
