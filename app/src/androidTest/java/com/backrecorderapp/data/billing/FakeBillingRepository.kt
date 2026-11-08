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

    /**
     * Refuses the next hand-off with this failure, so a test can drive the "nothing happens" path
     * that this fake otherwise always succeeds on.
     */
    var nextLaunchFails: PurchaseFailure? = null

    /** Refuses the next plan load, which is the other half of a refused upgrade. */
    var nextLoadFails: PurchaseFailure? = null

    /** Set by [launchPurchaseFlow], so a test can tell the screen handed off to Play. */
    var purchaseFlowLaunches: Int = 0
        private set

    /** The plan the screen last handed off, so a test can pin *which* plan it bought. */
    var lastPurchasedPlan: SubscriptionPlan? = null
        private set

    /**
     * What [loadSubscriptionPlans] answers: a monthly and a yearly plan priced so the yearly one
     * is ten months' worth, the same shape the Play Console is configured with. Prices are plain
     * strings because nothing here formats them - the real ones come from Play already localised.
     */
    var plans: SubscriptionPlans = SubscriptionPlans(
        listOf(
            SubscriptionPlan(
                period = BillingPeriod.MONTHLY,
                offerId = "monthly-free-trial",
                formattedPrice = "$2.99",
                priceAmountMicros = 2_990_000L,
                priceCurrencyCode = "USD",
                freeTrialDays = 7
            ),
            SubscriptionPlan(
                period = BillingPeriod.YEARLY,
                offerId = "yearly-free-trial",
                formattedPrice = "$29.90",
                priceAmountMicros = 29_900_000L,
                priceCurrencyCode = "USD",
                freeTrialDays = 7
            )
        )
    )

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

    override suspend fun loadSubscriptionPlans(): PlanQuery =
        nextLoadFails?.let { failure ->
            nextLoadFails = null
            PlanQuery.Unavailable(failure)
        } ?: PlanQuery.Available(plans)

    override suspend fun launchPurchaseFlow(
        activity: Activity,
        plan: SubscriptionPlan
    ): PurchaseFailure? {
        purchaseFlowLaunches++
        lastPurchasedPlan = plan
        return nextLaunchFails?.also { nextLaunchFails = null }
    }

    override suspend fun getSubscriptionStatus(): SubscriptionStatus = _subscriptionStatus.value

    /**
     * Always 0. Deliberately not a copy of the production trial length - nothing gated depends on
     * it, and duplicating the constant here would only let the two drift apart unnoticed.
     */
    override fun getTrialDaysRemaining(): Int = 0
}
