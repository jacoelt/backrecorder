package com.backrecorderapp.ui.settings

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.billing.PurchaseFailure
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
import java.io.IOException
import javax.inject.Inject

/** A Drive problem worth interrupting the user for. */
enum class DriveError {
    /** Consent failed, was cancelled, or could not be read. Retryable. */
    SIGN_IN_FAILED,

    /**
     * Google resolved an account other than the one the uploads belong to. Not retryable, and
     * deliberately loud: continuing would put the user's recordings in a stranger's Drive.
     */
    ACCOUNT_MISMATCH
}

data class SettingsUiState(
    val recordingEnabled: Boolean = false,
    val cloudUploadMode: CloudUploadMode = CloudUploadMode.OFF,
    val subscriptionStatus: SubscriptionStatus = SubscriptionStatus.FREE,
    val trialDaysRemaining: Int = 0,
    val driveConnected: Boolean = false,
    val driveNeedsReauth: Boolean = false,
    val driveStorageFull: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val billingRepository: BillingRepository,
    private val subscriptionGate: SubscriptionGate,
    private val cloudSyncManager: CloudSyncManager,
    private val driveAuthManager: DriveAuthManager
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    // The two Drive problem states are paired first because combine() only has typed overloads
    // up to five sources.
    private val driveProblems = combine(
        cloudSyncManager.needsReauth,
        cloudSyncManager.storageFull
    ) { needsReauth, storageFull -> needsReauth to storageFull }

    val uiState: StateFlow<SettingsUiState> = combine(
        RecordingService.isRunning,
        appPreferences.cloudUploadMode,
        billingRepository.subscriptionStatus,
        driveAuthManager.isAuthorized,
        driveProblems
    ) { recording, cloudMode, subStatus, driveConnected, problems ->
        SettingsUiState(
            recordingEnabled = recording,
            cloudUploadMode = cloudMode,
            subscriptionStatus = subStatus,
            trialDaysRemaining = billingRepository.getTrialDaysRemaining(),
            driveConnected = driveConnected,
            driveNeedsReauth = problems.first,
            driveStorageFull = problems.second
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    private val _showUpgradeDialog = MutableStateFlow(false)
    val showUpgradeDialog: StateFlow<Boolean> = _showUpgradeDialog.asStateFlow()

    private val _purchaseError = MutableStateFlow<PurchaseFailure?>(null)

    /** Why the last Upgrade tap never reached Play, or null. */
    val purchaseError: StateFlow<PurchaseFailure?> = _purchaseError.asStateFlow()

    private val _driveError = MutableStateFlow<DriveError?>(null)

    /** The Drive problem to show, or null. */
    val driveError: StateFlow<DriveError?> = _driveError.asStateFlow()

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
            is DriveAuthManager.AuthorizationOutcome.AccountMismatch -> {
                _driveError.value = DriveError.ACCOUNT_MISMATCH
                false
            }
            is DriveAuthManager.AuthorizationOutcome.Failed -> {
                _driveError.value = DriveError.SIGN_IN_FAILED
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
            val outcome = driveAuthManager.handleAuthorizationResult(data)
            if (outcome !is DriveAuthManager.AuthorizationOutcome.Authorized) {
                // Backing out of the consent sheet is a routine cancel, so the chosen upload mode
                // is left alone. Without a grant the sync loop simply never becomes active.
                _driveError.value =
                    if (outcome is DriveAuthManager.AuthorizationOutcome.AccountMismatch) {
                        DriveError.ACCOUNT_MISMATCH
                    } else {
                        DriveError.SIGN_IN_FAILED
                    }
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
            // Recorded before signing out: the OAuth grant survives sign-out, so if this write
            // never lands the next launch would silently restore the account the user removed.
            try {
                appPreferences.setDriveDisconnected(true)
            } catch (e: IOException) {
                Log.e(TAG, "Could not record the Drive disconnect", e)
            }
            driveAuthManager.signOut()
            appPreferences.setCloudUploadMode(CloudUploadMode.OFF)
        }
    }

    fun dismissDriveError() {
        _driveError.value = null
    }

    /**
     * Hands off to Play and reports back on this screen only. A null [activity] means the screen
     * could not resolve one, which is itself a failed hand-off.
     */
    fun launchPurchaseFlow(activity: Activity?) {
        if (activity == null) {
            _purchaseError.value = PurchaseFailure.LAUNCH_FAILED
            return
        }
        viewModelScope.launch {
            billingRepository.launchPurchaseFlow(activity)?.let { _purchaseError.value = it }
        }
    }

    fun dismissPurchaseError() {
        _purchaseError.value = null
    }

    fun dismissUpgradeDialog() {
        _showUpgradeDialog.value = false
    }
}
