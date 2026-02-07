package com.backrecorderapp.helpers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SubscriptionMode {
    FREE,
    TRIAL,
    PREMIUM
}

class SubscriptionManager(
    private var dataStore: SettingsDataStore,
) {

    private val _mode = MutableStateFlow(SubscriptionMode.FREE)
    val mode: StateFlow<SubscriptionMode> = _mode.asStateFlow()

    private val _currentOffers = MutableStateFlow<SubscriptionOffers?>(null)
    val currentOffers: StateFlow<SubscriptionOffers?> = _currentOffers.asStateFlow()

    suspend fun load() {
        _mode.value = dataStore.getSubscriptionMode()
    }

    suspend fun setMode(newMode: SubscriptionMode) {
        _mode.value = newMode
        dataStore.saveSubscriptionMode(newMode)
    }

    fun isPremium(): Boolean =
        _mode.value == SubscriptionMode.PREMIUM

    fun isTrial(): Boolean =
        _mode.value == SubscriptionMode.TRIAL

    fun isFree(): Boolean =
        _mode.value == SubscriptionMode.FREE

    fun setCurrentOffers(offers: SubscriptionOffers) {
        _currentOffers.value = offers
    }
}
