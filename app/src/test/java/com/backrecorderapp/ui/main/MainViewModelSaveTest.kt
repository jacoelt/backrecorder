package com.backrecorderapp.ui.main

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.cloud.CloudSyncManager
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.segment.SegmentManager
import com.backrecorderapp.data.waveform.WaveformRepository
import com.backrecorderapp.domain.GateResult
import com.backrecorderapp.domain.SaveRecordingUseCase
import com.backrecorderapp.domain.SaveResult
import com.backrecorderapp.domain.SubscriptionGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelSaveTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
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
        contentResolver = mockk(relaxed = true)
        every { context.contentResolver } returns contentResolver

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
    fun `requestSave with no segments in range shows error and skips picker (issue 23)`() = runTest {
        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns emptyList()

        val viewModel = buildViewModel()
        viewModel.requestSave()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("picker must not launch when range is empty", state.launchSavePicker)
        assertFalse(state.showUpgradeDialog)
        assertNotNull("error message must be shown", state.errorMessage)
        // Subscription gate must not be consulted — we never even get that far.
        coVerify(exactly = 0) { subscriptionGate.checkSaveAccess(any(), any()) }
    }

    @Test
    fun `saveRecording error path deletes empty output uri (issue 23)`() = runTest {
        val uri = mockk<Uri>()
        coEvery { saveRecordingUseCase.execute(any(), any(), any()) } returns
            SaveResult.Error("no data")

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.deleteDocument(contentResolver, uri) } returns true

        try {
            val viewModel = buildViewModel()
            viewModel.saveRecording(uri)
            advanceUntilIdle()

            verify(exactly = 1) { DocumentsContract.deleteDocument(contentResolver, uri) }
            assertEquals("no data", viewModel.uiState.value.errorMessage)
        } finally {
            unmockkStatic(DocumentsContract::class)
        }
    }

    @Test
    fun `saveRecording upgrade-required path also deletes empty output uri`() = runTest {
        val uri = mockk<Uri>()
        coEvery { saveRecordingUseCase.execute(any(), any(), any()) } returns
            SaveResult.UpgradeRequired(com.backrecorderapp.domain.BlockReason.SAVE_RANGE_EXCEEDS_FREE_LIMIT)

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.deleteDocument(contentResolver, uri) } returns true

        try {
            val viewModel = buildViewModel()
            viewModel.saveRecording(uri)
            advanceUntilIdle()

            verify(exactly = 1) { DocumentsContract.deleteDocument(contentResolver, uri) }
        } finally {
            unmockkStatic(DocumentsContract::class)
        }
    }

    @Test
    fun `saveRecording success path does not delete output uri`() = runTest {
        val uri = mockk<Uri>()
        coEvery { saveRecordingUseCase.execute(any(), any(), any()) } returns SaveResult.Success

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.deleteDocument(any(), any()) } returns true

        try {
            val viewModel = buildViewModel()
            viewModel.saveRecording(uri)
            advanceUntilIdle()

            verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
        } finally {
            unmockkStatic(DocumentsContract::class)
        }
    }

    @Test
    fun `requestSave with segments in range proceeds through subscription gate`() = runTest {
        val segment = mockk<com.backrecorderapp.data.db.SegmentEntity>(relaxed = true)
        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns listOf(segment)
        coEvery { subscriptionGate.checkSaveAccess(any(), any()) } returns GateResult.Allowed

        val viewModel = buildViewModel()
        viewModel.requestSave()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(true, state.launchSavePicker)
        coVerify(exactly = 1) { subscriptionGate.checkSaveAccess(any(), any()) }
    }
}
