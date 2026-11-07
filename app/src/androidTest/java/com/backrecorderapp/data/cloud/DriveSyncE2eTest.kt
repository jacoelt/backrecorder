package com.backrecorderapp.data.cloud

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.backrecorderapp.MainActivity
import com.backrecorderapp.data.billing.FakeBillingRepository
import com.backrecorderapp.data.billing.SubscriptionStatus
import com.backrecorderapp.data.db.SegmentDao
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.data.segment.SegmentManager
import com.backrecorderapp.service.RecordingService
import com.backrecorderapp.service.recorder.FakeAudioRecorderFactory
import com.backrecorderapp.testing.PreferenceSnapshot
import com.backrecorderapp.testing.STATE_TIMEOUT_MS
import com.backrecorderapp.testing.appContext
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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/**
 * The Drive sync flow, end to end against [FakeDriveServer].
 *
 * Everything between the grant and the wire is the production code: [DriveAuthManager]'s state
 * machine and token cache, the real reflective Drive client, [DriveCloudStorage]'s folder
 * resolution and [com.backrecorderapp.data.cloud.CloudSyncManager]'s loop, gated by the real
 * [com.backrecorderapp.domain.SubscriptionGate]. Only three things are faked: who grants the token,
 * where the client points, and what billing answers (that one through `FakeBillingRepository` from
 * jacoelt/backrecorder#41) — all three are impossible on a CI emulator.
 *
 * The segments that upload are real MP4s written by the recorder fake from jacoelt/backrecorder#38,
 * so this proves the whole chain a user's recording actually travels.
 *
 * What each test is defending, in order of how expensive the bug would be:
 * 1. a `recovery_<timestamp>` folder created **inside** `staging`, where buffer cleanup deletes it
 *    and every recording the user was told had been rescued;
 * 2. a folder id cached across a reconnect to a different account, which uploads one user's
 *    recordings into another account's Drive or into nowhere at all;
 * 3. a rejected token that is replayed rather than refreshed, which stops sync silently;
 * 4. segments that never reach `staging` at all.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DriveSyncE2eTest {

    @Inject lateinit var server: FakeDriveServer
    @Inject lateinit var authorizationClient: FakeDriveAuthorizationClient
    @Inject lateinit var billing: FakeBillingRepository
    @Inject lateinit var driveAuthManager: DriveAuthManager
    @Inject lateinit var driveCloudStorage: DriveCloudStorage
    @Inject lateinit var cloudSyncManager: CloudSyncManager
    @Inject lateinit var recorderFactory: FakeAudioRecorderFactory
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var segmentDao: SegmentDao

    private var scenario: ActivityScenario<MainActivity>? = null
    private val preferences = PreferenceSnapshot()

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /** The microphone foreground service type needs RECORD_AUDIO even with a faked recorder. */
    @get:Rule(order = 1)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    /**
     * Everything this class touches is process-wide and outlives it: `FakeCloudModule` installs
     * the Drive fakes for **every** instrumented test, `AppPreferences` is one DataStore, and
     * `RecordingService`'s state is static. So teardown does not just tidy up — it has to put the
     * fakes back to the state an unrelated later test expects, above all revoking the grant, or
     * that test opens Settings and finds a connected Drive account it never asked for.
     *
     * The order matters too: the sync loop is stopped and allowed to go quiet *before* the server
     * closes and the database is cleared, so a tick still in flight cannot write a row after the
     * clear or hit a half-shut server.
     *
     * It is a rule outside the seeding rule rather than an `@After`, because `ExternalResource`
     * skips its own `after()` when its `before()` threw — seeding here starts a server and grants
     * tokens, so a half-finished seed is exactly the case that most needs tearing down.
     */
    @get:Rule(order = 2)
    val cleanUpAfterwards = cleanUpRule(hiltRule) {
        try {
            cloudSyncManager.stopSync()
            runBlocking {
                stopRecordingAndSettle(recorderFactory)
                // The loop cancels, but a Drive call already blocked in the transport is not
                // interruptible; wait for the server to go quiet rather than race it.
                awaitDriveQuiet()
            }
        } finally {
            scenario?.close()
            scenario = null
            recorderFactory.reset()
            // stop() first: it waits for the handlers, so nothing can repopulate the reset map.
            server.stop()
            server.reset()
            authorizationClient.reset()
            billing.setUnresolved()
            clearRecordedState(segmentDao)
            runBlocking {
                preferences.restoreAll()
                // Clears the session and the pinned account from DataStore. In the `finally` on
                // purpose: a pin surviving this class changes `authorizationRequest()` for every
                // later test in the process.
                driveAuthManager.signOut()
            }
        }
    }

    /**
     * Seeding happens here rather than in `@Before`: the activity is launched by the tests
     * themselves, but DataStore, the Hilt singletons and the fake server all have to be in a known
     * state before anything touches them, and `NavGraph` reads `disclaimerAccepted` on its first
     * composition.
     */
    @get:Rule(order = 3)
    val injectAndSeed = object : ExternalResource() {
        override fun before() {
            server.reset()
            server.start()
            authorizationClient.reset()
            billing.setStatus(SubscriptionStatus.ACTIVE)
            recorderFactory.reset()
            createNotificationChannels()
            runBlocking {
                preferences.capture(
                    appPreferences.disclaimerAccepted,
                    appPreferences::setDisclaimerAccepted
                )
                preferences.capture(
                    appPreferences.cloudUploadMode,
                    appPreferences::setCloudUploadMode
                )
                preferences.capture(
                    appPreferences.pendingRecoveryFolder,
                    appPreferences::setPendingRecoveryFolder
                )
                appPreferences.setDisclaimerAccepted(false)
                appPreferences.setCloudUploadMode(CloudUploadMode.OFF)
                appPreferences.setPendingRecoveryFolder(null)
                // Clears the session *and* the pinned account, so each test starts able to
                // connect whichever account it wants. These are @Singleton and outlive a test.
                driveAuthManager.signOut()
            }
            clearRecordedState(segmentDao)
        }
    }

    /**
     * The headline flow: a recording made on this device ends up in `BackRecorder/staging`, byte
     * for byte, and the row that tracks it is marked with the id Drive gave back.
     */
    @Test
    fun aRecordedSegmentUploadsIntoStagingThroughTheSyncLoop() = runBlocking {
        authorize()
        val segmentFile = recordOneSegment()

        appPreferences.setCloudUploadMode(CloudUploadMode.ALWAYS_ON)
        cloudSyncManager.startSync()

        awaitUntil("The segment never reached the fake Drive", LOOP_TIMEOUT_MS) {
            server.entries().any { it.name == segmentFile.name }
        }

        val root = requireFolder(parentId = null, name = ROOT_FOLDER)
        val staging = requireFolder(parentId = root.id, name = STAGING_FOLDER)
        assertEquals(
            "staging must sit directly under $ROOT_FOLDER",
            listOf(root.id),
            staging.parents.toList()
        )

        val uploaded = server.entries().single { it.name == segmentFile.name }
        assertEquals(
            "The segment must be uploaded into staging, not anywhere else",
            listOf(staging.id),
            uploaded.parents.toList()
        )
        // Proves the real client actually streamed the file, rather than only its metadata.
        assertTrue(
            "The uploaded bytes do not match the segment on disk",
            segmentFile.readBytes().contentEquals(uploaded.content ?: ByteArray(0))
        )

        awaitUntil("The segment row was never marked uploaded", LOOP_TIMEOUT_MS) {
            segmentDao.getSegmentsPendingUpload(10).isEmpty()
        }
        val row = segmentDao.getAllSegments().first()
            .single { it.filePath == segmentFile.absolutePath }
        assertTrue("Segment row is not marked uploaded", row.cloudUploaded)
        assertEquals("Segment row holds the wrong Drive id", uploaded.id, row.cloudFileId)
    }

    /**
     * A `recovery_<timestamp>` folder is a sibling of `staging`, never a child: everything inside
     * `staging` is deleted once it ages past the 24h buffer, so a recovery folder placed there is
     * deleted with its contents — exactly the recordings the user was just told had been preserved.
     *
     * What this asserts, precisely: the folder's parent is the root and not `staging`; each rescued
     * file lives only in the recovery folder; and neither the folder nor its contents appears in
     * the staging listing that buffer cleanup works from. The last part is then checked by running
     * cleanup's own predicate — list staging, delete what is past the cutoff — and confirming the
     * rescued files survive it.
     */
    @Test
    fun aRecoveryFolderIsCreatedBesideStagingAndIsOutOfCleanupsReach() = runBlocking {
        authorize()
        val folders = driveCloudStorage.ensureFolderStructure()
        assertNotNull("Could not resolve the Drive folder structure", folders)
        val rootId = folders!!.rootId
        val stagingId = folders.stagingId

        // Two days old and spanning two hours: past the 24h buffer and over the one-hour span that
        // makes CloudSyncManager rescue files instead of simply deleting them.
        val now = System.currentTimeMillis()
        val stale = listOf(
            server.seedFile("stale-a.m4a", stagingId, now - hours(48)),
            server.seedFile("stale-b.m4a", stagingId, now - hours(46))
        )

        appPreferences.setCloudUploadMode(CloudUploadMode.ALWAYS_ON)
        cloudSyncManager.startSync()

        awaitUntil("No recovery folder was ever created", LOOP_TIMEOUT_MS) {
            recoveryFolder() != null
        }
        val recovery = recoveryFolder()!!
        assertTrue(
            "Recovery folder '${recovery.name}' is not named recovery_<timestamp>",
            RECOVERY_FOLDER_NAME.matches(recovery.name)
        )

        assertEquals(
            "The recovery folder must be a sibling of staging, directly under $ROOT_FOLDER",
            listOf(rootId),
            recovery.parents.toList()
        )
        assertFalse(
            "The recovery folder is inside staging, where cleanup will delete it and everything " +
                "in it",
            stagingId in recovery.parents
        )

        awaitUntil("The stale files were never moved into the recovery folder", LOOP_TIMEOUT_MS) {
            stale.all { server.entry(it.id)?.parents?.contains(recovery.id) == true }
        }
        stale.forEach { file ->
            assertEquals(
                "A rescued file must live only in the recovery folder",
                listOf(recovery.id),
                server.entry(file.id)!!.parents.toList()
            )
        }

        // The listing buffer cleanup works from must not see the rescued files or their folder.
        val staging = driveCloudStorage.listStagingFiles().map { it.id }
        stale.forEach {
            assertFalse("A rescued file is still listed in staging", it.id in staging)
        }
        assertFalse("The recovery folder itself is listed in staging", recovery.id in staging)

        // Now do what cleanup does, for real, and check the rescue held.
        val cutoff = System.currentTimeMillis() - SegmentManager.BUFFER_DURATION_MS
        driveCloudStorage.listStagingFiles()
            .filter { it.createdTime < cutoff }
            .forEach { driveCloudStorage.deleteFile(it.id) }

        assertNotNull("Buffer cleanup deleted the recovery folder", server.entry(recovery.id))
        stale.forEach {
            assertNotNull(
                "Buffer cleanup deleted a recovered recording",
                server.entry(it.id)
            )
        }
    }

    /**
     * Drive answers 401 once a token is no longer good. The manager must force-refresh and retry
     * rather than replay the rejected token for the rest of its 45-minute cache window — which
     * would stop sync silently while the UI still reported the account as connected.
     */
    @Test
    fun aRejectedTokenIsRefreshedAndTheRequestRetried() = runBlocking {
        authorize()
        assertNotNull(
            "Could not resolve the Drive folder structure",
            driveCloudStorage.ensureFolderStructure()
        )

        val rejected = authorizationClient.currentToken()
        server.revokeToken(rejected)

        // Succeeds only if the 401 was answered with a fresh token and the request replayed.
        val listed = driveCloudStorage.listStagingFiles()
        assertEquals("The retried listing should see an empty staging folder", 0, listed.size)

        val calls = server.recordedCalls()
        assertTrue(
            "No request was ever rejected, so nothing exercised the refresh path",
            calls.any { it.status == 401 && it.bearerToken == rejected }
        )
        val refreshed = authorizationClient.currentToken()
        assertTrue("The manager reused the rejected token", refreshed != rejected)
        assertTrue(
            "No request succeeded with the refreshed token",
            calls.any { it.status == 200 && it.bearerToken == refreshed }
        )
        assertTrue("The grant should still be live", driveAuthManager.isAuthorized.value)

        // The issue asks for the upload to complete, not merely for the refresh to happen: an
        // upload is a resumable two-request exchange, so a refresh that works for a plain GET
        // does not by itself prove one works here.
        val file = File(appContext.cacheDir, "refreshed-upload.m4a").apply {
            writeBytes(ByteArray(2048) { it.toByte() })
        }
        try {
            val uploadedId = driveCloudStorage.uploadSegment(file)
            assertNotNull("The upload after the refresh did not complete", uploadedId)
            val staging = requireFolder(parentId = null, name = ROOT_FOLDER).let { root ->
                requireFolder(parentId = root.id, name = STAGING_FOLDER)
            }
            assertEquals(
                "The refreshed upload did not land in staging",
                listOf(staging.id),
                server.entry(uploadedId!!)!!.parents.toList()
            )
        } finally {
            file.delete()
        }
    }

    /**
     * `grantGeneration` changes whenever the grant does, and anything caching account-scoped Drive
     * state must compare it rather than trust callers to invalidate. Reconnecting to a different
     * account must therefore resolve that account's own folders: reusing the first account's ids
     * would write a user's recordings into an id the new Drive has never heard of.
     */
    @Test
    fun folderIdsAreNotReusedAfterTheGrantChangesAccount() = runBlocking {
        authorize()
        val first = driveCloudStorage.ensureFolderStructure()
        assertNotNull("Could not resolve the first account's folders", first)
        val firstGeneration = driveAuthManager.grantGeneration

        driveAuthManager.signOut()
        server.switchAccount("fake-account-2", "second@example.com")
        authorize()

        assertTrue(
            "grantGeneration did not change across a disconnect and reconnect",
            driveAuthManager.grantGeneration != firstGeneration
        )
        assertEquals(
            "The new account should have been pinned",
            "fake-account-2",
            appPreferences.driveAccountId.first()
        )

        val second = driveCloudStorage.ensureFolderStructure()
        assertNotNull("Could not resolve the second account's folders", second)
        assertTrue(
            "The second account reused the first account's root folder id",
            second!!.rootId != first!!.rootId
        )
        assertTrue(
            "The second account reused the first account's staging folder id",
            second.stagingId != first.stagingId
        )

        // The ids handed back must be real in the account now connected, and the old ones gone.
        assertNotNull("The new staging id does not exist on the new account", server.entry(second.stagingId))
        assertNull("The old staging id should not exist on the new account", server.entry(first.stagingId))
        assertEquals(
            "The new staging folder must still sit under $ROOT_FOLDER",
            listOf(second.rootId),
            server.entry(second.stagingId)!!.parents.toList()
        )
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun authorize() {
        authorizationClient.grantAccess()
        val outcome = driveAuthManager.authorize()
        assertEquals(
            "The fake grant should authorize without consent UI",
            DriveAuthManager.AuthorizationOutcome.Authorized,
            outcome
        )
    }

    /**
     * Records one real segment through [RecordingService] and the recorder fake, and returns the
     * finalized MP4. The activity is launched first because Android forbids starting a microphone
     * foreground service from the background.
     *
     * The segment is completed with a **flush**, not by stopping: `RecordingService` only
     * finalizes a segment when it rotates, so a recording that is merely stopped leaves its last
     * segment open and therefore never pending upload (jacoelt/backrecorder#51). Flushing is the
     * production path that completes a segment — it is what `SaveRecordingUseCase` does — so this
     * is also the shape of the flow a user's upload really travels.
     */
    private suspend fun recordOneSegment(): File {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        RecordingService.start(appContext)
        awaitUntil("RecordingService never started") { RecordingService.isRunning.value }

        recorderFactory.awaitRecorder(index = 0).feed(FED_DURATION_MS)

        // Give the service's flush collector time to subscribe: it comes up shortly after the
        // first segment opens, and a request emitted before then is dropped (#47). No wall-clock
        // alignment is needed — segment filenames carry the segment's id, so a rotation inside
        // the second the segment started cannot reuse and truncate its file (#46).
        delay(FLUSH_READY_MARGIN_MS)
        val flushed = withTimeoutOrNull(STATE_TIMEOUT_MS) { RecordingService.flushCurrentSegment() }
        checkNotNull(flushed) { "flushCurrentSegment() never completed" }

        val file = recorderFactory.awaitFinalizedFiles(count = 1).single()
        awaitUntil("The recorded segment never became pending upload") {
            segmentDao.getSegmentsPendingUpload(10).any { it.filePath == file.absolutePath }
        }

        stopRecordingAndSettle(recorderFactory)
        return file
    }

    private fun recoveryFolder(): FakeDriveServer.Entry? = server.entries()
        .firstOrNull {
            it.mimeType == FakeDriveServer.FOLDER_MIME_TYPE &&
                it.name.startsWith(RECOVERY_FOLDER_PREFIX)
        }

    private fun requireFolder(parentId: String?, name: String): FakeDriveServer.Entry {
        val match = server.entries().singleOrNull {
            it.mimeType == FakeDriveServer.FOLDER_MIME_TYPE &&
                it.name == name &&
                (parentId == null || parentId in it.parents)
        }
        return requireNotNull(match) { "No folder named '$name' was created" }
    }

    private fun hours(count: Long): Long = count * 60 * 60 * 1000L

    /**
     * Waits until the fake server has been idle for a continuous window.
     *
     * A single idle sample proves nothing: one sync tick makes several blocking Drive calls in a
     * row and is idle in the gaps between them, so sampling there would let teardown close the
     * server and clear the database underneath the next call.
     */
    private suspend fun awaitDriveQuiet() {
        var consecutive = 0
        awaitUntil("A Drive request was still in flight") {
            consecutive = if (server.isIdle()) consecutive + 1 else 0
            consecutive >= QUIET_POLLS
        }
    }

    private companion object {
        const val ROOT_FOLDER = "BackRecorder"
        const val STAGING_FOLDER = "staging"
        const val RECOVERY_FOLDER_PREFIX = "recovery_"

        /** `recovery_` plus the `yyyy-MM-dd_HH-mm` stamp CloudSyncManager formats. */
        val RECOVERY_FOLDER_NAME = Regex("""recovery_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}""")

        const val FED_DURATION_MS = 1_000L
        /**
         * Head start for the service's flush collector, which subscribes shortly after the first
         * segment opens; a request emitted before then is dropped and never completes (#47).
         * Removable once #47 lands.
         */
        const val FLUSH_READY_MARGIN_MS = 1_000L

        /**
         * Awaits that depend on CloudSyncManager's loop get their own, much longer budget.
         * SYNC_INTERVAL_MS is 30s and one failed tick backs off to 60s, so a 30s deadline would
         * fail the test on the first skipped or unlucky tick rather than on the behaviour.
         */
        const val LOOP_TIMEOUT_MS = 150_000L

        /** Consecutive idle polls — about a second at the shared poll interval — that count as quiet. */
        const val QUIET_POLLS = 20
    }
}
