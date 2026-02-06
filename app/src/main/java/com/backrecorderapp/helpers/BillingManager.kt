package com.backrecorderapp.helpers

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.backrecorderapp.ui.SubscriptionOffer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SubscriptionOffer(
    val productId: String,
    val offerToken: String,
    val basePlanId: String,
    val billingPeriod: String, // P1M, P1Y, etc.
    val priceAmountMicros: Long,
    val priceCurrencyCode: String,
    val formattedPrice: String
)

/**
 * Simple BillingManager that:
 * - manages the BillingClient lifecycle
 * - exposes a suspend function to check if there's an active subscription
 *
 * Note: enablePendingPurchases(...) requires a PendingPurchasesParams instance.
 */
class BillingManager(
    private val context: Context,
    private val subscriptionManager: SubscriptionManager,
) : PurchasesUpdatedListener {

    private val productDetailsMap = mutableMapOf<String, ProductDetails>()

    companion object {
        private const val TAG = "BillingManager"
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
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
                    Log.e(TAG, "Billing setup failed: ${billingResult.responseCode}")
                }
            }

            override fun onBillingServiceDisconnected() {
                onDisconnected?.invoke()
            }
        })
    }

    fun launchSubscriptionPurchase(
        activity: Activity,
        offer: SubscriptionOffer
    ) {
        Log.e(TAG, "launchSubscriptionPurchase: Start")
        val productDetails = productDetailsMap[offer.productId]
        if (productDetails == null) {
            Log.e(TAG, "launchSubscriptionPurchase: ProductDetails not found for ${offer.productId}")
            return
        }

        val params =
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .setOfferToken(offer.offerToken)
                .build()

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(params))
            .build()

        Log.e(TAG, "launchSubscriptionPurchase: launch billing flow $billingFlowParams")
        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?
    ) {
        when (billingResult.responseCode) {

            BillingClient.BillingResponseCode.OK -> {
                if (purchases.isNullOrEmpty()) return

                purchases.forEach { purchase ->
                    handlePurchase(purchase)
                }
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "Purchase canceled by user")
            }

            else -> {
                Log.e(
                    TAG,
                    "Purchase failed: ${billingResult.responseCode} - ${billingResult.debugMessage}"
                )
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            return
        }

        // IMPORTANT: acknowledge subscriptions
        if (!purchase.isAcknowledged) {
            val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()

            billingClient.acknowledgePurchase(acknowledgeParams) { billingResult ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Purchase acknowledged")
                    runBlocking { onSubscriptionActivated() }
                } else {
                    Log.e(TAG, "Failed to acknowledge purchase: ${billingResult.responseCode}")
                }
            }
        } else {
            // Already acknowledged (e.g. restored purchase)

            runBlocking { onSubscriptionActivated() }
        }
    }

    private suspend fun onSubscriptionActivated() {
        Log.d(TAG, "Subscription activated")
        subscriptionManager.setMode(SubscriptionMode.PREMIUM)
    }

    suspend fun syncSubscriptionState() {
        if (hasActiveSubscription()) {
            subscriptionManager.setMode(SubscriptionMode.PREMIUM)
        }
    }

    /**
     * Returns true if there is at least one active (PURCHASED & acknowledged) subscription.
     */
    private suspend fun hasActiveSubscription(): Boolean {
        val purchases = try {
            withTimeout(5000) { getActiveSubscriptions() }
        } catch (e: Exception) {
            Log.w(TAG, "Error while getting subscriptions", e)
            return false
        }
        return purchases.any { p ->
            p.purchaseState == Purchase.PurchaseState.PURCHASED && p.isAcknowledged
        }
    }

    private suspend fun getActiveSubscriptions(): List<Purchase> = suspendCancellableCoroutine { cont ->
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        cont.invokeOnCancellation {
            Log.d(TAG, "Subscription query cancelled")
        }

        try {
            billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    cont.resume(purchasesList)
                } else {
                    cont.resumeWithException(RuntimeException("Billing query failed: ${billingResult.responseCode}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error when connecting to google billing", e)
            cont.resumeWithException(e)
        }
    }

    suspend fun getSubscriptionOffers(
        productId: String
    ): List<SubscriptionOffer> = suspendCancellableCoroutine { cont ->

        Log.d(TAG, "getSubscriptionOffers: Start")

        if (!billingClient.isReady) {
            Log.d(TAG, "getSubscriptionOffers: Billing client not ready")
            cont.resumeWithException(IllegalStateException("Billing client not ready"))
            return@suspendCancellableCoroutine
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->

            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "getSubscriptionOffers: Product query failed: ${billingResult.responseCode}")
                cont.resumeWithException(
                    RuntimeException("Product query failed: ${billingResult.responseCode}")
                )
                return@queryProductDetailsAsync
            }

            val productDetails = productDetailsList.productDetailsList.firstOrNull()
            if (productDetails == null) {
                cont.resume(emptyList())
                return@queryProductDetailsAsync
            }

            productDetailsMap[productDetails.productId] = productDetails

            val offers = productDetails.subscriptionOfferDetails.orEmpty()
                .flatMap { offer ->
                    val pricingPhases = offer.pricingPhases.pricingPhaseList

                    pricingPhases.map { phase ->
                        SubscriptionOffer(
                            productId = productDetails.productId,
                            offerToken = offer.offerToken,
                            basePlanId = offer.basePlanId,
                            billingPeriod = phase.billingPeriod,
                            priceAmountMicros = phase.priceAmountMicros,
                            priceCurrencyCode = phase.priceCurrencyCode,
                            formattedPrice = phase.formattedPrice
                        )
                    }
                }

            Log.d(TAG, "getSubscriptionOffers: Result $offers")
            cont.resume(offers)
        }
    }


    fun endConnection() {
        if (billingClient.isReady) billingClient.endConnection()
    }
}