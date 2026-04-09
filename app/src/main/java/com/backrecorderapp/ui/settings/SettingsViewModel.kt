package com.backrecorderapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.billing.SubscriptionStatus
import com.backrecorderapp.data.cloud.CloudSyncManager
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.domain.GateResult
import com.backrecorderapp.domain.SubscriptionGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val recordingEnabled: Boolean = false,
    val cloudUploadMode: CloudUploadMode = CloudUploadMode.OFF,
    val subscriptionStatus: SubscriptionStatus = SubscriptionStatus.FREE,
    val trialDaysRemaining: Int = 0,
    val showUpgradeDialog: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val billingRepository: BillingRepository,
    private val subscriptionGate: SubscriptionGate,
    private val cloudSyncManager: CloudSyncManager
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        appPreferences.recordingEnabled,
        appPreferences.cloudUploadMode,
        billingRepository.subscriptionStatus
    ) { recording, cloudMode, subStatus ->
        SettingsUiState(
            recordingEnabled = recording,
            cloudUploadMode = cloudMode,
            subscriptionStatus = subStatus,
            trialDaysRemaining = billingRepository.getTrialDaysRemaining()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    private val _showUpgradeDialog = MutableStateFlow(false)
    val showUpgradeDialog: StateFlow<Boolean> = _showUpgradeDialog.asStateFlow()

    fun setCloudUploadMode(mode: CloudUploadMode) {
        viewModelScope.launch {
            if (mode != CloudUploadMode.OFF) {
                val gateResult = subscriptionGate.checkCloudAccess()
                if (gateResult is GateResult.Blocked) {
                    _showUpgradeDialog.value = true
                    return@launch
                }
            }

            appPreferences.setCloudUploadMode(mode)

            if (mode != CloudUploadMode.OFF) {
                cloudSyncManager.startSync()
            } else {
                cloudSyncManager.stopSync()
            }
        }
    }

    fun dismissUpgradeDialog() {
        _showUpgradeDialog.value = false
    }
}
