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
import com.backrecorderapp.testing.stopRecordingAndSettle
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * What a *second* start does to a service that is already recording (#61).
 *
 * `RecordingSegmentTest` covers one start and one stop; this covers the start the user or the
 * system sends while the first is still live — a double tap, or `BootReceiver` racing an
 * app-initiated start.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RecordingStartTest {

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
     * A second start while the service is already recording must change nothing. `onStartCommand`
     * used to run `startRecordingLoop()` unconditionally, so the second start opened another
     * recorder over the top of the live one: the first was never stopped, held the microphone for
     * the life of the process, and left its row `isComplete = 0` for good.
     *
     * Fails without the fix, in two places and for two different reasons — a second recorder is
     * opened, and the recorders never settle once the service has gone, because nothing ever
     * stopped the one that was displaced.
     *
     * It also covers the part of the fix that is easy to get wrong: `startForeground` still runs
     * on every start. The duplicate start here goes through `startForegroundService` exactly as
     * the UI and `BootReceiver` do, so a guard that returned before reaching the foreground would
     * have the framework kill the process — and this test with it — rather than fail an assertion.
     */
    @Test
    fun aSecondStartLeavesTheLiveRecordingAloneInsteadOfOpeningASecondRecorder() = runBlocking {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        RecordingService.start(appContext)
        awaitRecordingService(running = true)
        recorderFactory.awaitRecorder(index = 0).feed(FED_DURATION_MS)

        RecordingService.start(appContext)
        // Long enough for the duplicate start to have done its damage: the first start opens a
        // recorder well inside this window, and the defect takes the same path.
        delay(DUPLICATE_START_WINDOW_MS)

        assertEquals(
            "A duplicate start opened a second recorder over the live one (#61)",
            1,
            recorderFactory.startedCount()
        )
        assertTrue("The duplicate start stopped the recording", RecordingService.isRunning.value)

        // The one recording is still whole: a flush rotates it, and what it finalizes is the
        // segment the *first* start opened.
        RecordingService.flushCurrentSegment()
        assertEquals(
            "The flush did not rotate the live recording",
            1,
            recorderFactory.finalizedCount()
        )

        stopRecordingAndSettle(recorderFactory)

        // Every recorder the service opened was also closed — the orphaned microphone the
        // duplicate start used to leave behind would never settle here.
        assertTrue("A recorder was left running (#61)", recorderFactory.isSettled())
        // Two segments and no more: the one the flush rotated, and the replacement it opened,
        // which the stop finalized (#62). A duplicate start that had opened its own segment would
        // show up here as a third row, or as one left incomplete.
        awaitUntil("A duplicate start left an extra segment behind (#61)") {
            val rows = segmentDao.getAllSegments().first()
            rows.size == 2 && rows.all { it.isComplete }
        }
    }

    private companion object {
        const val FED_DURATION_MS = 1_000L

        /** Only bounds how long the duplicate start is given to misbehave. */
        const val DUPLICATE_START_WINDOW_MS = 3_000L
    }
}
