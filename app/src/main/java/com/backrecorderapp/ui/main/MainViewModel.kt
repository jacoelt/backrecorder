package com.backrecorderapp.ui.main

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.backrecorderapp.data.cloud.CloudSyncManager
import com.backrecorderapp.data.db.SegmentEntity
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.segment.SegmentManager
import com.backrecorderapp.data.waveform.WaveformRepository
import com.backrecorderapp.domain.BlockReason
import com.backrecorderapp.domain.SaveRecordingUseCase
import com.backrecorderapp.domain.SaveResult
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
    private val cloudSyncManager: CloudSyncManager
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
        val snapped = ((raw + MINUTE_MS / 2) / MINUTE_MS) * MINUTE_MS
        val clamped = snapped.coerceIn(state.bufferStartTime, state.selectionEndTime - MINUTE_MS)
        _uiState.value = state.copy(selectionStartTime = clamped)
    }

    fun updateSelectionEnd(fraction: Float) {
        val state = _uiState.value
        val range = state.bufferEndTime - state.bufferStartTime
        if (range <= 0) return
        val raw = state.bufferStartTime + (fraction * range).toLong()
        val snapped = ((raw + MINUTE_MS / 2) / MINUTE_MS) * MINUTE_MS
        val clamped = snapped.coerceIn(state.selectionStartTime + MINUTE_MS, state.bufferEndTime)
        _uiState.value = state.copy(selectionEndTime = clamped)
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

    fun saveRecording(outputUri: Uri) {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.value = state.copy(saveInProgress = true, errorMessage = null, successMessage = null)

            val result = saveRecordingUseCase.execute(
                startTime = state.selectionStartTime,
                endTime = state.selectionEndTime,
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
