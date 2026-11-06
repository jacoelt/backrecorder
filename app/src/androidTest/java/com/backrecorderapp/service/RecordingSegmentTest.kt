package com.backrecorderapp.service

import android.Manifest
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.backrecorderapp.MainActivity
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.notification.NotificationChannels
import com.backrecorderapp.service.recorder.FakeAudioRecorderFactory
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
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
 * This is the piece the record → select → save and Drive sync flow tests build on, so it asserts
 * the contract those flows rely on rather than just "a file appeared".
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RecordingSegmentTest {

    @Inject lateinit var recorderFactory: FakeAudioRecorderFactory
    @Inject lateinit var appPreferences: AppPreferences

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
            // HiltTestApplication stands in for BackRecorderApp, so the channels it would have
            // created do not exist; posting the foreground notification to a missing channel kills
            // the process outright.
            NotificationChannels.createAll(
                ApplicationProvider.getApplicationContext<android.content.Context>()
            )
            // Keep the activity on the disclaimer screen: this test only needs the app in the
            // foreground, and the main screen's own work would be noise here.
            runBlocking { appPreferences.setDisclaimerAccepted(false) }
        }
    }

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        RecordingService.stop(context)
        runBlocking { awaitRunning(expected = false) }
        scenario?.close()
        scenario = null
    }

    @Test
    fun flushFinalizesTheInProgressSegmentAsAReadableMp4() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // Android forbids starting a microphone foreground service from the background, so put the
        // app in the foreground the way a user would before asking for a recording.
        scenario = ActivityScenario.launch(MainActivity::class.java)

        RecordingService.start(context)
        awaitRunning(expected = true)

        // Advance the recording deterministically: two seconds of audio are encoded now, rather
        // than waited for.
        val firstRecorder = recorderFactory.awaitRecorder(index = 0)
        firstRecorder.feed(SEGMENT_DURATION_MS)

        // `SegmentManager.createSegmentFile` names files to the second, so a rotation in the same
        // wall-clock second the segment started reuses the filename and the replacement segment's
        // muxer truncates the one just finalized. Encoding is far faster than real time here, so
        // cross the boundary deliberately rather than let the flush land inside it.
        awaitNextWallClockSecond()

        flushWithRetry()

        val finalized = recorderFactory.awaitFinalizedFiles(count = 1)
        assertEquals(1, finalized.size)
        assertReadableAudio(finalized.single())

        val replacement = recorderFactory.awaitRecorder(index = 1)
        assertTrue(
            "Replacement segment reused the finalized segment's file",
            replacement.outputFile?.absolutePath != finalized.single().absolutePath
        )

        // Flushing rotates rather than stops: the service keeps recording into a fresh segment and
        // the UI, which observes this StateFlow directly, must still see a running recording.
        assertTrue("Service stopped running after a flush", RecordingService.isRunning.value)
        assertTrue(
            "Flush did not open a replacement segment",
            recorderFactory.createdCount() >= 2
        )
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

            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            assertTrue(
                "Expected at least ${MIN_EXPECTED_DURATION_US}us of audio, got ${durationUs}us",
                durationUs >= MIN_EXPECTED_DURATION_US
            )

            extractor.selectTrack(0)
            val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
            assertTrue("No readable sample in ${file.name}", extractor.readSampleData(buffer, 0) > 0)
        } finally {
            extractor.release()
        }
    }

    /**
     * `flushRequests` is a replayless `MutableSharedFlow`, so a request emitted in the instant
     * between the service starting its first segment and its collector subscribing is dropped and
     * the caller waits forever. Production reaches the flush through the UI, long after that
     * window; a test can hit it, so retry rather than hang.
     */
    private suspend fun flushWithRetry() {
        repeat(FLUSH_ATTEMPTS) { attempt ->
            val completed = withTimeoutOrNull(FLUSH_TIMEOUT_MS) {
                RecordingService.flushCurrentSegment()
                true
            }
            if (completed == true) return
            check(attempt < FLUSH_ATTEMPTS - 1) { "flushCurrentSegment() never completed" }
        }
    }

    private suspend fun awaitNextWallClockSecond() {
        val second = System.currentTimeMillis() / 1000
        while (System.currentTimeMillis() / 1000 <= second) {
            kotlinx.coroutines.delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun awaitRunning(expected: Boolean) {
        val reached = withTimeoutOrNull(STATE_TIMEOUT_MS) {
            while (RecordingService.isRunning.value != expected) {
                kotlinx.coroutines.delay(POLL_INTERVAL_MS)
            }
            true
        }
        check(reached == true) { "RecordingService.isRunning never became $expected" }
    }

    private companion object {
        const val SEGMENT_DURATION_MS = 2_000L
        const val MIN_EXPECTED_DURATION_US = 1_500_000L
        const val SAMPLE_BUFFER_BYTES = 64 * 1024
        const val FLUSH_ATTEMPTS = 5
        const val FLUSH_TIMEOUT_MS = 10_000L
        const val STATE_TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
