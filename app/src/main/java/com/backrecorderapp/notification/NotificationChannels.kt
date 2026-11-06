package com.backrecorderapp.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.backrecorderapp.BackRecorderApp

/**
 * The app's three notification channels, created in one place.
 *
 * Extracted from [BackRecorderApp] so instrumented tests can create them too: those run under
 * `HiltTestApplication`, whose `onCreate` is not the real application's, and a foreground service
 * posting to a channel that does not exist kills the process with
 * `CannotPostForegroundServiceNotificationException`.
 */
object NotificationChannels {

    fun createAll(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        val recordingChannel = NotificationChannel(
            BackRecorderApp.RECORDING_CHANNEL_ID,
            "Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing recording notification"
            setShowBadge(false)
        }

        val cloudChannel = NotificationChannel(
            BackRecorderApp.CLOUD_CHANNEL_ID,
            "Cloud Sync",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Cloud storage sync status"
            setShowBadge(false)
        }

        val recoveryChannel = NotificationChannel(
            BackRecorderApp.RECOVERY_CHANNEL_ID,
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
