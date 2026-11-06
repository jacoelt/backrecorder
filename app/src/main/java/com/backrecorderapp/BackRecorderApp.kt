package com.backrecorderapp

import android.app.Application
import android.util.Log
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.cloud.CloudSyncManager
import com.backrecorderapp.data.cloud.DriveAuthManager
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.notification.NotificationChannels
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
        private const val TAG = "BackRecorderApp"

        const val RECORDING_CHANNEL_ID = "recording_channel"
        const val CLOUD_CHANNEL_ID = "cloud_channel"
        const val RECOVERY_CHANNEL_ID = "recovery_channel"
    }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
        billingRepository.connect()
        resumeCloudSync()
    }

    /**
     * Re-establishes the Drive session and resumes uploading on launch. Without this the sync
     * loop only ever started from the settings toggle, so uploads stopped at the first app
     * restart and never resumed on their own.
     */
    private fun resumeCloudSync() {
        appScope.launch {
            if (appPreferences.cloudUploadMode.first() == CloudUploadMode.OFF) return@launch

            // The subscription gate is enforced inside the sync loop, not here: at this point
            // billing has only just been asked and still reports FREE for everyone, so gating
            // here would stop a paying subscriber - and persisting OFF would erase their setting.

            // The OAuth grant outlives an explicit disconnect, so restoring silently here would
            // reconnect the account the user just removed.
            if (appPreferences.driveDisconnected.first()) return@launch

            if (!driveAuthManager.restoreSession()) {
                // Nothing to upload with. Starting the loop anyway would spin against an
                // unauthorized client every thirty seconds for the life of the process.
                Log.i(TAG, "Drive grant could not be restored; leaving sync stopped")
                return@launch
            }

            cloudSyncManager.startSync()
        }
    }
}
