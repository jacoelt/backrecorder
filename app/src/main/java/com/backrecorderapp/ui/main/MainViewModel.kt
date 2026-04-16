package com.backrecorderapp.ui.main

import android.app.Activity
import android.content.Context
import android.net.Uri
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
                    _uiState.value = _uiState.value.copy(
                        saveInProgress = false,
                        showUpgradeDialog = true,
                        upgradeReason = result.reason
                    )
                }
                is SaveResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        saveInProgress = false,
                        errorMessage = result.message
                    )
                }
            }
        }
    }

    fun launchPurchaseFlow(activity: Activity) {
        billingRepository.launchPurchaseFlow(activity)
    }

    fun dismissUpgradeDialog() {
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
