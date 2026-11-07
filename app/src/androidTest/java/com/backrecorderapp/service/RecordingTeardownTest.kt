package com.backrecorderapp.service

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.backrecorderapp.MainActivity
import com.backrecorderapp.data.db.SegmentDao
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.service.recorder.FakeAudioRecorderFactory
import com.backrecorderapp.testing.PreferenceSnapshot
import com.backrecorderapp.testing.appContext
import com.backrecorderapp.testing.awaitRecordersSettled
import com.backrecorderapp.testing.awaitRecordingService
import com.backrecorderapp.testing.awaitUntil
import com.backrecorderapp.testing.cleanUpRule
import com.backrecorderapp.testing.clearRecordedState
import com.backrecorderapp.testing.createNotificationChannels
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * What the service does when recording goes wrong or stops mid-rotation (#48).
 *
 * `RecordingSegmentTest` covers the happy path; this covers the paths that used to fail quietly —
 * a recorder that will not start, and a teardown that lands while a rotation is in flight.
 *
 * **What is not covered here, deliberately.** The rotation *loop* only ticks once a minute, so the
 * guard that one IO or database failure no longer cancels it for the whole session cannot be
 * exercised without a two-minute test or a new seam into `WaveformRepository`. What is covered is
 * the property that matters either way: a rotation that throws leaves the service recording and
 * able to rotate again, driven through the flush path, which runs the same `rotateSegment()`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RecordingTeardownTest {

    @Inject lateinit var recorderFactory: FakeAudioRecorderFactory
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var segmentDao: SegmentDao

    private var scenario: ActivityScenario<MainActivity>? = null
    private val preferences = PreferenceSnapshot()

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /** `startForeground` with the microphone service type needs this even with no mic involved. */
    @get:Rule(order = 1)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    @get:Rule(order = 2)
    val cleanUpAfterwards = cleanUpRule(hiltRule) {
        try {
            runBlocking {
                RecordingService.stop(appContext)
                awaitRecordingService(running = false)
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
            runBlocking {
                preferences.capture(
                    appPreferences.disclaimerAccepted,
                    appPreferences::setDisclaimerAccepted
                )
                appPreferences.setDisclaimerAccepted(false)
            }
        }
    }

    /**
     * Defect 4. A recorder that will not start used to be logged and ignored: `audioRecorder`
     * stayed null while `currentSegment` stayed set, so the service went on producing an empty
     * file and a database row every minute behind a notification that said it was recording.
     *
     * Fails without the fix — the service stays running.
     *
     * The failure is injected into the *second* segment rather than the first, which is both the
     * realistic case (another app takes the microphone mid-session) and the only one a test can
     * assert on: a service that stops within milliseconds of `startForegroundService` is killed by
     * the framework with `ForegroundServiceDidNotStartInTimeException` before any assertion runs.
     */
    @Test
    fun aRecorderThatWillNotStartStopsTheServiceInsteadOfRecordingNothing() = runBlocking {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        RecordingService.start(appContext)
        awaitRecordingService(running = true)
        recorderFactory.awaitRecorder(index = 0).feed(FED_DURATION_MS)

        // The next segment's recorder refuses to open. A flush is just a rotation on demand, so
        // this reaches the same `startNewSegment` the once-a-minute loop would.
        recorderFactory.failNextStart()
        runCatching { RecordingService.flushCurrentSegment() }

        awaitUntil("The service kept running with no recorder (#48, defect 4)") {
            RecordingService.isRunning.value == false
        }

        // The segment that was already recorded survives; the one that could not open left no row
        // behind, or `getSegmentsInRange` would match that incomplete row for the rest of the
        // install and every save would take the flush branch forever.
        val rows = segmentDao.getAllSegments().first()
        assertEquals("Expected only the segment that actually recorded", 1, rows.size)
        assertTrue("The recorded segment was not finalized", rows.single().isComplete)
        assertEquals("A failed start should open no recorder", 1, recorderFactory.startedCount())
    }

    /**
     * Defect 1. `onDestroy` used to stop the recorder without waiting for a rotation in flight, so
     * the rotation could go on to open a *new* segment afterwards and then be cancelled at its
     * next suspension point — leaving a started recorder nothing would ever stop, holding the
     * microphone for the life of the process, plus a row for a segment whose MOOV atom is never
     * written.
     *
     * The interleaving is *forced* rather than hoped for: the replacement segment's recorder is
     * held inside `start()` while the service is destroyed, then released. The assertion is the
     * invariant the orphan violates — once the service is gone, every recorder it opened has also
     * been closed.
     *
     * Without the fix this fails: the released `start()` publishes a recorder nobody owns, the
     * rotation is cancelled at its next suspension point, and nothing ever stops it.
     */
    @Test
    fun stoppingWhileARotationOpensASegmentLeavesNoOrphanedRecorder() = runBlocking {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        RecordingService.start(appContext)
        awaitRecordingService(running = true)
        recorderFactory.awaitRecorder(index = 0).feed(FED_DURATION_MS)

        val heldStart = recorderFactory.holdStarts()
        // Scoped to the test: an assertion that fails before the stop below would otherwise leave
        // this coroutine running against the next test in the process.
        val flushScope = CoroutineScope(Dispatchers.Default)
        try {
            // A flush is a rotation on demand. Do not wait for it — it is expected to be abandoned
            // by the teardown below (#47), which is exactly the situation under test.
            val flush = flushScope.launch {
                runCatching { RecordingService.flushCurrentSegment() }
            }

            awaitUntil("The rotation never reached the replacement segment's start()") {
                recorderFactory.startsHeld() > 0
            }

            RecordingService.stop(appContext)
            awaitRecordingService(running = false)

            heldStart.countDown()
            flush.join()
        } finally {
            heldStart.countDown()
            flushScope.cancel()
        }

        // The released start has to actually open the replacement segment, or there is no orphan
        // to look for and the test would pass vacuously.
        awaitUntil("The held start never opened a second segment, so nothing was under test") {
            recorderFactory.startedCount() == 2
        }
        awaitUntil(
            "A recorder was started during teardown and never stopped (#48, defect 1)",
            timeoutMs = ORPHAN_SETTLE_TIMEOUT_MS
        ) {
            recorderFactory.isSettled()
        }
    }

    /**
     * A rotation that throws must leave the service recording and able to rotate again, rather
     * than wedged on a segment that is never finalized. The waveform directory is replaced by a
     * plain file, so `saveAmplitudes` fails inside `rotateSegment` — no seam, just an unwritable
     * path.
     *
     * **This is not the regression guard for defect 2, and would pass without that fix.** Defect 2
     * is the bare `while (isActive)` rotation *loop* being cancelled by one throw; this drives the
     * flush path, which already caught and rethrew before the fix. The loop ticks once a minute,
     * so covering it properly needs a multi-minute test or a new seam into `WaveformRepository` —
     * neither of which belongs in a suite that gates every PR in the repository. What this does
     * cover is the shared half: `rotateSegment` is re-enterable after throwing.
     */
    @Test
    fun aRotationThatThrowsLeavesTheServiceRecordingAndAbleToRotateAgain() = runBlocking {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        RecordingService.start(appContext)
        awaitRecordingService(running = true)
        recorderFactory.awaitRecorder(index = 0).feed(FED_DURATION_MS)

        val waveformDir = File(appContext.cacheDir, "waveforms")
        waveformDir.deleteRecursively()
        waveformDir.writeText("not a directory")
        try {
            runCatching { RecordingService.flushCurrentSegment() }
        } finally {
            // Put the directory back, not just remove the file: the segment that is open now had
            // its waveform path minted before the sabotage, so leaving no directory would fail
            // the *next* rotation too and prove nothing about recovery.
            waveformDir.delete()
            waveformDir.mkdirs()
        }

        assertTrue("A failed rotation stopped the service", RecordingService.isRunning.value)

        // The real assertion: it can still rotate, and the rotation really happened — a new
        // recorder was opened and a segment finalized, not just a flush that returned.
        RecordingService.flushCurrentSegment()
        assertTrue(
            "The service could not rotate again after a failed rotation",
            segmentDao.getAllSegments().first().any { it.isComplete }
        )
        awaitUntil("No replacement recorder was opened after the failed rotation") {
            recorderFactory.startedCount() >= 2
        }
        assertTrue("The service stopped recording", RecordingService.isRunning.value)
    }


    private companion object {
        const val FED_DURATION_MS = 1_000L

        /** The fix stops an orphan immediately; this only bounds how long a regression takes. */
        const val ORPHAN_SETTLE_TIMEOUT_MS = 10_000L

    }
}

