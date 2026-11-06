package com.backrecorderapp.data.billing

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A [BillingRepository] whose entitlement a test sets directly.
 *
 * The real one needs a Play Billing connection, which cannot resolve on a CI emulator: every
 * query times out and the status stays at its unresolved FREE default, so a premium flow could
 * never be exercised at all. This reports whatever the test asked for, immediately.
 *
 * It deliberately does not simulate a purchase flow - purchases happen in the Play Store, not
 * in-app, so status is the whole of what the app ever asks billing for. [launchPurchaseFlow]
 * only records that the screen tried to hand off to Play.
 */
@Singleton
class FakeBillingRepository @Inject constructor() : BillingRepository {

    private val _subscriptionStatus = MutableStateFlow(SubscriptionStatus.FREE)
    override val subscriptionStatus: StateFlow<SubscriptionStatus> =
        _subscriptionStatus.asStateFlow()

    private val _statusResolved = MutableStateFlow(false)
    override val statusResolved: StateFlow<Boolean> = _statusResolved.asStateFlow()

    /** Set by [launchPurchaseFlow], so a test can tell the screen handed off to Play. */
    var purchaseFlowLaunches: Int = 0
        private set

    /**
     * Reports [status], and reports billing as having answered. Tests that need the pre-answer
     * window - where the status is only a default and gating on it would block a subscriber -
     * use [setUnresolved] instead.
     */
    fun setStatus(status: SubscriptionStatus) {
        _subscriptionStatus.value = status
        _statusResolved.value = true
    }

    /** Puts billing back in the "has not answered yet" state it starts a process in. */
    fun setUnresolved() {
        _subscriptionStatus.value = SubscriptionStatus.FREE
        _statusResolved.value = false
    }

    override fun connect() = Unit

    override fun queryPurchases() = Unit

    override fun launchPurchaseFlow(activity: Activity) {
        purchaseFlowLaunches++
    }

    override suspend fun getSubscriptionStatus(): SubscriptionStatus = _subscriptionStatus.value

    /**
     * Always 0. Deliberately not a copy of the production trial length - nothing gated depends on
     * it, and duplicating the constant here would only let the two drift apart unnoticed.
     */
    override fun getTrialDaysRemaining(): Int = 0
}
