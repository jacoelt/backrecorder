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
import com.backrecorderapp.data.preferences.ComplimentaryAccessStore
import com.backrecorderapp.data.preferences.RedeemResult
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
    /**
     * Whether to offer reconnecting, and whether to report Drive as full. Both are latched by
     * `CloudSyncManager` and cleared only by a tick the gate allows, a disconnect or a re-grant -
     * so once the plan blocks every tick, a 401 or a full Drive from before the lapse would stay
     * on screen giving a second, wrong reason for the same stopped uploads. The plan is the
     * operative one, so these yield to [driveSyncBlockedByPlan]. The precedence lives here rather
     * than in the composable so it can be tested.
     */
    val showDriveReauthPrompt: Boolean = false,
    val showDriveStorageFull: Boolean = false,
    /**
     * The Drive grant exists but the subscription no longer covers uploads. The grant outlives
     * the subscription that was required to obtain it (#85), so [driveConnected] alone reads
     * "Connected" indefinitely on an account that cannot upload.
     */
    val driveSyncBlockedByPlan: Boolean = false,
    /**
     * A non-OFF upload mode is stored but the subscription no longer covers uploads. Carries no
     * `driveConnected` term, unlike [driveSyncBlockedByPlan]: the stored mode claims uploads are
     * happening whether or not an account was ever connected.
     */
    val cloudUploadBlockedByPlan: Boolean = false,
    /** Replaces the plan card and removes the Upgrade affordance: there is nothing to buy (#98). */
    val complimentaryAccess: Boolean = false
)


/**
 * The two billing terms plus the complimentary one. Kept together because the status means
 * nothing without the other two: before billing resolves it is only its FREE default, and a
 * complimentary user's status stays FREE forever by design.
 */
private data class EntitlementState(
    val status: SubscriptionStatus,
    val resolved: Boolean,
    val complimentary: Boolean
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val billingRepository: BillingRepository,
    private val subscriptionGate: SubscriptionGate,
    private val cloudSyncManager: CloudSyncManager,
    private val driveAuthManager: DriveAuthManager,
    private val complimentaryAccessStore: ComplimentaryAccessStore
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

    private val entitlement = combine(
        billingRepository.subscriptionStatus,
        subscriptionGate.statusResolved,
        complimentaryAccessStore.granted
    ) { status, resolved, complimentary -> EntitlementState(status, resolved, complimentary) }

    val uiState: StateFlow<SettingsUiState> = combine(
        RecordingService.isRunning,
        appPreferences.cloudUploadMode,
        entitlement,
        driveAuthManager.isAuthorized,
        driveProblems
    ) { recording, cloudMode, entitlementState, driveConnected, problems ->
        val (subStatus, statusResolved, complimentary) = entitlementState
        // Before billing answers the status is only its FREE default; acting on it would tell a
        // paying subscriber at every launch that the account they pay for cannot upload.
        val planCoversUploads =
            !statusResolved || SubscriptionGate.isPremium(subStatus, complimentary)
        val driveSyncBlockedByPlan = driveConnected && !planCoversUploads
        SettingsUiState(
            recordingEnabled = recording,
            cloudUploadMode = cloudMode,
            subscriptionStatus = subStatus,
            complimentaryAccess = complimentary,
            trialDaysRemaining = billingRepository.getTrialDaysRemaining(),
            driveConnected = driveConnected,
            driveSyncBlockedByPlan = driveSyncBlockedByPlan,
            cloudUploadBlockedByPlan = cloudMode != CloudUploadMode.OFF && !planCoversUploads,
            showDriveReauthPrompt = problems.first && !driveSyncBlockedByPlan,
            showDriveStorageFull = problems.second && !driveSyncBlockedByPlan
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

    private val _driveConnecting = MutableStateFlow(false)

    /**
     * Holds the same claim as [_driveConnecting] across the consent round trip, which spans two
     * separate calls into this view model and so cannot be covered by one `finally` (#105).
     *
     * [requestDriveAccess] returns as soon as it publishes the `PendingIntent`; the screen
     * launches the sheet a frame or two later. Releasing the claim at that return left the
     * Connect button live in between, and a tap landing there started a second `authorize()`
     * that took the claim - so the real [onConsentResult] was discarded and a grant Google had
     * already approved was lost, with no error and no retry.
     *
     * It is released by [onConsentResult] - through `claimingDriveConnect(continuesConsent)`,
     * which takes the claim over rather than contending for it - and **not** at launch time: the
     * window still has focus for the hundreds of milliseconds the consent activity takes to
     * appear, so a claim dropped at launch leaves Connect live long enough for a second tap to
     * open a second sheet over the first.
     *
     * That it cannot outlive a flow which never returns - the leak that would be a permanently
     * disabled Connect button, #102 in a new place - rests on three things rather than on a
     * cleanup hook. It is only ever set together with [_consentRequest]; while that request is
     * live any composition of the settings screen launches it, and `ActivityResultRegistry`
     * answers a launch even when the intent sender is dead, so a result always arrives; and the
     * view model dies with the settings back-stack entry, so leaving outright ends it too.
     */
    private val _awaitingConsent = MutableStateFlow(false)

    /**
     * True while a Drive grant operation is in flight, the consent sheet's round trip included.
     * The connect queues behind the session lock and crosses Play Services, so the button must
     * render the wait rather than look live.
     *
     * Eagerly rather than `WhileSubscribed`, as [upgradeBusy]: with no collector the combine
     * never runs and `value` answers from a stale cache.
     */
    val driveConnecting: StateFlow<Boolean> =
        combine(_driveConnecting, _awaitingConsent) { connecting, consent -> connecting || consent }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

            // An account already connected needs no grant request, so the loop starts without
            // going near the claim - otherwise a mode chosen while some other grant operation
            // was running would be persisted and then never acted on.
            if (driveAuthManager.isAuthorized.value) {
                cloudSyncManager.startSync()
                return@launch
            }

            // Uploads cannot work without the grant, so ask for it as part of turning the mode
            // on rather than silently queueing segments that will never leave. If another grant
            // request is already running - or a consent sheet is still out - that one starts the
            // loop when it finishes, through here or through `completeConsent`.
            claimingDriveConnect {
                if (requestDriveAccess()) cloudSyncManager.startSync()
            }
        }
    }

    fun connectDrive() {
        viewModelScope.launch {
            claimingDriveConnect {
                if (!isCloudAccessAllowed()) return@claimingDriveConnect
                if (requestDriveAccess()) startSyncEnablingUploads()
            }
        }
    }

    /**
     * Runs [block] as *the* Drive grant operation, or not at all if one is already running.
     *
     * Every path that asks `DriveAuthManager` for a grant goes through here - the Connect button,
     * an upload-mode change, and the consent result - because they all queue on the one session
     * lock and all used to render as nothing while they waited (#102). Two at once is worse than
     * a discarded tap: the second publishes a second `_consentRequest`, which the screen launches
     * as a second consent sheet over the first.
     *
     * The claim is released only by the call that took it, in a `finally` - never by the user
     * leaving the screen. See [planLoadGeneration] above for why: a flag freed from outside the
     * call it guards lets a second operation start over the top of one still running. The one
     * exception is [_awaitingConsent], which guards no running call at all - `authorize()` has
     * already returned by then, and what is outstanding is a UI round trip rather than work.
     *
     * [continuesConsent] marks the call that adopts the result of that round trip; it owns
     * [_awaitingConsent], so it passes that guard and takes the claim over without a live gap.
     * It is defaulted rather than required - unlike the parameter #98 argues about, the default
     * is the *stricter* behaviour, so a new call site that forgets it is refused rather than
     * waved through. It still refuses while a real call is running, which is the one case where
     * an approved grant can still be dropped; that is logged rather than silent.
     */
    private suspend fun claimingDriveConnect(
        continuesConsent: Boolean = false,
        block: suspend () -> Unit
    ) {
        if (_driveConnecting.value) {
            // A tripwire rather than a path with a known trigger: every other claim taker is
            // refused while a consent is outstanding, so reaching this needs a second result
            // delivered while the first is still being adopted. A dropped grant is #105's own
            // harm, so if it ever does happen it must not be invisible on a release build -
            // Log.w survives `-assumenosideeffects`, Log.d and Log.i do not (#107).
            if (continuesConsent) Log.w(TAG, "Consent result dropped: a grant operation is running")
            return
        }
        if (_awaitingConsent.value && !continuesConsent) return
        _driveConnecting.value = true
        _awaitingConsent.value = false
        try {
            block()
        } finally {
            _driveConnecting.value = false
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
                // Claimed before the request is published, so the screen cannot launch the sheet
                // and answer it in between; see [_awaitingConsent].
                //
                // Published even when the user has already left the screen, which looks wrong and
                // is not: `authorize()` calls `clearLocked()` before it reports ConsentRequired,
                // so by this line the session is gone and this intent is the only route back to a
                // working account. Dropping it would disconnect Drive silently - no sheet, no
                // error, uploads simply stopped - which is a worse version of the lost grant this
                // issue is about. It is picked up by the next composition instead.
                _awaitingConsent.value = true
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

    /**
     * Connecting Drive *is* the request to upload, so an `OFF` mode at this point is the default
     * nobody chose rather than a decision. Leaving it was #80: consent completed, the screen read
     * "Connected", and `canSyncNow()` refused at its first gate forever, uploading nothing and
     * logging nothing. `WIFI_ONLY` rather than `ALWAYS_ON` because opting someone onto cellular
     * data is not ours to assume. Only reached from an explicit connect or consent result - never
     * from the ungated `restoreSession()` in `init`, which must not resurrect uploads the user
     * turned off.
     */
    private suspend fun startSyncEnablingUploads() {
        if (appPreferences.cloudUploadMode.first() == CloudUploadMode.OFF) {
            appPreferences.setCloudUploadMode(CloudUploadMode.WIFI_ONLY)
        }
        cloudSyncManager.startSync()
    }

    fun onConsentLaunched() {
        // Only the request is consumed here, never the claim. Releasing the claim at launch
        // leaves Connect live while the consent activity is still starting - hundreds of
        // milliseconds in which the window still has focus - and a tap there publishes a second
        // request that the still-composed LaunchedEffect opens as a second sheet over the first.
        _consentRequest.value = null
    }

    fun onConsentResult(data: Intent?) {
        viewModelScope.launch {
            // Adopting the grant crosses the same lock and lookup, so it is busy too, and it
            // continues the claim the published request took rather than starting a new one. It
            // is deliberately not conditional on that claim still being held: a process death
            // during the sheet replaces this view model, and the restored launcher then delivers
            // a real grant to a fresh instance that was never awaiting it. A grant operation
            // actually running is the one thing that still refuses it, and logs when it does.
            claimingDriveConnect(continuesConsent = true) { completeConsent(data) }
        }
    }

    private suspend fun completeConsent(data: Intent?) {
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
            return
        }

        onDriveConnected()
        startSyncEnablingUploads()
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

    private val _redeemDialogVisible = MutableStateFlow(false)
    val redeemDialogVisible: StateFlow<Boolean> = _redeemDialogVisible.asStateFlow()

    private val _redeemOutcome = MutableStateFlow<RedeemResult?>(null)

    val redeemOutcome: StateFlow<RedeemResult?> = _redeemOutcome.asStateFlow()

    /** See [planLoadGeneration]; the acceptance dialog is raised by the answer, not by a tap. */
    private var redeemGeneration = 0

    private val _redeemInFlight = MutableStateFlow(false)

    /** Rendered by the Redeem button: a tap while this is set is discarded, so it must not look
     * live. The first write to a cold DataStore is a real disk round-trip. */
    val redeemInFlight: StateFlow<Boolean> = _redeemInFlight.asStateFlow()

    fun showRedeemDialog() {
        _redeemOutcome.value = null
        _redeemDialogVisible.value = true
    }

    fun dismissRedeemDialog() {
        // Cancelling abandons the answer too, like dismissing the upgrade offer: a REJECTED
        // landing afterwards would mark a code the user has not submitted as invalid.
        redeemGeneration++
        _redeemDialogVisible.value = false
    }

    fun dismissRedeemResult() {
        _redeemOutcome.value = null
    }

    /**
     * The dialog closes only on acceptance - a rejected code leaves the field up to be corrected
     * rather than making the user find the entry point again.
     *
     * An answer that arrives after the user has left is discarded rather than published: an
     * ACCEPTED landing then is the render condition for the confirmation dialog, which would
     * otherwise be waiting on the screen the next time Settings is opened.
     */
    fun redeemCode(code: String) {
        if (_redeemInFlight.value) return
        val issued = redeemGeneration
        _redeemInFlight.value = true
        viewModelScope.launch {
            try {
                val result = complimentaryAccessStore.redeem(code)
                if (issued != redeemGeneration) return@launch
                _redeemOutcome.value = result
                if (result == RedeemResult.ACCEPTED) _redeemDialogVisible.value = false
            } finally {
                _redeemInFlight.value = false
            }
        }
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
        // The consent claim and its request are deliberately left alone here, unlike every other
        // dialog this drops: `authorize()` has already called `clearLocked()` by the time either
        // exists, so the request is not a stale offer but the only route back to a working
        // account, and dropping it would disconnect Drive silently. They are not a leak either -
        // see [_awaitingConsent] for what ends the claim.
        abandonPendingPlanLoad()
        // Leaving abandons the purchase too, unlike dismissing the offer: its answer would
        // otherwise raise a failure dialog over this screen on the way back.
        // `_purchaseInFlight` is left set: it is the only thing stopping a second hand-off, and
        // the first has not been cancelled. Clearing it let the user leave, pick the other plan
        // from the main screen's Upgrade, and be shown two Play sheets - the first for the plan
        // they had moved on from.
        purchaseGeneration++
        // The offer goes with them, like the chooser: it was raised by an action they never
        // answered, and it would otherwise be waiting on the screen when they came back.
        _showUpgradeDialog.value = false
        _subscriptionPlans.value = null
        _purchaseError.value = null
        // Same reason: a half-typed code and its last rejection would be waiting on the screen
        // on the way back from the disclaimer.
        redeemGeneration++
        _redeemDialogVisible.value = false
        _redeemOutcome.value = null
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
