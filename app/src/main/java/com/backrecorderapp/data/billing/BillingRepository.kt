package com.backrecorderapp.data.billing

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

enum class SubscriptionStatus {
    FREE, TRIAL, ACTIVE, EXPIRED
}

/**
 * Why a hand-off to Play never reached the purchase sheet.
 *
 * Reported so the user is told something, and so a tester can tell a Play Console
 * misconfiguration (no such product, no offer on the base plan, an account not on a track that
 * can buy) from a connection problem.
 */
enum class PurchaseFailure {
    /** The billing client could not connect, or did not answer. */
    BILLING_UNAVAILABLE,

    /** Play has no purchasable offer for [BillingRepository.PREMIUM_SUBSCRIPTION_ID]. */
    PRODUCT_UNAVAILABLE,

    /** Play refused to show the purchase sheet, or there was no live screen to show it over. */
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
     * Puts the user in front of Play's purchase sheet, and answers the caller rather than
     * broadcasting: null when the sheet was shown, otherwise why it was not.
     *
     * The result goes back to the one screen that asked for it. An earlier version published
     * failures on a shared flow, which both view models collected - so a refusal in settings also
     * latched a snackbar on the main screen that fired whenever the user next went back.
     *
     * Always returns, including when Play never calls back. A hand-off that hangs is the dead
     * button this method exists to prevent.
     */
    suspend fun launchPurchaseFlow(activity: Activity): PurchaseFailure?

    suspend fun getSubscriptionStatus(): SubscriptionStatus

    fun getTrialDaysRemaining(): Int
}
