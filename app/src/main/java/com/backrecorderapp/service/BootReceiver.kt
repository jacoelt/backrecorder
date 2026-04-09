package com.backrecorderapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.backrecorderapp.data.preferences.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var appPreferences: AppPreferences

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val wasRecording = runBlocking {
            appPreferences.wasRecordingBeforeShutdown.first()
        }

        if (wasRecording) {
            RecordingService.start(context)
        }
    }
}
