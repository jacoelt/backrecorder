package com.backrecorderapp.data.billing

import android.app.Activity
import android.text.TextUtils
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The hand-off to Play, driven through a mocked [BillingClient] (issue #72).
 *
 * This class exists because the logic it covers was previously untestable - `BillingClient` can
 * only be built on a device - and a review found a bug in it that reported every *successful*
 * purchase as a failure. The failure paths are the whole point of the change, so they are worth
 * more here than the happy path alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayBillingRepositoryTest {

    private lateinit var client: BillingClient
    private lateinit var activity: Activity

    private fun okResult() = result(BillingClient.BillingResponseCode.OK)

    private fun result(code: Int): BillingResult = mockk(relaxed = true) {
        every { responseCode } returns code
        every { debugMessage } returns "mock"
    }

    @Before
    fun setup() {
        // Every failure path logs, and android.util.Log is not implemented on the JVM.
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0

        // BillingFlowParams.build() validates through TextUtils, which is equally absent.
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }

        // The sheet is launched on the main thread, which the JVM has no dispatcher for.
        Dispatchers.setMain(UnconfinedTestDispatcher())

        client = mockk(relaxed = true)
        activity = mockk(relaxed = true) {
            every { isFinishing } returns false
            every { isDestroyed } returns false
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
        unmockkStatic(TextUtils::class)
    }

    private fun TestScope.createRepository(): PlayBillingRepository {
        val factory = object : BillingClientFactory {
            override fun create(listener: PurchasesUpdatedListener): BillingClient = client
        }
        return PlayBillingRepository(factory, this)
    }

    /** Makes the client answer a product query with [details], or with no product at all. */
    private fun stubProductQuery(details: ProductDetails?, code: Int = BillingClient.BillingResponseCode.OK) {
        val listener = slot<ProductDetailsResponseListener>()
        every {
            client.queryProductDetailsAsync(any<QueryProductDetailsParams>(), capture(listener))
        } answers {
            val response: QueryProductDetailsResult = mockk(relaxed = true) {
                every { productDetailsList } returns listOfNotNull(details)
            }
            listener.captured.onProductDetailsResponse(result(code), response)
        }
    }

    private fun purchase(
        acknowledged: Boolean,
        state: Int = Purchase.PurchaseState.PURCHASED,
        token: String = "purchase-token"
    ): Purchase = mockk(relaxed = true) {
        every { purchaseState } returns state
        every { isAcknowledged } returns acknowledged
        every { purchaseToken } returns token
    }

    /** Makes the client's entitlement query answer with [purchases]. */
    private fun stubQueryPurchases(vararg purchases: Purchase) {
        val listener = slot<PurchasesResponseListener>()
        every {
            client.queryPurchasesAsync(any<QueryPurchasesParams>(), capture(listener))
        } answers {
            listener.captured.onQueryPurchasesResponse(okResult(), purchases.toList())
        }
    }

    private fun premiumOffer(offerToken: String? = "offer-token"): ProductDetails {
        val offer: ProductDetails.SubscriptionOfferDetails? = offerToken?.let {
            mockk(relaxed = true) { every { this@mockk.offerToken } returns it }
        }
        return mockk(relaxed = true) {
            every { productId } returns BillingRepository.PREMIUM_SUBSCRIPTION_ID
            every { subscriptionOfferDetails } returns offer?.let { listOf(it) }
        }
    }

    /**
     * The regression this class was written for: `withTimeoutOrNull` returning null on timeout
     * was indistinguishable from the success path also returning null, so a purchase that
     * reached Play reported "billing unavailable" over the sheet Play had just opened.
     */
    @Test
    fun `a hand-off that reaches Play reports no failure`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(premiumOffer())
        every { client.launchBillingFlow(any(), any()) } returns okResult()

        val failure = createRepository().launchPurchaseFlow(activity)

        assertNull("a sheet that opened must not be reported as a failure", failure)
        verify(exactly = 1) { client.launchBillingFlow(activity, any()) }
    }

    @Test
    fun `Play with no offer for this account is a product problem`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(details = null)

        assertEquals(
            PurchaseFailure.PRODUCT_UNAVAILABLE,
            createRepository().launchPurchaseFlow(activity)
        )
        verify(exactly = 0) { client.launchBillingFlow(any(), any()) }
    }

    @Test
    fun `a product with no purchasable offer is a product problem`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(premiumOffer(offerToken = null))

        assertEquals(
            PurchaseFailure.PRODUCT_UNAVAILABLE,
            createRepository().launchPurchaseFlow(activity)
        )
    }

    /**
     * A dropped connection is not a Play Console problem. Reporting it as one would send a
     * tester to go and check a product that is perfectly well configured.
     */
    @Test
    fun `a failed query is a billing problem, not a product one`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(premiumOffer(), code = BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)

        assertEquals(
            PurchaseFailure.BILLING_UNAVAILABLE,
            createRepository().launchPurchaseFlow(activity)
        )
    }

    @Test
    fun `Play refusing the sheet is reported`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(premiumOffer())
        every { client.launchBillingFlow(any(), any()) } returns
            result(BillingClient.BillingResponseCode.DEVELOPER_ERROR)

        assertEquals(
            PurchaseFailure.LAUNCH_FAILED,
            createRepository().launchPurchaseFlow(activity)
        )
    }

    @Test
    fun `a screen that went away before the sheet is reported, not launched over`() = runTest {
        every { client.isReady } returns true
        every { activity.isDestroyed } returns true
        stubProductQuery(premiumOffer())

        assertEquals(
            PurchaseFailure.LAUNCH_FAILED,
            createRepository().launchPurchaseFlow(activity)
        )
        verify(exactly = 0) { client.launchBillingFlow(any(), any()) }
    }

    @Test
    fun `a connection that fails is reported as unavailable`() = runTest {
        every { client.isReady } returns false
        val listener = slot<BillingClientStateListener>()
        every { client.startConnection(capture(listener)) } answers {
            listener.captured.onBillingSetupFinished(
                result(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)
            )
        }

        assertEquals(
            PurchaseFailure.BILLING_UNAVAILABLE,
            createRepository().launchPurchaseFlow(activity)
        )
    }

    /**
     * The service can die while setup is still pending, in which case `onBillingSetupFinished`
     * never arrives. Answering from the disconnect callback is what keeps that from being a
     * twenty-second wait ending in a generic timeout.
     */
    @Test
    fun `a disconnect during setup answers the waiting hand-off`() = runTest {
        every { client.isReady } returns false
        val listener = slot<BillingClientStateListener>()
        every { client.startConnection(capture(listener)) } answers {
            listener.captured.onBillingServiceDisconnected()
        }

        assertEquals(
            PurchaseFailure.BILLING_UNAVAILABLE,
            createRepository().launchPurchaseFlow(activity)
        )
    }

    /** Play never calling back at all is the dead button this change exists to remove. */
    @Test
    fun `Play never answering times out rather than hanging`() = runTest(StandardTestDispatcher()) {
        every { client.isReady } returns false
        every { client.startConnection(any()) } answers { /* never calls back */ }

        val repository = createRepository()
        val failure = async { repository.launchPurchaseFlow(activity) }
        advanceTimeBy(21_000)

        assertEquals(PurchaseFailure.BILLING_UNAVAILABLE, failure.await())
    }

    /**
     * Both taps must reach Play: a tap that is merely dropped leaves the screen that made it
     * with nothing to show, which is the dead button again.
     *
     * Note what this does **not** cover: the two calls serialize on `purchaseMutex`, so the
     * second never races the first into `ensureConnected`. The shared-connection deferred is
     * exercised by `connect()` racing a purchase, which is not pinned by a test - see the PR
     * description.
     */
    @Test
    fun `a second tap is served rather than dropped`() =
        runTest(StandardTestDispatcher()) {
            every { client.isReady } returns false
            val listener = slot<BillingClientStateListener>()
            // Captured and left hanging, so both hand-offs are genuinely in flight at once.
            every { client.startConnection(capture(listener)) } answers { }
            stubProductQuery(premiumOffer())
            every { client.launchBillingFlow(any(), any()) } returns okResult()

            val repository = createRepository()
            val first = async { repository.launchPurchaseFlow(activity) }
            val second = async { repository.launchPurchaseFlow(activity) }
            runCurrent()

            verify(exactly = 1) { client.startConnection(any()) }

            every { client.isReady } returns true
            listener.captured.onBillingSetupFinished(okResult())

            assertNull(first.await())
            assertNull(second.await())
            verify(exactly = 2) { client.launchBillingFlow(activity, any()) }
            verify(exactly = 1) { client.startConnection(any()) }
        }

    /**
     * A connection nobody is waiting on any more must not be left in place: the next tap would
     * join a deferred that no callback will ever complete, and no reconnect would be tried.
     */
    @Test
    fun `a tap after an abandoned connection starts a fresh one`() =
        runTest(StandardTestDispatcher()) {
            every { client.isReady } returns false
            val listener = slot<BillingClientStateListener>()
            every { client.startConnection(capture(listener)) } answers { }

            val repository = createRepository()
            val abandoned = async { repository.launchPurchaseFlow(activity) }
            runCurrent()
            advanceTimeBy(21_000)
            assertEquals(PurchaseFailure.BILLING_UNAVAILABLE, abandoned.await())

            val retry = async { repository.launchPurchaseFlow(activity) }
            runCurrent()

            verify(exactly = 2) { client.startConnection(any()) }
            retry.cancel()
        }

    /**
     * Play refunds a subscription that is never acknowledged within three days. Nothing in the
     * app acknowledged until the Upgrade button started reaching Play at all.
     */
    @Test
    fun `a new purchase is acknowledged exactly once`() = runTest {
        val purchase = purchase(acknowledged = false)
        stubQueryPurchases(purchase)

        val repository = createRepository()
        repository.onPurchasesUpdated(okResult(), listOf(purchase))

        verify(exactly = 1) { client.acknowledgePurchase(any(), any()) }
        assertEquals(SubscriptionStatus.ACTIVE, repository.subscriptionStatus.value)
    }

    @Test
    fun `an already acknowledged purchase is left alone`() = runTest {
        val purchase = purchase(acknowledged = true)
        stubQueryPurchases(purchase)

        createRepository().onPurchasesUpdated(okResult(), listOf(purchase))

        verify(exactly = 0) { client.acknowledgePurchase(any(), any()) }
    }

    @Test
    fun `a purchase still pending is not acknowledged`() = runTest {
        val purchase = purchase(acknowledged = false, state = Purchase.PurchaseState.PENDING)
        stubQueryPurchases(purchase)

        val repository = createRepository()
        repository.onPurchasesUpdated(okResult(), listOf(purchase))

        verify(exactly = 0) { client.acknowledgePurchase(any(), any()) }
        assertEquals(SubscriptionStatus.FREE, repository.subscriptionStatus.value)
    }

    /**
     * Play hands `onPurchasesUpdated` only the purchases from that flow, which is not an
     * entitlement snapshot - taking it as one would downgrade a subscriber mid-session.
     */
    @Test
    fun `status comes from a fresh query, not the callback's partial list`() = runTest {
        stubQueryPurchases(purchase(acknowledged = true))

        val repository = createRepository()
        repository.onPurchasesUpdated(okResult(), emptyList())

        verify(exactly = 1) { client.queryPurchasesAsync(any<QueryPurchasesParams>(), any()) }
        assertEquals(SubscriptionStatus.ACTIVE, repository.subscriptionStatus.value)
    }

    /**
     * The authoritative read can fail right after a purchase completes. The user has paid by
     * then, so the callback may still upgrade them - it just may never downgrade.
     */
    @Test
    fun `a purchase still counts when the follow-up query fails`() = runTest {
        val listener = slot<PurchasesResponseListener>()
        every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), capture(listener)) } answers {
            listener.captured.onQueryPurchasesResponse(
                result(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED),
                emptyList()
            )
        }

        val repository = createRepository()
        repository.onPurchasesUpdated(okResult(), listOf(purchase(acknowledged = true)))

        assertEquals(SubscriptionStatus.ACTIVE, repository.subscriptionStatus.value)
        assertEquals(true, repository.statusResolved.value)
    }

    @Test
    fun `a billing client that throws is reported rather than crashing the caller`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(premiumOffer())
        every { client.launchBillingFlow(any(), any()) } throws IllegalStateException("dead binder")

        assertEquals(
            PurchaseFailure.LAUNCH_FAILED,
            createRepository().launchPurchaseFlow(activity)
        )
    }
}
