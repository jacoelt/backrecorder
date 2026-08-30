package com.backrecorderapp.ui.settings

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.billing.SubscriptionStatus
import com.backrecorderapp.data.cloud.CloudSyncManager
import com.backrecorderapp.data.cloud.DriveAuthManager
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.domain.GateResult
import com.backrecorderapp.domain.SubscriptionGate
import com.backrecorderapp.service.RecordingService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val recordingEnabled: Boolean = false,
    val cloudUploadMode: CloudUploadMode = CloudUploadMode.OFF,
    val subscriptionStatus: SubscriptionStatus = SubscriptionStatus.FREE,
    val trialDaysRemaining: Int = 0,
    val driveConnected: Boolean = false,
    val driveNeedsReauth: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val billingRepository: BillingRepository,
    private val subscriptionGate: SubscriptionGate,
    private val cloudSyncManager: CloudSyncManager,
    private val driveAuthManager: DriveAuthManager
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        RecordingService.isRunning,
        appPreferences.cloudUploadMode,
        billingRepository.subscriptionStatus,
        driveAuthManager.isAuthorized,
        cloudSyncManager.needsReauth
    ) { recording, cloudMode, subStatus, driveConnected, needsReauth ->
        SettingsUiState(
            recordingEnabled = recording,
            cloudUploadMode = cloudMode,
            subscriptionStatus = subStatus,
            trialDaysRemaining = billingRepository.getTrialDaysRemaining(),
            driveConnected = driveConnected,
            driveNeedsReauth = needsReauth
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    private val _showUpgradeDialog = MutableStateFlow(false)
    val showUpgradeDialog: StateFlow<Boolean> = _showUpgradeDialog.asStateFlow()

    private val _signInError = MutableStateFlow(false)
    val signInError: StateFlow<Boolean> = _signInError.asStateFlow()

    /**
     * Set when Google needs the user to approve Drive access. The screen launches it through
     * an activity-result contract and reports back via [onConsentResult].
     */
    private val _consentRequest = MutableStateFlow<PendingIntent?>(null)
    val consentRequest: StateFlow<PendingIntent?> = _consentRequest.asStateFlow()

    init {
        viewModelScope.launch {
            // Reflect the real grant rather than defaulting to "not connected": the grant may
            // already exist from a previous run, and this check never shows UI. It is skipped
            // after an explicit disconnect, which the grant itself does not record - otherwise
            // simply reopening this screen would silently reconnect the account.
            if (!appPreferences.driveDisconnected.first()) {
                driveAuthManager.restoreSession()
            }
        }
    }

    fun setCloudUploadMode(mode: CloudUploadMode) {
        viewModelScope.launch {
            if (mode == CloudUploadMode.OFF) {
                appPreferences.setCloudUploadMode(mode)
                cloudSyncManager.stopSync()
                return@launch
            }

            if (!isCloudAccessAllowed()) return@launch

            appPreferences.setCloudUploadMode(mode)

            // Uploads cannot work without the Drive grant, so ask for it as part of turning
            // the mode on rather than silently queueing segments that will never leave.
            if (!requestDriveAccess()) return@launch

            cloudSyncManager.startSync()
        }
    }

    fun connectDrive() {
        viewModelScope.launch {
            if (!isCloudAccessAllowed()) return@launch
            if (requestDriveAccess() && appPreferences.cloudUploadMode.first() != CloudUploadMode.OFF) {
                cloudSyncManager.startSync()
            }
        }
    }

    /**
     * Returns false - and raises the upgrade dialog - when the subscription does not cover
     * cloud storage.
     */
    private suspend fun isCloudAccessAllowed(): Boolean {
        if (subscriptionGate.checkCloudAccess() is GateResult.Blocked) {
            _showUpgradeDialog.value = true
            return false
        }
        return true
    }

    /**
     * Returns true when Drive is authorized and ready. Returns false both when consent UI has
     * been handed to the screen (the flow continues in [onConsentResult]) and on failure.
     */
    private suspend fun requestDriveAccess(): Boolean {
        return when (val outcome = driveAuthManager.authorize()) {
            is DriveAuthManager.AuthorizationOutcome.Authorized -> {
                onDriveConnected()
                true
            }
            is DriveAuthManager.AuthorizationOutcome.ConsentRequired -> {
                _consentRequest.value = outcome.pendingIntent
                false
            }
            is DriveAuthManager.AuthorizationOutcome.Failed -> {
                _signInError.value = true
                false
            }
        }
    }

    /**
     * The folder-id cache invalidates itself off the grant generation, so there is nothing to
     * reset here - only the two pieces of state the grant cannot carry on its own.
     */
    private suspend fun onDriveConnected() {
        appPreferences.setDriveDisconnected(false)
        cloudSyncManager.onAuthorizationRestored()
    }

    fun onConsentLaunched() {
        _consentRequest.value = null
    }

    fun onConsentResult(data: Intent?) {
        viewModelScope.launch {
            if (!driveAuthManager.handleAuthorizationResult(data)) {
                _signInError.value = true
                // Don't leave the app in a mode that cannot upload anything.
                appPreferences.setCloudUploadMode(CloudUploadMode.OFF)
                cloudSyncManager.stopSync()
                return@launch
            }

            onDriveConnected()
            if (appPreferences.cloudUploadMode.first() != CloudUploadMode.OFF) {
                cloudSyncManager.startSync()
            }
        }
    }

    fun disconnectDrive() {
        viewModelScope.launch {
            cloudSyncManager.stopSync()
            driveAuthManager.signOut()
            appPreferences.setDriveDisconnected(true)
            appPreferences.setCloudUploadMode(CloudUploadMode.OFF)
        }
    }

    fun dismissSignInError() {
        _signInError.value = false
    }

    fun launchPurchaseFlow(activity: Activity) {
        billingRepository.launchPurchaseFlow(activity)
    }

    fun dismissUpgradeDialog() {
        _showUpgradeDialog.value = false
    }
}
