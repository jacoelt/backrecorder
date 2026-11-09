package com.backrecorderapp.ui.main

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import com.backrecorderapp.data.billing.BillingPeriod
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.billing.PlanQuery
import com.backrecorderapp.data.billing.PurchaseFailure
import com.backrecorderapp.data.billing.SubscriptionPlan
import com.backrecorderapp.data.billing.SubscriptionPlans
import com.backrecorderapp.data.cloud.CloudSyncManager
import com.backrecorderapp.domain.BlockReason
import com.backrecorderapp.domain.GateResult
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.segment.SegmentManager
import com.backrecorderapp.data.waveform.WaveformRepository
import com.backrecorderapp.domain.SaveRecordingUseCase
import com.backrecorderapp.domain.SubscriptionGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The upgrade hand-off on the main screen (issue #72).
 *
 * The defect being guarded against is a *silent* one: the upgrade dialog closes and nothing
 * happens, which is indistinguishable from a dead button. So every one of these asserts that a
 * refusal reaches the state the screen renders.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelPurchaseTest {

    private lateinit var context: Context
    private lateinit var segmentManager: SegmentManager
    private lateinit var waveformRepository: WaveformRepository
    private lateinit var saveRecordingUseCase: SaveRecordingUseCase
    private lateinit var appPreferences: AppPreferences
    private lateinit var cloudSyncManager: CloudSyncManager
    private lateinit var billingRepository: BillingRepository
    private lateinit var subscriptionGate: SubscriptionGate

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        context = mockk(relaxed = true)
        every { context.contentResolver } returns mockk<ContentResolver>(relaxed = true)

        segmentManager = mockk(relaxed = true)
        every { segmentManager.getAllSegments() } returns flowOf(emptyList())

        waveformRepository = mockk(relaxed = true)
        saveRecordingUseCase = mockk(relaxed = true)
        appPreferences = mockk(relaxed = true)

        cloudSyncManager = mockk(relaxed = true)
        every { cloudSyncManager.recoveryMessage } returns MutableStateFlow(null)

        billingRepository = mockk(relaxed = true)
        subscriptionGate = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A plan as the chooser would have handed it over. */
    private val monthlyPlan = SubscriptionPlan(
        period = BillingPeriod.MONTHLY,
        offerId = "monthly-free-trial",
        formattedPrice = "$2.99",
        priceAmountMicros = 2_990_000L,
        priceCurrencyCode = "USD",
        freeTrialDays = 7
    )

    /** The other plan, so a test can tell "the plan picked" from "the only plan there is". */
    private val yearlyPlan = SubscriptionPlan(
        period = BillingPeriod.YEARLY,
        offerId = "yearly-free-trial",
        formattedPrice = "$29.90",
        priceAmountMicros = 29_900_000L,
        priceCurrencyCode = "USD",
        freeTrialDays = 7
    )

    private val plans = SubscriptionPlans(listOf(monthlyPlan, yearlyPlan))

    private fun buildViewModel(): MainViewModel = MainViewModel(
        context,
        segmentManager,
        waveformRepository,
        saveRecordingUseCase,
        appPreferences,
        cloudSyncManager,
        billingRepository,
        subscriptionGate
    )

    @Test
    fun `a refused hand-off is reported on this screen`() = runTest {
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } returns
            PurchaseFailure.PRODUCT_UNAVAILABLE

        val viewModel = buildViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        advanceUntilIdle()

        assertEquals(PurchaseFailure.PRODUCT_UNAVAILABLE, viewModel.uiState.value.purchaseError)
    }

    @Test
    fun `each failure reaches the state unchanged`() = runTest {
        PurchaseFailure.entries.forEach { failure ->
            coEvery { billingRepository.launchPurchaseFlow(any(), any()) } returns failure

            val viewModel = buildViewModel()
            viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
            advanceUntilIdle()

            assertEquals(failure, viewModel.uiState.value.purchaseError)
        }
    }

    @Test
    fun `a hand-off that reached Play reports nothing`() = runTest {
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } returns null

        val viewModel = buildViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.purchaseError)
    }

    /**
     * No activity to launch over is a failed hand-off, not a reason to do nothing: the screen
     * resolves one from its `LocalContext`, and a null there would otherwise be the dead button.
     */
    @Test
    fun `no activity is reported rather than ignored`() = runTest {
        val viewModel = buildViewModel()
        viewModel.launchPurchaseFlow(null, monthlyPlan)
        advanceUntilIdle()

        assertEquals(PurchaseFailure.LAUNCH_FAILED, viewModel.uiState.value.purchaseError)
        coVerify(exactly = 0) { billingRepository.launchPurchaseFlow(any(), any()) }
    }

    @Test
    fun `dismissing clears the failure`() = runTest {
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } returns
            PurchaseFailure.BILLING_UNAVAILABLE

        val viewModel = buildViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        advanceUntilIdle()
        viewModel.dismissPurchaseError()

        assertNull(viewModel.uiState.value.purchaseError)
    }

    /**
     * The purchase failure has its own slot rather than sharing `errorMessage` with saves: the
     * two can arrive together, and whichever wrote last would otherwise erase the other.
     */
    @Test
    fun `reporting a failure leaves a pending save error intact`() = runTest {
        // A save error first, so the assertion below is about survival rather than emptiness.
        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns emptyList()
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } returns
            PurchaseFailure.LAUNCH_FAILED

        val viewModel = buildViewModel()
        viewModel.requestSave()
        advanceUntilIdle()
        val saveError = viewModel.uiState.value.errorMessage
        assertNotNull("precondition: the save must have reported an error", saveError)

        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        advanceUntilIdle()

        assertEquals(saveError, viewModel.uiState.value.errorMessage)
        assertEquals(PurchaseFailure.LAUNCH_FAILED, viewModel.uiState.value.purchaseError)

        viewModel.dismissPurchaseError()
        assertEquals(saveError, viewModel.uiState.value.errorMessage)
    }

    // ---- The plan chooser (#74) ----

    @Test
    fun `the chooser opens with the plans Play offered`() = runTest {
        coEvery { billingRepository.loadSubscriptionPlans() } returns PlanQuery.Available(plans)

        val viewModel = buildViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()

        assertEquals(plans, viewModel.uiState.value.subscriptionPlans)
        assertEquals(false, viewModel.uiState.value.plansLoading)
        // Nothing is bought until the user picks.
        coVerify(exactly = 0) { billingRepository.launchPurchaseFlow(any(), any()) }
    }

    /**
     * Plans that cannot be loaded are the dead button in a new place: the chooser never opens, so
     * the refusal has to be shown rather than leaving the dialog to close onto nothing.
     */
    @Test
    fun `plans that cannot be loaded are reported, not swallowed`() = runTest {
        coEvery { billingRepository.loadSubscriptionPlans() } returns
            PlanQuery.Unavailable(PurchaseFailure.PRODUCT_UNAVAILABLE)

        val viewModel = buildViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.subscriptionPlans)
        assertEquals(PurchaseFailure.PRODUCT_UNAVAILABLE, viewModel.uiState.value.purchaseError)
        assertEquals(false, viewModel.uiState.value.plansLoading)
    }

    @Test
    fun `buying closes the chooser and sends the plan that was picked`() = runTest {
        coEvery { billingRepository.loadSubscriptionPlans() } returns PlanQuery.Available(plans)
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } returns null

        val viewModel = buildViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()
        // Yearly, deliberately: it is not the first plan in the set, so "sends the plan that
        // was picked" cannot pass by accident on a fixture holding only one plan.
        viewModel.launchPurchaseFlow(mockk<Activity>(), yearlyPlan)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.subscriptionPlans)
        coVerify(exactly = 1) { billingRepository.launchPurchaseFlow(any(), yearlyPlan) }
        coVerify(exactly = 0) { billingRepository.launchPurchaseFlow(any(), monthlyPlan) }
    }

    /**
     * The #72 latching guard. A load the user walked away from must not publish anything: its
     * chooser would appear on the next back navigation with no tap behind it.
     */
    @Test
    fun `a load the user left behind publishes nothing`() = runTest {
        val parked = CompletableDeferred<PlanQuery>()
        coEvery { billingRepository.loadSubscriptionPlans() } coAnswers { parked.await() }

        val viewModel = buildViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()
        assertEquals("precondition: the load must be in flight", true, viewModel.uiState.value.plansLoading)

        viewModel.onScreenLeft()
        parked.complete(PlanQuery.Available(plans))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.subscriptionPlans)
        assertNull(viewModel.uiState.value.purchaseError)
        assertEquals(false, viewModel.uiState.value.plansLoading)
    }

    /** Leaving also drops a failure, which would otherwise greet the user on the way back. */
    @Test
    fun `leaving the screen clears a purchase failure`() = runTest {
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } returns
            PurchaseFailure.BILLING_UNAVAILABLE

        val viewModel = buildViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.purchaseError)

        viewModel.onScreenLeft()

        assertNull(viewModel.uiState.value.purchaseError)
    }

    /**
     * Dismissing the offer takes its load with it: otherwise the chooser arrives seconds after
     * the user said no.
     */
    @Test
    fun `dismissing the upgrade dialog drops the load it started`() = runTest {
        val parked = CompletableDeferred<PlanQuery>()
        coEvery { billingRepository.loadSubscriptionPlans() } coAnswers { parked.await() }

        val viewModel = buildViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()
        viewModel.dismissUpgradeDialog()
        parked.complete(PlanQuery.Available(plans))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.subscriptionPlans)
        assertEquals(false, viewModel.uiState.value.plansLoading)
    }

    @Test
    fun `the screen is told the plans are loading while Play is asked`() = runTest {
        val parked = CompletableDeferred<PlanQuery>()
        coEvery { billingRepository.loadSubscriptionPlans() } coAnswers { parked.await() }

        val viewModel = buildViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.plansLoading)

        parked.complete(PlanQuery.Available(plans))
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.plansLoading)
    }

    /** A second tap while the first is still in flight must not ask Play twice. */
    @Test
    fun `a second tap during a load is not a second query`() = runTest {
        val parked = CompletableDeferred<PlanQuery>()
        coEvery { billingRepository.loadSubscriptionPlans() } coAnswers { parked.await() }

        val viewModel = buildViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()
        viewModel.showPlanChooser()
        advanceUntilIdle()

        coVerify(exactly = 1) { billingRepository.loadSubscriptionPlans() }
        parked.complete(PlanQuery.Available(plans))
    }

    /** A chooser must never open behind Play's sheet. */
    @Test
    fun `no chooser opens while a purchase is being handed to Play`() = runTest {
        val parked = CompletableDeferred<PurchaseFailure?>()
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } coAnswers { parked.await() }

        val viewModel = buildViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        advanceUntilIdle()
        viewModel.showPlanChooser()
        advanceUntilIdle()

        coVerify(exactly = 0) { billingRepository.loadSubscriptionPlans() }
        assertNull(viewModel.uiState.value.subscriptionPlans)
        parked.complete(null)
    }

    /**
     * The main screen's Upgrade button has to show the hand-off, not just the plan load: the
     * save gate can raise the offer again mid-purchase, and `showPlanChooser` refuses then.
     */
    @Test
    fun `the state says a purchase is in flight so the button can show it`() = runTest {
        val parked = CompletableDeferred<PurchaseFailure?>()
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } coAnswers { parked.await() }

        val viewModel = buildViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.purchaseInFlight)

        parked.complete(null)
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.purchaseInFlight)
    }

    /**
     * Dismissing the offer must not reach into a purchase it did not start. The gate can raise
     * the offer again while a hand-off is still running, and Cancel there used to throw away
     * that purchase's answer and free the guard that stops a second one.
     */
    @Test
    fun `dismissing the offer leaves a purchase in flight alone`() = runTest {
        val parked = CompletableDeferred<PurchaseFailure?>()
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } coAnswers { parked.await() }

        val viewModel = buildViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        advanceUntilIdle()
        viewModel.dismissUpgradeDialog()

        assertEquals("the hand-off is still running", true, viewModel.uiState.value.purchaseInFlight)

        parked.complete(PurchaseFailure.PRODUCT_UNAVAILABLE)
        advanceUntilIdle()

        assertEquals(PurchaseFailure.PRODUCT_UNAVAILABLE, viewModel.uiState.value.purchaseError)
        assertEquals(false, viewModel.uiState.value.purchaseInFlight)
    }

    /** Two Continue taps must not start two hand-offs. */
    @Test
    fun `a double tap on Continue hands off once`() = runTest {
        val parked = CompletableDeferred<PurchaseFailure?>()
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } coAnswers { parked.await() }

        val viewModel = buildViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        advanceUntilIdle()

        coVerify(exactly = 1) { billingRepository.launchPurchaseFlow(any(), any()) }
        parked.complete(null)
    }

    /** The offer dialog closes only once there is something to replace it with. */
    @Test
    fun `the upgrade offer closes when the chooser takes its place`() = runTest {
        coEvery { billingRepository.loadSubscriptionPlans() } returns PlanQuery.Available(plans)
        // A save the gate refuses is what raises the offer in the first place.
        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns listOf(mockk())
        coEvery { subscriptionGate.checkSaveAccess(any(), any()) } returns
            GateResult.Blocked(BlockReason.SAVE_RANGE_EXCEEDS_FREE_LIMIT)

        val viewModel = buildViewModel()
        viewModel.requestSave()
        advanceUntilIdle()
        assertEquals("precondition: the offer must be up", true, viewModel.uiState.value.showUpgradeDialog)

        viewModel.showPlanChooser()
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.showUpgradeDialog)
        assertNull(viewModel.uiState.value.upgradeReason)
        assertEquals(plans, viewModel.uiState.value.subscriptionPlans)
    }

    @Test
    fun `dismissing the chooser buys nothing`() = runTest {
        coEvery { billingRepository.loadSubscriptionPlans() } returns PlanQuery.Available(plans)

        val viewModel = buildViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()
        viewModel.dismissPlanChooser()

        assertNull(viewModel.uiState.value.subscriptionPlans)
        coVerify(exactly = 0) { billingRepository.launchPurchaseFlow(any(), any()) }
    }

    /**
     * Leaving must drop the *answer*, not the guard. The hand-off is deliberately never
     * cancelled, so clearing `purchaseInFlight` on the way out let the user come back, pick the
     * other plan and be handed to Play twice - the first sheet for the plan they had moved on
     * from. That is the arbitrary-plan charge this issue exists to prevent, reached through the
     * UI rather than through `firstOrNull()`.
     */
    @Test
    fun `leaving the screen does not free the guard over a live hand-off`() = runTest {
        val parked = CompletableDeferred<PurchaseFailure?>()
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } coAnswers { parked.await() }

        val viewModel = buildViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        advanceUntilIdle()
        viewModel.onScreenLeft()
        advanceUntilIdle()

        assertEquals(
            "the hand-off is still running, so the guard must still be up",
            true,
            viewModel.uiState.value.purchaseInFlight
        )

        // The second plan must not reach Play while the first hand-off is alive.
        viewModel.launchPurchaseFlow(mockk<Activity>(), yearlyPlan)
        advanceUntilIdle()
        coVerify(exactly = 0) { billingRepository.launchPurchaseFlow(any(), yearlyPlan) }

        parked.complete(PurchaseFailure.BILLING_UNAVAILABLE)
        advanceUntilIdle()

        // Abandoned, so the failure is dropped - but the flag is the call's own to clear.
        assertNull(viewModel.uiState.value.purchaseError)
        assertEquals(false, viewModel.uiState.value.purchaseInFlight)
    }

    /**
     * The same rule for the load. Freeing `plansLoading` on dismissal let the next tap queue
     * behind a load nobody was waiting for, inside the repository's purchase mutex - and spend
     * its whole timeout on the lock, reporting BILLING_UNAVAILABLE over a working connection.
     */
    @Test
    fun `dismissing the offer does not free the guard over a live load`() = runTest {
        val parked = CompletableDeferred<PlanQuery>()
        coEvery { billingRepository.loadSubscriptionPlans() } coAnswers { parked.await() }

        val viewModel = buildViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()
        viewModel.dismissUpgradeDialog()
        advanceUntilIdle()

        assertEquals(
            "the load is still running, so the button must still look busy",
            true,
            viewModel.uiState.value.plansLoading
        )

        viewModel.showPlanChooser()
        advanceUntilIdle()
        coVerify(exactly = 1) { billingRepository.loadSubscriptionPlans() }

        parked.complete(PlanQuery.Available(plans))
        advanceUntilIdle()

        assertNull("the abandoned load publishes nothing", viewModel.uiState.value.subscriptionPlans)
        assertEquals(false, viewModel.uiState.value.plansLoading)
    }

    /**
     * The chooser is the only thing on screen while Play is being asked for the sheet, so it has
     * to stay up: closing it left the main screen with no dialog, no spinner and nothing
     * disabled for as long as preparing took - the dead button again, with a charge at the end.
     */
    @Test
    fun `the chooser stays up while the hand-off runs`() = runTest {
        val parked = CompletableDeferred<PurchaseFailure?>()
        coEvery { billingRepository.loadSubscriptionPlans() } returns PlanQuery.Available(plans)
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } coAnswers { parked.await() }

        val viewModel = buildViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()
        viewModel.launchPurchaseFlow(mockk<Activity>(), yearlyPlan)
        advanceUntilIdle()

        assertNotNull(
            "the chooser renders the busy Continue button",
            viewModel.uiState.value.subscriptionPlans
        )
        assertEquals(true, viewModel.uiState.value.purchaseInFlight)

        parked.complete(null)
        advanceUntilIdle()

        assertNull("and goes once Play has the sheet", viewModel.uiState.value.subscriptionPlans)
    }
}
