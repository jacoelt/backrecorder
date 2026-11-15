package com.backrecorderapp.data.cloud

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.backrecorderapp.BackRecorderApp
import com.backrecorderapp.MainActivity
import com.backrecorderapp.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The out-of-app half of an upload-failure report.
 *
 * A seam for the same reason the recorder and billing client are: `NotificationCompat.build()`
 * reaches the platform `Notification.Builder`, which throws on the JVM, so without it the one
 * thing worth asserting - that a user whose upload failed is actually told while the app is in
 * the background - could not be covered by a unit test at all.
 */
interface UploadFailureNotifier {
    fun notifyUploadFailed(text: String)
}

class SystemUploadFailureNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) : UploadFailureNotifier {

    private companion object {
        const val UPLOAD_FAILED_NOTIFICATION_ID = 101
    }

    override fun notifyUploadFailed(text: String) {
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, BackRecorderApp.CLOUD_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.cloud_saved_upload_failed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(UPLOAD_FAILED_NOTIFICATION_ID, notification)
    }
}
