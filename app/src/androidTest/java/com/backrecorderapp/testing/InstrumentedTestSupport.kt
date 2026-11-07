package com.backrecorderapp.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.backrecorderapp.data.db.SegmentDao
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.notification.NotificationChannels
import com.backrecorderapp.service.RecordingService
import com.backrecorderapp.service.recorder.FakeAudioRecorderFactory
import dagger.hilt.android.testing.HiltAndroidRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import java.io.File

/**
 * The setup, teardown and polling the instrumented flow tests have in common
 * (jacoelt/backrecorder#55).
 *
 * These tests share one process, one Room database, one DataStore and one cache directory, so the
 * cleanup contract has to be the same in all of them or they leak state into each other — and
 * since CI has run them on every PR since #37, a leak is a flaky **required** check. Before this
 * file each test carried its own copy and the copies had already drifted. What is shared here is
 * only the part that is genuinely identical: the rule *shape*, the state reset, the preference
 * capture/restore and the poll helper. **Seeding stays in each test**, because what a test needs
 * the world to look like is the part that actually differs.
 */

/** The app under test. `ApplicationProvider` is valid from any instrumented thread. */
internal val appContext: Context get() = ApplicationProvider.getApplicationContext()

internal const val POLL_INTERVAL_MS = 50L

/** Long enough for a cold service start on a loaded CI emulator, short enough to fail a hang. */
internal const val STATE_TIMEOUT_MS = 30_000L

/**
 * Polls [condition] until it holds, failing with [message] rather than with a bare timeout.
 *
 * [condition] is always evaluated at least once, and the deadline is only consulted after a
 * failing evaluation, so a condition that is already true cannot lose a race with a slow poll.
 */
internal suspend fun awaitUntil(
    message: String,
    timeoutMs: Long = STATE_TIMEOUT_MS,
    condition: suspend () -> Boolean
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        if (condition()) return
        if (System.currentTimeMillis() >= deadline) throw AssertionError(message)
        delay(POLL_INTERVAL_MS)
    }
}

/** Waits for `RecordingService.isRunning` — the single source of truth the UI observes too. */
internal suspend fun awaitRecordingService(running: Boolean, timeoutMs: Long = STATE_TIMEOUT_MS) =
    awaitUntil("RecordingService.isRunning never became $running", timeoutMs) {
        RecordingService.isRunning.value == running
    }

/**
 * Waits until every recorder the fake handed out has been finalized.
 *
 * `RecordingService.onDestroy` clears `isRunning` *before* it finishes finalizing, so a stop that
 * has been observed is not yet a stop that has finished writing. Clearing the database or the
 * cache at that point races a segment still being written and leaves its row behind for whichever
 * test runs next.
 */
internal suspend fun awaitRecordersSettled(
    recorderFactory: FakeAudioRecorderFactory,
    timeoutMs: Long = STATE_TIMEOUT_MS
) = awaitUntil("Recorders never settled: the service is still finalizing a segment", timeoutMs) {
    recorderFactory.isSettled()
}

/** Stops the recording service and waits for it, and the recorders it owns, to go quiet. */
internal suspend fun stopRecordingAndSettle(recorderFactory: FakeAudioRecorderFactory) {
    RecordingService.stop(appContext)
    awaitRecordingService(running = false)
    awaitRecordersSettled(recorderFactory)
}

/**
 * Creates the notification channels `BackRecorderApp` would have created at startup.
 *
 * `HiltTestApplication` stands in for `BackRecorderApp` under instrumentation, so nothing has
 * created them — and a foreground service posting to a channel that does not exist kills the
 * process with `CannotPostForegroundServiceNotificationException` rather than failing the test.
 */
internal fun createNotificationChannels() = NotificationChannels.createAll(appContext)

/**
 * The cleanup rule.
 *
 * **Order it outside (a lower `order` than) the test's seeding rule.** `ExternalResource` skips
 * its own `after()` when its `before()` threw, so a rule that both seeds and cleans up leaves
 * segment rows, cache files and flipped preferences behind whenever seeding fails part-way — for
 * whichever test runs next, in a process they all share. Only `RecordSelectSaveFlowTest` had this
 * right before #55; the shape is now in one place so it cannot drift again.
 *
 * Its `before()` is also where `hiltRule.inject()` happens, which is what lets the seeding rule it
 * wraps use the test's injected fields.
 *
 * The rule stack a flow test ends up with:
 * ```
 * order = 0  HiltAndroidRule
 * order = 1  GrantPermissionRule        (only where a granted start is wanted)
 * order = 2  cleanUpRule(hiltRule)      — injects, and cleans up whatever happens below
 * order = 3  the test's own seeding     — may throw; cleanup above still runs
 * order = 4  createAndroidComposeRule   — launches the activity, so seeding must precede it
 * ```
 * `NavGraph` fixes its start destination from `disclaimerAccepted` on first composition, which is
 * why seeding has to sit above the compose rule rather than in a `@Before` method.
 */
internal fun cleanUpRule(hiltRule: HiltAndroidRule, after: () -> Unit): ExternalResource =
    object : ExternalResource() {
        override fun before() = hiltRule.inject()

        override fun after() = after()
    }

/**
 * Deletes every `segments` row and the cache directories the recorder and waveform writers use,
 * plus any [alsoDelete] files named relative to the cache directory.
 *
 * Rows go first: a row pointing at a file that has already been deleted is the state that makes a
 * later test's buffer read fail for a reason that has nothing to do with what it tests.
 *
 * A free function rather than a method on [PreferenceSnapshot], so the two tests that never write
 * a segment do not have to inject a [SegmentDao] just to get preference capture/restore.
 */
internal fun clearRecordedState(segmentDao: SegmentDao, vararg alsoDelete: String) = runBlocking {
    segmentDao.getAllSegments().first().forEach { segmentDao.delete(it) }
    File(appContext.cacheDir, "segments").deleteRecursively()
    File(appContext.cacheDir, "waveforms").deleteRecursively()
    alsoDelete.forEach { File(appContext.cacheDir, it).delete() }
}

/**
 * The preferences a test is about to overwrite, and how to put them back.
 *
 * One instance per test, held as a plain field; nothing here needs injection.
 */
internal class PreferenceSnapshot {

    private val captured = mutableListOf<suspend () -> Unit>()

    /**
     * Reads [preference] now and queues a write of that value for [restoreAll].
     *
     * DataStore is the one piece of state that genuinely outlives a test even when the Hilt
     * component does not: the `preferencesDataStore` delegate is a process-wide singleton with its
     * own cache, which is also why preferences are always written through [AppPreferences] and
     * never by deleting the backing file.
     *
     * Only what was actually captured is restored, so a seeding rule that throws half-way puts
     * back exactly the preferences it had read by then and invents nothing for the rest — the
     * behaviour `RecordSelectSaveFlowTest` spelled out with a pair of nullable fields.
     */
    suspend fun <T> capture(preference: Flow<T>, restore: suspend (T) -> Unit) {
        val value = preference.first()
        captured += { restore(value) }
    }

    /** Puts back every preference [capture] recorded, in the order they were read. */
    suspend fun restoreAll() {
        captured.forEach { it() }
        captured.clear()
    }
}
