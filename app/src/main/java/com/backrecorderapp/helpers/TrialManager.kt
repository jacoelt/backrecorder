package com.backrecorderapp.helpers

import android.content.Context
import android.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class TrialManager(
    private var dataStore: SettingsDataStore,
) {

    companion object {
        const val TAG = "TrialManager"
        const val TRIAL_PERIOD: Int = 30

        @Volatile private var INSTANCE: TrialManager? = null

        fun getInstance(context: Context): TrialManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TrialManager(
                    SettingsDataStore.getInstance(context.applicationContext)
                ).also { INSTANCE = it }
            }
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
     * Returns the trial end date, or null if no trial has been started.
     */
    fun trialEndDate(): LocalDate? {
        val start = trialStartTime ?: return null
        return Instant.ofEpochMilli(start)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .plusDays(TRIAL_PERIOD.toLong())
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
