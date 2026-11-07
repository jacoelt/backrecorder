package com.backrecorderapp.service

import android.Manifest
import android.content.Intent
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
import com.backrecorderapp.testing.stopRecordingAndSettle
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/**
 * What the notification's **Stop** action leaves behind (#63).
 *
 * `RecordingTeardownTest` covers the stops the service decides on for itself; this covers the one
 * the user asks for, which is the only path that writes to DataStore on its way out.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RecordingStopTest {

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
                preferences.capture(
                    appPreferences.wasRecordingBeforeShutdown,
                    appPreferences::setWasRecordingBeforeShutdown
                )
                appPreferences.setDisclaimerAccepted(false)
                // What `MainViewModel.toggleRecording` leaves set for the length of a session.
                appPreferences.setWasRecordingBeforeShutdown(true)
            }
        }
    }

    /**
     * Tapping **Stop** must leave the crash-recovery flag clear, or the user ends a recording,
     * reboots, and `BootReceiver` reads a stale `true` and starts recording again (#63).
     *
     * **What this proves, and what it does not.** It fails if the clear is dropped or moved onto a
     * path that does not run, and it is the only coverage of the `ACTION_STOP` branch at all. It
     * does *not* reproduce the cancellation race the issue describes — the write used to be
     * launched on `serviceScope` while `stopSelf()` was called in the same breath, leaving
     * `onDestroy` free to cancel it. `stopSelf()` only posts the teardown to the main thread, so
     * on an idle emulator the launched write wins that race anyway and the old code passes this
     * test; forcing the loss needs a slow DataStore or a starved dispatcher, and attempts at both
     * (occupying every `Dispatchers.Default` worker) failed to flip it reliably. Sequencing the
     * stop behind the write removes the race by construction instead, which is what the self-stop
     * path added in #48 already does.
     *
     * A segment is allowed to open first: a service torn down within milliseconds of
     * `startForegroundService` is killed by the framework before any assertion runs.
     */
    @Test
    fun theNotificationsStopActionClearsTheCrashRecoveryFlag() = runBlocking {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        RecordingService.start(appContext)
        awaitRecordingService(running = true)
        recorderFactory.awaitRecorder(index = 0).feed(FED_DURATION_MS)

        appContext.startService(
            Intent(appContext, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
            }
        )
        awaitRecordingService(running = false)

        // Read once the service has gone, with nothing left running that could still write it:
        // the fix is precisely that the write lands *before* the stop, so no polling is needed.
        assertFalse(
            "Stop left the crash-recovery flag set; a reboot would restart the recording (#63)",
            appPreferences.wasRecordingBeforeShutdown.first()
        )
    }

    /**
     * The segment that is still recording when the service stops has to be closed by somebody.
     * No stop path used to do it, so its row stayed `isComplete = 0` with `endTime` and
     * `durationMs` zero — a row `pruneOldSegments` can never reclaim, because it only deletes
     * complete rows, and one that `getSegmentsInRange` matches on every later save (#62).
     *
     * Fails without the fix: the row is still incomplete once the service has gone.
     */
    @Test
    fun stoppingFinalizesTheSegmentThatWasStillRecording() = runBlocking {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        RecordingService.start(appContext)
        awaitRecordingService(running = true)
        recorderFactory.awaitRecorder(index = 0).feed(FED_DURATION_MS)

        stopRecordingAndSettle(recorderFactory)

        // The close runs on a scope that outlives the service, so it lands shortly *after* the
        // stop is observable rather than before it — polled, not read once.
        awaitUntil("The segment open at the stop was never closed (#62)") {
            segmentDao.getAllSegments().first().singleOrNull()?.isComplete == true
        }

        val row = segmentDao.getAllSegments().first().single()
        assertTrue("The finalized segment has no duration", row.durationMs > 0)
        assertTrue("The segment's waveform was never written", File(row.waveformPath).exists())
        assertTrue("The segment's audio was discarded", File(row.filePath).length() > 0)
    }

    /**
     * The defect's actual shape: *one* orphan row and its two files per recording session, for the
     * life of the install. Two sessions back to back must leave two complete rows and nothing else.
     *
     * Fails without the fix — two incomplete rows instead.
     */
    @Test
    fun backToBackSessionsAccumulateNoOrphanRows() = runBlocking {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        repeat(SESSIONS) { session ->
            RecordingService.start(appContext)
            awaitRecordingService(running = true)
            recorderFactory.awaitRecorder(index = session).feed(FED_DURATION_MS)
            stopRecordingAndSettle(recorderFactory)
        }

        awaitUntil("Each stopped session left an orphan row behind (#62)") {
            val rows = segmentDao.getAllSegments().first()
            rows.size == SESSIONS && rows.all { it.isComplete }
        }
    }

    private companion object {
        const val FED_DURATION_MS = 1_000L
        const val SESSIONS = 2
    }
}
