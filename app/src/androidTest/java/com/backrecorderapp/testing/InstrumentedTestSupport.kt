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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.ExternalResource
import java.io.File

/*
 * The setup, teardown and polling the instrumented tests have in common.
 *
 * They share one process, one Room database, one DataStore and one cache directory, so all of them
 * need the same cleanup contract or they leak state into each other — and the `Instrumented tests`
 * check is required on `main`, so a leak is a flaky required check. Only what is genuinely
 * identical lives here: the rule shape, the state reset, the preference capture/restore and the
 * poll helper. **Seeding stays in each test**, because what a test needs the world to look like is
 * the part that actually differs.
 */

/** The app under test. */
internal val appContext: Context get() = ApplicationProvider.getApplicationContext()

internal const val POLL_INTERVAL_MS = 50L

/** Long enough for a cold service start on a loaded CI emulator, short enough to fail a hang. */
internal const val STATE_TIMEOUT_MS = 30_000L

/**
 * Polls [condition] until it holds, failing with [message] rather than with a bare timeout.
 *
 * The deadline wraps the whole loop rather than only being consulted between polls, so a
 * [condition] that suspends and never returns — a DataStore or Room read against state the service
 * is still holding — fails the test at [timeoutMs] instead of hanging the instrumentation run.
 */
internal suspend fun awaitUntil(
    message: String,
    timeoutMs: Long = STATE_TIMEOUT_MS,
    condition: suspend () -> Boolean
) {
    val satisfied = withTimeoutOrNull(timeoutMs) {
        while (!condition()) {
            delay(POLL_INTERVAL_MS)
        }
        true
    }
    if (satisfied == null) throw AssertionError(message)
}

/** Waits for `RecordingService.isRunning` to reach [running]. */
internal suspend fun awaitRecordingService(running: Boolean) =
    awaitUntil("RecordingService.isRunning never became $running") {
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
internal suspend fun awaitRecordersSettled(recorderFactory: FakeAudioRecorderFactory) =
    awaitUntil("Recorders never settled: the service is still finalizing a segment") {
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
 * Performs `hiltRule.inject()` in `before()` and runs [cleanUp] in `after()`.
 *
 * **Order it outside (a lower `order` than) the test's seeding rule.** `ExternalResource` skips
 * its own `after()` when its `before()` threw, so a rule that both seeds and cleans up leaves
 * segment rows, cache files and flipped preferences behind whenever seeding fails part-way — for
 * whichever test runs next, in a process they all share. Injecting here is what lets the seeding
 * rule below it use the test's `@Inject` fields.
 *
 * Relative order in a test: the Hilt rule, then `GrantPermissionRule` where a granted start is
 * wanted, then this rule, then the test's own seeding, then the compose rule if there is one.
 * `NavGraph` fixes its start destination from `disclaimerAccepted` on first composition, which is
 * why seeding has to sit above the compose rule rather than in a `@Before` method.
 *
 * Because this rule sits outside the compose rule, its `after()` runs *after* the activity has
 * been torn down — later than an `@After` method would.
 */
internal fun cleanUpRule(hiltRule: HiltAndroidRule, cleanUp: () -> Unit): ExternalResource =
    object : ExternalResource() {
        override fun before() = hiltRule.inject()

        override fun after() = cleanUp()
    }

/**
 * Deletes every `segments` row and the cache directories the recorder and waveform writers use,
 * plus any [alsoDelete] files named relative to the cache directory.
 *
 * Rows go first: a row pointing at a file that has already been deleted is the state that makes a
 * later test's buffer read fail for a reason that has nothing to do with what it tests.
 *
 * A free function rather than a method on [PreferenceSnapshot], so the tests that never write a
 * segment do not have to inject a [SegmentDao] just to get preference capture/restore.
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
     * Only what was captured is restored, so a seeding rule that throws half-way puts back exactly
     * the preferences it had read by then and invents nothing for the rest. Note the consequence:
     * a test puts the world back as it *found* it rather than forcing a default, so a value another
     * test leaked is preserved rather than corrected. Under the AndroidX Test Orchestrator's
     * `clearPackageData` each test starts from the real defaults anyway, and restoring what was
     * there is the honest contract — correcting someone else's leak would hide it.
     */
    suspend fun <T> capture(preference: Flow<T>, restore: suspend (T) -> Unit) {
        val value = preference.first()
        captured += { restore(value) }
    }

    /**
     * Puts back every preference [capture] recorded, in the order they were read.
     *
     * Every restore is attempted even when one throws, so a single failure cannot strand the rest
     * of the preferences — the first failure is rethrown once they have all run.
     */
    suspend fun restoreAll() {
        var failure: Throwable? = null
        captured.forEach { restore ->
            try {
                restore()
            } catch (e: Throwable) {
                if (failure == null) failure = e
            }
        }
        captured.clear()
        failure?.let { throw it }
    }
}
