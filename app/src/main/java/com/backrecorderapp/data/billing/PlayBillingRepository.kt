package com.backrecorderapp.data.billing

import android.app.Activity
import android.content.Context
import android.util.Log
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** The real [BillingRepository], backed by the Play Billing client. */
@Singleton
class PlayBillingRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : BillingRepository, PurchasesUpdatedListener {

    companion object {
        private const val TAG = "PlayBilling"

        /**
         * How long the whole hand-off may take before it is reported as unavailable.
         *
         * Play's callbacks are not guaranteed to arrive at all - the service can be killed or
         * mid-update - and a hand-off that waits forever is exactly the dead button this class
         * exists to prevent.
         */
        private const val LAUNCH_TIMEOUT_MS = 20_000L
    }

    private val _subscriptionStatus = MutableStateFlow(SubscriptionStatus.FREE)
    override val subscriptionStatus: StateFlow<SubscriptionStatus> =
        _subscriptionStatus.asStateFlow()

    private val _statusResolved = MutableStateFlow(false)

    override val statusResolved: StateFlow<Boolean> = _statusResolved.asStateFlow()

    /**
     * One hand-off at a time. A second tap while Play is still answering the first used to open
     * a second connection and a second query on the one client, which Play answers with an error
     * - so double-tapping a button that felt dead produced a false "unavailable" over a purchase
     * flow that was about to work.
     */
    private val launchInFlight = AtomicBoolean(false)

    private var billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build()
        )
        .build()

    override fun connect() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryPurchases()
                }
            }

            override fun onBillingServiceDisconnected() {
                // Retry on next user action
            }
        })
    }

    override fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { result, purchasesList ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                updateSubscriptionStatus(purchasesList)
            }
        }
    }

    private fun updateSubscriptionStatus(purchases: List<Purchase>) {
        val activePurchase = purchases.firstOrNull { purchase ->
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        }

        _subscriptionStatus.value = if (activePurchase == null) {
            SubscriptionStatus.FREE
        } else {
            SubscriptionStatus.ACTIVE
        }
        _statusResolved.value = true
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            updateSubscriptionStatus(purchases)
        }
    }

    override suspend fun launchPurchaseFlow(activity: Activity): PurchaseFailure? {
        // A tap that lands while the previous one is still being answered is dropped rather than
        // started again: the hand-off already under way will show the sheet or report why not.
        if (!launchInFlight.compareAndSet(false, true)) {
            Log.i(TAG, "Upgrade tap ignored: a hand-off is already in flight")
            return null
        }
        try {
            return withTimeoutOrNull(LAUNCH_TIMEOUT_MS) { launchPurchase(activity) }
                ?: reportFailure(PurchaseFailure.BILLING_UNAVAILABLE, "timed out after ${LAUNCH_TIMEOUT_MS}ms")
        } finally {
            launchInFlight.set(false)
        }
    }

    private suspend fun launchPurchase(activity: Activity): PurchaseFailure? {
        if (!billingClient.isReady) {
            val connection = connectForPurchase()
            if (connection != null) return connection
        }

        val details = queryPremiumOffer() ?: return reportFailure(
            PurchaseFailure.PRODUCT_UNAVAILABLE,
            "no product details for ${BillingRepository.PREMIUM_SUBSCRIPTION_ID}"
        )

        // Play has the product but no offer to buy it on: a base plan with no active offer, or
        // one this account cannot take.
        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
            ?: return reportFailure(
                PurchaseFailure.PRODUCT_UNAVAILABLE,
                "no subscription offer on ${details.productId}"
            )

        return showPurchaseSheet(activity, details, offerToken)
    }

    /** Non-null when the connection failed, carrying the failure to report. */
    private suspend fun connectForPurchase(): PurchaseFailure? =
        suspendCancellableCoroutine { continuation ->
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (!continuation.isActive) return
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        continuation.resume(null)
                    } else {
                        continuation.resume(
                            reportFailure(
                                PurchaseFailure.BILLING_UNAVAILABLE,
                                "billing setup failed: ${result.responseCode} ${result.debugMessage}"
                            )
                        )
                    }
                }

                override fun onBillingServiceDisconnected() {
                    // The service can die while this purchase's setup is still pending, in which
                    // case the callback above never arrives. Answer here rather than wait out the
                    // timeout.
                    if (!continuation.isActive) return
                    continuation.resume(
                        reportFailure(
                            PurchaseFailure.BILLING_UNAVAILABLE,
                            "billing service disconnected before setup finished"
                        )
                    )
                }
            })
        }

    /** The premium product's details, or null when Play has none to offer this account. */
    private suspend fun queryPremiumOffer(): ProductDetails? =
        suspendCancellableCoroutine { continuation ->
            val productList = listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(BillingRepository.PREMIUM_SUBSCRIPTION_ID)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build()

            billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsResult ->
                if (!continuation.isActive) return@queryProductDetailsAsync
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(
                        TAG,
                        "queryProductDetails: ${billingResult.responseCode} ${billingResult.debugMessage}"
                    )
                    continuation.resume(null)
                    return@queryProductDetailsAsync
                }
                continuation.resume(productDetailsResult.productDetailsList.firstOrNull())
            }
        }

    private suspend fun showPurchaseSheet(
        activity: Activity,
        details: ProductDetails,
        offerToken: String
    ): PurchaseFailure? = withContext(Dispatchers.Main) {
        // The query above crosses an async callback, so the screen that asked may be gone by now;
        // launching over a finishing Activity shows nothing and can still answer OK.
        if (activity.isFinishing || activity.isDestroyed) {
            return@withContext reportFailure(
                PurchaseFailure.LAUNCH_FAILED,
                "activity gone before the sheet could be shown"
            )
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()

        // launchBillingFlow must run on the main thread - off it, Play answers DEVELOPER_ERROR
        // and no sheet is ever shown. Hence the withContext above.
        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            null
        } else {
            reportFailure(
                PurchaseFailure.LAUNCH_FAILED,
                "launchBillingFlow: ${result.responseCode} ${result.debugMessage}"
            )
        }
    }

    /** Logs the detail the user never sees and returns [failure], so callers can `return` it. */
    private fun reportFailure(failure: PurchaseFailure, detail: String): PurchaseFailure {
        Log.w(TAG, "Upgrade hand-off failed ($failure): $detail")
        return failure
    }

    override suspend fun getSubscriptionStatus(): SubscriptionStatus = _subscriptionStatus.value

    override fun getTrialDaysRemaining(): Int {
        return if (_subscriptionStatus.value == SubscriptionStatus.TRIAL) 30 else 0
    }
}
