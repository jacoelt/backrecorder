package com.backrecorderapp.ui.main

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.cloud.CloudSyncManager
import com.backrecorderapp.data.cloud.SavedRecordingUploader
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelSaveTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var segmentManager: SegmentManager
    private lateinit var waveformRepository: WaveformRepository
    private lateinit var saveRecordingUseCase: SaveRecordingUseCase
    private lateinit var appPreferences: AppPreferences
    private lateinit var cloudSyncManager: CloudSyncManager
    private lateinit var savedRecordingUploader: SavedRecordingUploader
    private lateinit var uploadFailures: MutableSharedFlow<String>
    private lateinit var billingRepository: BillingRepository
    private lateinit var subscriptionGate: SubscriptionGate

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic(Log::class)
        every { Log.e(any(), any<String>(), any()) } returns 0

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

        savedRecordingUploader = mockk(relaxed = true)
        uploadFailures = MutableSharedFlow(extraBufferCapacity = 8)
        every { savedRecordingUploader.uploadFailures } returns uploadFailures

        billingRepository = mockk(relaxed = true)
        subscriptionGate = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): MainViewModel = MainViewModel(
        context,
        segmentManager,
        waveformRepository,
        saveRecordingUseCase,
        appPreferences,
        cloudSyncManager,
        savedRecordingUploader,
        billingRepository,
        subscriptionGate,
        UnconfinedTestDispatcher()
    )

    @Test
    fun `an upload failure reaches the screen and clears when dismissed (issue 84)`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        uploadFailures.emit("first failure")
        advanceUntilIdle()
        assertEquals("first failure", viewModel.uiState.value.cloudUploadError?.text)

        viewModel.dismissCloudUploadError()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.cloudUploadError)
    }

    /**
     * Two failures must be shown one after the other, not collapsed: the second arriving while
     * the first snackbar is still up would otherwise overwrite a message the user never read.
     */
    @Test
    fun `a second upload failure waits for the first to be dismissed (issue 84)`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        uploadFailures.emit("first failure")
        uploadFailures.emit("second failure")
        advanceUntilIdle()
        assertEquals("first failure", viewModel.uiState.value.cloudUploadError?.text)

        viewModel.dismissCloudUploadError()
        advanceUntilIdle()
        assertEquals("second failure", viewModel.uiState.value.cloudUploadError?.text)
    }

    /**
     * The reason `UploadError` carries an id at all. Two failures naming the same recording are
     * the same string; without a distinguishing id the screen's `LaunchedEffect` key would not
     * change, the second snackbar would never show, and the collector waiting on its dismissal
     * would park for good.
     */
    @Test
    fun `two failures with identical text are distinguishable to the screen (issue 84)`() =
        runTest {
            val viewModel = buildViewModel()
            advanceUntilIdle()

            uploadFailures.emit("same text")
            advanceUntilIdle()
            val first = viewModel.uiState.value.cloudUploadError

            viewModel.dismissCloudUploadError()
            advanceUntilIdle()
            uploadFailures.emit("same text")
            advanceUntilIdle()
            val second = viewModel.uiState.value.cloudUploadError

            assertEquals("same text", second?.text)
            assertNotEquals("the two must not compare equal", first, second)
        }

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

    /**
     * The save suspends since #93, so the Save affordance is live again while one is still
     * running. Two concurrent saves each force a flush rotation of the live recording and race to
     * write the outcome message, so the second tap is discarded.
     */
    @Test
    fun `refuses a second save while one is still in flight (issue 93)`() = runTest {
        val uri = mockk<Uri>()
        val firstSave = CompletableDeferred<SaveResult>()
        coEvery { saveRecordingUseCase.execute(any(), any(), any()) } coAnswers { firstSave.await() }

        val viewModel = buildViewModel()
        viewModel.saveRecording(uri)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.saveInProgress)

        viewModel.saveRecording(uri)
        advanceUntilIdle()

        firstSave.complete(SaveResult.Success)
        advanceUntilIdle()

        coVerify(exactly = 1) { saveRecordingUseCase.execute(any(), any(), any()) }
        assertEquals("Recording saved successfully", viewModel.uiState.value.successMessage)
        assertFalse(viewModel.uiState.value.saveInProgress)
    }

    /**
     * The guard that matters: refusing before the picker opens is what costs nothing. A refusal
     * after it would leave the 0-byte file the picker had already created.
     */
    @Test
    fun `will not open the picker while a save is in flight (issue 93)`() = runTest {
        val firstSave = CompletableDeferred<SaveResult>()
        coEvery { saveRecordingUseCase.execute(any(), any(), any()) } coAnswers { firstSave.await() }

        val viewModel = buildViewModel()
        viewModel.saveRecording(mockk())
        advanceUntilIdle()

        viewModel.requestSave()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.launchSavePicker)
        coVerify(exactly = 0) { subscriptionGate.checkSaveAccess(any(), any()) }

        firstSave.complete(SaveResult.Success)
        advanceUntilIdle()
    }

    /**
     * `getSegmentsInRange` and `checkSaveAccess` sit outside the use case's own `try`, so a Room
     * or DataStore failure escapes `execute`. A flag left set by that would make Save a
     * permanently disabled spinner both guards then refuse for the life of the view model.
     */
    @Test
    fun `clears the in-flight flag and cleans up when the save throws (issue 93)`() = runTest {
        val uri = mockk<Uri>()
        coEvery { saveRecordingUseCase.execute(any(), any(), any()) } throws IOException("boom")

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.deleteDocument(contentResolver, uri) } returns true

        try {
            val viewModel = buildViewModel()
            viewModel.saveRecording(uri)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.saveInProgress)
            assertEquals("Could not save the recording", viewModel.uiState.value.errorMessage)
            verify(exactly = 1) { DocumentsContract.deleteDocument(contentResolver, uri) }
        } finally {
            unmockkStatic(DocumentsContract::class)
        }
    }

    /** A refusal that arrives after the picker ran must not leave its 0-byte file behind. */
    @Test
    fun `a save refused while one is in flight deletes the file its picker created`() = runTest {
        val firstUri = mockk<Uri>()
        val strayUri = mockk<Uri>()
        val firstSave = CompletableDeferred<SaveResult>()
        coEvery { saveRecordingUseCase.execute(any(), any(), any()) } coAnswers { firstSave.await() }

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.deleteDocument(contentResolver, strayUri) } returns true

        try {
            val viewModel = buildViewModel()
            viewModel.saveRecording(firstUri)
            advanceUntilIdle()

            viewModel.saveRecording(strayUri)
            advanceUntilIdle()

            verify(exactly = 1) { DocumentsContract.deleteDocument(contentResolver, strayUri) }

            firstSave.complete(SaveResult.Success)
            advanceUntilIdle()
        } finally {
            unmockkStatic(DocumentsContract::class)
        }
    }

    /**
     * The save and its cleanup run under `NonCancellable` (#93). Without it the result is
     * discarded at the resume boundary while the uninterruptible trim and copy run on, so the
     * empty SAF file is left on the user's storage - issue #23, reopened.
     */
    @Test
    fun `cleans up the output uri even when the scope is cancelled mid-save (issue 93)`() = runTest {
        val uri = mockk<Uri>()
        val save = CompletableDeferred<SaveResult>()
        coEvery { saveRecordingUseCase.execute(any(), any(), any()) } coAnswers { save.await() }

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.deleteDocument(contentResolver, uri) } returns true

        try {
            val viewModel = buildViewModel()
            viewModel.saveRecording(uri)
            advanceUntilIdle()

            viewModel.viewModelScope.cancel()
            save.complete(SaveResult.Error("no data"))
            advanceUntilIdle()

            verify(exactly = 1) { DocumentsContract.deleteDocument(contentResolver, uri) }
            assertFalse(viewModel.uiState.value.saveInProgress)
            assertEquals("no data", viewModel.uiState.value.errorMessage)
        } finally {
            unmockkStatic(DocumentsContract::class)
        }
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
            assertFalse("a stuck flag leaves Save dead", viewModel.uiState.value.saveInProgress)
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
