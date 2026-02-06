package com.backrecorderapp.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.backrecorderapp.helpers.SubscriptionManager
import com.backrecorderapp.helpers.SubscriptionMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
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
