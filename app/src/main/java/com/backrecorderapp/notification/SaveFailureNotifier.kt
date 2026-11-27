package com.backrecorderapp.notification

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
 * Tells a user who left the screen mid-save that it failed (issue #111). A seam for the reason
 * `UploadFailureNotifier` is one: `NotificationCompat.build()` throws on the JVM.
 */
interface SaveFailureNotifier {
    fun notifySaveFailed()
}

class SystemSaveFailureNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) : SaveFailureNotifier {

    private companion object {
        /** Clear of the recording service (1, 2), the recovery notice (100) and upload failures (1000+). */
        const val NOTIFICATION_ID = 200
    }

    override fun notifySaveFailed() {
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // The recovery channel, not the quiet cloud one: the audio is still only in the rolling
        // buffer, and it is evicted after 24h unless the user saves it again.
        val notification = NotificationCompat.Builder(context, BackRecorderApp.RECOVERY_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.save_failed_title))
            .setContentText(context.getString(R.string.save_failed_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }
}
