package com.backrecorderapp.helpers

import android.util.Log
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class TrialManager(
    private var dataStore: SettingsDataStore,
) {

    companion object {
        const val TAG = "TrialManager"
        const val TRIAL_PERIOD: Int = 30
    }

    private var trialStartTime: Long? = null

    suspend fun load() {
        Log.d(TAG, "TrialManager load values")
        if (trialStartTime != null) return
        trialStartTime = dataStore.getTrialStartTime()
    }

    suspend fun setTrialStartTime(startTime: Long) {
        dataStore.setTrialStartTime(startTime)
        trialStartTime = startTime
    }

    /**
     * Returns remaining trial days (>= 0).
     */
    fun trialDaysRemaining(
        nowMillis: Long = System.currentTimeMillis()
    ): Int {
        Log.d(TAG, "Get trial remaining days")
        val start = trialStartTime ?: return 0

        val elapsed = (nowMillis - start)
            .toDuration(DurationUnit.MILLISECONDS)

        val remaining = TRIAL_PERIOD.toDuration(DurationUnit.DAYS) - elapsed
        return remaining.inWholeDays.toInt().coerceAtLeast(0)
    }
}
