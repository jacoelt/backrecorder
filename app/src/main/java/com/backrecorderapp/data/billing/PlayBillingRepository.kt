package com.backrecorderapp.data.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PlayBilling"

/** The real [BillingRepository], backed by the Play Billing client. */
@Singleton
class PlayBillingRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : BillingRepository, PurchasesUpdatedListener {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * `extraBufferCapacity = 1` with no replay, so [reportFailure] can emit from the billing
     * client's own callback thread without suspending and without a scope of its own.
     */
    private val _purchaseFailures = MutableSharedFlow<PurchaseFailure>(extraBufferCapacity = 1)
    override val purchaseFailures: Flow<PurchaseFailure> = _purchaseFailures.asSharedFlow()

    private val _subscriptionStatus = MutableStateFlow(SubscriptionStatus.FREE)
    override val subscriptionStatus: StateFlow<SubscriptionStatus> =
        _subscriptionStatus.asStateFlow()

    private val _statusResolved = MutableStateFlow(false)

    override val statusResolved: StateFlow<Boolean> = _statusResolved.asStateFlow()

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

    override fun launchPurchaseFlow(activity: Activity) {
        if (!billingClient.isReady) {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryAndLaunchPurchase(activity)
                    } else {
                        reportFailure(
                            PurchaseFailure.BILLING_UNAVAILABLE,
                            "billing setup failed: ${result.responseCode} ${result.debugMessage}"
                        )
                    }
                }

                override fun onBillingServiceDisconnected() {
                    // Only reached after a connection that had succeeded; the setup callback
                    // above covers the failure this purchase was waiting on.
                }
            })
        } else {
            queryAndLaunchPurchase(activity)
        }
    }

    private fun reportFailure(failure: PurchaseFailure, detail: String) {
        Log.w(TAG, "Upgrade hand-off failed ($failure): $detail")
        _purchaseFailures.tryEmit(failure)
    }

    private fun queryAndLaunchPurchase(activity: Activity) {
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
            val productDetailsList = productDetailsResult.productDetailsList
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                reportFailure(
                    PurchaseFailure.BILLING_UNAVAILABLE,
                    "queryProductDetails: ${billingResult.responseCode} " +
                        billingResult.debugMessage
                )
                return@queryProductDetailsAsync
            }
            if (productDetailsList.isEmpty()) {
                // Play has no such product for this account/build: the product id does not exist
                // in the Play Console, is not active, or this build is not on a track the test
                // account can buy from. Nothing the app can do, but the user must be told.
                reportFailure(
                    PurchaseFailure.PRODUCT_UNAVAILABLE,
                    "no product details for ${BillingRepository.PREMIUM_SUBSCRIPTION_ID}"
                )
                return@queryProductDetailsAsync
            }

            val productDetails = productDetailsList[0]
            val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (offerToken == null) {
                reportFailure(
                    PurchaseFailure.PRODUCT_UNAVAILABLE,
                    "no subscription offer on ${productDetails.productId}"
                )
                return@queryProductDetailsAsync
            }

            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .setOfferToken(offerToken)
                            .build()
                    )
                )
                .build()

            // launchBillingFlow must run on the main thread, and this callback carries no such
            // guarantee - off it, Play answers DEVELOPER_ERROR and no sheet is ever shown.
            runOnMainThread {
                val launchResult = billingClient.launchBillingFlow(activity, flowParams)
                if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    reportFailure(
                        PurchaseFailure.LAUNCH_FAILED,
                        "launchBillingFlow: ${launchResult.responseCode} " +
                            launchResult.debugMessage
                    )
                }
            }
        }
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    override suspend fun getSubscriptionStatus(): SubscriptionStatus = _subscriptionStatus.value

    override fun getTrialDaysRemaining(): Int {
        return if (_subscriptionStatus.value == SubscriptionStatus.TRIAL) 30 else 0
    }
}
