package com.backrecorderapp.helpers

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SubscriptionMode {
    FREE,
    TRIAL,
    PREMIUM
}

class SubscriptionManager(context: Context) {

    private val dataStore = SettingsDataStore.getInstance(context.applicationContext)
    private val _mode = MutableStateFlow(SubscriptionMode.FREE)
    val mode: StateFlow<SubscriptionMode> = _mode.asStateFlow()

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
}
