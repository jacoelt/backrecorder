package com.backrecorderapp.ui.settings

import android.app.Activity
import android.app.PendingIntent
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.backrecorderapp.data.billing.BillingPeriod
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.billing.PlanQuery
import com.backrecorderapp.data.billing.PurchaseFailure
import com.backrecorderapp.data.billing.SubscriptionPlan
import com.backrecorderapp.data.billing.SubscriptionPlans
import com.backrecorderapp.data.billing.SubscriptionStatus
import com.backrecorderapp.data.cloud.CloudSyncManager
import com.backrecorderapp.data.cloud.DriveAuthManager
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.domain.BlockReason
import com.backrecorderapp.domain.GateResult
import com.backrecorderapp.domain.SubscriptionGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Waits for a state satisfying [predicate] rather than for a fixed number of emissions, so a test
 * that changes two sources does not depend on whether the combine coalesced them.
 */
private suspend fun ReceiveTurbine<SettingsUiState>.awaitUntil(
    predicate: (SettingsUiState) -> Boolean
): SettingsUiState {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}

/**
 * Covers the Drive consent / reconnect / disconnect flow. The failure modes that matter here are
 * silent: a blocked subscription that still authorizes, a failed consent that leaves the app in a
 * mode it cannot upload in, and a disconnect that the OAuth grant quietly undoes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var appPreferences: AppPreferences
    private lateinit var billingRepository: BillingRepository
    private lateinit var subscriptionGate: SubscriptionGate
    private lateinit var cloudSyncManager: CloudSyncManager
    private lateinit var driveAuthManager: DriveAuthManager

    private lateinit var cloudUploadMode: MutableStateFlow<CloudUploadMode>
    private lateinit var driveDisconnected: MutableStateFlow<Boolean>
    private lateinit var driveAuthorized: MutableStateFlow<Boolean>
    private lateinit var needsReauth: MutableStateFlow<Boolean>
    private lateinit var storageFull: MutableStateFlow<Boolean>
    private lateinit var subscriptionStatus: MutableStateFlow<SubscriptionStatus>
    private lateinit var statusResolved: MutableStateFlow<Boolean>

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        appPreferences = mockk(relaxed = true)
        billingRepository = mockk(relaxed = true)
        subscriptionGate = mockk(relaxed = true)
        cloudSyncManager = mockk(relaxed = true)
        driveAuthManager = mockk(relaxed = true)

        cloudUploadMode = MutableStateFlow(CloudUploadMode.WIFI_ONLY)
        driveDisconnected = MutableStateFlow(false)
        driveAuthorized = MutableStateFlow(false)
        needsReauth = MutableStateFlow(false)
        storageFull = MutableStateFlow(false)
        subscriptionStatus = MutableStateFlow(SubscriptionStatus.ACTIVE)
        statusResolved = MutableStateFlow(true)

        every { appPreferences.cloudUploadMode } returns cloudUploadMode
        every { appPreferences.driveDisconnected } returns driveDisconnected
        every { billingRepository.subscriptionStatus } returns subscriptionStatus
        every { subscriptionGate.statusResolved } returns statusResolved
        every { billingRepository.getTrialDaysRemaining() } returns 0
        every { driveAuthManager.isAuthorized } returns driveAuthorized
        every { cloudSyncManager.needsReauth } returns needsReauth
        every { cloudSyncManager.storageFull } returns storageFull

        coEvery { subscriptionGate.checkCloudAccess() } returns GateResult.Allowed
        coEvery { driveAuthManager.restoreSession() } returns true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = SettingsViewModel(
        appPreferences = appPreferences,
        billingRepository = billingRepository,
        subscriptionGate = subscriptionGate,
        cloudSyncManager = cloudSyncManager,
        driveAuthManager = driveAuthManager
    )

    @Test
    fun `restores the session on init when Drive was not disconnected`() = runTest {
        driveDisconnected.value = false

        createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 1) { driveAuthManager.restoreSession() }
    }

    @Test
    fun `restoring the session on init never enables uploads`() = runTest {
        // A grant outliving a subscription must not resurrect uploads the user switched off, so
        // init's restoreSession() must never reach startSyncEnablingUploads().
        cloudUploadMode.value = CloudUploadMode.OFF
        driveDisconnected.value = false

        createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { appPreferences.setCloudUploadMode(any()) }
        verify(exactly = 0) { cloudSyncManager.startSync() }
    }

    @Test
    fun `does not restore the session after an explicit disconnect`() = runTest {
        // The OAuth grant survives sign-out, so an unconditional restore would silently
        // reconnect the account the user just removed.
        driveDisconnected.value = true

        createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { driveAuthManager.restoreSession() }
    }

    @Test
    fun `connectDrive raises the upgrade dialog and never authorizes when blocked`() = runTest {
        coEvery { subscriptionGate.checkCloudAccess() } returns
            GateResult.Blocked(BlockReason.CLOUD_STORAGE_PREMIUM_ONLY)

        val viewModel = createViewModel()
        viewModel.connectDrive()
        advanceUntilIdle()

        assertTrue(viewModel.showUpgradeDialog.value)
        coVerify(exactly = 0) { driveAuthManager.authorize() }
        verify(exactly = 0) { cloudSyncManager.startSync() }
    }

    @Test
    fun `connectDrive hands consent to the screen without starting sync`() = runTest {
        val pendingIntent = mockk<PendingIntent>()
        coEvery { driveAuthManager.authorize() } returns
            DriveAuthManager.AuthorizationOutcome.ConsentRequired(pendingIntent)

        val viewModel = createViewModel()
        viewModel.connectDrive()
        advanceUntilIdle()

        assertNotNull(viewModel.consentRequest.value)
        verify(exactly = 0) { cloudSyncManager.startSync() }
    }

    @Test
    fun `connectDrive surfaces a sign-in error without starting sync`() = runTest {
        coEvery { driveAuthManager.authorize() } returns
            DriveAuthManager.AuthorizationOutcome.Failed

        val viewModel = createViewModel()
        viewModel.connectDrive()
        advanceUntilIdle()

        assertEquals(DriveError.SIGN_IN_FAILED, viewModel.driveError.value)
        verify(exactly = 0) { cloudSyncManager.startSync() }
    }

    @Test
    fun `connectDrive starts sync and clears the disconnect flag once authorized`() = runTest {
        coEvery { driveAuthManager.authorize() } returns
            DriveAuthManager.AuthorizationOutcome.Authorized
        cloudUploadMode.value = CloudUploadMode.WIFI_ONLY

        val viewModel = createViewModel()
        viewModel.connectDrive()
        advanceUntilIdle()

        coVerify { appPreferences.setDriveDisconnected(false) }
        verify(exactly = 1) { cloudSyncManager.onAuthorizationRestored() }
        verify(exactly = 1) { cloudSyncManager.startSync() }
    }

    @Test
    fun `connecting Drive enables uploads when no mode was chosen`() = runTest {
        // #80: consent completed, the screen read "Connected", and nothing ever uploaded. The
        // mode stayed on its OFF default, so canSyncNow() refused at its first gate.
        coEvery { driveAuthManager.authorize() } returns
            DriveAuthManager.AuthorizationOutcome.Authorized
        cloudUploadMode.value = CloudUploadMode.OFF

        val viewModel = createViewModel()
        viewModel.connectDrive()
        advanceUntilIdle()

        coVerifyOrder {
            appPreferences.setCloudUploadMode(CloudUploadMode.WIFI_ONLY)
            cloudSyncManager.startSync()
        }
        verify(exactly = 1) { cloudSyncManager.startSync() }
        coVerify(exactly = 1) { appPreferences.setCloudUploadMode(CloudUploadMode.WIFI_ONLY) }
    }

    @Test
    fun `completing consent enables uploads when no mode was chosen`() = runTest {
        // The reporter's actual path: the grant arrives through the consent sheet, a separate
        // branch from connectDrive that carried the same defect.
        coEvery { driveAuthManager.handleAuthorizationResult(any()) } returns
            DriveAuthManager.AuthorizationOutcome.Authorized
        cloudUploadMode.value = CloudUploadMode.OFF

        val viewModel = createViewModel()
        viewModel.onConsentResult(null)
        advanceUntilIdle()

        coVerifyOrder {
            appPreferences.setCloudUploadMode(CloudUploadMode.WIFI_ONLY)
            cloudSyncManager.startSync()
        }
        verify(exactly = 1) { cloudSyncManager.startSync() }
        coVerify(exactly = 1) { appPreferences.setCloudUploadMode(CloudUploadMode.WIFI_ONLY) }
    }

    @Test
    fun `connecting Drive keeps an upload mode the user already chose`() = runTest {
        // Only the OFF default is overridden. ALWAYS_ON is a decision, not the absence of one.
        coEvery { driveAuthManager.authorize() } returns
            DriveAuthManager.AuthorizationOutcome.Authorized
        cloudUploadMode.value = CloudUploadMode.ALWAYS_ON

        val viewModel = createViewModel()
        viewModel.connectDrive()
        advanceUntilIdle()

        coVerify(exactly = 0) { appPreferences.setCloudUploadMode(any()) }
        verify(exactly = 1) { cloudSyncManager.startSync() }
    }

    @Test
    fun `a failed consent result stops sync but keeps the chosen upload mode`() = runTest {
        // Backing out of the consent sheet is a routine cancel; erasing the mode the user just
        // picked would be a surprising side effect of tapping Back.
        coEvery { driveAuthManager.handleAuthorizationResult(any()) } returns
            DriveAuthManager.AuthorizationOutcome.Failed
        cloudUploadMode.value = CloudUploadMode.WIFI_ONLY

        val viewModel = createViewModel()
        viewModel.onConsentResult(null)
        advanceUntilIdle()

        assertEquals(DriveError.SIGN_IN_FAILED, viewModel.driveError.value)
        coVerify(exactly = 0) { appPreferences.setCloudUploadMode(any()) }
        verify(exactly = 1) { cloudSyncManager.stopSync() }
        verify(exactly = 0) { cloudSyncManager.startSync() }
    }

    @Test
    fun `a successful consent result starts sync and clears the disconnect flag`() = runTest {
        coEvery { driveAuthManager.handleAuthorizationResult(any()) } returns
            DriveAuthManager.AuthorizationOutcome.Authorized
        cloudUploadMode.value = CloudUploadMode.ALWAYS_ON

        val viewModel = createViewModel()
        viewModel.onConsentResult(null)
        advanceUntilIdle()

        assertNull(viewModel.driveError.value)
        coVerify { appPreferences.setDriveDisconnected(false) }
        verify(exactly = 1) { cloudSyncManager.startSync() }
    }

    @Test
    fun `disconnectDrive records the disconnect before signing out`() = runTest {
        val viewModel = createViewModel()
        viewModel.disconnectDrive()
        advanceUntilIdle()

        verify(exactly = 1) { cloudSyncManager.stopSync() }
        // Order matters: the OAuth grant outlives sign-out, so if the flag write never lands the
        // next launch would silently restore the account the user just removed.
        coVerifyOrder {
            appPreferences.setDriveDisconnected(true)
            driveAuthManager.signOut()
        }
        coVerify { appPreferences.setCloudUploadMode(CloudUploadMode.OFF) }
    }

    @Test
    fun `setCloudUploadMode off stops sync without asking for the grant`() = runTest {
        val viewModel = createViewModel()
        viewModel.setCloudUploadMode(CloudUploadMode.OFF)
        advanceUntilIdle()

        coVerify { appPreferences.setCloudUploadMode(CloudUploadMode.OFF) }
        verify(exactly = 1) { cloudSyncManager.stopSync() }
        coVerify(exactly = 0) { driveAuthManager.authorize() }
    }

    @Test
    fun `setCloudUploadMode does not persist a premium mode when blocked`() = runTest {
        coEvery { subscriptionGate.checkCloudAccess() } returns
            GateResult.Blocked(BlockReason.CLOUD_STORAGE_PREMIUM_ONLY)

        val viewModel = createViewModel()
        viewModel.setCloudUploadMode(CloudUploadMode.ALWAYS_ON)
        advanceUntilIdle()

        assertTrue(viewModel.showUpgradeDialog.value)
        coVerify(exactly = 0) { appPreferences.setCloudUploadMode(CloudUploadMode.ALWAYS_ON) }
        verify(exactly = 0) { cloudSyncManager.startSync() }
    }

    @Test
    fun `uiState reflects the Drive connection and both problem states`() = runTest {
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // initial default, emitted before the sources are collected

            driveAuthorized.value = true
            assertTrue(awaitItem().driveConnected)

            needsReauth.value = true
            assertTrue(awaitItem().showDriveReauthPrompt)

            storageFull.value = true
            assertTrue(awaitItem().showDriveStorageFull)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Both problems are latched by `CloudSyncManager` and cleared only by a tick the gate allows,
     * a disconnect or a re-grant - none of which can happen while the plan blocks every tick. So
     * a 401 from before the lapse would otherwise sit on the row permanently, giving a second and
     * wrong reason for the same stopped uploads, with Connect next to a row reading "Connected".
     */
    @Test
    fun `the plan takes precedence over a latched drive problem`() = runTest {
        subscriptionStatus.value = SubscriptionStatus.ACTIVE

        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // initial default, emitted before the sources are collected

            driveAuthorized.value = true
            awaitItem()
            needsReauth.value = true
            storageFull.value = true

            val premium = awaitUntil { it.showDriveReauthPrompt && it.showDriveStorageFull }
            assertFalse("precondition: the plan is not blocking", premium.driveSyncBlockedByPlan)

            subscriptionStatus.value = SubscriptionStatus.EXPIRED

            val lapsed = awaitUntil { it.driveSyncBlockedByPlan }
            assertFalse(lapsed.showDriveReauthPrompt)
            assertFalse(lapsed.showDriveStorageFull)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `drive row reports the grant as unusable once the subscription lapses`() = runTest {
        subscriptionStatus.value = SubscriptionStatus.ACTIVE

        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // initial default, emitted before the sources are collected

            driveAuthorized.value = true
            val active = awaitItem()
            assertTrue("precondition: the grant is in place", active.driveConnected)
            assertFalse(
                "precondition: a paying subscriber is not told to upgrade",
                active.driveSyncBlockedByPlan
            )

            subscriptionStatus.value = SubscriptionStatus.EXPIRED

            val lapsed = awaitItem()
            // The grant itself is deliberately kept, so re-subscribing does not need a fresh
            // consent round - but the row must stop claiming uploads work.
            assertTrue(lapsed.driveConnected)
            assertTrue(lapsed.driveSyncBlockedByPlan)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * TRIAL is premium, and is the status most easily lost by a regression to a bare
     * `== ACTIVE` - which would tell every trial user their Drive account was unusable.
     */
    @Test
    fun `a trial subscriber is not told to upgrade`() = runTest {
        subscriptionStatus.value = SubscriptionStatus.TRIAL

        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // initial default, emitted before the sources are collected

            driveAuthorized.value = true
            val trial = awaitItem()
            assertTrue("precondition: the grant is in place", trial.driveConnected)
            assertFalse(trial.driveSyncBlockedByPlan)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Without the `driveConnected` term the row would offer to restore uploads for an account
     * that was never connected.
     */
    @Test
    fun `a lapsed subscriber with no grant is not told their account is blocked`() = runTest {
        subscriptionStatus.value = SubscriptionStatus.ACTIVE

        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // initial default, emitted before the sources are collected

            subscriptionStatus.value = SubscriptionStatus.EXPIRED
            val lapsed = awaitItem()
            assertFalse("precondition: no grant", lapsed.driveConnected)
            assertFalse(lapsed.driveSyncBlockedByPlan)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The stored upload mode is the screen's *other* claim that uploads are happening, and it
     * outlives the subscription exactly as the grant does. It carries no `driveConnected` term:
     * a mode set before the account was connected still says "Always on".
     */
    @Test
    fun `a stored upload mode is reported as paused once the subscription lapses`() = runTest {
        subscriptionStatus.value = SubscriptionStatus.ACTIVE
        cloudUploadMode.value = CloudUploadMode.ALWAYS_ON

        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // initial default, emitted before the sources are collected

            subscriptionStatus.value = SubscriptionStatus.EXPIRED
            val lapsed = awaitItem()
            assertTrue(lapsed.cloudUploadBlockedByPlan)
            // The preference itself is kept, so uploads resume on re-subscribe.
            assertEquals(CloudUploadMode.ALWAYS_ON, lapsed.cloudUploadMode)
            assertFalse("no grant, so the Drive row says nothing", lapsed.driveSyncBlockedByPlan)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /** OFF makes no claim, so there is nothing to contradict. */
    @Test
    fun `an upload mode of off is never reported as paused`() = runTest {
        subscriptionStatus.value = SubscriptionStatus.ACTIVE
        cloudUploadMode.value = CloudUploadMode.OFF

        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // initial default, emitted before the sources are collected

            subscriptionStatus.value = SubscriptionStatus.EXPIRED
            assertFalse(awaitItem().cloudUploadBlockedByPlan)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The constraint from CLAUDE.md's "Subscription gate": at process start the status is only
     * its FREE default. Reading that as a lapsed subscription would tell a paying subscriber at
     * every launch that their Drive account is unusable.
     */
    @Test
    fun `does not call the grant unusable before billing has answered`() = runTest {
        statusResolved.value = false
        subscriptionStatus.value = SubscriptionStatus.FREE

        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // initial default, emitted before the sources are collected

            driveAuthorized.value = true
            val unresolved = awaitItem()
            assertTrue("precondition: the grant is in place", unresolved.driveConnected)
            assertFalse(unresolved.driveSyncBlockedByPlan)
            // The stored mode is WIFI_ONLY here, so this pins the upload flag's own
            // statusResolved term rather than riding on the Drive flag's.
            assertFalse(unresolved.cloudUploadBlockedByPlan)

            statusResolved.value = true

            val resolved = awaitItem()
            assertTrue(resolved.driveSyncBlockedByPlan)
            assertTrue(resolved.cloudUploadBlockedByPlan)

            cancelAndIgnoreRemainingEvents()
        }
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

    /**
     * Settings is the second upgrade entry point (issue #72). A refusal has to be reported here
     * too, and only here - the main screen's view model is alive behind this one, so a failure
     * both of them acted on would show twice.
     */
    @Test
    fun `a refused upgrade hand-off is reported and clears on dismiss`() = runTest {
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } returns
            PurchaseFailure.PRODUCT_UNAVAILABLE

        val viewModel = createViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        advanceUntilIdle()

        assertEquals(PurchaseFailure.PRODUCT_UNAVAILABLE, viewModel.purchaseError.value)

        viewModel.dismissPurchaseError()
        assertNull(viewModel.purchaseError.value)
    }

    @Test
    fun `a hand-off that reached Play reports nothing`() = runTest {
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } returns null

        val viewModel = createViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        advanceUntilIdle()

        assertNull(viewModel.purchaseError.value)
    }

    @Test
    fun `no activity to launch over is reported rather than ignored`() = runTest {
        val viewModel = createViewModel()
        viewModel.launchPurchaseFlow(null, monthlyPlan)
        advanceUntilIdle()

        assertEquals(PurchaseFailure.LAUNCH_FAILED, viewModel.purchaseError.value)
        coVerify(exactly = 0) { billingRepository.launchPurchaseFlow(any(), any()) }
    }

    @Test
    fun `the chooser opens here too, with the plans Play offered`() = runTest {
        coEvery { billingRepository.loadSubscriptionPlans() } returns PlanQuery.Available(plans)

        val viewModel = createViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()

        assertEquals(plans, viewModel.subscriptionPlans.value)
        assertEquals(false, viewModel.upgradeBusy.value)
        coVerify(exactly = 0) { billingRepository.launchPurchaseFlow(any(), any()) }
    }

    @Test
    fun `buying from settings closes the chooser and sends the plan that was picked`() = runTest {
        coEvery { billingRepository.loadSubscriptionPlans() } returns PlanQuery.Available(plans)
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } returns null

        val viewModel = createViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()
        // Yearly, deliberately: not the first plan in the set, so this cannot pass by accident.
        viewModel.launchPurchaseFlow(mockk<Activity>(), yearlyPlan)
        advanceUntilIdle()

        assertNull(viewModel.subscriptionPlans.value)
        coVerify(exactly = 1) { billingRepository.launchPurchaseFlow(any(), yearlyPlan) }
        coVerify(exactly = 0) { billingRepository.launchPurchaseFlow(any(), monthlyPlan) }
    }

    /**
     * Settings is not always torn down on the way out - it navigates forward to the disclaimer -
     * so a load the user left behind must not publish its chooser over this screen later.
     */
    @Test
    fun `a load the user left behind publishes nothing`() = runTest {
        val parked = CompletableDeferred<PlanQuery>()
        coEvery { billingRepository.loadSubscriptionPlans() } coAnswers { parked.await() }

        val viewModel = createViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()
        assertTrue("precondition: the load must be in flight", viewModel.upgradeBusy.value)

        viewModel.onScreenLeft()
        parked.complete(PlanQuery.Available(plans))
        advanceUntilIdle()

        assertNull(viewModel.subscriptionPlans.value)
        assertNull(viewModel.purchaseError.value)
        assertFalse(viewModel.upgradeBusy.value)
    }

    @Test
    fun `leaving the screen clears a purchase failure`() = runTest {
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } returns
            PurchaseFailure.BILLING_UNAVAILABLE

        val viewModel = createViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        advanceUntilIdle()
        assertEquals(PurchaseFailure.BILLING_UNAVAILABLE, viewModel.purchaseError.value)

        viewModel.onScreenLeft()

        assertNull(viewModel.purchaseError.value)
    }

    /** Dismissing the offer takes its load with it, on this screen too. */
    @Test
    fun `dismissing the upgrade dialog drops the load it started`() = runTest {
        val parked = CompletableDeferred<PlanQuery>()
        coEvery { billingRepository.loadSubscriptionPlans() } coAnswers { parked.await() }

        val viewModel = createViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()
        viewModel.dismissUpgradeDialog()
        parked.complete(PlanQuery.Available(plans))
        advanceUntilIdle()

        assertNull(viewModel.subscriptionPlans.value)
        assertFalse(viewModel.upgradeBusy.value)
    }

    /** The Upgrade button must look busy while a purchase is on its way to Play, not live. */
    @Test
    fun `the upgrade button stays busy while a purchase is handed over`() = runTest {
        val parked = CompletableDeferred<PurchaseFailure?>()
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } coAnswers { parked.await() }

        val viewModel = createViewModel()
        viewModel.upgradeBusy.test {
            assertFalse(awaitItem())

            viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
            assertTrue(awaitItem())

            parked.complete(null)
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no chooser opens while a purchase is being handed to Play`() = runTest {
        val parked = CompletableDeferred<PurchaseFailure?>()
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } coAnswers { parked.await() }

        val viewModel = createViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        advanceUntilIdle()
        viewModel.showPlanChooser()
        advanceUntilIdle()

        coVerify(exactly = 0) { billingRepository.loadSubscriptionPlans() }
        parked.complete(null)
    }

    @Test
    fun `a double tap on Continue hands off once`() = runTest {
        val parked = CompletableDeferred<PurchaseFailure?>()
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } coAnswers { parked.await() }

        val viewModel = createViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        advanceUntilIdle()

        coVerify(exactly = 1) { billingRepository.launchPurchaseFlow(any(), any()) }
        parked.complete(null)
    }

    @Test
    fun `dismissing the chooser in settings buys nothing`() = runTest {
        coEvery { billingRepository.loadSubscriptionPlans() } returns PlanQuery.Available(plans)

        val viewModel = createViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()
        viewModel.dismissPlanChooser()

        assertNull(viewModel.subscriptionPlans.value)
        coVerify(exactly = 0) { billingRepository.launchPurchaseFlow(any(), any()) }
    }

    /** The same refusal the hand-off would report, in the step before it (#74). */
    @Test
    fun `plans that cannot be loaded are reported here too`() = runTest {
        coEvery { billingRepository.loadSubscriptionPlans() } returns
            PlanQuery.Unavailable(PurchaseFailure.BILLING_UNAVAILABLE)

        val viewModel = createViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()

        assertNull(viewModel.subscriptionPlans.value)
        assertEquals(PurchaseFailure.BILLING_UNAVAILABLE, viewModel.purchaseError.value)
        assertEquals(false, viewModel.upgradeBusy.value)
    }

    @Test
    fun `onConsentLaunched clears the pending request so it cannot fire twice`() = runTest {
        val pendingIntent = mockk<PendingIntent>()
        coEvery { driveAuthManager.authorize() } returns
            DriveAuthManager.AuthorizationOutcome.ConsentRequired(pendingIntent)

        val viewModel = createViewModel()
        viewModel.connectDrive()
        advanceUntilIdle()
        viewModel.onConsentLaunched()

        assertNull(viewModel.consentRequest.value)
    }

    /**
     * The mirror of the main screen's rule, and the reason it matters here specifically: settings
     * navigates *forward* to the disclaimer and keeps its back-stack entry, so `onScreenLeft`
     * runs with the hand-off still alive. Freeing the guard then let the user reach the main
     * screen's Upgrade, pick the other plan, and be handed to Play twice.
     */
    @Test
    fun `leaving settings does not free the guard over a live hand-off`() = runTest {
        val parked = CompletableDeferred<PurchaseFailure?>()
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } coAnswers { parked.await() }

        val viewModel = createViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>(), monthlyPlan)
        advanceUntilIdle()
        viewModel.onScreenLeft()
        advanceUntilIdle()

        assertTrue("the hand-off is still running", viewModel.upgradeBusy.value)

        viewModel.launchPurchaseFlow(mockk<Activity>(), yearlyPlan)
        advanceUntilIdle()
        coVerify(exactly = 0) { billingRepository.launchPurchaseFlow(any(), yearlyPlan) }
        // Pinned by count as well as by plan: the second tap must be discarded whatever it
        // carries, not merely resolved to the same plan as the first.
        coVerify(exactly = 1) { billingRepository.launchPurchaseFlow(any(), any()) }

        parked.complete(PurchaseFailure.BILLING_UNAVAILABLE)
        advanceUntilIdle()

        assertNull("the abandoned answer is dropped", viewModel.purchaseError.value)
        assertFalse(viewModel.upgradeBusy.value)
    }

    /**
     * Settings carries the Upgrade affordance twice - the subscription card and the offer dialog
     * - so a dismissal here can land on a load the card started. It still must not free the
     * guard: the next tap would queue inside the repository's mutex and spend its whole timeout
     * on the lock, reporting BILLING_UNAVAILABLE over a connection that is working.
     */
    @Test
    fun `dismissing the offer does not free the guard over a live load`() = runTest {
        val parked = CompletableDeferred<PlanQuery>()
        coEvery { billingRepository.loadSubscriptionPlans() } coAnswers { parked.await() }

        val viewModel = createViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()
        viewModel.dismissUpgradeDialog()
        advanceUntilIdle()

        assertTrue("the load is still running", viewModel.upgradeBusy.value)

        // No second tap: the Upgrade affordance is disabled while a load runs, so there is none.

        parked.complete(PlanQuery.Available(plans))
        advanceUntilIdle()

        assertNull(viewModel.subscriptionPlans.value)
        coVerify(exactly = 1) { billingRepository.loadSubscriptionPlans() }
        assertFalse(viewModel.upgradeBusy.value)
    }

    /** The chooser is what renders the busy state here too, so it stays up until Play has it. */
    @Test
    fun `the chooser stays up while the hand-off runs`() = runTest {
        val parked = CompletableDeferred<PurchaseFailure?>()
        coEvery { billingRepository.loadSubscriptionPlans() } returns PlanQuery.Available(plans)
        coEvery { billingRepository.launchPurchaseFlow(any(), any()) } coAnswers { parked.await() }

        val viewModel = createViewModel()
        viewModel.showPlanChooser()
        advanceUntilIdle()
        viewModel.launchPurchaseFlow(mockk<Activity>(), yearlyPlan)
        advanceUntilIdle()

        assertNotNull(viewModel.subscriptionPlans.value)
        assertTrue(viewModel.upgradeBusy.value)

        parked.complete(null)
        advanceUntilIdle()

        assertNull(viewModel.subscriptionPlans.value)
    }

    /** The settings half: the cloud offer must not be waiting on the way back either. */
    @Test
    fun `leaving settings takes the unanswered upgrade offer with it`() = runTest {
        coEvery { subscriptionGate.checkCloudAccess() } returns
            GateResult.Blocked(BlockReason.CLOUD_STORAGE_PREMIUM_ONLY)

        val viewModel = createViewModel()
        viewModel.connectDrive()
        advanceUntilIdle()
        assertTrue("precondition: the offer must be up", viewModel.showUpgradeDialog.value)

        viewModel.onScreenLeft()

        assertFalse(viewModel.showUpgradeDialog.value)
    }
}
