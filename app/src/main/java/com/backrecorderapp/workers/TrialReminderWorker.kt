package com.backrecorderapp.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.backrecorderapp.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrialReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val CHANNEL_ID = "trial_reminder_channel"
        private const val CHANNEL_NAME = "Trial Reminder"
        const val NOTIF_ID = 1001

        const val INPUT_TRIAL_END = "trial_end_timestamp"
    }

    override suspend fun doWork(): Result {
        val trialEndMillis = inputData.getLong(INPUT_TRIAL_END, -1L)
        if (trialEndMillis == -1L) return Result.failure()

        showNotification(trialEndMillis)
        return Result.success()
    }

    private fun showNotification(trialEndMillis: Long) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)

        val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
        val endDateStr = dateFormat.format(Date(trialEndMillis))

        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon)
            .setContentTitle(applicationContext.getString(R.string.trial_ending_soon))
            .setContentText(
                applicationContext.getString(R.string.trial_ends_on, endDateStr)
            )
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIF_ID, notif)
    }
}
