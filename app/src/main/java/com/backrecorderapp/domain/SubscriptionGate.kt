package com.backrecorderapp.domain

import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.billing.SubscriptionStatus
import com.backrecorderapp.data.preferences.ComplimentaryAccessStore
import com.backrecorderapp.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    private val billingRepository: BillingRepository,
    private val complimentaryAccessStore: ComplimentaryAccessStore,
    @ApplicationScope scope: CoroutineScope
) {
    companion object {
        const val FREE_SAVE_LIMIT_MS = 15 * 60 * 1000L // 15 minutes
        private const val MINUTE_MS = 60_000L

        /**
         * The whole entitlement rule, as a pure function: a caller that already holds both terms
         * - the settings screen renders them - would otherwise restate it and drift from it.
         *
         * [complimentary] is required rather than defaulted so that adding it could not leave a
         * call site silently reading billing alone, which is the drift #85 exists to prevent.
         */
        fun isPremium(status: SubscriptionStatus, complimentary: Boolean): Boolean =
            complimentary ||
                status == SubscriptionStatus.ACTIVE ||
                status == SubscriptionStatus.TRIAL
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
    val statusResolved: StateFlow<Boolean> = combine(
        complimentaryAccessStore.granted,
        billingRepository.statusResolved
    ) { complimentary, billingResolved ->
        // Complimentary access is known locally and does not wait on Play, so for those users
        // this closes the #87 window rather than widening it: a Play that never answers no
        // longer leaves them unresolved for the session.
        complimentary || billingResolved
    }.stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun checkCloudAccess(): GateResult {
        return if (isPremium()) {
            GateResult.Allowed
        } else {
            GateResult.Blocked(BlockReason.CLOUD_STORAGE_PREMIUM_ONLY)
        }
    }

    suspend fun isPremium(): Boolean = isPremium(
        billingRepository.getSubscriptionStatus(),
        complimentaryAccessStore.isGranted()
    )
}
