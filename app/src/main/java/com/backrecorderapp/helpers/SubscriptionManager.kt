package com.backrecorderapp.helpers

import android.content.Context
import android.util.Log
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
    companion object {
        private const val TAG = "SubscriptionManager"

        @Volatile private var INSTANCE: SubscriptionManager? = null

        fun getInstance(context: Context): SubscriptionManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SubscriptionManager(
                    SettingsDataStore.getInstance(context.applicationContext)
                ).also { INSTANCE = it }
            }
    }

    private val _mode = MutableStateFlow<SubscriptionMode?>(null)
    val mode: StateFlow<SubscriptionMode?> = _mode.asStateFlow()

    private val _currentOffers = MutableStateFlow<SubscriptionOffers?>(null)
    val currentOffers: StateFlow<SubscriptionOffers?> = _currentOffers.asStateFlow()

    suspend fun load() {
        _mode.value = dataStore.getSubscriptionMode()
        Log.d(TAG, "Loaded sub mode: ${_mode.value}")
    }

    suspend fun setMode(newMode: SubscriptionMode) {
        dataStore.saveSubscriptionMode(newMode)
        _mode.value = newMode
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

    fun clearOffers() {
        _currentOffers.value = null
    }
}
