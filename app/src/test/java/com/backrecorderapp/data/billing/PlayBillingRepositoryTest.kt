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

    private companion object {
        const val MONTHLY_TOKEN = "monthly-free-trial-token"
        const val YEARLY_TOKEN = "yearly-free-trial-token"
    }

    private lateinit var client: BillingClient
    private lateinit var activity: Activity

    /** The plan a caller picked in the chooser. */
    private fun plan(
        period: BillingPeriod,
        offerId: String? = "${period.basePlanId}-free-trial"
    ) = SubscriptionPlan(
        period = period,
        offerId = offerId,
        formattedPrice = "ignored",
        priceAmountMicros = 1L,
        priceCurrencyCode = "USD",
        freeTrialDays = 0
    )

    private val monthlyPlan get() = plan(BillingPeriod.MONTHLY)
    private val yearlyPlan get() = plan(BillingPeriod.YEARLY)

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

    /** One phase of an offer. A trial phase is simply one that costs nothing. */
    private fun phase(
        micros: Long,
        formatted: String,
        period: String,
        currency: String = "USD"
    ): ProductDetails.PricingPhase = mockk(relaxed = true) {
        every { priceAmountMicros } returns micros
        every { formattedPrice } returns formatted
        every { priceCurrencyCode } returns currency
        every { billingPeriod } returns period
    }

    private fun offer(
        basePlanId: String,
        offerId: String?,
        token: String,
        phases: List<ProductDetails.PricingPhase>
    ): ProductDetails.SubscriptionOfferDetails = mockk(relaxed = true) {
        every { this@mockk.basePlanId } returns basePlanId
        every { this@mockk.offerId } returns offerId
        every { offerToken } returns token
        every { pricingPhases } returns mockk(relaxed = true) {
            every { pricingPhaseList } returns phases
        }
    }

    /** The `monthly` base plan with its free-trial offer, as the Play Console has it. */
    private fun monthlyOffer(token: String = MONTHLY_TOKEN) = offer(
        basePlanId = "monthly",
        offerId = "monthly-free-trial",
        token = token,
        phases = listOf(
            phase(0L, "Free", "P1W"),
            phase(2_990_000L, "$2.99", "P1M")
        )
    )

    /** The `yearly` base plan with its free-trial offer: ten months' worth of the monthly one. */
    private fun yearlyOffer(token: String = YEARLY_TOKEN) = offer(
        basePlanId = "yearly",
        offerId = "yearly-free-trial",
        token = token,
        phases = listOf(
            phase(0L, "Free", "P1W"),
            phase(29_900_000L, "$29.90", "P1Y")
        )
    )

    /**
     * The subscription as the Play Console actually has it: two base plans, each with a
     * free-trial offer.
     *
     * `productId` reads the production constant rather than a literal, so the fixture cannot
     * quietly disagree with what the app asks Play for - the disagreement that made the real
     * button refuse every tap until #74.
     */
    private fun premiumProduct(
        offers: List<ProductDetails.SubscriptionOfferDetails>? =
            listOf(monthlyOffer(), yearlyOffer())
    ): ProductDetails = mockk(relaxed = true) {
        every { productId } returns BillingRepository.PREMIUM_SUBSCRIPTION_ID
        every { subscriptionOfferDetails } returns offers
    }

    /**
     * The regression this class was written for: `withTimeoutOrNull` returning null on timeout
     * was indistinguishable from the success path also returning null, so a purchase that
     * reached Play reported "billing unavailable" over the sheet Play had just opened.
     */
    @Test
    fun `a hand-off that reaches Play reports no failure`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(premiumProduct())
        every { client.launchBillingFlow(any(), any()) } returns okResult()

        val failure = createRepository().launchPurchaseFlow(activity, monthlyPlan)

        assertNull("a sheet that opened must not be reported as a failure", failure)
        verify(exactly = 1) { client.launchBillingFlow(activity, any()) }
    }

    @Test
    fun `Play with no offer for this account is a product problem`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(details = null)

        assertEquals(
            PurchaseFailure.PRODUCT_UNAVAILABLE,
            createRepository().launchPurchaseFlow(activity, monthlyPlan)
        )
        verify(exactly = 0) { client.launchBillingFlow(any(), any()) }
    }

    @Test
    fun `a product with no purchasable offer is a product problem`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(premiumProduct(offers = null))

        assertEquals(
            PurchaseFailure.PRODUCT_UNAVAILABLE,
            createRepository().launchPurchaseFlow(activity, monthlyPlan)
        )
    }

    /**
     * A dropped connection is not a Play Console problem. Reporting it as one would send a
     * tester to go and check a product that is perfectly well configured.
     */
    @Test
    fun `a failed query is a billing problem, not a product one`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(premiumProduct(), code = BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)

        assertEquals(
            PurchaseFailure.BILLING_UNAVAILABLE,
            createRepository().launchPurchaseFlow(activity, monthlyPlan)
        )
    }

    @Test
    fun `Play refusing the sheet is reported`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(premiumProduct())
        every { client.launchBillingFlow(any(), any()) } returns
            result(BillingClient.BillingResponseCode.DEVELOPER_ERROR)

        assertEquals(
            PurchaseFailure.LAUNCH_FAILED,
            createRepository().launchPurchaseFlow(activity, monthlyPlan)
        )
    }

    @Test
    fun `a screen that went away before the sheet is reported, not launched over`() = runTest {
        every { client.isReady } returns true
        every { activity.isDestroyed } returns true
        stubProductQuery(premiumProduct())

        assertEquals(
            PurchaseFailure.LAUNCH_FAILED,
            createRepository().launchPurchaseFlow(activity, monthlyPlan)
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
            createRepository().launchPurchaseFlow(activity, monthlyPlan)
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
            createRepository().launchPurchaseFlow(activity, monthlyPlan)
        )
    }

    /** Play never calling back at all is the dead button this change exists to remove. */
    @Test
    fun `Play never answering times out rather than hanging`() = runTest(StandardTestDispatcher()) {
        every { client.isReady } returns false
        every { client.startConnection(any()) } answers { /* never calls back */ }

        val repository = createRepository()
        val failure = async { repository.launchPurchaseFlow(activity, monthlyPlan) }
        advanceTimeBy(21_000)

        assertEquals(PurchaseFailure.BILLING_UNAVAILABLE, failure.await())
    }

    /**
     * Both taps must reach Play: a tap that is merely dropped leaves the screen that made it
     * with nothing to show, which is the dead button again.
     *
     * Note what this does **not** cover: the two calls serialize on `purchaseMutex`, so the
     * second never races the first into `ensureConnected`. The shared-connection deferred is
     * exercised by `connect()` racing a purchase - the test below it.
     */
    @Test
    fun `a second tap is served rather than dropped`() =
        runTest(StandardTestDispatcher()) {
            every { client.isReady } returns false
            val listener = slot<BillingClientStateListener>()
            // Captured and left hanging, so both hand-offs are genuinely in flight at once.
            every { client.startConnection(capture(listener)) } answers { }
            stubProductQuery(premiumProduct())
            every { client.launchBillingFlow(any(), any()) } returns okResult()

            val repository = createRepository()
            val first = async { repository.launchPurchaseFlow(activity, monthlyPlan) }
            val second = async { repository.launchPurchaseFlow(activity, monthlyPlan) }
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
     * The shared-connection deferred, which the test above cannot reach. An Upgrade tap in the
     * first seconds after launch lands while `connect()` is still connecting: it must wait on
     * that attempt rather than issue a second `startConnection`, which Play answers with an
     * error seconds before the first connection succeeds.
     */
    @Test
    fun `a tap during the launch connect joins it instead of opening a second`() =
        runTest(StandardTestDispatcher()) {
            every { client.isReady } returns false
            val listener = slot<BillingClientStateListener>()
            every { client.startConnection(capture(listener)) } answers { }
            stubProductQuery(premiumProduct())
            stubQueryPurchases()
            every { client.launchBillingFlow(any(), any()) } returns okResult()

            val repository = createRepository()
            repository.connect()
            runCurrent()
            verify(exactly = 1) { client.startConnection(any()) }

            val tap = async { repository.launchPurchaseFlow(activity, monthlyPlan) }
            runCurrent()
            // Joined the attempt already in flight - no second connection on the one client.
            verify(exactly = 1) { client.startConnection(any()) }

            every { client.isReady } returns true
            listener.captured.onBillingSetupFinished(okResult())
            runCurrent()

            assertNull(tap.await())
            verify(exactly = 1) { client.startConnection(any()) }
            verify(exactly = 1) { client.launchBillingFlow(activity, any()) }
        }

    /**
     * `statusResolved` is what every gate waiting on billing keys off, and only a query Play
     * answered sets it. A single launch-time attempt that timed out left it false for the whole
     * session, so a paying subscriber was refused cloud sync until they restarted the app.
     */
    @Test
    fun `a launch connect that times out is retried until entitlement resolves`() =
        runTest(StandardTestDispatcher()) {
            every { client.isReady } returns false
            val listener = slot<BillingClientStateListener>()
            // First attempt is left hanging; later ones connect.
            var attempts = 0
            every { client.startConnection(capture(listener)) } answers {
                if (++attempts > 1) listener.captured.onBillingSetupFinished(okResult())
            }
            stubQueryPurchases(purchase(acknowledged = true))

            val repository = createRepository()
            repository.connect()
            runCurrent()
            assertEquals(false, repository.statusResolved.value)

            // Past the prepare timeout and the first backoff.
            advanceTimeBy(25_000)
            runCurrent()

            assertEquals(true, repository.statusResolved.value)
            assertEquals(SubscriptionStatus.ACTIVE, repository.subscriptionStatus.value)
        }

    /**
     * The binding can drop between the `isReady` check and the product query - the client
     * reports ready right up to the moment it dies, so no check closes that window.
     */
    @Test
    fun `a service that drops mid-query is reconnected and asked again`() = runTest {
        every { client.isReady } returns true
        val productListener = slot<ProductDetailsResponseListener>()
        var queries = 0
        every {
            client.queryProductDetailsAsync(any<QueryProductDetailsParams>(), capture(productListener))
        } answers {
            val response: QueryProductDetailsResult = mockk(relaxed = true) {
                every { productDetailsList } returns listOf(premiumProduct())
            }
            val code = if (++queries == 1) {
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED
            } else {
                BillingClient.BillingResponseCode.OK
            }
            productListener.captured.onProductDetailsResponse(result(code), response)
        }
        every { client.launchBillingFlow(any(), any()) } returns okResult()

        assertNull(createRepository().launchPurchaseFlow(activity, monthlyPlan))
        verify(exactly = 2) { client.queryProductDetailsAsync(any<QueryProductDetailsParams>(), any()) }
    }

    /** A product Play genuinely does not have is not retried - that is not a transient error. */
    @Test
    fun `an unavailable product is not retried`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(null, code = BillingClient.BillingResponseCode.ITEM_UNAVAILABLE)

        assertEquals(
            PurchaseFailure.PRODUCT_UNAVAILABLE,
            createRepository().launchPurchaseFlow(activity, monthlyPlan)
        )
        verify(exactly = 1) { client.queryProductDetailsAsync(any<QueryProductDetailsParams>(), any()) }
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
            val abandoned = async { repository.launchPurchaseFlow(activity, monthlyPlan) }
            runCurrent()
            advanceTimeBy(21_000)
            assertEquals(PurchaseFailure.BILLING_UNAVAILABLE, abandoned.await())

            val retry = async { repository.launchPurchaseFlow(activity, monthlyPlan) }
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
        runCurrent()

        verify(exactly = 1) { client.acknowledgePurchase(any(), any()) }
        assertEquals(SubscriptionStatus.ACTIVE, repository.subscriptionStatus.value)
    }

    /**
     * Play's acknowledgement callback is not guaranteed to arrive. A token held in flight
     * forever is never retried by any later query, and an unacknowledged subscription is
     * refunded after three days - so the token must be freed even when nothing comes back.
     */
    @Test
    fun `an acknowledgement Play never answers is retried later`() =
        runTest(StandardTestDispatcher()) {
            val purchase = purchase(acknowledged = false)
            stubQueryPurchases(purchase)
            // Relaxed mock: acknowledgePurchase is recorded and its callback never fires.

            val repository = createRepository()
            repository.onPurchasesUpdated(okResult(), listOf(purchase))
            runCurrent()
            verify(exactly = 1) { client.acknowledgePurchase(any(), any()) }

            // Still in flight: a second pass must not send a duplicate.
            repository.onPurchasesUpdated(okResult(), listOf(purchase))
            runCurrent()
            verify(exactly = 1) { client.acknowledgePurchase(any(), any()) }

            advanceTimeBy(61_000)
            runCurrent()

            repository.onPurchasesUpdated(okResult(), listOf(purchase))
            runCurrent()
            verify(exactly = 2) { client.acknowledgePurchase(any(), any()) }
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
        stubProductQuery(premiumProduct())
        every { client.launchBillingFlow(any(), any()) } throws IllegalStateException("dead binder")

        assertEquals(
            PurchaseFailure.LAUNCH_FAILED,
            createRepository().launchPurchaseFlow(activity, monthlyPlan)
        )
    }

    // ---- The plan the user picked (#74) ----

    @Test
    fun `the plans offered are both base plans, priced from Play`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(premiumProduct())

        val query = createRepository().loadSubscriptionPlans()

        val plans = (query as PlanQuery.Available).plans
        assertEquals(listOf(BillingPeriod.MONTHLY, BillingPeriod.YEARLY), plans.plans.map { it.period })
        assertEquals("$2.99", plans.monthly?.formattedPrice)
        assertEquals("$29.90", plans.yearly?.formattedPrice)
        // The trial is read from the free phase, not assumed.
        assertEquals(7, plans.monthly?.freeTrialDays)
        // Ten months' worth of the monthly price.
        assertEquals(16, plans.yearlySavingsPercent)
    }

    /**
     * The whole of #74's second half: buying must use the base plan the user chose, not whichever
     * offer Play returned first. With only the monthly plan on offer, a yearly purchase has to
     * refuse - if it fell back to the first offer it would charge a month the user did not pick,
     * and the same bug in reverse would charge a year.
     */
    @Test
    fun `a plan with no offer is refused rather than silently swapped for another`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(premiumProduct(offers = listOf(monthlyOffer())))

        assertEquals(
            PurchaseFailure.PRODUCT_UNAVAILABLE,
            createRepository().launchPurchaseFlow(activity, yearlyPlan)
        )
        verify(exactly = 0) { client.launchBillingFlow(any(), any()) }
    }

    @Test
    fun `either plan can be bought when Play offers both`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(premiumProduct())
        every { client.launchBillingFlow(any(), any()) } returns okResult()

        val repository = createRepository()

        assertNull(repository.launchPurchaseFlow(activity, yearlyPlan))
        assertNull(repository.launchPurchaseFlow(activity, monthlyPlan))
        verify(exactly = 2) { client.launchBillingFlow(activity, any()) }
    }

    /**
     * Play answering with a product that has no purchasable plan is a Console problem, and must
     * stay distinguishable from a connection one.
     */
    @Test
    fun `a product with no purchasable plan has no chooser to show`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(premiumProduct(offers = null))

        assertEquals(
            PlanQuery.Unavailable(PurchaseFailure.PRODUCT_UNAVAILABLE),
            createRepository().loadSubscriptionPlans()
        )
    }

    @Test
    fun `plans that cannot be loaded report the billing failure, not an empty chooser`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(premiumProduct(), code = BillingClient.BillingResponseCode.ERROR)

        assertEquals(
            PlanQuery.Unavailable(PurchaseFailure.BILLING_UNAVAILABLE),
            createRepository().loadSubscriptionPlans()
        )
    }

    /**
     * The strongest available pin on "the plan the user picked is the one that is bought".
     *
     * `BillingFlowParams` exposes no getter for the offer token it was built with, so the token
     * itself cannot be asserted through a mocked client. This asserts the same property from the
     * other side: with *both* base plans present and only the yearly one purchasable, a monthly
     * purchase must refuse. Any regression that falls back to "whichever offer came first" would
     * launch here instead, and any swap of the two base plan ids would launch the wrong one.
     */
    @Test
    fun `a plan is never substituted when the other one is purchasable`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(
            premiumProduct(offers = listOf(monthlyOffer(token = ""), yearlyOffer()))
        )
        every { client.launchBillingFlow(any(), any()) } returns okResult()

        val repository = createRepository()

        assertEquals(
            PurchaseFailure.PRODUCT_UNAVAILABLE,
            repository.launchPurchaseFlow(activity, monthlyPlan)
        )
        verify(exactly = 0) { client.launchBillingFlow(any(), any()) }

        // The same product, the other plan: proves the refusal above was about the plan and not
        // about the product being unbuyable in general.
        assertNull(repository.launchPurchaseFlow(activity, yearlyPlan))
        verify(exactly = 1) { client.launchBillingFlow(activity, any()) }
    }

    /**
     * A base plan can carry more than one eligible offer, and the chooser's figures came from a
     * specific one. Re-running the trial-first preference over a newer list would be the same
     * unguaranteed ordering this change exists to stop relying on.
     */
    @Test
    fun `the purchase asks for the exact offer the chooser priced`() = runTest {
        val promo = offer(
            basePlanId = "monthly",
            offerId = "monthly-promo",
            token = "promo-token",
            phases = listOf(phase(0L, "Free", "P1M"), phase(2_990_000L, "$2.99", "P1M"))
        )
        every { client.isReady } returns true
        // The promo is listed first and also has a trial, so the preference rule alone would
        // pick it over the offer the chooser actually used.
        stubProductQuery(premiumProduct(offers = listOf(promo, monthlyOffer())))
        every { client.launchBillingFlow(any(), any()) } returns okResult()

        val details = (createRepository().loadSubscriptionPlans() as PlanQuery.Available).plans
        assertEquals("monthly-promo", details.monthly?.offerId)

        // A plan priced from the non-preferred offer still buys that offer.
        assertNull(
            createRepository().launchPurchaseFlow(
                activity,
                plan(BillingPeriod.MONTHLY, offerId = "monthly-free-trial")
            )
        )
    }

    @Test
    fun `plans Play never answers for time out rather than hanging`() =
        runTest(StandardTestDispatcher()) {
            every { client.isReady } returns false
            every { client.startConnection(any()) } answers { /* never calls back */ }

            val repository = createRepository()
            val query = async { repository.loadSubscriptionPlans() }
            advanceTimeBy(21_000)

            assertEquals(
                PlanQuery.Unavailable(PurchaseFailure.BILLING_UNAVAILABLE),
                query.await()
            )
        }

    @Test
    fun `a billing client that throws while loading plans is reported, not crashed`() = runTest {
        every { client.isReady } returns true
        every {
            client.queryProductDetailsAsync(any<QueryProductDetailsParams>(), any())
        } throws IllegalStateException("dead binder")

        assertEquals(
            PlanQuery.Unavailable(PurchaseFailure.BILLING_UNAVAILABLE),
            createRepository().loadSubscriptionPlans()
        )
    }

    /** An offer with no token cannot be bought, so the plan it belongs to is not on offer. */
    @Test
    fun `a base plan whose offer carries no token is not offered`() = runTest {
        every { client.isReady } returns true
        stubProductQuery(premiumProduct(offers = listOf(monthlyOffer(token = ""), yearlyOffer())))

        val plans = (createRepository().loadSubscriptionPlans() as PlanQuery.Available).plans

        assertEquals(listOf(BillingPeriod.YEARLY), plans.plans.map { it.period })
        // One plan on its own has nothing to be cheaper than, so no badge.
        assertNull(plans.yearlySavingsPercent)
    }
}
