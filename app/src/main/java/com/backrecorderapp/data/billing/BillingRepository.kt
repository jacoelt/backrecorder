package com.backrecorderapp.data.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class SubscriptionStatus {
    FREE, TRIAL, ACTIVE, EXPIRED
}

/**
 * Why a hand-off to Play never reached the purchase sheet.
 *
 * Each of these used to be a silent `return`: the upgrade dialog closed and nothing else
 * happened, which from the outside is a dead button. They are reported so the user sees
 * something, and so a tester can tell a Play Console misconfiguration (no such product, no offer
 * on the base plan, the account not on a track that can buy) from a connection problem.
 */
enum class PurchaseFailure {
    /** The billing client could not connect, or this device/account cannot buy at all. */
    BILLING_UNAVAILABLE,

    /**
     * Play answered but has no purchasable offer for [BillingRepository.PREMIUM_SUBSCRIPTION_ID]
     * - the usual cause of a dead Upgrade button on a fresh internal-test build.
     */
    PRODUCT_UNAVAILABLE,

    /** Play refused to show the purchase sheet. */
    LAUNCH_FAILED
}

/**
 * Entitlement seam. The app only ever asks what the subscription *is* - purchases happen in the
 * Play Store, not in-app - so this interface reports status and hands the purchase flow to Play.
 *
 * Its own interface, bound in [com.backrecorderapp.di.BillingModule], so instrumented tests can
 * replace just this binding with `@TestInstallIn(replaces = [BillingModule::class])`. A real Play
 * connection cannot resolve on a CI emulator, and without the seam every gated flow would read
 * FREE whatever the test intended.
 */
interface BillingRepository {

    companion object {
        const val PREMIUM_SUBSCRIPTION_ID = "premium"
    }

    val subscriptionStatus: StateFlow<SubscriptionStatus>

    /**
     * False until a purchase query has actually answered. Until then [subscriptionStatus] is only
     * its FREE default, so gating a premium feature on it would block a paying subscriber.
     */
    val statusResolved: StateFlow<Boolean>

    fun connect()

    fun queryPurchases()

    /**
     * Emitted when [launchPurchaseFlow] could not put the user in front of the Play purchase
     * sheet. A failure is an event, not a state to clear, so this is a hot stream with no
     * replay: a screen that is not listening at the time simply misses it.
     */
    val purchaseFailures: Flow<PurchaseFailure>

    fun launchPurchaseFlow(activity: Activity)

    suspend fun getSubscriptionStatus(): SubscriptionStatus

    fun getTrialDaysRemaining(): Int
}
