package com.backrecorder

import android.content.Context
import com.android.billingclient.api.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Simple BillingManager that:
 * - manages the BillingClient lifecycle
 * - exposes a suspend function to check if there's an active subscription
 *
 * Note: enablePendingPurchases(...) requires a PendingPurchasesParams instance.
 */
class BillingManager(private val context: Context) : PurchasesUpdatedListener {

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams
                .newBuilder()
                .enableOneTimeProducts()// required even if we only use subscriptions
                .build())
        .build()

    fun startConnection(onConnected: () -> Unit, onDisconnected: (() -> Unit)? = null) {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    onConnected()
                } else {
                    // Optionally log or handle non-OK result codes
                }
            }

            override fun onBillingServiceDisconnected() {
                onDisconnected?.invoke()
            }
        })
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        // Handle purchase updates if you initiate purchase flows here.
        // For subscription state checks we rely on queryPurchasesAsync().
    }

    /**
     * Returns true if there is at least one active (PURCHASED & acknowledged) subscription.
     */
    suspend fun hasActiveSubscription(): Boolean {
        return try {
            val purchases = querySubscriptions()
            purchases.any { p ->
                p.purchaseState == Purchase.PurchaseState.PURCHASED && p.isAcknowledged
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun querySubscriptions(): List<Purchase> = suspendCancellableCoroutine { cont ->
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                cont.resume(purchasesList)
            } else {
                cont.resumeWithException(RuntimeException("Billing query failed: ${billingResult.responseCode}"))
            }
        }
    }

    fun endConnection() {
        if (billingClient.isReady) billingClient.endConnection()
    }
}