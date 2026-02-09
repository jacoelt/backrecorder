package com.backrecorderapp.helpers

import kotlin.time.DurationUnit
import kotlin.time.toDuration

class TrialManager(
    private var dataStore: SettingsDataStore,
) {

    companion object {
        const val TRIAL_PERIOD: Int = 30
    }

    private var trialStartTime: Long? = null

    suspend fun load() {
        trialStartTime = dataStore.getTrialStartTime()
    }

    suspend fun setTrialStartTime(startTime: Long) {
        trialStartTime = startTime
        dataStore.setTrialStartTime(startTime)
    }

    /**
     * Returns remaining trial days (>= 0).
     */
    fun trialDaysRemaining(): Int {
        if (trialStartTime == null) {
            return TRIAL_PERIOD
        }

        val remaining = TRIAL_PERIOD.toDuration(DurationUnit.DAYS) - trialStartTime!!.toDuration(DurationUnit.MILLISECONDS)
        return if (remaining.isPositive()) remaining.inWholeDays.toInt() else 0
    }
}
