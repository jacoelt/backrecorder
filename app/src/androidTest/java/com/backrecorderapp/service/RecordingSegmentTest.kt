package com.backrecorderapp.service

import android.Manifest
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.backrecorderapp.MainActivity
import com.backrecorderapp.data.db.SegmentDao
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.service.recorder.FakeAudioRecorderFactory
import com.backrecorderapp.testing.PreferenceSnapshot
import com.backrecorderapp.testing.STATE_TIMEOUT_MS
import com.backrecorderapp.testing.appContext
import com.backrecorderapp.testing.awaitRecordersSettled
import com.backrecorderapp.testing.awaitRecordingService
import com.backrecorderapp.testing.cleanUpRule
import com.backrecorderapp.testing.clearRecordedState
import com.backrecorderapp.testing.createNotificationChannels
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * Proves the recorder seam: recording runs end to end on [FakeAudioRecorderFactory], the flush
 * handshake finalizes the in-progress segment, and what lands on disk is an MP4 that
 * `MediaExtractor` can read — all without a microphone, which a CI emulator does not have.
 *
 * Assertions deliberately cover the service's own output (the `segments` rows it wrote) as well as
 * the files, so a service that rotated but forgot to record the segment fails here.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RecordingSegmentTest {

    @Inject lateinit var recorderFactory: FakeAudioRecorderFactory
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var segmentDao: SegmentDao

    private var scenario: ActivityScenario<MainActivity>? = null
    private val preferences = PreferenceSnapshot()

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /**
     * `startForeground` with `FOREGROUND_SERVICE_TYPE_MICROPHONE` needs `RECORD_AUDIO` even when
     * nothing opens the microphone — the permission gates the service type, not the hardware.
     */
    @get:Rule(order = 1)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    /**
     * Injects, and cleans up from outside the seeding rule so a seeding failure still tidies up.
     * Segments, rows and preferences survive the process, and later flow tests share this
     * database, this cache directory and this DataStore.
     */
    @get:Rule(order = 2)
    val cleanUpAfterwards = cleanUpRule(hiltRule) {
        try {
            runBlocking {
                RecordingService.stop(appContext)
                awaitRecordingService(running = false)
                // `onDestroy` clears `isRunning` first and finalizes afterwards, so wait for the
                // recorders to settle too — otherwise the cleanup below races a segment the
                // service is still writing and leaves its row behind for the next test.
                awaitRecordersSettled(recorderFactory)
            }
        } finally {
            scenario?.close()
            scenario = null
            recorderFactory.reset()
            clearRecordedState(segmentDao)
            runBlocking { preferences.restoreAll() }
        }
    }

    @get:Rule(order = 3)
    val injectAndSeed = object : ExternalResource() {
        override fun before() {
            recorderFactory.reset()
            clearRecordedState(segmentDao)
            createNotificationChannels()
            // Keep the activity on the disclaimer screen: this test only needs the app in the
            // foreground, and the main screen's own work would be noise here. DataStore outlives
            // the test, so the previous value is captured for the cleanup rule to put back.
            runBlocking {
                preferences.capture(
                    appPreferences.disclaimerAccepted,
                    appPreferences::setDisclaimerAccepted
                )
                appPreferences.setDisclaimerAccepted(false)
            }
        }
    }

    @Test
    fun flushFinalizesTheInProgressSegmentAsAReadableMp4() = runBlocking {
        // Android forbids starting a microphone foreground service from the background, so put the
        // app in the foreground the way a user would before asking for a recording.
        scenario = ActivityScenario.launch(MainActivity::class.java)

        RecordingService.start(appContext)
        awaitRecordingService(running = true)

        val firstRecorder = recorderFactory.awaitRecorder(index = 0)
        firstRecorder.feed(FED_DURATION_MS)

        // Nothing is waited for before the flush: filenames carry the segment's id, so a rotation
        // inside the second it started cannot reuse its path (#46), and a request made before the
        // flush collector is ready is queued rather than dropped (#47).
        withTimeout("flushCurrentSegment() never completed") {
            RecordingService.flushCurrentSegment()
        }

        val flushedFile = recorderFactory.awaitFinalizedFiles(count = 1).single()
        // The segment must hold roughly what was fed. Without this the whole test would still pass
        // if feed() did nothing, because stop() writes a short fallback segment regardless.
        val flushedLastSampleUs = assertReadableAudio(flushedFile)
        assertTrue(
            "Flushed segment holds only ${flushedLastSampleUs}us, not the ${FED_DURATION_MS}ms fed",
            flushedLastSampleUs >= MIN_FED_SAMPLE_US
        )

        // The service's own record of the segment, not just the fake's bookkeeping.
        val rows = segmentDao.getAllSegments().first()
        assertEquals("Expected the flushed segment plus its replacement", 2, rows.size)
        val flushedRow = rows.first()
        assertTrue("Flushed segment row is not marked complete", flushedRow.isComplete)
        assertEquals(flushedFile.absolutePath, flushedRow.filePath)
        assertTrue("Flushed segment has no duration", flushedRow.durationMs > 0)
        assertTrue("Replacement segment row should still be open", !rows[1].isComplete)

        // Flushing rotates rather than stops: the service keeps recording into a fresh segment and
        // the UI, which observes this StateFlow directly, must still see a running recording.
        assertTrue("Service stopped running after a flush", RecordingService.isRunning.value)

        val replacementFilePath = recorderFactory.awaitRecorder(index = 1).outputFile?.absolutePath
        assertTrue("Replacement segment never opened a file", replacementFilePath != null)
        assertTrue(
            "Replacement segment reused the finalized segment's file",
            replacementFilePath != flushedFile.absolutePath
        )

        // Stopping finalizes the replacement segment, which the test never fed. That path is what
        // every later flow test hits on teardown, so prove it also yields a readable MP4.
        RecordingService.stop(appContext)
        awaitRecordingService(running = false)
        val replacementFile = recorderFactory.awaitFinalizedFiles(count = 2)[1]
        val replacementLastSampleUs = assertReadableAudio(replacementFile)
        assertTrue(
            "Never-fed segment should be short, not ${replacementLastSampleUs}us",
            replacementLastSampleUs < MIN_FED_SAMPLE_US
        )
    }

    /**
     * Issue #47: a flush asked for in the gap between the service reporting itself running and its
     * flush collector being ready. Nothing is fed on purpose — the point is to hit that gap.
     *
     * A smoke test, not the regression guard: the gap is milliseconds wide and this cannot force
     * the race. `FlushCoordinatorTest` on the JVM is what actually pins the behaviour.
     */
    @Test
    fun flushRequestedBeforeTheCollectorIsReadyStillCompletes() = runBlocking {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        RecordingService.start(context)
        awaitRunning(expected = true)

        withTimeout("flushCurrentSegment() never completed — the request was dropped (#47)") {
            RecordingService.flushCurrentSegment()
        }

        // The flush rotated rather than stopped: one finalized segment, one still open.
        // Assert on the partition, not on row order — nothing was fed, so both rows can
        // share a startTime and getAllSegments only orders by that.
        recorderFactory.awaitFinalizedFiles(count = 1)
        assertTrue("Service stopped running after a flush", RecordingService.isRunning.value)
        val rows = segmentDao.getAllSegments().first()
        assertEquals("Expected the flushed segment plus its replacement", 2, rows.size)
        assertEquals("Expected exactly one finalized segment", 1, rows.count { it.isComplete })

        RecordingService.stop(context)
        awaitRunning(expected = false)
    }

    /**
     * A second recording session in the same process, which is the first thing every later flow
     * test does. Covers the never-fed segment path on its own and proves the harness's static
     * state — `isRunning`, the flush flow, the `@Singleton` factory — survives a teardown and
     * [FakeAudioRecorderFactory.reset] intact.
     */
    @Test
    fun aRecordingSessionCanStartAndStopWithoutBeingFed() = runBlocking {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        RecordingService.start(appContext)
        awaitRecordingService(running = true)
        recorderFactory.awaitRecorder(index = 0)

        RecordingService.stop(appContext)
        awaitRecordingService(running = false)

        val file = recorderFactory.awaitFinalizedFiles(count = 1).single()
        assertReadableAudio(file)
        assertEquals("Expected exactly one segment row", 1, segmentDao.getAllSegments().first().size)
    }

    /**
     * Asserts [file] is an MP4 `MediaExtractor` can read, and returns the presentation time of its
     * last audio sample — which is how callers tell a segment that was fed from one that was not.
     */
    private fun assertReadableAudio(file: File): Long {
        assertTrue("Segment file ${file.name} does not exist", file.exists())
        assertTrue("Segment file ${file.name} is empty", file.length() > 0)

        val extractor = MediaExtractor()
        try {
            // Throws if the MOOV atom is missing, which is exactly the failure this fake exists to
            // rule out of the flow tests.
            extractor.setDataSource(file.absolutePath)
            assertEquals("Expected one audio track", 1, extractor.trackCount)

            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            assertTrue("Track is not audio but '$mime'", mime.startsWith("audio/"))

            extractor.selectTrack(0)
            val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
            var samples = 0
            var lastSampleUs = 0L
            while (extractor.readSampleData(buffer, 0) > 0) {
                samples++
                lastSampleUs = extractor.sampleTime
                extractor.advance()
            }
            assertTrue("No readable samples in ${file.name}", samples > 0)
            return lastSampleUs
        } finally {
            extractor.release()
        }
    }

    private suspend fun withTimeout(message: String, block: suspend () -> Unit) {
        val completed = withTimeoutOrNull(STATE_TIMEOUT_MS) {
            block()
            true
        }
        check(completed == true) { message }
    }

    private companion object {
        const val FED_DURATION_MS = 2_000L

        /**
         * Comfortably below [FED_DURATION_MS] but well above the fake's never-fed fallback, so the
         * bound distinguishes a fed segment from an unfed one without depending on how AAC priming
         * and padding land on a given emulator codec.
         */
        const val MIN_FED_SAMPLE_US = 1_000_000L

        const val SAMPLE_BUFFER_BYTES = 64 * 1024
    }
}
