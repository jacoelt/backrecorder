package com.backrecorderapp.ui.settings

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.billing.PlanQuery
import com.backrecorderapp.data.billing.PurchaseFailure
import com.backrecorderapp.data.billing.SubscriptionPlan
import com.backrecorderapp.data.billing.SubscriptionPlans
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

    /**
     * Bumped whenever the user abandons something they started, counted separately for the plan
     * load and the hand-off. See [com.backrecorderapp.ui.main.MainViewModel] for both halves of
     * the reasoning: why an answer in flight is discarded rather than cancelled (the call shares
     * one Play connection attempt with every other caller, and a cancelled caller's cleanup
     * answers all of them with a refusal), and why dismissing the offer must not touch a
     * purchase that is already on its way - including why abandoning leaves the busy flag set
     * for the call itself to clear.
     */
    private var planLoadGeneration = 0

    private var purchaseGeneration = 0

    private val _purchaseInFlight = MutableStateFlow(false)

    private val _plansLoading = MutableStateFlow(false)

    /**
     * True while the Upgrade affordance is busy - asking Play for the plans, or handing a chosen
     * plan over to it. Both are multi-second waits in which a tap would be swallowed by the
     * guards below, so the button has to say so rather than look live.
     *
     * Started eagerly, not `WhileSubscribed`: with no collector the combine never runs and
     * `value` answers with a stale cached one, which is both wrong to read and vacuous to assert.
     */
    val upgradeBusy: StateFlow<Boolean> =
        combine(_plansLoading, _purchaseInFlight) { loading, purchasing -> loading || purchasing }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _subscriptionPlans = MutableStateFlow<SubscriptionPlans?>(null)

    /** The plans to choose between, or null when the chooser is not up. */
    val subscriptionPlans: StateFlow<SubscriptionPlans?> = _subscriptionPlans.asStateFlow()

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
     * Loads the plans and opens the chooser. A refusal is reported like a refused hand-off, on
     * this screen only.
     */
    fun showPlanChooser() {
        // A purchase already on its way to Play would otherwise be joined by a second chooser
        // opening behind Play's sheet.
        if (_plansLoading.value || _purchaseInFlight.value) return
        val issued = planLoadGeneration
        _plansLoading.value = true
        viewModelScope.launch {
            try {
                val query = billingRepository.loadSubscriptionPlans()
                if (issued != planLoadGeneration) return@launch
                when (query) {
                    is PlanQuery.Available -> _subscriptionPlans.value = query.plans
                    is PlanQuery.Unavailable -> _purchaseError.value = query.failure
                }
                // Closed only once there is something to replace it with, so the tap is never
                // answered by an empty screen.
                _showUpgradeDialog.value = false
            } finally {
                // Unconditional: abandonment leaves this set, so it is always this load's to drop.
                _plansLoading.value = false
            }
        }
    }

    fun dismissPlanChooser() {
        _subscriptionPlans.value = null
    }

    /**
     * Hands off to Play for the plan the user picked, and reports back on this screen only. A
     * null [activity] means the screen could not resolve one, which is itself a failed hand-off.
     */
    fun launchPurchaseFlow(activity: Activity?, plan: SubscriptionPlan) {
        // Two Continue taps delivered before the dialog leaves composition would otherwise start
        // two hand-offs; the second serializes behind the first and reports a failure over a
        // purchase that is going ahead.
        if (_purchaseInFlight.value) return
        if (activity == null) {
            _subscriptionPlans.value = null
            _purchaseError.value = PurchaseFailure.LAUNCH_FAILED
            return
        }
        val issued = purchaseGeneration
        // The chooser stays up, with Continue busy, until Play's sheet is in front of the user:
        // preparing takes seconds, and a dialog that vanishes onto an unchanged screen is the
        // same silence as a dead button, with a charge at the end of it.
        _purchaseInFlight.value = true
        viewModelScope.launch {
            try {
                val failure = billingRepository.launchPurchaseFlow(activity, plan)
                if (issued != purchaseGeneration) return@launch
                failure?.let { _purchaseError.value = it }
            } finally {
                // Unconditional, like the load's: nothing else clears it.
                _purchaseInFlight.value = false
                _subscriptionPlans.value = null
            }
        }
    }

    /**
     * Stops listening to a plan load the user has walked away from, so its chooser or its error
     * does not arrive with no tap behind it. A purchase in flight is deliberately untouched: it
     * is not what the dismissed offer started. See [planLoadGeneration].
     */
    private fun abandonPendingPlanLoad() {
        // The flag stays set until the call returns: clearing it here would free the guard over a
        // load that still holds the repository's mutex, and the next tap would spend its whole
        // timeout waiting for that lock and report BILLING_UNAVAILABLE over a working connection.
        planLoadGeneration++
    }

    /**
     * The user has left this screen for good - not merely rotated it.
     *
     * This view model is not always torn down on the way out: settings can navigate *forward* to
     * the disclaimer, which keeps its back-stack entry, and with it anything left in state here
     * to be rendered again on the way back.
     */
    fun onScreenLeft() {
        abandonPendingPlanLoad()
        // Leaving abandons the purchase too, unlike dismissing the offer: its answer would
        // otherwise raise a failure dialog over this screen on the way back.
        // `_purchaseInFlight` is left set: it is the only thing stopping a second hand-off, and
        // the first has not been cancelled. Clearing it let the user leave, pick the other plan
        // from the main screen's Upgrade, and be shown two Play sheets - the first for the plan
        // they had moved on from.
        purchaseGeneration++
        _subscriptionPlans.value = null
        _purchaseError.value = null
    }

    fun dismissPurchaseError() {
        _purchaseError.value = null
    }

    fun dismissUpgradeDialog() {
        // The load that dialog's Upgrade button started goes with it: an answer still on its way
        // would raise the chooser, or an error, seconds after the user dismissed the offer. A
        // purchase already handed to Play is left alone - this dialog did not start it.
        abandonPendingPlanLoad()
        _showUpgradeDialog.value = false
    }
}
