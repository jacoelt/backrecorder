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
import java.util.concurrent.atomic.AtomicInteger
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
        /**
         * Well above the ids the recording service (1, 2) and the recovery notice (100) hold.
         * Each failure gets its own, because a batch of them is likely - they queue while the
         * device is offline - and one reused id would leave the user looking at a single
         * notification naming only the last recording that failed. The start point varies per
         * process so that a failure after a process death does not replace one still on screen
         * from before it.
         */
        const val NOTIFICATION_ID_FLOOR = 1_000
        const val NOTIFICATION_ID_SPREAD = 100_000L

        /** Lets the system bundle a batch of these rather than stack them individually. */
        const val GROUP_KEY = "com.backrecorderapp.UPLOAD_FAILURES"
    }

    private val nextNotificationId = AtomicInteger(
        NOTIFICATION_ID_FLOOR + (System.currentTimeMillis() % NOTIFICATION_ID_SPREAD).toInt()
    )

    override fun notifyUploadFailed(text: String) {
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // The recording's name is the one piece of this the user may not want read over their
        // shoulder - this app exists to record discreetly, and "Chat with HR.m4a" on a lock
        // screen defeats that. The public version carries the generic title alone.
        val publicVersion = baseBuilder(intent)
            .setContentText(context.getString(R.string.cloud_saved_upload_failed_generic))
            .build()

        val notification = baseBuilder(intent)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(nextNotificationId.getAndIncrement(), notification)
    }

    private fun baseBuilder(intent: PendingIntent) =
        NotificationCompat.Builder(context, BackRecorderApp.CLOUD_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.cloud_saved_upload_failed_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
}
