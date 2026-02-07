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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val SKU_ID = "main_subscription"

data class SubscriptionOffer(
    val productId: String,
    val offerToken: String,
    val basePlanId: String,

    val billingPeriod: String,      // P1M / P1Y (recurring)
    val formattedPrice: String,     // price after trial
    val priceAmountMicros: Long,
    val priceCurrencyCode: String,
    val monthlyEquivalent: String,

    val hasTrial: Boolean,
    val trialPeriod: String?,       // P30D
)

data class SubscriptionOffers (
    val yearly: SubscriptionOffer,
    val monthly: SubscriptionOffer,
)


/**
 * Simple BillingManager that:
 * - manages the BillingClient lifecycle
 * - exposes a suspend function to check if there's an active subscription
 *
 * Note: enablePendingPurchases(...) requires a PendingPurchasesParams instance.
 */
class BillingManager(
    context: Context,
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

    suspend fun getSubscriptionOffers(): SubscriptionOffers? = suspendCancellableCoroutine { cont ->

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
                        .setProductId(SKU_ID)
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
                Log.d(TAG, "getSubscriptionOffers: No offers found")
                cont.resume(null)
                return@queryProductDetailsAsync
            }

            productDetailsMap[productDetails.productId] = productDetails

            var yearly: SubscriptionOffer? = null
            var monthly: SubscriptionOffer? = null

            for (offer in productDetails.subscriptionOfferDetails.orEmpty()) {
                val phases = offer.pricingPhases.pricingPhaseList

                val trialPhase = phases.firstOrNull { it.priceAmountMicros == 0L }
                val recurringPhase = phases.firstOrNull { it.priceAmountMicros > 0 }

                if (recurringPhase == null) {
                    Log.d(TAG, "getSubscriptionOffers: No recurring phase found in offer")
                    continue
                }

                val monthlyEquivalent = if (recurringPhase.billingPeriod == "P1Y") {
                    val value =
                        recurringPhase.priceAmountMicros / 12.0 / 1_000_000.0
                    "%,.2f %s".format(
                        Locale.getDefault(),
                        value,
                        recurringPhase.priceCurrencyCode
                    )
                } else {
                    recurringPhase.formattedPrice
                }

                val offerInstance = SubscriptionOffer(
                    productId = productDetails.productId,
                    offerToken = offer.offerToken,
                    basePlanId = offer.basePlanId,
                    billingPeriod = recurringPhase.billingPeriod,
                    formattedPrice = recurringPhase.formattedPrice,
                    priceAmountMicros = recurringPhase.priceAmountMicros,
                    priceCurrencyCode = recurringPhase.priceCurrencyCode,
                    monthlyEquivalent = monthlyEquivalent,
                    hasTrial = trialPhase != null,
                    trialPeriod = trialPhase?.billingPeriod
                )


                when (recurringPhase.billingPeriod) {
                    "P1Y" -> yearly = offerInstance
                    "P1M" -> monthly = offerInstance
                }
            }
            if (yearly == null || monthly == null) {
                Log.d(TAG, "Incorrect subscription offers configuration. Yearly: $yearly ; Monthly: $monthly")
                cont.resumeWithException(
                    RuntimeException("Incorrect subscription offers configuration")
                )
                return@queryProductDetailsAsync
            }

            val offers = SubscriptionOffers(yearly, monthly)

            Log.d(TAG, "getSubscriptionOffers: Result $offers")
            cont.resume(offers)
        }
    }


    fun endConnection() {
        if (billingClient.isReady) billingClient.endConnection()
    }
}