package com.backrecorderapp.ui.main

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.cloud.CloudSyncManager
import com.backrecorderapp.data.db.SegmentEntity
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.segment.SegmentManager
import com.backrecorderapp.data.waveform.WaveformRepository
import com.backrecorderapp.domain.BlockReason
import com.backrecorderapp.domain.GateResult
import com.backrecorderapp.domain.SaveRecordingUseCase
import com.backrecorderapp.domain.SaveResult
import com.backrecorderapp.domain.SubscriptionGate
import com.backrecorderapp.data.billing.PlanQuery
import com.backrecorderapp.data.billing.PurchaseFailure
import com.backrecorderapp.data.billing.SubscriptionPlan
import com.backrecorderapp.data.billing.SubscriptionPlans
import com.backrecorderapp.service.RecordingService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val MINUTE_MS = 60_000L

data class MainUiState(
    val isRecording: Boolean = false,
    val segments: List<SegmentEntity> = emptyList(),
    val waveformData: FloatArray = floatArrayOf(),
    val bufferStartTime: Long = 0L,
    val bufferEndTime: Long = 0L,
    val selectionStartTime: Long = 0L,
    val selectionEndTime: Long = 0L,
    val saveInProgress: Boolean = false,
    val launchSavePicker: Boolean = false,
    val showUpgradeDialog: Boolean = false,
    val upgradeReason: BlockReason? = null,
    val plansLoading: Boolean = false,
    val purchaseInFlight: Boolean = false,
    val subscriptionPlans: SubscriptionPlans? = null,
    val purchaseError: PurchaseFailure? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val recoveryMessage: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MainUiState) return false
        return isRecording == other.isRecording &&
            segments == other.segments &&
            waveformData.contentEquals(other.waveformData) &&
            bufferStartTime == other.bufferStartTime &&
            bufferEndTime == other.bufferEndTime &&
            selectionStartTime == other.selectionStartTime &&
            selectionEndTime == other.selectionEndTime &&
            saveInProgress == other.saveInProgress &&
            launchSavePicker == other.launchSavePicker &&
            showUpgradeDialog == other.showUpgradeDialog &&
            upgradeReason == other.upgradeReason &&
            plansLoading == other.plansLoading &&
            purchaseInFlight == other.purchaseInFlight &&
            subscriptionPlans == other.subscriptionPlans &&
            purchaseError == other.purchaseError &&
            errorMessage == other.errorMessage &&
            successMessage == other.successMessage &&
            recoveryMessage == other.recoveryMessage
    }

    override fun hashCode(): Int {
        var result = isRecording.hashCode()
        result = 31 * result + segments.hashCode()
        result = 31 * result + waveformData.contentHashCode()
        result = 31 * result + bufferStartTime.hashCode()
        result = 31 * result + bufferEndTime.hashCode()
        result = 31 * result + selectionStartTime.hashCode()
        result = 31 * result + selectionEndTime.hashCode()
        result = 31 * result + saveInProgress.hashCode()
        result = 31 * result + launchSavePicker.hashCode()
        result = 31 * result + showUpgradeDialog.hashCode()
        result = 31 * result + (upgradeReason?.hashCode() ?: 0)
        result = 31 * result + plansLoading.hashCode()
        result = 31 * result + purchaseInFlight.hashCode()
        result = 31 * result + (subscriptionPlans?.hashCode() ?: 0)
        result = 31 * result + (purchaseError?.hashCode() ?: 0)
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + (successMessage?.hashCode() ?: 0)
        result = 31 * result + (recoveryMessage?.hashCode() ?: 0)
        return result
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val segmentManager: SegmentManager,
    private val waveformRepository: WaveformRepository,
    private val saveRecordingUseCase: SaveRecordingUseCase,
    private val appPreferences: AppPreferences,
    private val cloudSyncManager: CloudSyncManager,
    private val billingRepository: BillingRepository,
    private val subscriptionGate: SubscriptionGate
) : ViewModel() {

    /**
     * Bumped whenever the user abandons something they started; an answer carrying a stale value
     * is dropped instead of written.
     *
     * A generation decides only whether an answer is **published**. It never frees a busy flag:
     * the call is still running, and that flag is the only thing stopping a second one from
     * starting alongside it. So abandoning leaves `plansLoading` / `purchaseInFlight` set and
     * each `finally` clears its own unconditionally - which also means two operations of the
     * same kind can never be in flight at once, nor share an `issued` value.
     *
     * Plain `Int`s, read and written without synchronization, because every mutator here runs on
     * the main thread: each is called from a Compose lambda or from a `viewModelScope` body, and
     * `viewModelScope` is `Dispatchers.Main.immediate`. The same confinement is what makes the
     * `_uiState.value = _uiState.value.copy(...)` read-modify-writes below safe. Calling any of
     * them from another dispatcher would break both at once.
     *
     * The load and the hand-off count **separately**, which is the point: dismissing the upgrade
     * offer abandons the load that offer started, but a purchase already on its way to Play is
     * none of its business - one counter for both meant Cancel on a re-raised offer dialog threw
     * away a live purchase's answer and freed the guard that stops a second one.
     *
     * Abandonment is deliberately *not* implemented by cancelling the coroutine. The call it is
     * waiting on is `PlayBillingRepository`'s, which shares one connection attempt across every
     * caller: a cancelled caller's cleanup completes that shared attempt with a failure,
     * answering the launch-time resolve ladder - the thing `statusResolved`, and so cloud sync
     * for a paying subscriber, depends on - with a refusal nobody asked for. (View-model
     * teardown still cancels it; nothing is watching by then.)
     */
    private var planLoadGeneration = 0

    private var purchaseGeneration = 0

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        observeSegments()
        observeRecoveryMessages()
    }

    private fun observeSegments() {
        viewModelScope.launch {
            combine(
                segmentManager.getAllSegments(),
                RecordingService.isRunning
            ) { segments, isRecording ->
                Pair(segments, isRecording)
            }.collect { (segments, isRecording) ->
                val now = System.currentTimeMillis()
                val waveformData = loadWaveformData(segments)
                val bufferStart = segments.minOfOrNull { it.startTime } ?: now
                val bufferEnd = segments.maxOfOrNull { it.endTime.takeIf { e -> e > 0 } ?: now } ?: now

                _uiState.value = _uiState.value.copy(
                    isRecording = isRecording,
                    segments = segments,
                    waveformData = waveformData,
                    bufferStartTime = bufferStart,
                    bufferEndTime = bufferEnd,
                    selectionStartTime = if (_uiState.value.selectionStartTime == 0L) {
                        (bufferEnd - 5 * 60 * 1000).coerceAtLeast(bufferStart)
                    } else {
                        _uiState.value.selectionStartTime
                    },
                    selectionEndTime = if (_uiState.value.selectionEndTime == 0L) {
                        bufferEnd
                    } else {
                        _uiState.value.selectionEndTime
                    }
                )
            }
        }
    }

    private fun observeRecoveryMessages() {
        viewModelScope.launch {
            cloudSyncManager.recoveryMessage.collect { message ->
                _uiState.value = _uiState.value.copy(recoveryMessage = message)
            }
        }
    }

    private fun loadWaveformData(segments: List<SegmentEntity>): FloatArray {
        val allAmplitudes = mutableListOf<Float>()
        for (segment in segments) {
            val file = File(segment.waveformPath)
            val amplitudes = waveformRepository.loadAmplitudes(file)
            allAmplitudes.addAll(amplitudes.toList())
        }
        return allAmplitudes.toFloatArray()
    }

    fun updateSelectionStart(fraction: Float) {
        val state = _uiState.value
        val range = state.bufferEndTime - state.bufferStartTime
        if (range <= 0) return
        val raw = state.bufferStartTime + (fraction * range).toLong()
        // Floor to previous minute so the start always rounds outward (earlier),
        // preserving any audio the user dragged near.
        val snapped = (raw / MINUTE_MS) * MINUTE_MS
        val clamped = snapped.coerceIn(state.bufferStartTime, state.selectionEndTime - MINUTE_MS)
        _uiState.value = state.copy(selectionStartTime = clamped)
    }

    fun updateSelectionEnd(fraction: Float) {
        val state = _uiState.value
        val range = state.bufferEndTime - state.bufferStartTime
        if (range <= 0) return
        val raw = state.bufferStartTime + (fraction * range).toLong()
        // Ceil to next minute so the end always rounds outward (later). The
        // clamp to bufferEndTime then lets the user reach the currently-recording
        // minute even when bufferEndTime is not on a minute boundary (issue #7).
        val snapped = ((raw + MINUTE_MS - 1) / MINUTE_MS) * MINUTE_MS
        val clamped = snapped.coerceIn(state.selectionStartTime + MINUTE_MS, state.bufferEndTime)
        _uiState.value = state.copy(selectionEndTime = clamped)
    }

    fun selectLastDuration(durationMs: Long) {
        val state = _uiState.value
        if (state.bufferEndTime <= state.bufferStartTime) return

        // Presets select an exact duration — no minute-snapping (that's for drag
        // handles only). When recording is active, use current wall-clock time
        // instead of stale bufferEndTime (which only updates on segment rotation).
        val end = if (state.isRecording) System.currentTimeMillis() else state.bufferEndTime
        val start = (end - durationMs).coerceAtLeast(state.bufferStartTime)

        _uiState.value = state.copy(
            selectionStartTime = start,
            selectionEndTime = end
        )
    }

    fun selectWholeBuffer() {
        val state = _uiState.value
        if (state.bufferEndTime <= state.bufferStartTime) return
        val end = if (state.isRecording) System.currentTimeMillis() else state.bufferEndTime
        _uiState.value = state.copy(
            selectionStartTime = state.bufferStartTime,
            selectionEndTime = end
        )
    }

    fun toggleRecording() {
        viewModelScope.launch {
            val isCurrentlyRecording = _uiState.value.isRecording
            if (isCurrentlyRecording) {
                RecordingService.stop(context)
                appPreferences.setWasRecordingBeforeShutdown(false)
            } else {
                appPreferences.setWasRecordingBeforeShutdown(true)
                RecordingService.start(context)
            }
        }
    }

    fun requestSave() {
        viewModelScope.launch {
            val state = _uiState.value

            // Preflight: never launch the SAF picker if the selection has no
            // recorded data — SAF creates a 0-byte file as soon as the picker
            // returns a URI, and the save would then fail on an empty selection
            // leaving that empty file behind (issue #23).
            val endTime = if (state.isRecording && state.selectionEndTime >= state.bufferEndTime) {
                System.currentTimeMillis()
            } else {
                state.selectionEndTime
            }
            val segments = segmentManager.getSegmentsInRange(state.selectionStartTime, endTime)
            if (segments.isEmpty()) {
                _uiState.value = state.copy(
                    errorMessage = "No recorded data found for the selected time range"
                )
                return@launch
            }

            val gateResult = subscriptionGate.checkSaveAccess(
                state.selectionStartTime,
                state.selectionEndTime
            )
            when (gateResult) {
                is GateResult.Allowed -> {
                    _uiState.value = state.copy(launchSavePicker = true)
                }
                is GateResult.Blocked -> {
                    _uiState.value = state.copy(
                        showUpgradeDialog = true,
                        upgradeReason = gateResult.reason
                    )
                }
            }
        }
    }

    fun onSavePickerLaunched() {
        _uiState.value = _uiState.value.copy(launchSavePicker = false)
    }

    fun saveRecording(outputUri: Uri) {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.value = state.copy(saveInProgress = true, errorMessage = null, successMessage = null)

            // When recording is active and the selection end is at the buffer edge,
            // snap to current wall-clock time so the in-progress segment is included.
            // bufferEndTime is stale between segment rotations (~60 s); without this
            // the most recent audio would be silently excluded.
            val endTime = if (state.isRecording && state.selectionEndTime >= state.bufferEndTime) {
                System.currentTimeMillis()
            } else {
                state.selectionEndTime
            }

            val result = saveRecordingUseCase.execute(
                startTime = state.selectionStartTime,
                endTime = endTime,
                outputUri = outputUri
            )

            when (result) {
                is SaveResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        saveInProgress = false,
                        successMessage = "Recording saved successfully"
                    )
                }
                is SaveResult.UpgradeRequired -> {
                    deleteOutputUri(outputUri)
                    _uiState.value = _uiState.value.copy(
                        saveInProgress = false,
                        showUpgradeDialog = true,
                        upgradeReason = result.reason
                    )
                }
                is SaveResult.Error -> {
                    deleteOutputUri(outputUri)
                    _uiState.value = _uiState.value.copy(
                        saveInProgress = false,
                        errorMessage = result.message
                    )
                }
            }
        }
    }

    // SAF creates a 0-byte destination file as soon as the picker returns a URI.
    // If the save fails for any reason, delete that empty file so we never leave
    // empty recordings on the user's storage (issue #23).
    private fun deleteOutputUri(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (_: Exception) {
            // Best-effort cleanup; ignore if the provider rejects deletion.
        }
    }

    /**
     * Loads the plans and opens the chooser. A refusal is reported like a refused hand-off, on
     * this screen only.
     */
    fun showPlanChooser() {
        val current = _uiState.value
        // A purchase already on its way to Play would otherwise be joined by a second chooser
        // opening behind Play's sheet.
        if (current.plansLoading || current.purchaseInFlight) return
        val issued = planLoadGeneration
        _uiState.value = current.copy(plansLoading = true)
        viewModelScope.launch {
            try {
                val query = billingRepository.loadSubscriptionPlans()
                if (issued != planLoadGeneration) return@launch
                _uiState.value = when (query) {
                    // The upgrade dialog stays up until there is something to replace it with,
                    // so the tap is never answered by an empty screen.
                    is PlanQuery.Available -> _uiState.value.copy(
                        showUpgradeDialog = false,
                        upgradeReason = null,
                        subscriptionPlans = query.plans
                    )
                    is PlanQuery.Unavailable -> _uiState.value.copy(
                        showUpgradeDialog = false,
                        upgradeReason = null,
                        purchaseError = query.failure
                    )
                }
            } finally {
                // Cleared whatever happened, and unconditionally: the flag means "a load is in
                // flight", not "someone is still listening". Abandonment deliberately leaves it
                // set, so this is always the load that set it. A flag left set would refuse
                // every later tap, which is the dead button again.
                _uiState.value = _uiState.value.copy(plansLoading = false)
            }
        }
    }

    /**
     * Stops listening to a plan load the user has walked away from.
     *
     * It keeps running - see [planLoadGeneration] for why cancelling it would be worse - but its
     * answer no longer reaches the screen. Without this, a chooser or an error dialog arrives
     * with no tap behind it, seconds or minutes later: the cross-screen latching #72 removed,
     * and it must not return through the chooser.
     *
     * A purchase in flight is deliberately untouched: it is not what the dismissed offer started.
     *
     * `plansLoading` stays **set** until the call itself returns. Clearing it here would free the
     * guard in [showPlanChooser] over a load that still holds the repository's purchase mutex, so
     * the next tap would queue behind a load nobody is waiting for and spend its whole timeout
     * budget on the lock - reporting BILLING_UNAVAILABLE over a connection that is working.
     */
    private fun abandonPendingPlanLoad() {
        planLoadGeneration++
    }

    /**
     * The user has left this screen for good - not merely rotated it.
     *
     * This view model outlives its screen: settings sits on top of it in the back stack, so
     * anything left in state here is rendered again on the way back. A configuration change must
     * *not* reach here - the screen is coming straight back, and discarding the chooser (or the
     * load behind it) would make a rotation silently swallow the tap.
     */
    fun onScreenLeft() {
        abandonPendingPlanLoad()
        // Leaving abandons the purchase too - unlike dismissing the offer, which has no business
        // with one. Its answer would otherwise raise a failure dialog on the way back.
        //
        // `purchaseInFlight` is left set for the same reason the load's flag is: it is the only
        // thing stopping a second hand-off, and the first one has *not* been cancelled. Clearing
        // it here let the user leave, come back (or reach settings' own Upgrade), pick the other
        // plan, and be shown two Play sheets - the first for the plan they had moved on from.
        // That is the arbitrary-plan hazard this issue exists to close, reached through the UI.
        purchaseGeneration++
        _uiState.value = _uiState.value.copy(
            subscriptionPlans = null,
            purchaseError = null
        )
    }

    fun dismissPlanChooser() {
        _uiState.value = _uiState.value.copy(subscriptionPlans = null)
    }

    /**
     * Hands off to Play for the plan the user picked, and reports back on this screen only.
     * [activity] is null when the screen could not resolve one, which is itself a failed
     * hand-off - saying nothing would be the dead button again.
     */
    fun launchPurchaseFlow(activity: Activity?, plan: SubscriptionPlan) {
        // Two Continue taps delivered before the dialog leaves composition would otherwise start
        // two hand-offs; the second serializes behind the first and reports a failure over a
        // purchase that is going ahead.
        if (_uiState.value.purchaseInFlight) return
        if (activity == null) {
            _uiState.value = _uiState.value.copy(
                subscriptionPlans = null,
                purchaseError = PurchaseFailure.LAUNCH_FAILED
            )
            return
        }
        val issued = purchaseGeneration
        // The chooser stays up, with Continue busy, until Play's sheet is in front of the user.
        // Closing it here left this screen with no dialog, no spinner and nothing disabled for
        // as long as preparing takes - the same silence as a dead button, with a charge at the
        // end of it. The screens render `purchaseInFlight`; only this keeps it on screen.
        _uiState.value = _uiState.value.copy(purchaseInFlight = true)
        viewModelScope.launch {
            try {
                val failure = billingRepository.launchPurchaseFlow(activity, plan)
                if (issued != purchaseGeneration) return@launch
                failure?.let {
                    _uiState.value = _uiState.value.copy(purchaseError = it)
                }
            } finally {
                // Unconditional, like the load's: nothing else clears this flag, so it is always
                // this hand-off's to drop.
                _uiState.value = _uiState.value.copy(
                    purchaseInFlight = false,
                    subscriptionPlans = null
                )
            }
        }
    }

    fun dismissPurchaseError() {
        _uiState.value = _uiState.value.copy(purchaseError = null)
    }

    fun dismissUpgradeDialog() {
        // The load that dialog's Upgrade button started goes with it: an answer still on its way
        // would raise the chooser, or an error, seconds after the user dismissed the offer.
        abandonPendingPlanLoad()
        _uiState.value = _uiState.value.copy(showUpgradeDialog = false, upgradeReason = null)
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun dismissSuccess() {
        _uiState.value = _uiState.value.copy(successMessage = null)
    }

    fun dismissRecoveryMessage() {
        cloudSyncManager.dismissRecoveryMessage()
    }
}
