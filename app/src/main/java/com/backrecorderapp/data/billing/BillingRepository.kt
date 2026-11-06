package com.backrecorderapp.data.billing

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

enum class SubscriptionStatus {
    FREE, TRIAL, ACTIVE, EXPIRED
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

    fun launchPurchaseFlow(activity: Activity)

    suspend fun getSubscriptionStatus(): SubscriptionStatus

    fun getTrialDaysRemaining(): Int
}
