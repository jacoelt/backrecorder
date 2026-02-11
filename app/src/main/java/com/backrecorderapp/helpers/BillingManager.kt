package com.backrecorderapp.helpers

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
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
import com.backrecorderapp.workers.TrialReminderWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val SKU_ID = "main_subscription"
const val REMINDER_DAYS_BEFORE_END = 3L

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
    val trialEndMillis: Long?
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
    private val context: Context,
    private val subscriptionManager: SubscriptionManager,
    private val trialManager: TrialManager,
) : PurchasesUpdatedListener {

    private val productDetailsMap = mutableMapOf<String, ProductDetails>()
    private val billingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        check(billingClient.isReady) { "BillingClient not ready" }

        Log.d(TAG, "launchSubscriptionPurchase: Start")
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

        Log.d(TAG, "launchSubscriptionPurchase: launch billing flow $billingFlowParams")
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
                    billingScope.launch {
                        onSubscriptionActivated(purchase)
                    }
                } else {
                    Log.e(TAG, "Failed to acknowledge purchase: ${billingResult.responseCode}")
                }
            }
        } else {
            // Already acknowledged (e.g. restored purchase)
            billingScope.launch {
                onSubscriptionActivated(purchase)
            }
        }
    }

    private suspend fun onSubscriptionActivated(purchase: Purchase) {
        Log.d(TAG, "Subscription activated")
        subscriptionManager.setMode(SubscriptionMode.PREMIUM)
        trialManager.setTrialStartTime(purchase.purchaseTime)

        val subscriptionOffers = subscriptionManager.currentOffers
        val trialEndMillis = subscriptionOffers.value?.monthly?.trialEndMillis
            ?: subscriptionOffers.value?.yearly?.trialEndMillis
            ?: return

        scheduleTrialReminder(context, trialEndMillis)
    }

    private fun scheduleTrialReminder(context: Context, trialEndMillis: Long) {
        val reminderTime = trialEndMillis - TimeUnit.DAYS.toMillis(REMINDER_DAYS_BEFORE_END)
        val delay = reminderTime - System.currentTimeMillis()

        if (delay <= 0) return

        val data = Data.Builder()
            .putLong(TrialReminderWorker.INPUT_TRIAL_END, trialEndMillis)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<TrialReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "EndOfTrialReminder",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    suspend fun syncSubscriptionState() {
        val purchases: List<Purchase>
        try {
            purchases = getActiveSubscriptions()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync subscriptions", e)
            return
        }

        if (purchases.isEmpty()) {
            subscriptionManager.setMode(SubscriptionMode.FREE)
            return
        }

        val purchase = purchases.first()

        val offers = subscriptionManager.currentOffers.value
            ?: run {
                subscriptionManager.setMode(SubscriptionMode.FREE)
                return
            }

        val matchingOffer =
            listOf(offers.monthly, offers.yearly)
                .firstOrNull { offer ->
                    purchase.products.contains(offer.productId)
                }

        val mode = matchingOffer?.let {
            resolveSubscriptionMode(purchase, it)
        } ?: SubscriptionMode.FREE

        subscriptionManager.setMode(mode)
    }

    private fun resolveSubscriptionMode(
        purchase: Purchase,
        offer: SubscriptionOffer
    ): SubscriptionMode {

        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            return SubscriptionMode.FREE
        }

        if (!offer.hasTrial || offer.trialEndMillis == null) {
            return SubscriptionMode.PREMIUM
        }

        val now = System.currentTimeMillis()

        return if (now < offer.trialEndMillis) {
            SubscriptionMode.TRIAL
        } else {
            SubscriptionMode.PREMIUM
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
                if (!cont.isActive) return@queryPurchasesAsync
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    cont.resume(purchasesList)
                } else {
                    cont.resumeWithException(RuntimeException("Billing query failed: ${billingResult.responseCode}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error when connecting to google billing", e)
            if (!cont.isActive) return@suspendCancellableCoroutine
            cont.resumeWithException(e)
        }
    }

    suspend fun getSubscriptionOffers(): SubscriptionOffers? = suspendCancellableCoroutine { cont ->

        Log.d(TAG, "getSubscriptionOffers: Start")

        if (!billingClient.isReady) {
            Log.d(TAG, "getSubscriptionOffers: Billing client not ready")
            if (!cont.isActive) return@suspendCancellableCoroutine
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
                if (!cont.isActive) return@queryProductDetailsAsync
                cont.resumeWithException(
                    RuntimeException("Product query failed: ${billingResult.responseCode}")
                )
                return@queryProductDetailsAsync
            }

            val productDetails = productDetailsList.productDetailsList.firstOrNull()
            if (productDetails == null) {
                Log.d(TAG, "getSubscriptionOffers: No offers found")
                if (!cont.isActive) return@queryProductDetailsAsync
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

                val trialEndMillis = trialPhase?.let { System.currentTimeMillis() + iso8601PeriodToMillis(it.billingPeriod) }

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
                    trialPeriod = trialPhase?.billingPeriod,
                    trialEndMillis = trialEndMillis,
                )


                when (recurringPhase.billingPeriod) {
                    "P1Y" -> yearly = offerInstance
                    "P1M" -> monthly = offerInstance
                }
            }
            if (yearly == null || monthly == null) {
                Log.d(TAG, "Incorrect subscription offers configuration. Yearly: $yearly ; Monthly: $monthly")
                if (!cont.isActive) return@queryProductDetailsAsync
                cont.resumeWithException(
                    RuntimeException("Incorrect subscription offers configuration")
                )
                return@queryProductDetailsAsync
            }

            val offers = SubscriptionOffers(yearly, monthly)

            subscriptionManager.setCurrentOffers(offers)

            Log.d(TAG, "getSubscriptionOffers: Result $offers")
            cont.resume(offers)
        }
    }

    /**
     * Convert an ISO 8601 period (ex: P7D, P1M) to milliseconds
     */
    private fun iso8601PeriodToMillis(period: String): Long {
        return when {
            period.startsWith("P") && period.endsWith("D") -> {
                val days = period.removePrefix("P").removeSuffix("D").toLongOrNull() ?: 0L
                TimeUnit.DAYS.toMillis(days)
            }
            period.startsWith("P") && period.endsWith("M") -> {
                val months = period.removePrefix("P").removeSuffix("M").toLongOrNull() ?: 0L
                TimeUnit.DAYS.toMillis(months * 30)
            }
            period.startsWith("P") && period.endsWith("Y") -> {
                val years = period.removePrefix("P").removeSuffix("Y").toLongOrNull() ?: 0L
                TimeUnit.DAYS.toMillis(years * 365)
            }
            else -> 0L
        }
    }

    fun endConnection() {
        if (billingClient.isReady) billingClient.endConnection()
    }
}