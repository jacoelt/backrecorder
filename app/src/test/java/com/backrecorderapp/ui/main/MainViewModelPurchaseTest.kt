package com.backrecorderapp.ui.main

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.billing.PurchaseFailure
import com.backrecorderapp.data.cloud.CloudSyncManager
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.segment.SegmentManager
import com.backrecorderapp.data.waveform.WaveformRepository
import com.backrecorderapp.domain.SaveRecordingUseCase
import com.backrecorderapp.domain.SubscriptionGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
        coEvery { billingRepository.launchPurchaseFlow(any()) } returns
            PurchaseFailure.PRODUCT_UNAVAILABLE

        val viewModel = buildViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>())
        advanceUntilIdle()

        assertEquals(PurchaseFailure.PRODUCT_UNAVAILABLE, viewModel.uiState.value.purchaseError)
    }

    @Test
    fun `each failure reaches the state unchanged`() = runTest {
        PurchaseFailure.entries.forEach { failure ->
            coEvery { billingRepository.launchPurchaseFlow(any()) } returns failure

            val viewModel = buildViewModel()
            viewModel.launchPurchaseFlow(mockk<Activity>())
            advanceUntilIdle()

            assertEquals(failure, viewModel.uiState.value.purchaseError)
        }
    }

    @Test
    fun `a hand-off that reached Play reports nothing`() = runTest {
        coEvery { billingRepository.launchPurchaseFlow(any()) } returns null

        val viewModel = buildViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>())
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
        viewModel.launchPurchaseFlow(null)
        advanceUntilIdle()

        assertEquals(PurchaseFailure.LAUNCH_FAILED, viewModel.uiState.value.purchaseError)
        coVerify(exactly = 0) { billingRepository.launchPurchaseFlow(any()) }
    }

    @Test
    fun `dismissing clears the failure`() = runTest {
        coEvery { billingRepository.launchPurchaseFlow(any()) } returns
            PurchaseFailure.BILLING_UNAVAILABLE

        val viewModel = buildViewModel()
        viewModel.launchPurchaseFlow(mockk<Activity>())
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
        coEvery { billingRepository.launchPurchaseFlow(any()) } returns
            PurchaseFailure.LAUNCH_FAILED

        val viewModel = buildViewModel()
        viewModel.requestSave()
        advanceUntilIdle()
        val saveError = viewModel.uiState.value.errorMessage
        assertNotNull("precondition: the save must have reported an error", saveError)

        viewModel.launchPurchaseFlow(mockk<Activity>())
        advanceUntilIdle()

        assertEquals(saveError, viewModel.uiState.value.errorMessage)
        assertEquals(PurchaseFailure.LAUNCH_FAILED, viewModel.uiState.value.purchaseError)

        viewModel.dismissPurchaseError()
        assertEquals(saveError, viewModel.uiState.value.errorMessage)
    }
}
