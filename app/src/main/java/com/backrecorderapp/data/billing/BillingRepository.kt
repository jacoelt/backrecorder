package com.backrecorderapp.data.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class SubscriptionStatus {
    FREE, TRIAL, ACTIVE, EXPIRED
}

@Singleton
class BillingRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : PurchasesUpdatedListener {

    companion object {
        const val PRODUCT_SUBSCRIPTION = "main_subscription"
        const val BASE_PLAN_MONTHLY = "monthly"
        const val BASE_PLAN_YEARLY = "yearly"
        const val OFFER_MONTHLY_TRIAL = "monthly-free-trial"
        const val OFFER_YEARLY_TRIAL = "yearly-free-trial"
    }

    private val _subscriptionStatus = MutableStateFlow(SubscriptionStatus.FREE)
    val subscriptionStatus: StateFlow<SubscriptionStatus> = _subscriptionStatus.asStateFlow()

    private val _productDetails = MutableStateFlow<List<ProductDetails>>(emptyList())
    val productDetails: StateFlow<List<ProductDetails>> = _productDetails.asStateFlow()

    private var billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build()
        )
        .build()

    fun connect() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProductDetails()
                    queryPurchases()
                }
            }

            override fun onBillingServiceDisconnected() {
                // Retry on next user action
            }
        })
    }

    private fun queryProductDetails() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_SUBSCRIPTION)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { result, detailsList ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _productDetails.value = detailsList
            }
        }
    }

    fun getOfferDetails(basePlanId: String): ProductDetails.SubscriptionOfferDetails? {
        val product = _productDetails.value.firstOrNull() ?: return null
        val offers = product.subscriptionOfferDetails ?: return null
        // Prefer the free trial offer if available, otherwise fall back to the base offer
        val trialOfferId = when (basePlanId) {
            BASE_PLAN_MONTHLY -> OFFER_MONTHLY_TRIAL
            BASE_PLAN_YEARLY -> OFFER_YEARLY_TRIAL
            else -> null
        }
        return offers.firstOrNull { it.basePlanId == basePlanId && it.offerId == trialOfferId }
            ?: offers.firstOrNull { it.basePlanId == basePlanId }
    }

    fun queryPurchases() {
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

        _subscriptionStatus.value = when {
            activePurchase == null -> SubscriptionStatus.FREE
            isTrialPurchase(activePurchase) -> SubscriptionStatus.TRIAL
            else -> SubscriptionStatus.ACTIVE
        }
    }

    private fun isTrialPurchase(purchase: Purchase): Boolean {
        // Google Play handles trial period internally.
        // A purchase during trial is still PURCHASED state.
        // We check if the product has a free trial offer that was used.
        // For simplicity, we rely on the subscription being active.
        // The trial vs active distinction can be refined by checking
        // the purchase token with the Google Play Developer API server-side.
        return false
    }

    fun launchPurchaseFlow(activity: Activity, productDetails: ProductDetails, offerToken: String) {
        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(offerToken)
            .build()

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            updateSubscriptionStatus(purchases)
        }
    }

    suspend fun getSubscriptionStatus(): SubscriptionStatus {
        return _subscriptionStatus.value
    }

    fun getTrialDaysRemaining(): Int {
        // This would be calculated server-side in production.
        // Placeholder: return 0 if not in trial.
        return if (_subscriptionStatus.value == SubscriptionStatus.TRIAL) 30 else 0
    }

    fun disconnect() {
        billingClient.endConnection()
    }
}
