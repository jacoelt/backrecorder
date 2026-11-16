package com.backrecorderapp.domain

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.billing.SubscriptionStatus
import com.backrecorderapp.data.cloud.SavedRecordingUploader
import com.backrecorderapp.data.db.SegmentEntity
import com.backrecorderapp.data.segment.SegmentManager
import com.backrecorderapp.service.FlushAbandonedException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

class SaveRecordingUseCaseTest {

    private companion object {
        const val IO_THREAD_NAME = "save-recording-io-test"
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var segmentManager: SegmentManager
    private lateinit var trimUseCase: TrimSegmentUseCase
    private lateinit var billingRepository: BillingRepository
    private lateinit var subscriptionGate: SubscriptionGate
    private lateinit var recordingFlusher: RecordingFlusher
    private lateinit var savedRecordingUploader: SavedRecordingUploader
    private lateinit var saveRecordingUseCase: SaveRecordingUseCase

    /**
     * The flush timeout's `delay` is now intercepted by the use case's own dispatcher rather than
     * by the one `runTest` installs, so the two must share a scheduler or the timeout leaves
     * virtual time and waits ten wall-clock seconds. Bare `runTest` adopts the scheduler of a
     * `TestDispatcher` set as `Dispatchers.Main`, which is what makes that sharing possible here.
     */
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0

        context = mockk(relaxed = true)
        segmentManager = mockk(relaxed = true)
        trimUseCase = mockk(relaxed = true)
        billingRepository = mockk()
        every { billingRepository.statusResolved } returns MutableStateFlow(true)
        subscriptionGate = testSubscriptionGate(billingRepository)
        recordingFlusher = mockk(relaxed = true)

        contentResolver = mockk(relaxed = true)
        every { context.contentResolver } returns contentResolver

        savedRecordingUploader = mockk(relaxed = true)

        saveRecordingUseCase = SaveRecordingUseCase(
            context,
            segmentManager,
            trimUseCase,
            subscriptionGate,
            recordingFlusher,
            savedRecordingUploader,
            StandardTestDispatcher(scheduler)
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    /**
     * Issue #93: the blocking work must leave the caller's thread. Asserted by thread name rather
     * than by the dispatcher the use case was handed, since only the thread shows where the work
     * actually ran.
     */
    @Test
    fun `runs the blocking save work off the caller's thread (issue 93)`() = runTest {
        val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, IO_THREAD_NAME)
        }
        try {
            coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE
            every { context.cacheDir } returns temporaryFolder.root
            coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns
                listOf(completeSegment())
            every { contentResolver.query(any(), any(), any(), any(), any()) } returns null

            val workThreads = mutableListOf<String>()
            every { trimUseCase.trim(any(), any(), any(), any()) } answers {
                workThreads += Thread.currentThread().name
                secondArg<File>().writeBytes(ByteArray(8))
            }
            every { contentResolver.openOutputStream(any()) } answers {
                workThreads += Thread.currentThread().name
                ByteArrayOutputStream()
            }

            val useCase = SaveRecordingUseCase(
                context,
                segmentManager,
                trimUseCase,
                subscriptionGate,
                recordingFlusher,
                savedRecordingUploader,
                ioExecutor.asCoroutineDispatcher()
            )

            val now = System.currentTimeMillis()
            val result = useCase.execute(now - (5 * 60 * 1000), now, mockk())

            assertTrue(result is SaveResult.Success)
            assertEquals(listOf(IO_THREAD_NAME, IO_THREAD_NAME), workThreads)
        } finally {
            ioExecutor.shutdown()
        }
    }

    @Test
    fun `returns upgrade required when free user tries to save over 15 min`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.FREE

        val now = System.currentTimeMillis()
        val result = saveRecordingUseCase.execute(
            startTime = now - (20 * 60 * 1000), // 20 min duration
            endTime = now,
            outputUri = mockk()
        )

        assertTrue(result is SaveResult.UpgradeRequired)
        assertTrue((result as SaveResult.UpgradeRequired).reason == BlockReason.SAVE_RANGE_EXCEEDS_FREE_LIMIT)
    }

    @Test
    fun `returns error when no segments found`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE
        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns emptyList()

        val now = System.currentTimeMillis()
        val result = saveRecordingUseCase.execute(
            startTime = now - (5 * 60 * 1000),
            endTime = now,
            outputUri = mockk()
        )

        assertTrue(result is SaveResult.Error)
    }

    @Test
    fun `premium user can save old recordings`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE

        val now = System.currentTimeMillis()
        val segment = SegmentEntity(
            id = "1",
            filePath = "/nonexistent/file.m4a",
            waveformPath = "/test/waveform.bin",
            startTime = now - (25 * 60 * 1000),
            endTime = now - (24 * 60 * 1000),
            durationMs = 60000,
            isComplete = true
        )

        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns listOf(segment)

        val outputUri = mockk<Uri>()
        val result = saveRecordingUseCase.execute(
            startTime = now - (25 * 60 * 1000),
            endTime = now - (24 * 60 * 1000),
            outputUri = outputUri
        )

        // Result will be Error because file doesn't exist, but NOT UpgradeRequired
        assertTrue(result !is SaveResult.UpgradeRequired)
    }

    /**
     * The wiring behind issue #84. Asserted at this level because it is the only place that knows
     * the save succeeded *and* still holds the finished file - the uploader can only be handed
     * something that exists, and a save that reported success without offering it would leave the
     * Drive copy quietly unimplemented.
     */
    @Test
    fun `hands the finished recording to the Drive upload under the name the user chose`() =
        runTest {
            coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE
            every { context.cacheDir } returns temporaryFolder.root
            every { trimUseCase.trim(any(), any(), any(), any()) } answers {
                secondArg<File>().writeBytes(ByteArray(8))
            }
            every { contentResolver.query(any(), any(), any(), any(), any()) } returns null

            val now = System.currentTimeMillis()
            val segment = SegmentEntity(
                id = "1",
                filePath = "/nonexistent/file.m4a",
                waveformPath = "/test/waveform.bin",
                startTime = now - (5 * 60 * 1000),
                endTime = now,
                durationMs = 300_000,
                isComplete = true
            )
            coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns listOf(segment)

            val result = saveRecordingUseCase.execute(
                startTime = now - (5 * 60 * 1000),
                endTime = now,
                outputUri = mockk()
            )

            assertTrue(result is SaveResult.Success)
            verify(exactly = 1) { savedRecordingUploader.enqueue(any(), any()) }
        }

    /**
     * A provider that refuses the write used to be silent: the save reported Success over the
     * zero-byte file the picker had just created, and - once the Drive copy existed - shipped the
     * audio that was missing from it. It must fail instead, and offer nothing to the uploader.
     */
    @Test
    fun `fails the save when the chosen location cannot be opened for writing`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE
        every { context.cacheDir } returns temporaryFolder.root
        every { trimUseCase.trim(any(), any(), any(), any()) } answers {
            secondArg<File>().writeBytes(ByteArray(8))
        }
        every { contentResolver.openOutputStream(any()) } returns null
        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns listOf(completeSegment())

        val now = System.currentTimeMillis()
        val result = saveRecordingUseCase.execute(now - (5 * 60 * 1000), now, mockk())

        assertTrue(result is SaveResult.Error)
        verify(exactly = 0) { savedRecordingUploader.enqueue(any(), any()) }
    }

    @Test
    fun `uploads under the name the picker reports`() = runTest {
        val name = captureUploadName(pickerName = "Team sync.m4a")
        assertEquals("Team sync.m4a", name)
    }

    @Test
    fun `strips characters a provider should not have put in a name`() = runTest {
        val name = captureUploadName(pickerName = "Hello\u0000\nthere/evil.m4a")
        assertEquals("Hellothereevil.m4a", name)
    }

    @Test
    fun `shortens a very long name but keeps its extension`() = runTest {
        val name = captureUploadName(pickerName = "a".repeat(300) + ".m4a")
        assertTrue("the name must be bounded", name.length <= 100)
        assertTrue("a Drive file with no extension is not recognised as audio", name.endsWith(".m4a"))
    }

    @Test
    fun `shortens a long name whose extension is too long to keep`() = runTest {
        val name = captureUploadName(pickerName = "b".repeat(300) + ".superlongextension")
        assertTrue("the name must be bounded", name.length <= 100)
    }

    @Test
    fun `shortens a long name that ends in a bare dot`() = runTest {
        val name = captureUploadName(pickerName = "c".repeat(300) + ".")
        assertTrue("the name must be bounded", name.length <= 100)
        assertTrue("got: $name", name.isNotBlank())
    }

    @Test
    fun `falls back to a generated name when nothing usable survives`() = runTest {
        val name = captureUploadName(pickerName = "///")
        assertTrue("got: $name", name.startsWith("recording_") && name.endsWith(".m4a"))
    }

    @Test
    fun `falls back to a generated name when the provider throws`() = runTest {
        val name = captureUploadName(pickerName = null, queryThrows = true)
        assertTrue("got: $name", name.startsWith("recording_"))
    }

    private fun completeSegment() = SegmentEntity(
        id = "1",
        filePath = "/nonexistent/file.m4a",
        waveformPath = "/test/waveform.bin",
        startTime = System.currentTimeMillis() - (5 * 60 * 1000),
        endTime = System.currentTimeMillis(),
        durationMs = 300_000,
        isComplete = true
    )

    /** Runs one save and hands back the name the Drive upload was actually offered. */
    private suspend fun captureUploadName(
        pickerName: String?,
        queryThrows: Boolean = false
    ): String {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE
        every { context.cacheDir } returns temporaryFolder.root
        every { trimUseCase.trim(any(), any(), any(), any()) } answers {
            secondArg<File>().writeBytes(ByteArray(8))
        }
        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns listOf(completeSegment())

        if (queryThrows) {
            every { contentResolver.query(any(), any(), any(), any(), any()) } throws
                RuntimeException("provider died")
        } else {
            val cursor = mockk<android.database.Cursor>(relaxed = true)
            every { cursor.moveToFirst() } returns true
            every { cursor.getString(0) } returns pickerName
            every { contentResolver.query(any(), any(), any(), any(), any()) } returns cursor
        }

        val captured = slot<String>()
        every { savedRecordingUploader.enqueue(any(), capture(captured)) } returns Unit

        val now = System.currentTimeMillis()
        val result = saveRecordingUseCase.execute(now - (5 * 60 * 1000), now, mockk())
        assertTrue("the save itself must succeed", result is SaveResult.Success)
        return captured.captured
    }

    /** A save must never be reported as failed because the bonus Drive copy could not be queued. */
    @Test
    fun `still reports success when the Drive upload cannot be queued`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE
        every { context.cacheDir } returns temporaryFolder.root
        every { trimUseCase.trim(any(), any(), any(), any()) } answers {
            secondArg<File>().writeBytes(ByteArray(8))
        }
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns null
        every { savedRecordingUploader.enqueue(any(), any()) } throws RuntimeException("no room")

        val now = System.currentTimeMillis()
        val segment = SegmentEntity(
            id = "1",
            filePath = "/nonexistent/file.m4a",
            waveformPath = "/test/waveform.bin",
            startTime = now - (5 * 60 * 1000),
            endTime = now,
            durationMs = 300_000,
            isComplete = true
        )
        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns listOf(segment)

        val result = saveRecordingUseCase.execute(
            startTime = now - (5 * 60 * 1000),
            endTime = now,
            outputUri = mockk()
        )

        assertTrue(result is SaveResult.Success)
    }

    @Test
    fun `incomplete segment is accepted for save (issue 7)`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE

        val now = System.currentTimeMillis()
        val incompleteSegment = SegmentEntity(
            id = "in-progress",
            filePath = "/nonexistent/file.m4a",
            waveformPath = "/test/waveform.bin",
            startTime = now - 30_000, // started 30s ago
            endTime = 0,             // still recording
            durationMs = 0,
            isComplete = false
        )
        val finalizedSegment = incompleteSegment.copy(
            endTime = now,
            durationMs = 30_000,
            isComplete = true
        )

        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returnsMany listOf(
            listOf(incompleteSegment),
            listOf(finalizedSegment)
        )

        val result = saveRecordingUseCase.execute(
            startTime = now - 20_000,
            endTime = now,
            outputUri = mockk()
        )

        // Should attempt the save (Error from missing file), not reject it
        assertTrue(result !is SaveResult.UpgradeRequired)
        // The key point: it didn't return "no segments found"
        assertTrue(result !is SaveResult.Error || (result as SaveResult.Error).message != "No recorded data found for the selected time range")
    }

    @Test
    fun `short recording under 1 minute is accepted for save (issue 5)`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE

        val now = System.currentTimeMillis()
        val shortSegment = SegmentEntity(
            id = "short",
            filePath = "/nonexistent/file.m4a",
            waveformPath = "/test/waveform.bin",
            startTime = now - 15_000, // 15 seconds of recording
            endTime = 0,
            durationMs = 0,
            isComplete = false
        )
        val finalized = shortSegment.copy(endTime = now, durationMs = 15_000, isComplete = true)

        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returnsMany listOf(
            listOf(shortSegment),
            listOf(finalized)
        )

        val result = saveRecordingUseCase.execute(
            startTime = now - 10_000,
            endTime = now,
            outputUri = mockk()
        )

        // Should not reject with "no segments found"
        assertTrue(result !is SaveResult.Error || (result as SaveResult.Error).message != "No recorded data found for the selected time range")
    }

    @Test
    fun `flushes in-progress segment before saving (issue 18)`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE

        val now = System.currentTimeMillis()
        val incomplete = SegmentEntity(
            id = "in-progress",
            filePath = "/nonexistent/file.m4a",
            waveformPath = "/test/waveform.bin",
            startTime = now - 30_000,
            endTime = 0,
            durationMs = 0,
            isComplete = false
        )
        val finalized = incomplete.copy(
            endTime = now,
            durationMs = 30_000,
            isComplete = true
        )

        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returnsMany listOf(
            listOf(incomplete),
            listOf(finalized)
        )

        saveRecordingUseCase.execute(
            startTime = now - 20_000,
            endTime = now,
            outputUri = mockk()
        )

        coVerify(exactly = 1) { recordingFlusher.flushCurrentSegment() }
    }

    @Test
    fun `does not flush when all segments are already complete`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE

        val now = System.currentTimeMillis()
        val complete = SegmentEntity(
            id = "done",
            filePath = "/nonexistent/file.m4a",
            waveformPath = "/test/waveform.bin",
            startTime = now - 120_000,
            endTime = now - 60_000,
            durationMs = 60_000,
            isComplete = true
        )

        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns listOf(complete)

        saveRecordingUseCase.execute(
            startTime = now - 120_000,
            endTime = now - 60_000,
            outputUri = mockk()
        )

        coVerify(exactly = 0) { recordingFlusher.flushCurrentSegment() }
    }

    @Test
    fun `returns error when flush fails`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE
        coEvery { recordingFlusher.flushCurrentSegment() } throws RuntimeException("boom")

        val now = System.currentTimeMillis()
        val incomplete = SegmentEntity(
            id = "in-progress",
            filePath = "/nonexistent/file.m4a",
            waveformPath = "/test/waveform.bin",
            startTime = now - 30_000,
            endTime = 0,
            durationMs = 0,
            isComplete = false
        )
        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns listOf(incomplete)

        val result = saveRecordingUseCase.execute(
            startTime = now - 20_000,
            endTime = now,
            outputUri = mockk()
        )

        assertTrue(result is SaveResult.Error)
        assertEquals(
            "Failed to finalize in-progress recording: boom",
            (result as SaveResult.Error).message
        )
    }

    /**
     * Issue #47: before the timeout, a flush request the service never answered left
     * this coroutine suspended forever and the save UI spinning with no error. The save
     * must now fail visibly so the user can retry.
     */
    @Test
    fun `returns error when the flush never completes (issue 47)`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE
        coEvery { recordingFlusher.flushCurrentSegment() } coAnswers { awaitCancellation() }

        val now = System.currentTimeMillis()
        val incomplete = SegmentEntity(
            id = "in-progress",
            filePath = "/nonexistent/file.m4a",
            waveformPath = "/test/waveform.bin",
            startTime = now - 30_000,
            endTime = 0,
            durationMs = 0,
            isComplete = false
        )
        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns listOf(incomplete)

        val result = saveRecordingUseCase.execute(
            startTime = now - 20_000,
            endTime = now,
            outputUri = mockk()
        )

        assertTrue(result is SaveResult.Error)
        assertTrue(
            "the error should name the timeout, not look like a generic failure",
            (result as SaveResult.Error).message.contains("Timed out")
        )
    }

    /**
     * Issue #47: the service being torn down mid-save fails the flush with
     * [FlushAbandonedException], which must read as an ordinary flush failure — not be
     * confused with the timeout branch above.
     */
    @Test
    fun `returns error when the service is torn down mid-flush (issue 47)`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE
        coEvery { recordingFlusher.flushCurrentSegment() } throws FlushAbandonedException()

        val now = System.currentTimeMillis()
        val incomplete = SegmentEntity(
            id = "in-progress",
            filePath = "/nonexistent/file.m4a",
            waveformPath = "/test/waveform.bin",
            startTime = now - 30_000,
            endTime = 0,
            durationMs = 0,
            isComplete = false
        )
        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns listOf(incomplete)

        val result = saveRecordingUseCase.execute(
            startTime = now - 20_000,
            endTime = now,
            outputUri = mockk()
        )

        assertTrue(result is SaveResult.Error)
        assertTrue(
            (result as SaveResult.Error).message.startsWith("Failed to finalize in-progress recording")
        )
    }

    /**
     * Issue #47: the save screen going away cancels this coroutine. That must propagate,
     * not be reported as a failed save — the flush block's timeout handling must not
     * swallow a cancellation that is not its own.
     */
    @Test
    fun `rethrows cancellation of the caller rather than reporting an error (issue 47)`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE
        coEvery { recordingFlusher.flushCurrentSegment() } throws CancellationException("scope cleared")

        val now = System.currentTimeMillis()
        val incomplete = SegmentEntity(
            id = "in-progress",
            filePath = "/nonexistent/file.m4a",
            waveformPath = "/test/waveform.bin",
            startTime = now - 30_000,
            endTime = 0,
            durationMs = 0,
            isComplete = false
        )
        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns listOf(incomplete)

        var thrown: Throwable? = null
        try {
            saveRecordingUseCase.execute(
                startTime = now - 20_000,
                endTime = now,
                outputUri = mockk()
            )
            fail("execute should have rethrown the cancellation")
        } catch (e: CancellationException) {
            thrown = e
        }

        assertEquals("scope cleared", thrown?.message)
    }
}
