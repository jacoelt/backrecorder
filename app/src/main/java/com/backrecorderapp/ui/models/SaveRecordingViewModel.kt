package com.backrecorderapp.ui.models

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SaveRecordingViewModel(
    availableDurationMinutes: Int
) : ViewModel() {

    private val presets = listOf(5, 10, 30, 60)

    private val _uiState = MutableStateFlow(
        SaveRecordingUiState(
            availableDurationMinutes = availableDurationMinutes,
            selectedDurationMinutes = presets.first(),
            presetDurationsMinutes = presets
        )
    )
    val uiState: StateFlow<SaveRecordingUiState> = _uiState.asStateFlow()

    fun onPresetSelected(minutes: Int) {
        updateSelectedDuration(minutes)
    }

    fun onDurationChanged(minutes: Int) {
        updateSelectedDuration(minutes)
    }

    private fun updateSelectedDuration(minutes: Int) {
        val clamped = minutes.coerceIn(
            minimumValue = 1,
            maximumValue = _uiState.value.availableDurationMinutes
        )

        _uiState.update { state ->
            state.copy(selectedDurationMinutes = clamped)
        }
    }

    fun getSelectedDurationMinutes(): Int {
        return _uiState.value.selectedDurationMinutes
    }
}

data class SaveRecordingUiState(
    val availableDurationMinutes: Int,
    val selectedDurationMinutes: Int,
    val presetDurationsMinutes: List<Int>
)