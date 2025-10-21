package com.backrecorder

import android.content.Context
import android.content.pm.PackageManager
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

object TrialManager {
    private val TRIAL_PERIOD: Duration = 30.toDuration(DurationUnit.DAYS)

    /**
     * Returns remaining trial days (>= 0).
     */
    fun trialDaysRemaining(context: Context): Long {
        val installTime = getInstallTime(context)
        val now = System.currentTimeMillis()
        val elapsed = (now - installTime).toDuration(DurationUnit.MILLISECONDS)
        val remaining = TRIAL_PERIOD - elapsed
        return if (remaining.isPositive()) remaining.inWholeDays else 0
    }

    /**
     * Safe and compatible way to get firstInstallTime across all API levels.
     */
    private fun getInstallTime(context: Context): Long {
        val packageManager = context.packageManager
        val packageName = context.packageName
        return try {
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            packageInfo.firstInstallTime
        } catch (e: Exception) {
            // fallback to current time if something goes wrong
            System.currentTimeMillis()
        }
    }
}
