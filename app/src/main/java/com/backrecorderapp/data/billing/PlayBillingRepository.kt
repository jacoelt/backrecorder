package com.backrecorderapp.data.billing

import android.app.Activity
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.backrecorderapp.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** The real [BillingRepository], backed by the Play Billing client. */
@Singleton
class PlayBillingRepository @Inject constructor(
    clientFactory: BillingClientFactory,
    @ApplicationScope private val appScope: CoroutineScope
) : BillingRepository, PurchasesUpdatedListener {

    companion object {
        private const val TAG = "PlayBilling"

        /**
         * How long connecting and asking Play for the offer may take.
         *
         * Play's callbacks are not guaranteed to arrive at all - the service can be killed or
         * mid-update - and a hand-off that waits forever is the dead button this class exists to
         * prevent. It deliberately does *not* cover showing the sheet: cancelling that after
         * `launchBillingFlow` has already put Play on screen would report a failure over a
         * purchase that is going ahead.
         */
        private const val PREPARE_TIMEOUT_MS = 20_000L
    }

    private val _subscriptionStatus = MutableStateFlow(SubscriptionStatus.FREE)
    override val subscriptionStatus: StateFlow<SubscriptionStatus> =
        _subscriptionStatus.asStateFlow()

    private val _statusResolved = MutableStateFlow(false)

    override val statusResolved: StateFlow<Boolean> = _statusResolved.asStateFlow()

    private val billingClient: BillingClient = clientFactory.create(this)

    /**
     * One hand-off at a time, queued rather than dropped. Two overlapping attempts would open two
     * connections on the one client, which Play answers with an error - so a double tap on a
     * button that felt dead used to produce a false "unavailable" over a purchase that was
     * working. A dropped tap is not an option either: the second caller is often a different
     * screen, and it would be left with nothing to show.
     */
    private val purchaseMutex = Mutex()

    /**
     * The connection attempt in progress, so a purchase started while the app-launch [connect] is
     * still connecting waits for that one. Play answers a second concurrent `startConnection`
     * with an error, which would surface as "billing unavailable" seconds before the first
     * connection succeeds.
     */
    private val connection = AtomicReference<CompletableDeferred<PurchaseFailure?>?>(null)

    /** Purchase tokens with an acknowledgement already on its way to Play. */
    private val acknowledging: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    override fun connect() {
        appScope.launch {
            // Bounded like the purchase path, and for a sharper reason: if Play never answers,
            // statusResolved stays false for the whole session, and every premium gate that
            // waits on it - cloud sync above all - would lock out a paying subscriber.
            val outcome = withTimeoutOrNull(PREPARE_TIMEOUT_MS) { Connection(ensureConnected()) }
            if (outcome == null) {
                Log.w(TAG, "billing connect got no answer within ${PREPARE_TIMEOUT_MS}ms")
                return@launch
            }
            if (outcome.failure == null) queryPurchases()
        }
    }

    /** Wraps a nullable failure so a timeout stays distinguishable from success. */
    private class Connection(val failure: PurchaseFailure?)

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

        acknowledge(purchases)
    }

    /**
     * Play refunds a subscription that is never acknowledged after three days, so a purchase the
     * user has paid for would quietly expire. Nothing acknowledged before, which was harmless
     * only because the Upgrade button never reached Play at all.
     */
    private fun acknowledge(purchases: List<Purchase>) {
        purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
            .forEach { purchase ->
                // `isAcknowledged` is a snapshot, and the same purchase reaches us from both the
                // purchase callback and the query that follows it, so without this the second
                // one acknowledges a token already in flight and Play rejects it.
                if (!acknowledging.add(purchase.purchaseToken)) return@forEach

                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient.acknowledgePurchase(params) { result ->
                    acknowledging.remove(purchase.purchaseToken)
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        Log.w(
                            TAG,
                            "acknowledgePurchase: ${result.responseCode} ${result.debugMessage}"
                        )
                    }
                }
            }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            acknowledge(purchases)
            // Play delivers only the purchases from this flow, which is not an entitlement
            // snapshot: handing that list to updateSubscriptionStatus would read FREE - and
            // downgrade a subscriber mid-session - whenever it holds nothing PURCHASED.
            queryPurchases()
        }
    }

    override suspend fun launchPurchaseFlow(activity: Activity): PurchaseFailure? =
        purchaseMutex.withLock {
            try {
                // Only the preparation is timed. A non-null Prepared keeps "the sheet was shown"
                // (null) distinguishable from "nothing came back", which an earlier version
                // conflated - reporting every successful purchase as a timeout.
                val prepared = withTimeoutOrNull(PREPARE_TIMEOUT_MS) { preparePurchase() }
                    ?: return@withLock reportFailure(
                        PurchaseFailure.BILLING_UNAVAILABLE,
                        "no answer from Play within ${PREPARE_TIMEOUT_MS}ms"
                    )

                when (prepared) {
                    is Prepared.Unavailable -> prepared.failure
                    is Prepared.Offer -> showPurchaseSheet(activity, prepared)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A throw out of the Play client (a dead service binding, say) would otherwise
                // reach viewModelScope and take the app down instead of reporting.
                reportFailure(PurchaseFailure.LAUNCH_FAILED, "billing threw: $e")
            }
        }

    private sealed interface Prepared {
        data class Offer(val details: ProductDetails, val offerToken: String) : Prepared
        data class Unavailable(val failure: PurchaseFailure) : Prepared
    }

    private suspend fun preparePurchase(): Prepared {
        if (!billingClient.isReady) {
            ensureConnected()?.let { return Prepared.Unavailable(it) }
        }

        val details = when (val query = queryPremiumOffer()) {
            is OfferQuery.Failed -> return Prepared.Unavailable(query.failure)
            is OfferQuery.Found -> query.details
        }

        // Play knows the product but has no offer to buy it on: a base plan with no active
        // offer, or none this account can take.
        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
            ?: return Prepared.Unavailable(
                reportFailure(
                    PurchaseFailure.PRODUCT_UNAVAILABLE,
                    "no subscription offer on ${details.productId}"
                )
            )

        return Prepared.Offer(details, offerToken)
    }

    /** Null once connected; otherwise the failure to report. */
    private suspend fun ensureConnected(): PurchaseFailure? {
        if (billingClient.isReady) return null

        while (true) {
            val existing = connection.get()
            if (existing != null && !existing.isCompleted) return existing.await()

            val attempt = CompletableDeferred<PurchaseFailure?>()
            if (!connection.compareAndSet(existing, attempt)) continue

            startConnection(attempt)
            try {
                return attempt.await()
            } finally {
                // An attempt whose owner gave up - timed out, or its screen went away - must not
                // be left in place: every later tap would wait on a deferred nobody completes,
                // and no reconnect would ever be tried.
                if (!attempt.isCompleted) {
                    attempt.complete(
                        reportFailure(
                            PurchaseFailure.BILLING_UNAVAILABLE,
                            "connection attempt abandoned before Play answered"
                        )
                    )
                }
                connection.compareAndSet(attempt, null)
            }
        }
    }

    private fun startConnection(pending: CompletableDeferred<PurchaseFailure?>) {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    pending.complete(null)
                } else {
                    pending.complete(
                        reportFailure(
                            PurchaseFailure.BILLING_UNAVAILABLE,
                            "billing setup failed: ${result.responseCode} ${result.debugMessage}"
                        )
                    )
                }
            }

            override fun onBillingServiceDisconnected() {
                // The service can die while setup is still pending, in which case the callback
                // above never arrives. Answer whoever is waiting rather than let them time out.
                // This listener outlives the attempt, so an ordinary later disconnect lands here
                // too - that one answers nobody and must not be logged as a hand-off failure.
                if (pending.isCompleted) return
                pending.complete(
                    reportFailure(
                        PurchaseFailure.BILLING_UNAVAILABLE,
                        "billing service disconnected before setup finished"
                    )
                )
            }
        })
    }

    private sealed interface OfferQuery {
        data class Found(val details: ProductDetails) : OfferQuery
        data class Failed(val failure: PurchaseFailure) : OfferQuery
    }

    private suspend fun queryPremiumOffer(): OfferQuery = suspendCancellableCoroutine { cont ->
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
            if (!cont.isActive) return@queryProductDetailsAsync

            val code = billingResult.responseCode
            if (code != BillingClient.BillingResponseCode.OK) {
                // A dropped connection or a network error is not a Play Console problem, and
                // saying "not available for this account" would send a tester to fix the wrong
                // thing. Only Play's own "no such item" means the product.
                val failure = if (code == BillingClient.BillingResponseCode.ITEM_UNAVAILABLE) {
                    PurchaseFailure.PRODUCT_UNAVAILABLE
                } else {
                    PurchaseFailure.BILLING_UNAVAILABLE
                }
                cont.resume(
                    OfferQuery.Failed(
                        reportFailure(failure, "queryProductDetails: $code ${billingResult.debugMessage}")
                    )
                )
                return@queryProductDetailsAsync
            }

            val details = productDetailsResult.productDetailsList.firstOrNull()
            if (details == null) {
                // Play answered, and has nothing to sell this account: the product id does not
                // exist, is not active, or this build is not on a track that can buy.
                cont.resume(
                    OfferQuery.Failed(
                        reportFailure(
                            PurchaseFailure.PRODUCT_UNAVAILABLE,
                            "no product details for ${BillingRepository.PREMIUM_SUBSCRIPTION_ID}"
                        )
                    )
                )
            } else {
                cont.resume(OfferQuery.Found(details))
            }
        }
    }

    private suspend fun showPurchaseSheet(
        activity: Activity,
        offer: Prepared.Offer
    ): PurchaseFailure? = withContext(Dispatchers.Main) {
        // Preparing crossed a callback, so the screen that asked may be gone by now; launching
        // over a finishing Activity shows nothing and can still answer OK.
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
                        .setProductDetails(offer.details)
                        .setOfferToken(offer.offerToken)
                        .build()
                )
            )
            .build()

        // launchBillingFlow must run on the main thread - off it Play answers DEVELOPER_ERROR
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
