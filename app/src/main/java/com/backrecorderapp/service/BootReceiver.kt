package com.backrecorderapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.backrecorderapp.data.preferences.AppPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Restarts recording after a reboot when it was running at shutdown.
 *
 * Dependencies are resolved through [EntryPointAccessors] rather than `@AndroidEntryPoint`, so that
 * a `BOOT_COMPLETED` broadcast arriving while no Hilt component exists is ignored instead of
 * killing the process. That happens under instrumentation: `HiltTestApplication`'s component is
 * built by `HiltAndroidRule`, so between process start and the first test there is nothing to
 * inject from, and a generated `inject()` call at that moment aborts the whole test run.
 */
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootReceiverEntryPoint {
        fun appPreferences(): AppPreferences
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val appPreferences = appPreferencesOrNull(context) ?: return

        val wasRecording = runBlocking {
            appPreferences.wasRecordingBeforeShutdown.first()
        }

        if (wasRecording) {
            RecordingService.start(context)
        }
    }

    private fun appPreferencesOrNull(context: Context): AppPreferences? = try {
        EntryPointAccessors
            .fromApplication(context.applicationContext, BootReceiverEntryPoint::class.java)
            .appPreferences()
    } catch (e: IllegalStateException) {
        // No Hilt component yet (instrumentation before the first test). Nothing to restart.
        Log.w(TAG, "Boot broadcast arrived with no Hilt component; skipping restart", e)
        null
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
