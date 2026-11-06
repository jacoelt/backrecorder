package com.backrecorderapp.service

import android.Manifest
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.backrecorderapp.MainActivity
import com.backrecorderapp.data.db.SegmentDao
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.notification.NotificationChannels
import com.backrecorderapp.service.recorder.FakeAudioRecorderFactory
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
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

    private val context: Context get() = ApplicationProvider.getApplicationContext()

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

    @get:Rule(order = 2)
    val injectAndSeed = object : ExternalResource() {
        override fun before() {
            hiltRule.inject()
            recorderFactory.reset()
            clearRecordedState()
            // HiltTestApplication stands in for BackRecorderApp, so the channels it would have
            // created do not exist; posting the foreground notification to a missing channel kills
            // the process.
            NotificationChannels.createAll(context)
            // Keep the activity on the disclaimer screen: this test only needs the app in the
            // foreground, and the main screen's own work would be noise here.
            runBlocking { appPreferences.setDisclaimerAccepted(false) }
        }
    }

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        try {
            RecordingService.stop(context)
            runBlocking { awaitRunning(expected = false) }
        } finally {
            scenario?.close()
            scenario = null
            recorderFactory.reset()
            // Segments and rows survive the process, and later flow tests share this database and
            // this cache directory.
            clearRecordedState()
        }
    }

    @Test
    fun flushFinalizesTheInProgressSegmentAsAReadableMp4() = runBlocking {
        // Android forbids starting a microphone foreground service from the background, so put the
        // app in the foreground the way a user would before asking for a recording.
        scenario = ActivityScenario.launch(MainActivity::class.java)

        RecordingService.start(context)
        awaitRunning(expected = true)

        val firstRecorder = recorderFactory.awaitRecorder(index = 0)
        firstRecorder.feed(FED_DURATION_MS)

        // `SegmentManager.createSegmentFile` names files to the second, so a rotation in the same
        // wall-clock second the segment started reuses the filename and the replacement segment's
        // muxer truncates the one just finalized. Encoding is far faster than real time here, so
        // cross the boundary deliberately rather than let the flush land inside it. Crossing it
        // also guarantees the service's flush collector is subscribed before the request is sent.
        awaitNextWallClockSecond()

        withTimeout("flushCurrentSegment() never completed") {
            RecordingService.flushCurrentSegment()
        }

        val flushedFile = recorderFactory.awaitFinalizedFiles(count = 1).single()
        assertReadableAudio(flushedFile)

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
        RecordingService.stop(context)
        awaitRunning(expected = false)
        val replacementFile = recorderFactory.awaitFinalizedFiles(count = 2)[1]
        assertReadableAudio(replacementFile)
    }

    private fun assertReadableAudio(file: File) {
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

            // Only a lower bound: AAC priming and padding vary by emulator codec, and a very short
            // segment may carry no duration key at all, so the sample walk below is the real proof.
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                assertTrue("Segment ${file.name} holds no audio (${durationUs}us)", durationUs > 0)
            }

            extractor.selectTrack(0)
            val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
            var samples = 0
            while (extractor.readSampleData(buffer, 0) > 0) {
                samples++
                extractor.advance()
            }
            assertTrue("No readable samples in ${file.name}", samples > 0)
        } finally {
            extractor.release()
        }
    }

    private fun clearRecordedState() = runBlocking {
        segmentDao.getAllSegments().first().forEach { segmentDao.delete(it) }
        File(context.cacheDir, "segments").deleteRecursively()
        File(context.cacheDir, "waveforms").deleteRecursively()
    }

    private suspend fun awaitNextWallClockSecond() {
        val second = System.currentTimeMillis() / 1000
        while (System.currentTimeMillis() / 1000 <= second) {
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun awaitRunning(expected: Boolean) = withTimeout(
        "RecordingService.isRunning never became $expected"
    ) {
        while (RecordingService.isRunning.value != expected) {
            delay(POLL_INTERVAL_MS)
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
        const val SAMPLE_BUFFER_BYTES = 64 * 1024
        const val STATE_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
