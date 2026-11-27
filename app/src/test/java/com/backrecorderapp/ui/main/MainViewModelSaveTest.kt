package com.backrecorderapp.ui.main

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.cloud.CloudSyncManager
import com.backrecorderapp.data.cloud.SavedRecordingUploader
import com.backrecorderapp.data.db.SegmentEntity
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.segment.SegmentManager
import com.backrecorderapp.data.waveform.WaveformRepository
import com.backrecorderapp.domain.BlockReason
import com.backrecorderapp.domain.GateResult
import com.backrecorderapp.domain.SaveCoordinator
import com.backrecorderapp.domain.SaveRecordingUseCase
import com.backrecorderapp.domain.SaveResult
import com.backrecorderapp.domain.SubscriptionGate
import com.backrecorderapp.notification.SaveFailureNotifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
    private lateinit var failureNotifier: SaveFailureNotifier
    private lateinit var saveCoordinator: SaveCoordinator
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
        every { Log.w(any(), any<String>(), any()) } returns 0

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.deleteDocument(any(), any()) } returns true

        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        every { context.contentResolver } returns contentResolver

        segmentManager = mockk(relaxed = true)
        every { segmentManager.getAllSegments() } returns flowOf(emptyList())

        waveformRepository = mockk(relaxed = true)
        saveRecordingUseCase = mockk(relaxed = true)
        failureNotifier = mockk(relaxed = true)
        // One coordinator for every view model a test builds: it is a process-wide singleton, and
        // a relaunched screen sharing it with the one that left is the case this file is about.
        saveCoordinator = SaveCoordinator(
            context,
            saveRecordingUseCase,
            failureNotifier,
            CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
            UnconfinedTestDispatcher()
        )
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
        unmockkStatic(DocumentsContract::class)
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): MainViewModel = MainViewModel(
        context,
        segmentManager,
        waveformRepository,
        saveCoordinator,
        appPreferences,
        cloudSyncManager,
        savedRecordingUploader,
        billingRepository,
        subscriptionGate
    )

    /** A screen the test can leave for good: clearing the store is what runs `onCleared`. */
    private fun screen(store: ViewModelStore): MainViewModel =
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = buildViewModel() as T
            }
        )[MainViewModel::class.java]

    /** What `MainScreen` does while STARTED; cancelling the job is the screen going to Home. */
    private fun TestScope.startCollecting(viewModel: MainViewModel): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.collectSaveOutcomes()
        }

    private fun TestScope.openScreen(): MainViewModel =
        buildViewModel().also { startCollecting(it) }

    private fun givenSaveablePickerFlow() {
        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns listOf(mockk<SegmentEntity>(relaxed = true))
        coEvery { subscriptionGate.checkSaveAccess(any(), any()) } returns GateResult.Allowed
    }

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
        assertNotNull("a refused preflight must give the slot back", saveCoordinator.tryClaim())
    }

    @Test
    fun `requestSave with segments in range proceeds through subscription gate`() = runTest {
        givenSaveablePickerFlow()

        val viewModel = buildViewModel()
        viewModel.requestSave()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.launchSavePicker)
        coVerify(exactly = 1) { subscriptionGate.checkSaveAccess(any(), any()) }
    }

    /**
     * The claim used to be taken only once the picker answered, so two taps both passed the guard,
     * both ran the preflight and each raised the picker - two `CreateDocument` sheets back to back.
     */
    @Test
    fun `a double tap on Save opens one picker (issue 111)`() = runTest {
        val gate = CompletableDeferred<GateResult>()
        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns listOf(mockk<SegmentEntity>(relaxed = true))
        coEvery { subscriptionGate.checkSaveAccess(any(), any()) } coAnswers { gate.await() }

        val viewModel = buildViewModel()
        viewModel.requestSave()
        viewModel.requestSave()
        gate.complete(GateResult.Allowed)
        advanceUntilIdle()

        coVerify(exactly = 1) { segmentManager.getSegmentsInRange(any(), any()) }
        coVerify(exactly = 1) { subscriptionGate.checkSaveAccess(any(), any()) }
    }

    /**
     * The defect the issue is named for. The save outlives its screen; a relaunched screen used to
     * read a fresh `saveInProgress = false` and start a second save over the first.
     */
    @Test
    fun `a relaunched screen will not start a save while the last screen's save runs (issue 111)`() =
        runTest {
            val firstSave = CompletableDeferred<SaveResult>()
            coEvery { saveRecordingUseCase.execute(any(), any(), any()) } coAnswers { firstSave.await() }
            givenSaveablePickerFlow()

            val firstStore = ViewModelStore()
            screen(firstStore).saveRecording(mockk())
            advanceUntilIdle()
            firstStore.clear()

            val relaunched = buildViewModel()
            advanceUntilIdle()
            assertTrue("the relaunched screen must render the running save", relaunched.uiState.value.saveInProgress)

            relaunched.requestSave()
            advanceUntilIdle()
            assertFalse(relaunched.uiState.value.launchSavePicker)
            coVerify(exactly = 0) { subscriptionGate.checkSaveAccess(any(), any()) }

            firstSave.complete(SaveResult.Success)
            advanceUntilIdle()
            coVerify(exactly = 1) { saveRecordingUseCase.execute(any(), any(), any()) }
            assertFalse(relaunched.uiState.value.saveInProgress)
        }

    /** The outcome used to land in the departed view model's state, which nothing collected. */
    @Test
    fun `a failure after the screen is gone is notified and shown on the next screen (issue 111)`() =
        runTest {
            val uri = mockk<Uri>()
            val save = CompletableDeferred<SaveResult>()
            coEvery { saveRecordingUseCase.execute(any(), any(), any()) } coAnswers { save.await() }

            val firstStore = ViewModelStore()
            val first = screen(firstStore)
            val collecting = startCollecting(first)
            first.saveRecording(uri)
            advanceUntilIdle()
            collecting.cancel()
            firstStore.clear()

            save.complete(SaveResult.Error("no data"))
            advanceUntilIdle()

            verify(exactly = 1) { failureNotifier.notifySaveFailed() }
            verify(exactly = 1) { DocumentsContract.deleteDocument(contentResolver, uri) }

            val next = openScreen()
            advanceUntilIdle()
            assertEquals("no data", next.uiState.value.errorMessage)
        }

    /**
     * Home keeps the view model alive, so a collector tied to its scope counted as "watching" and
     * a failure there was never notified - the commonest way of leaving mid-save.
     */
    @Test
    fun `a failure while the app is in the background is notified (issue 111)`() = runTest {
        val save = CompletableDeferred<SaveResult>()
        coEvery { saveRecordingUseCase.execute(any(), any(), any()) } coAnswers { save.await() }

        val viewModel = buildViewModel()
        val collecting = startCollecting(viewModel)
        viewModel.saveRecording(mockk())
        advanceUntilIdle()
        collecting.cancel()

        save.complete(SaveResult.Error("no data"))
        advanceUntilIdle()
        verify(exactly = 1) { failureNotifier.notifySaveFailed() }

        startCollecting(viewModel)
        advanceUntilIdle()
        assertEquals("no data", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `a success nobody watched is never notified and is shown on the next screen`() = runTest {
        val save = CompletableDeferred<SaveResult>()
        coEvery { saveRecordingUseCase.execute(any(), any(), any()) } coAnswers { save.await() }

        buildViewModel().saveRecording(mockk())
        save.complete(SaveResult.Success)
        advanceUntilIdle()

        verify(exactly = 0) { failureNotifier.notifySaveFailed() }
        assertEquals("Recording saved successfully", openScreen().uiState.value.successMessage)
    }

    @Test
    fun `a notification that cannot be posted still leaves the failure for the next screen`() =
        runTest {
            every { failureNotifier.notifySaveFailed() } throws RuntimeException("no channel")
            coEvery { saveRecordingUseCase.execute(any(), any(), any()) } returns SaveResult.Error("no data")

            buildViewModel().saveRecording(mockk())
            advanceUntilIdle()

            assertEquals("no data", openScreen().uiState.value.errorMessage)
            assertNotNull(saveCoordinator.tryClaim())
        }

    @Test
    fun `a failure while the screen is open is shown there and not notified`() = runTest {
        coEvery { saveRecordingUseCase.execute(any(), any(), any()) } returns SaveResult.Error("no data")

        val viewModel = openScreen()
        viewModel.saveRecording(mockk())
        advanceUntilIdle()

        assertEquals("no data", viewModel.uiState.value.errorMessage)
        verify(exactly = 0) { failureNotifier.notifySaveFailed() }
    }

    @Test
    fun `cancelling the picker frees Save for the next tap (issue 111)`() = runTest {
        givenSaveablePickerFlow()

        val viewModel = buildViewModel()
        viewModel.requestSave()
        advanceUntilIdle()
        viewModel.onSavePickerLaunched()
        viewModel.onSavePickerCancelled()

        viewModel.requestSave()
        advanceUntilIdle()
        coVerify(exactly = 2) { subscriptionGate.checkSaveAccess(any(), any()) }
    }

    /** A claim held for a picker that will never answer this instance must not outlive it. */
    @Test
    fun `leaving for good with a picker unanswered frees Save (issue 111)`() = runTest {
        givenSaveablePickerFlow()

        val store = ViewModelStore()
        screen(store).requestSave()
        advanceUntilIdle()
        store.clear()

        assertNotNull(saveCoordinator.tryClaim())
    }

    /**
     * Two concurrent saves each force a flush rotation of the live recording and race over the
     * cache temp files, so a picker result arriving while a save runs is refused.
     */
    @Test
    fun `refuses a second save while one is still in flight (issue 93)`() = runTest {
        val uri = mockk<Uri>()
        val firstSave = CompletableDeferred<SaveResult>()
        coEvery { saveRecordingUseCase.execute(any(), any(), any()) } coAnswers { firstSave.await() }

        val viewModel = openScreen()
        viewModel.saveRecording(uri)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.saveInProgress)

        viewModel.saveRecording(uri)
        advanceUntilIdle()

        firstSave.complete(SaveResult.Success)
        advanceUntilIdle()

        coVerify(exactly = 1) { saveRecordingUseCase.execute(any(), any(), any()) }
        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
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
     * or DataStore failure escapes `execute`. A slot left held by that would make Save a
     * permanently disabled spinner for the life of the process.
     */
    @Test
    fun `clears the in-flight flag and cleans up when the save throws (issue 93)`() = runTest {
        val uri = mockk<Uri>()
        coEvery { saveRecordingUseCase.execute(any(), any(), any()) } throws IOException("boom")

        val viewModel = openScreen()
        viewModel.saveRecording(uri)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.saveInProgress)
        assertEquals("Could not save the recording", viewModel.uiState.value.errorMessage)
        verify(exactly = 1) { DocumentsContract.deleteDocument(contentResolver, uri) }
        assertNotNull(saveCoordinator.tryClaim())
    }

    /** A refusal that arrives after the picker ran must not leave its 0-byte file behind. */
    @Test
    fun `a save refused while one is in flight deletes the file its picker created`() = runTest {
        val firstUri = mockk<Uri>()
        val strayUri = mockk<Uri>()
        val firstSave = CompletableDeferred<SaveResult>()
        coEvery { saveRecordingUseCase.execute(any(), any(), any()) } coAnswers { firstSave.await() }

        val viewModel = buildViewModel()
        viewModel.saveRecording(firstUri)
        advanceUntilIdle()

        viewModel.saveRecording(strayUri)
        advanceUntilIdle()

        verify(exactly = 1) { DocumentsContract.deleteDocument(contentResolver, strayUri) }
        verify(exactly = 0) { DocumentsContract.deleteDocument(contentResolver, firstUri) }

        firstSave.complete(SaveResult.Success)
        advanceUntilIdle()
    }

    @Test
    fun `saveRecording error path deletes empty output uri (issue 23)`() = runTest {
        val uri = mockk<Uri>()
        coEvery { saveRecordingUseCase.execute(any(), any(), any()) } returns
            SaveResult.Error("no data")

        val viewModel = openScreen()
        viewModel.saveRecording(uri)
        advanceUntilIdle()

        verify(exactly = 1) { DocumentsContract.deleteDocument(contentResolver, uri) }
        assertEquals("no data", viewModel.uiState.value.errorMessage)
        assertFalse("a stuck flag leaves Save dead", viewModel.uiState.value.saveInProgress)
    }

    @Test
    fun `saveRecording upgrade-required path also deletes empty output uri`() = runTest {
        val uri = mockk<Uri>()
        coEvery { saveRecordingUseCase.execute(any(), any(), any()) } returns
            SaveResult.UpgradeRequired(BlockReason.SAVE_RANGE_EXCEEDS_FREE_LIMIT)

        val viewModel = openScreen()
        viewModel.saveRecording(uri)
        advanceUntilIdle()

        verify(exactly = 1) { DocumentsContract.deleteDocument(contentResolver, uri) }
        assertTrue(viewModel.uiState.value.showUpgradeDialog)
    }

    @Test
    fun `saveRecording success path does not delete output uri`() = runTest {
        coEvery { saveRecordingUseCase.execute(any(), any(), any()) } returns SaveResult.Success

        val viewModel = buildViewModel()
        viewModel.saveRecording(mockk())
        advanceUntilIdle()

        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
    }
}
