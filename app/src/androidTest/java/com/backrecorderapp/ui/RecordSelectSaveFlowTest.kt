package com.backrecorderapp.ui

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.rule.IntentsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.backrecorderapp.MainActivity
import com.backrecorderapp.R
import com.backrecorderapp.data.db.SegmentDao
import com.backrecorderapp.data.db.SegmentEntity
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.segment.SegmentManager
import com.backrecorderapp.data.waveform.WaveformRepository
import com.backrecorderapp.notification.NotificationChannels
import com.backrecorderapp.service.RecordingService
import com.backrecorderapp.service.recorder.FakeAudioRecorder
import com.backrecorderapp.service.recorder.FakeAudioRecorderFactory
import com.backrecorderapp.ui.components.WAVEFORM_CANVAS_TEST_TAG
import com.backrecorderapp.util.TimeUtils
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject

/**
 * The first critical flow of the automated test environment (jacoelt/backrecorder#39): record,
 * select a range on the waveform, save a clip.
 *
 * It drives the real `MainScreen` — the record button, a time-preset chip, real drags on the
 * `WaveformView` handle zones, the save FAB — and only the two things a test process cannot
 * supply are replaced: the microphone (by `FakeAudioRecorderFactory`, which still writes real
 * MPEG-4/AAC) and the system file picker (by an Espresso-Intents stub answering with a file the
 * test can read back).
 *
 * ### What it is built to catch
 *
 * - **Minute snapping on the drag handles.** The start handle floors to a minute boundary and the
 *   end handle ceils. The assertions are on the resulting times, not on "a save happened": the
 *   save filename carries `selectionStartTime` at second resolution, which pins the floored start
 *   exactly, and the "Selected: m:ss" label pins the span, so the ceiled end follows.
 * - **The end handle reaching the currently-recording minute.** Dragged to the right edge it
 *   clamps to `bufferEndTime`, which sits inside the live segment — past the last complete one.
 * - **The in-progress segment being flushed and saved.** `SaveRecordingUseCase` asks
 *   `RecordingFlusher` to finalize the open segment; the saved MP4 must then hold the live
 *   segment's audio and not merely the completed history. "The last minute is not saved" has
 *   shipped here before (#22), and only the audio length distinguishes the two.
 *
 * ### Preset chips do **not** snap
 *
 * `CLAUDE.md` used to say preset chips snap to minute boundaries like the handles. They do not:
 * `eb92752` ("presets select exact duration", #21) made them select an exact duration from the
 * recording edge on purpose, so the "5 min" chip selects exactly five minutes. This test asserts
 * that contract rather than the stale documentation, and the documentation has been corrected.
 *
 * ### Why the buffer is seeded
 *
 * Minute snapping needs a buffer several minutes wide, and recording one in real time would make
 * this test take a quarter of an hour. The history is therefore written straight to the database
 * as completed one-minute segments backed by real (short) MP4 files, and only the final, live
 * segment comes from the service. Selection, snapping, the subscription gate, trimming,
 * concatenation and the flush handshake then all run exactly as they do in production.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RecordSelectSaveFlowTest {

    @Inject lateinit var recorderFactory: FakeAudioRecorderFactory
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var segmentDao: SegmentDao
    @Inject lateinit var segmentManager: SegmentManager
    @Inject lateinit var waveformRepository: WaveformRepository

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Start of the seeded buffer: a wall-clock minute boundary, so snapped times are exact. */
    private var bufferStart = 0L

    private var disclaimerWasAccepted = false

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /** `RECORD_AUDIO` gates the microphone foreground service type, not the hardware. */
    @get:Rule(order = 1)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    /**
     * Injects and seeds before the compose rule launches the activity. `NavGraph` fixes its start
     * destination from `disclaimerAccepted` on first composition and `MainScreen` reads the
     * segment buffer as soon as it composes, so both have to be in place by now — a `@Before`
     * method runs too late. Preferences go through [AppPreferences] rather than the backing file,
     * which the `preferencesDataStore` delegate caches in-process.
     */
    @get:Rule(order = 2)
    val injectAndSeed = object : ExternalResource() {
        override fun before() {
            hiltRule.inject()
            recorderFactory.reset()
            clearRecordedState()
            // HiltTestApplication stands in for BackRecorderApp, so the channels it would have
            // created do not exist, and a foreground service posting to a missing channel kills
            // the process rather than failing the test.
            NotificationChannels.createAll(context)
            runBlocking {
                disclaimerWasAccepted = appPreferences.disclaimerAccepted.first()
                appPreferences.setDisclaimerAccepted(true)
            }
            bufferStart = seedHistory()
        }
    }

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * Innermost, so Espresso is not intercepting while the compose rule launches the activity —
     * it only has to be recording by the time the test body opens the save picker.
     */
    @get:Rule(order = 4)
    val intentsRule = IntentsRule()

    @After
    fun tearDown() {
        try {
            RecordingService.stop(context)
            runBlocking {
                awaitUntil("RecordingService never stopped") { !RecordingService.isRunning.value }
                // `onDestroy` clears `isRunning` before it finishes finalizing, so clearing the
                // cache underneath a segment still being written would leave its row behind.
                awaitUntil("Recorders never settled") { recorderFactory.isSettled() }
            }
        } finally {
            recorderFactory.reset()
            clearRecordedState()
            runBlocking {
                appPreferences.setDisclaimerAccepted(disclaimerWasAccepted)
                appPreferences.setWasRecordingBeforeShutdown(false)
            }
        }
    }

    @Test
    fun recordThenSelectOnTheWaveformThenSaveWritesTheSelectedRangeIncludingTheLiveMinute() {
        val savedFile = File(context.cacheDir, SAVED_CLIP_NAME).also { it.delete() }
        val expectedStart = bufferStart + SELECTION_START_OFFSET_MS
        val expectedEnd = bufferStart + SELECTION_END_OFFSET_MS

        awaitText(composeRule.activity.getString(R.string.select_time_range))
        composeRule.onNodeWithText(NOT_RECORDING).assertExists()

        // ---- record ---------------------------------------------------------------------------
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.start_recording))
            .performClick()
        awaitText(RECORDING)
        runBlocking {
            awaitUntil("RecordingService never started") { RecordingService.isRunning.value }
        }
        // Real AAC in the live segment, long enough that its presence in the saved clip is a
        // measurement rather than a judgement call against the history segments.
        recorderFactory.awaitRecorder(index = 0).feed(LIVE_FEED_MS)
        // The service's own record of when the live segment opened. Taken from the database
        // rather than the clock, because the test only learns that recording started some time
        // after it actually did.
        val liveSegmentStart = runBlocking { liveSegmentRows().single().startTime }

        // ---- a time-preset chip -----------------------------------------------------------------
        // Presets select an exact duration from the recording edge (#21) — deliberately not
        // minute-snapped, unlike the handles below.
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.preset_5_min))
            .performClick()
        composeRule.waitForIdle()
        assertEquals(
            "The 5 min preset must select exactly five minutes, not a snapped range",
            5 * MINUTE_MS,
            selectedDurationMs()
        )

        // ---- drag the handles -------------------------------------------------------------------
        // "All" first, so both handles sit at a known edge of the canvas for the drags to start
        // from: the start handle at fraction 0 and the end handle at fraction 1.
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.preset_all))
            .performClick()
        composeRule.waitForIdle()

        dragHandle(
            isStart = true,
            fromFraction = 0f,
            toFraction = fractionLandingIn(SELECTION_START_OFFSET_MS, snapsUp = false)
        )
        dragHandle(
            isStart = false,
            fromFraction = 1f,
            toFraction = fractionLandingIn(SELECTION_END_OFFSET_MS, snapsUp = true)
        )
        composeRule.waitForIdle()

        assertEquals(
            "Both handles snap to minute boundaries, so the span must be a whole number of minutes",
            expectedEnd - expectedStart,
            selectedDurationMs()
        )
        assertTextExists(
            "Start handle label should read the floored minute",
            TimeUtils.formatTime(expectedStart)
        )
        assertTextExists(
            "End handle label should read the ceiled minute",
            TimeUtils.formatTime(expectedEnd)
        )

        // ---- the end handle must reach the currently-recording minute ---------------------------
        // Dragged to the right edge the ceil overshoots the buffer, so the clamp to bufferEndTime
        // decides — and bufferEndTime sits inside the live segment, past the last complete minute.
        dragHandle(
            isStart = false,
            fromFraction = renderedFraction(SELECTION_END_OFFSET_MS),
            toFraction = 1f
        )
        composeRule.waitForIdle()
        val liveEnd = expectedStart + selectedDurationMs()
        assertTrue(
            "End handle stopped ${liveSegmentStart - liveEnd}ms before the live segment opened, " +
                "so it never reached the currently-recording minute",
            // The duration label truncates to whole seconds, hence the second of slack.
            liveEnd + 1_000 >= liveSegmentStart
        )
        assertTrue(
            "Selection ran past the recording edge",
            liveEnd <= System.currentTimeMillis()
        )

        // The selection must really span the tail of the history plus the live segment, or the
        // audio-length bound below would be measuring the wrong thing.
        val covered = runBlocking {
            segmentManager.getSegmentsInRange(expectedStart, System.currentTimeMillis())
        }
        assertEquals(
            "Unexpected segments under the selection: " + covered.joinToString { segment ->
                "+${(segment.startTime - bufferStart) / 1000}s complete=${segment.isComplete}"
            },
            EXPECTED_COVERED_SEGMENTS,
            covered.size
        )

        // ---- save ---------------------------------------------------------------------------------
        Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(
            Instrumentation.ActivityResult(
                Activity.RESULT_OK,
                Intent().setData(Uri.fromFile(savedFile))
            )
        )
        composeRule.onNodeWithContentDescription(SAVE_FAB).performClick()

        // The picker's requested filename is built from selectionStartTime at second resolution,
        // which is the only exact read of the snapped start that the UI offers.
        composeRule.waitUntil(TIMEOUT_MS) { createDocumentTitles().isNotEmpty() }
        assertEquals(
            "Save filename must carry the floored start of the selection",
            "recording_${TimeUtils.formatForFilename(expectedStart)}.m4a",
            createDocumentTitles().last()
        )

        assertEquals("The save reported a failure", SAVE_SUCCESS, awaitSaveOutcome())

        // ---- what the flush did ---------------------------------------------------------------------
        assertEquals(
            "The save must have flushed exactly the in-progress segment",
            1,
            recorderFactory.finalizedCount()
        )
        assertTrue(
            "Flushing rotates rather than stops — the service must still be recording",
            RecordingService.isRunning.value
        )
        val liveRows = runBlocking { liveSegmentRows() }
        assertEquals("Expected the flushed live segment plus its replacement", 2, liveRows.size)
        assertTrue("Flushed live segment is not marked complete", liveRows[0].isComplete)
        assertTrue("Replacement segment should still be open", !liveRows[1].isComplete)

        // ---- what landed on disk -----------------------------------------------------------------
        assertTrue("Nothing was written to the picked destination", savedFile.length() > 0)
        val savedAudioUs = lastAudioSampleUs(savedFile)
        assertTrue(
            "Saved clip holds only ${savedAudioUs}us — too short to contain the live segment, so " +
                "the final in-progress minute was not flushed into the save",
            savedAudioUs >= MIN_SAVED_WITH_LIVE_US
        )

        // ---- and plain stop still works -------------------------------------------------------------
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.stop_recording))
            .performClick()
        awaitText(NOT_RECORDING)
        runBlocking {
            awaitUntil("RecordingService never stopped") { !RecordingService.isRunning.value }
        }
    }

    // ---- seeding -----------------------------------------------------------------------------------

    /**
     * Writes [HISTORY_SEGMENTS] completed one-minute segments ending at the last minute boundary,
     * and returns the start of the first — itself a minute boundary, so every snapped time the
     * test expects is an exact offset from it.
     *
     * Each row's `endTime` stops [SEGMENT_GAP_MS] short of the next row's start, as a real
     * finalize does. That also keeps `getSegmentsInRange` from pulling in the segment *before* a
     * selection beginning exactly on a boundary — which would otherwise be trimmed from an offset
     * past the end of its (deliberately short) audio and produce an empty track.
     */
    private fun seedHistory(): Long {
        val historyEnd = (System.currentTimeMillis() / MINUTE_MS) * MINUTE_MS
        val start = historyEnd - HISTORY_SPAN_MS
        runBlocking {
            for (index in 0 until HISTORY_SEGMENTS) {
                val segmentStart = start + index * MINUTE_MS
                val audioFile = segmentManager.createSegmentFile(segmentStart)
                val waveformFile = segmentManager.createWaveformFile(segmentStart)
                writeShortSegment(audioFile)
                waveformRepository.saveAmplitudes(waveformFile, List(WAVEFORM_SAMPLES) { 0.5f })
                segmentDao.insert(
                    SegmentEntity(
                        id = UUID.randomUUID().toString(),
                        filePath = audioFile.absolutePath,
                        waveformPath = waveformFile.absolutePath,
                        startTime = segmentStart,
                        endTime = segmentStart + MINUTE_MS - SEGMENT_GAP_MS,
                        durationMs = MINUTE_MS - SEGMENT_GAP_MS,
                        isComplete = true
                    )
                )
            }
        }
        return start
    }

    /**
     * A real MPEG-4/AAC file, from the same encoder the service's fake recorder uses, so
     * `MediaExtractor` reads the seeded history exactly as it reads a recorded segment. Short on
     * purpose: every selection this test makes starts on a segment boundary, so nothing is ever
     * trimmed from an offset into these files.
     */
    private fun writeShortSegment(file: File) {
        val recorder = FakeAudioRecorder()
        recorder.start(file)
        recorder.feed(HISTORY_FEED_MS)
        recorder.stop()
    }

    /** The rows the recording service wrote, in start order — everything past the seeded history. */
    private suspend fun liveSegmentRows(): List<SegmentEntity> =
        segmentDao.getAllSegments().first().filter { it.startTime > bufferStart + HISTORY_SPAN_MS }

    private fun clearRecordedState() = runBlocking {
        segmentDao.getAllSegments().first().forEach { segmentDao.delete(it) }
        File(context.cacheDir, "segments").deleteRecursively()
        File(context.cacheDir, "waveforms").deleteRecursively()
        File(context.cacheDir, SAVED_CLIP_NAME).delete()
    }

    // ---- aiming at the waveform ---------------------------------------------------------------------

    /**
     * What the waveform's time range can currently be, as `[lowest, highest]`.
     *
     * `MainViewModel` sets `bufferEndTime` to the wall clock of whichever segment emission it last
     * collected, so the right edge is somewhere between the end of the seeded history and now. The
     * test cannot read it, but both bounds are known, and that is enough to aim a drag.
     */
    private fun bufferRangeBounds(): Pair<Long, Long> =
        (HISTORY_SPAN_MS - SEGMENT_GAP_MS) to (System.currentTimeMillis() - bufferStart)

    /**
     * The fraction of the canvas width to drag a handle to so the snap lands on [targetOffsetMs]
     * past [bufferStart], whatever the right edge turns out to be.
     *
     * A floor needs the raw time anywhere in `[target, target + 1min)`, a ceil anywhere in
     * `(target - 1min, target]`. Aiming at the middle of that window leaves the widest margin for
     * the unknown right edge, and the bounds are then asserted rather than hoped for — a machine
     * slow enough to invalidate them says so instead of failing on an off-by-a-minute mystery.
     */
    private fun fractionLandingIn(targetOffsetMs: Long, snapsUp: Boolean): Float {
        val (rangeLow, rangeHigh) = bufferRangeBounds()
        val aim = if (snapsUp) targetOffsetMs - MINUTE_MS / 2 else targetOffsetMs + MINUTE_MS / 2
        val fraction = aim.toFloat() / ((rangeLow + rangeHigh) / 2f)
        val rawLow = fraction * rangeLow
        val rawHigh = fraction * rangeHigh
        val windowLow = if (snapsUp) targetOffsetMs - MINUTE_MS else targetOffsetMs
        val windowHigh = if (snapsUp) targetOffsetMs else targetOffsetMs + MINUTE_MS
        assertTrue(
            "The waveform's right edge has drifted too far (range ${rangeLow}..${rangeHigh}ms) " +
                "for a drag to $targetOffsetMs to be unambiguous: raw lands in " +
                "${rawLow.toLong()}..${rawHigh.toLong()}ms, outside $windowLow..$windowHigh",
            rawLow > windowLow && rawHigh < windowHigh
        )
        return fraction
    }

    /**
     * Where a handle sitting [offsetMs] past [bufferStart] is drawn, so a second drag can grab it.
     *
     * A handle is redrawn at the fraction of its *snapped* time, not of the fraction it was
     * dragged to. The midpoint of the possible fractions is well inside `WaveformView`'s 32.dp hit
     * width for any right edge this test tolerates.
     */
    private fun renderedFraction(offsetMs: Long): Float {
        val (rangeLow, rangeHigh) = bufferRangeBounds()
        return (offsetMs.toFloat() / rangeLow + offsetMs.toFloat() / rangeHigh) / 2f
    }

    /**
     * Drags one waveform handle from [fromFraction] to [toFraction] of the canvas width.
     *
     * `WaveformView` runs a custom `awaitEachGesture` loop with dedicated handle zones above and
     * below the waveform, so the press has to land in the right zone: when both handles are within
     * the hit width the loop disambiguates them by 2D distance to the visible tab.
     */
    private fun dragHandle(isStart: Boolean, fromFraction: Float, toFraction: Float) {
        composeRule.onNodeWithTag(WAVEFORM_CANVAS_TEST_TAG).performTouchInput {
            val labelPx = LABEL_ROW_HEIGHT_DP.dp.toPx()
            val zonePx = HANDLE_ZONE_HEIGHT_DP.dp.toPx()
            val y = if (isStart) labelPx + zonePx / 2f else height - labelPx - zonePx / 2f
            val fromX = (fromFraction * width).coerceIn(1f, width - 1f)
            val toX = (toFraction * width).coerceIn(1f, width - 1f)
            down(Offset(fromX, y))
            moveTo(Offset((fromX + toX) / 2f, y))
            moveTo(Offset(toX, y))
            up()
        }
    }

    // ---- reading the UI back --------------------------------------------------------------------------

    /** The "Selected: m:ss" label, parsed — the UI's own report of the selected span. */
    private fun selectedDurationMs(): Long {
        val text = composeRule
            .onNode(hasText(SELECTED_PREFIX, substring = true), useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }
        val parts = text.removePrefix(SELECTED_PREFIX).split(":").map { it.trim().toLong() }
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]) * 1000L
            3 -> ((parts[0] * 60 + parts[1]) * 60 + parts[2]) * 1000L
            else -> error("Unparseable selection duration '$text'")
        }
    }

    private fun createDocumentTitles(): List<String> = Intents.getIntents()
        .filter { it.action == Intent.ACTION_CREATE_DOCUMENT }
        .mapNotNull { it.getStringExtra(Intent.EXTRA_TITLE) }

    /**
     * The snackbar the save produced, success or failure. Both are waited for together so a save
     * that errored reports its own message instead of timing out on the success text.
     */
    private fun awaitSaveOutcome(): String {
        var outcome: String? = null
        composeRule.waitUntil(SAVE_TIMEOUT_MS) {
            outcome = SAVE_OUTCOME_TEXTS.firstNotNullOfOrNull { candidate ->
                composeRule
                    .onAllNodesWithText(candidate, substring = true)
                    .fetchSemanticsNodes()
                    .firstOrNull()
                    ?.config
                    ?.get(SemanticsProperties.Text)
                    ?.joinToString("") { it.text }
            }
            outcome != null
        }
        return outcome ?: error("No save outcome was shown")
    }

    private fun awaitText(text: String, timeoutMs: Long = TIMEOUT_MS) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertTextExists(message: String, text: String) {
        assertTrue(
            "$message — no node reads '$text'",
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        )
    }

    // ---- reading the saved clip -----------------------------------------------------------------------

    /** Presentation time of the last audio sample, which is how long the saved clip really is. */
    private fun lastAudioSampleUs(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    .orEmpty().startsWith("audio/")
            }
            assertTrue("Saved clip has no audio track", track != null)
            extractor.selectTrack(track!!)

            val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
            var samples = 0
            var lastSampleUs = 0L
            while (extractor.readSampleData(buffer, 0) > 0) {
                samples++
                lastSampleUs = extractor.sampleTime
                extractor.advance()
            }
            assertTrue("Saved clip holds no readable samples", samples > 0)
            return lastSampleUs
        } finally {
            extractor.release()
        }
    }

    private suspend fun awaitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { message }
            delay(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L

        /**
         * Twelve minutes of history. Wide enough that a drag aimed at the middle of a minute still
         * lands in it however far the live edge has moved on, and recent enough that the selection
         * below stays inside the free tier's 15-minute save limit.
         */
        const val HISTORY_SEGMENTS = 12
        const val HISTORY_SPAN_MS = HISTORY_SEGMENTS * MINUTE_MS

        const val SELECTION_START_OFFSET_MS = 4 * MINUTE_MS
        const val SELECTION_END_OFFSET_MS = 6 * MINUTE_MS

        /** History segments 4..11 — the earlier ones end before the selection — plus the live one. */
        const val EXPECTED_COVERED_SEGMENTS = 9

        /** Mirrors `WaveformView`'s private zone geometry, so a press lands on a handle tab. */
        const val LABEL_ROW_HEIGHT_DP = 20
        const val HANDLE_ZONE_HEIGHT_DP = 32

        const val HISTORY_FEED_MS = 400L
        const val LIVE_FEED_MS = 2_500L
        const val WAVEFORM_SAMPLES = 50

        /**
         * The selection covers eight history segments (~3.2s of audio at most) plus the live one
         * (~2.5s). The bound sits between the two, so it fails when the live segment is missing
         * and not when AAC priming shifts a few milliseconds.
         */
        const val MIN_SAVED_WITH_LIVE_US = 3_500_000L

        /** A real finalize never lands exactly on the next segment's start either. */
        const val SEGMENT_GAP_MS = 100L

        const val SAVED_CLIP_NAME = "e2e_saved_recording.m4a"
        const val NOT_RECORDING = "Not recording"
        const val RECORDING = "Recording"
        const val SELECTED_PREFIX = "Selected: "
        const val SAVE_FAB = "Save Recording"
        const val SAVE_SUCCESS = "Recording saved successfully"

        /**
         * Matched as substrings, so a failed save reports its own snackbar rather than timing out
         * on the success text. A flush that never completes (jacoelt/backrecorder#47) shows
         * nothing at all and is left to time out — which is the right outcome for a hang.
         */
        val SAVE_OUTCOME_TEXTS = listOf(
            SAVE_SUCCESS,
            "No recorded data found",
            "Failed to finalize in-progress recording"
        )

        const val SAMPLE_BUFFER_BYTES = 64 * 1024
        const val TIMEOUT_MS = 30_000L
        const val SAVE_TIMEOUT_MS = 60_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
