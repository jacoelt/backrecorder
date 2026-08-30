package com.backrecorderapp.data.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.backrecorderapp.data.db.SegmentEntity
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.data.segment.SegmentManager
import com.backrecorderapp.domain.BlockReason
import com.backrecorderapp.domain.GateResult
import com.backrecorderapp.domain.SubscriptionGate
import com.google.api.client.http.HttpHeaders
import com.google.api.client.http.HttpResponseException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Drives the sync loop itself on a virtual clock.
 *
 * The loop is the part of this feature that historically failed silently: it can look alive while
 * uploading nothing, keep hammering Drive after the grant is gone, or delete recordings it was
 * meant to rescue. None of that is visible from a single-call unit test, so these tests advance
 * time through real ticks and assert on what the loop actually did to Drive.
 *
 * `CloudSyncManager` takes its dispatcher via `@IoDispatcher`, which is what lets `delay()` here
 * resolve against `runTest`'s scheduler instead of costing 30 real seconds per tick.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudSyncManagerTest {

    private companion object {
        private const val TICK_MS = 30_000L
        private const val BUFFER_MS = 24 * 60 * 60 * 1000L
        private const val HOUR_MS = 60 * 60 * 1000L
    }

    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var capabilities: NetworkCapabilities
    private lateinit var driveCloudStorage: DriveCloudStorage
    private lateinit var segmentManager: SegmentManager
    private lateinit var appPreferences: AppPreferences
    private lateinit var subscriptionGate: SubscriptionGate

    private lateinit var uploadMode: MutableStateFlow<CloudUploadMode>
    private lateinit var statusResolved: MutableStateFlow<Boolean>

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        context = mockk(relaxed = true)
        connectivityManager = mockk(relaxed = true)
        capabilities = mockk(relaxed = true)
        driveCloudStorage = mockk(relaxed = true)
        segmentManager = mockk(relaxed = true)
        appPreferences = mockk(relaxed = true)
        subscriptionGate = mockk(relaxed = true)

        every { context.getSystemService(ConnectivityManager::class.java) } returns
            connectivityManager
        every { connectivityManager.activeNetwork } returns mockk(relaxed = true)
        every { connectivityManager.getNetworkCapabilities(any()) } returns capabilities
        onWifi()

        uploadMode = MutableStateFlow(CloudUploadMode.WIFI_ONLY)
        statusResolved = MutableStateFlow(true)

        every { appPreferences.cloudUploadMode } returns uploadMode
        every { subscriptionGate.statusResolved } returns statusResolved
        coEvery { subscriptionGate.checkCloudAccess() } returns GateResult.Allowed
        coEvery { appPreferences.pendingRecoveryFolder } returns MutableStateFlow(null)

        every { driveCloudStorage.isInitialized } returns true
        coEvery { driveCloudStorage.listStagingFiles() } returns emptyList()
        coEvery { segmentManager.getSegmentsPendingUpload(any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun onWifi() {
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        every { capabilities.hasCapability(any()) } returns true
    }

    private fun onCellular() {
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
        every { capabilities.hasCapability(any()) } returns true
    }

    private fun segment(id: String) = mockk<SegmentEntity>(relaxed = true).also {
        every { it.id } returns id
        // A real temp file so the loop's File(...).exists() guard passes.
        every { it.filePath } returns File.createTempFile("seg_$id", ".m4a")
            .also { f -> f.deleteOnExit() }.absolutePath
    }

    private fun httpError(status: Int, content: String): HttpResponseException =
        HttpResponseException.Builder(status, "test", HttpHeaders())
            .setContent(content)
            .build()

    private fun manager(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        CloudSyncManager(
            context = context,
            driveCloudStorage = driveCloudStorage,
            segmentManager = segmentManager,
            appPreferences = appPreferences,
            subscriptionGate = subscriptionGate,
            ioDispatcher = StandardTestDispatcher(scheduler)
        )

    @Test
    fun `uploads pending segments and records the cloud id`() = runTest {
        coEvery { segmentManager.getSegmentsPendingUpload(any()) } returns listOf(segment("a"))
        coEvery { driveCloudStorage.uploadSegment(any()) } returns "cloud-a"

        val sync = manager(testScheduler)
        sync.startSync()
        advanceTimeBy(1)

        coVerify(exactly = 1) { segmentManager.markAsUploaded("a", "cloud-a") }
        sync.stopSync()
    }

    @Test
    fun `does not upload while the subscription gate is blocked`() = runTest {
        coEvery { subscriptionGate.checkCloudAccess() } returns
            GateResult.Blocked(BlockReason.CLOUD_STORAGE_PREMIUM_ONLY)
        coEvery { segmentManager.getSegmentsPendingUpload(any()) } returns listOf(segment("a"))

        val sync = manager(testScheduler)
        sync.startSync()
        advanceTimeBy(TICK_MS * 3)

        coVerify(exactly = 0) { driveCloudStorage.uploadSegment(any()) }
        // Critically, a blocked gate must not rewrite the user's preference: at launch billing
        // has not answered yet, and persisting OFF there erased paying subscribers' settings.
        coVerify(exactly = 0) { appPreferences.setCloudUploadMode(any()) }
        sync.stopSync()
    }

    @Test
    fun `waits for billing to answer before acting on the gate`() = runTest {
        statusResolved.value = false
        coEvery { segmentManager.getSegmentsPendingUpload(any()) } returns listOf(segment("a"))

        val sync = manager(testScheduler)
        sync.startSync()
        advanceTimeBy(TICK_MS * 2)
        coVerify(exactly = 0) { driveCloudStorage.uploadSegment(any()) }

        // Once billing answers, the very next tick proceeds - no restart needed.
        coEvery { driveCloudStorage.uploadSegment(any()) } returns "cloud-a"
        statusResolved.value = true
        advanceTimeBy(TICK_MS * 2)

        coVerify(atLeast = 1) { driveCloudStorage.uploadSegment(any()) }
        sync.stopSync()
    }

    @Test
    fun `does not upload on cellular while the mode is wifi only`() = runTest {
        onCellular()
        coEvery { segmentManager.getSegmentsPendingUpload(any()) } returns listOf(segment("a"))

        val sync = manager(testScheduler)
        sync.startSync()
        advanceTimeBy(TICK_MS * 3)

        coVerify(exactly = 0) { driveCloudStorage.uploadSegment(any()) }
        sync.stopSync()
    }

    @Test
    fun `asks the database for at most one tick worth of segments`() = runTest {
        val sync = manager(testScheduler)
        sync.startSync()
        advanceTimeBy(1)

        // Materialising all ~1440 backlog rows to upload 20 of them is pure churn.
        coVerify { segmentManager.getSegmentsPendingUpload(20) }
        sync.stopSync()
    }

    @Test
    fun `a 401 asks the user to reconnect`() = runTest {
        coEvery { segmentManager.getSegmentsPendingUpload(any()) } returns listOf(segment("a"))
        coEvery { driveCloudStorage.uploadSegment(any()) } throws httpError(401, "unauthorized")

        val sync = manager(testScheduler)
        sync.startSync()
        advanceTimeBy(1)

        assertTrue(sync.needsReauth.value)
        assertFalse(sync.storageFull.value)
        sync.stopSync()
    }

    @Test
    fun `a full Drive is reported as full, not as an authorization problem`() = runTest {
        // Bundling this with the transient rate-limit reasons made the loop back off in silence
        // while the buffer kept evicting recordings that were never uploaded.
        coEvery { segmentManager.getSegmentsPendingUpload(any()) } returns listOf(segment("a"))
        coEvery { driveCloudStorage.uploadSegment(any()) } throws
            httpError(403, """{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""")

        val sync = manager(testScheduler)
        sync.startSync()
        advanceTimeBy(1)

        assertTrue(sync.storageFull.value)
        assertFalse(sync.needsReauth.value)
        sync.stopSync()
    }

    @Test
    fun `throttling neither prompts for re-auth nor reports a full Drive`() = runTest {
        coEvery { segmentManager.getSegmentsPendingUpload(any()) } returns listOf(segment("a"))
        coEvery { driveCloudStorage.uploadSegment(any()) } throws
            httpError(403, """{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}""")

        val sync = manager(testScheduler)
        sync.startSync()
        advanceTimeBy(1)

        assertFalse(sync.needsReauth.value)
        assertFalse(sync.storageFull.value)
        sync.stopSync()
    }

    @Test
    fun `does not delete aged files while a recovery move is unfinished`() = runTest {
        // The regression this guards: cleanup deletes on the same "older than the buffer"
        // predicate the recovery scan uses, so running it after a partial move destroys exactly
        // the recordings still queued to be rescued.
        val now = System.currentTimeMillis()
        val stale = listOf(
            DriveFileInfo("old_1", now - BUFFER_MS - 5 * HOUR_MS),
            DriveFileInfo("old_2", now - BUFFER_MS - 2 * HOUR_MS)
        )
        coEvery { driveCloudStorage.listStagingFiles() } returns stale
        coEvery { driveCloudStorage.moveToRecoveryFolder(any(), any()) } returns 0

        val sync = manager(testScheduler)
        sync.startSync()
        advanceTimeBy(TICK_MS * 3)

        coVerify(exactly = 0) { driveCloudStorage.deleteFile(any()) }
        sync.stopSync()
    }

    @Test
    fun `backs off instead of retrying a failing tick every interval`() = runTest {
        // A whole-tick failure, not a per-segment one: the listing is what every pass depends on.
        coEvery { driveCloudStorage.listStagingFiles() } throws httpError(500, "backendError")

        val sync = manager(testScheduler)
        sync.startSync()

        // Ticks land at 0, then +60s, +120s ... rather than every 30s.
        advanceTimeBy(1)
        coVerify(exactly = 1) { driveCloudStorage.listStagingFiles() }

        advanceTimeBy(TICK_MS)
        coVerify(exactly = 1) { driveCloudStorage.listStagingFiles() }

        advanceTimeBy(TICK_MS)
        coVerify(exactly = 2) { driveCloudStorage.listStagingFiles() }

        sync.stopSync()
    }

    @Test
    fun `one unusable segment does not stall the tick or trigger a back-off`() = runTest {
        // A single corrupt or rejected segment must not hold up the rest of the backlog, and
        // must not push the whole loop into a 15-minute back-off.
        coEvery { segmentManager.getSegmentsPendingUpload(any()) } returns
            listOf(segment("bad"), segment("good"))
        coEvery { driveCloudStorage.uploadSegment(any()) } throws
            httpError(500, "backendError") andThen "cloud-good"

        val sync = manager(testScheduler)
        sync.startSync()
        advanceTimeBy(1)

        // The second segment was still attempted...
        coVerify(exactly = 2) { driveCloudStorage.uploadSegment(any()) }
        coVerify(exactly = 1) { segmentManager.markAsUploaded("good", "cloud-good") }

        // ...and the next tick still lands on the normal interval.
        advanceTimeBy(TICK_MS)
        coVerify(exactly = 4) { driveCloudStorage.uploadSegment(any()) }

        sync.stopSync()
    }

    @Test
    fun `stopSync clears the prompts so they cannot outlive the account`() = runTest {
        coEvery { segmentManager.getSegmentsPendingUpload(any()) } returns listOf(segment("a"))
        coEvery { driveCloudStorage.uploadSegment(any()) } throws httpError(401, "unauthorized")

        val sync = manager(testScheduler)
        sync.startSync()
        advanceTimeBy(1)
        assertTrue(sync.needsReauth.value)

        sync.stopSync()

        assertFalse(sync.needsReauth.value)
        assertFalse(sync.storageFull.value)
    }

    @Test
    fun `racing starts leave exactly one loop, and stopSync ends it`() = runTest {
        coEvery { segmentManager.getSegmentsPendingUpload(any()) } returns listOf(segment("a"))
        coEvery { driveCloudStorage.uploadSegment(any()) } returns "cloud-a"

        val sync = manager(testScheduler)
        sync.startSync()
        sync.startSync()
        advanceTimeBy(1)

        // Two loops would upload twice per tick, and an orphaned one would survive the stop.
        coVerify(exactly = 1) { driveCloudStorage.uploadSegment(any()) }

        sync.stopSync()
        advanceTimeBy(TICK_MS * 5)

        coVerify(exactly = 1) { driveCloudStorage.uploadSegment(any()) }
    }
}
