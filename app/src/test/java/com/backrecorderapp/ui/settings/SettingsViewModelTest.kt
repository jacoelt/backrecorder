package com.backrecorderapp.ui.settings

import android.app.PendingIntent
import app.cash.turbine.test
import com.backrecorderapp.data.billing.BillingRepository
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

        every { appPreferences.cloudUploadMode } returns cloudUploadMode
        every { appPreferences.driveDisconnected } returns driveDisconnected
        every { billingRepository.subscriptionStatus } returns
            MutableStateFlow(SubscriptionStatus.ACTIVE)
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
    fun `connectDrive leaves sync stopped while the upload mode is off`() = runTest {
        coEvery { driveAuthManager.authorize() } returns
            DriveAuthManager.AuthorizationOutcome.Authorized
        cloudUploadMode.value = CloudUploadMode.OFF

        val viewModel = createViewModel()
        viewModel.connectDrive()
        advanceUntilIdle()

        verify(exactly = 0) { cloudSyncManager.startSync() }
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
            assertTrue(awaitItem().driveNeedsReauth)

            storageFull.value = true
            assertTrue(awaitItem().driveStorageFull)

            cancelAndIgnoreRemainingEvents()
        }
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
}
