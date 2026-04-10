package com.backrecorderapp.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.backrecorderapp.helpers.SubscriptionManager
import com.backrecorderapp.helpers.SubscriptionMode
import com.backrecorderapp.services.RecordingService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

class MainViewModel(
    private val subscriptionManager: SubscriptionManager
) : ViewModel() {

    val subscriptionMode = subscriptionManager.mode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SubscriptionMode.FREE
        )

    // Raw status from the service callback
    private val _recordingStatus = MutableStateFlow(RecordingService.RecordingStatus.STOPPED)

    // Debounced for the UI: STOPPED is delayed 300 ms to prevent flicker during file rotation
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiRecordingStatus: StateFlow<RecordingService.RecordingStatus> = _recordingStatus
        .transformLatest { status ->
            if (status != RecordingService.RecordingStatus.STOPPED) {
                emit(status)
            } else {
                delay(300)
                emit(status)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RecordingService.RecordingStatus.STOPPED
        )

    private val _currentDuration = MutableStateFlow(0)
    val currentDuration: StateFlow<Int> = _currentDuration.asStateFlow()

    private val _usingCloud = MutableStateFlow(false)
    val usingCloud: StateFlow<Boolean> = _usingCloud.asStateFlow()

    fun onRecordingStatusChanged(status: RecordingService.RecordingStatus) {
        _recordingStatus.value = status
    }

    fun onDurationChanged(duration: Int) {
        _currentDuration.value = duration
    }

    fun setUsingCloud(enabled: Boolean) {
        _usingCloud.value = enabled
    }

    fun onUpgradeToTrial() {
        viewModelScope.launch {
            subscriptionManager.setMode(SubscriptionMode.TRIAL)
        }
    }

    fun onUpgradeToPremium() {
        viewModelScope.launch {
            subscriptionManager.setMode(SubscriptionMode.PREMIUM)
        }
    }
}
