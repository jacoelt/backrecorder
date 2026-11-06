package com.backrecorderapp.domain

import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.billing.SubscriptionStatus
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class GateResult {
    data object Allowed : GateResult()
    data class Blocked(val reason: BlockReason) : GateResult()
}

enum class BlockReason {
    SAVE_RANGE_EXCEEDS_FREE_LIMIT,
    CLOUD_STORAGE_PREMIUM_ONLY
}

@Singleton
class SubscriptionGate @Inject constructor(
    private val billingRepository: BillingRepository
) {
    companion object {
        const val FREE_SAVE_LIMIT_MS = 15 * 60 * 1000L // 15 minutes
        private const val MINUTE_MS = 60_000L
    }

    suspend fun checkSaveAccess(startTime: Long, endTime: Long): GateResult {
        if (isPremium()) return GateResult.Allowed

        val now = System.currentTimeMillis()
        // One-minute grace: the selection start was computed against a
        // slightly-stale bufferEndTime, and the in-progress minute at the
        // recording edge may add extra seconds — both are acceptable.
        val freeWindowStart = now - FREE_SAVE_LIMIT_MS - MINUTE_MS

        return if (startTime >= freeWindowStart) {
            GateResult.Allowed
        } else {
            GateResult.Blocked(BlockReason.SAVE_RANGE_EXCEEDS_FREE_LIMIT)
        }
    }

    /**
     * False until billing has actually answered. Callers that gate on a schedule rather than on a
     * user action must wait for this: at process start the status is only its FREE default, and
     * acting on it would block a paying subscriber.
     */
    val statusResolved: StateFlow<Boolean> get() = billingRepository.statusResolved

    suspend fun checkCloudAccess(): GateResult {
        return if (isPremium()) {
            GateResult.Allowed
        } else {
            GateResult.Blocked(BlockReason.CLOUD_STORAGE_PREMIUM_ONLY)
        }
    }

    suspend fun isPremium(): Boolean {
        val status = billingRepository.getSubscriptionStatus()
        return status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.TRIAL
    }
}
