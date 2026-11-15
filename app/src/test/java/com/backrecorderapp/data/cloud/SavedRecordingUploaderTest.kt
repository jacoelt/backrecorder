package com.backrecorderapp.data.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.billing.SubscriptionStatus
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.domain.SubscriptionGate
import com.google.api.client.http.HttpHeaders
import com.google.api.client.http.HttpResponseException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The saved-recording Drive copy (issue #84).
 *
 * Three properties matter more than the happy path and are what most of these pin: the upload
 * never runs for someone who is not entitled to it or who turned uploads off; a file handed over
 * is always cleaned up afterwards - success, refusal or exhausted retries alike - because the
 * caller has already let go of it; and a failure the user *was* entitled to is always reported,
 * including the second of two identical ones, which a conflating `StateFlow` used to swallow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedRecordingUploaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var capabilities: NetworkCapabilities
    private lateinit var uploadFailureNotifier: UploadFailureNotifier
    private lateinit var driveCloudStorage: DriveCloudStorage
    private lateinit var appPreferences: AppPreferences
    private lateinit var billingRepository: BillingRepository
    private lateinit var subscriptionGate: SubscriptionGate
    private lateinit var statusResolved: MutableStateFlow<Boolean>

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0

        context = mockk(relaxed = true)
        every { context.cacheDir } returns temporaryFolder.root
        every { context.getString(any(), *anyVararg()) } answers {
            "upload of ${secondArg<Array<Any>>().firstOrNull()} failed"
        }

        connectivityManager = mockk()
        capabilities = mockk()
        uploadFailureNotifier = mockk(relaxed = true)
        every { context.getSystemService(ConnectivityManager::class.java) } returns
            connectivityManager
        every { connectivityManager.activeNetwork } returns mockk()
        every { connectivityManager.getNetworkCapabilities(any()) } returns capabilities
        every { capabilities.hasTransport(any()) } returns true
        every { capabilities.hasCapability(any()) } returns true

        driveCloudStorage = mockk(relaxed = true)
        every { driveCloudStorage.isInitialized } returns true

        appPreferences = mockk(relaxed = true)
        every { appPreferences.cloudUploadMode } returns flowOf(CloudUploadMode.WIFI_ONLY)

        billingRepository = mockk(relaxed = true)
        statusResolved = MutableStateFlow(true)
        every { billingRepository.statusResolved } returns statusResolved
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE
        subscriptionGate = SubscriptionGate(billingRepository)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun buildUploader(scope: CoroutineScope) = SavedRecordingUploader(
        context,
        driveCloudStorage,
        appPreferences,
        subscriptionGate,
        uploadFailureNotifier,
        scope
    )

    /**
     * Runs [body] with a live collector on [SavedRecordingUploader.uploadFailures] and hands back
     * everything it reported. The collector is started first and drained at the end because the
     * channel's delivery is itself under test — reading the flow only afterwards would hide an
     * ordering or buffering bug.
     */
    private fun TestScope.withFailureCollector(
        uploader: SavedRecordingUploader,
        body: () -> Unit
    ): List<String> {
        val reported = mutableListOf<String>()
        val collector = launch { uploader.uploadFailures.collect { reported += it } }
        advanceUntilIdle()

        body()
        advanceUntilIdle()

        collector.cancel()
        return reported
    }

    /** A finished recording, in a directory the uploader does not own. */
    private fun sourceFile(name: String = "My Recording.m4a"): File {
        val dir = File(temporaryFolder.root, "source").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(RECORDING_BYTES) }
    }

    private fun stagingDir() = File(temporaryFolder.root, "saved_uploads")

    private fun httpError(status: Int, body: String) =
        HttpResponseException.Builder(status, "failed", HttpHeaders())
            .setContent(body)
            .build()

    @Test
    fun `uploads a saved recording to the Drive root under the name the user chose`() = runTest {
        // Asserted inside the stub: the staged copy must still exist, with the recording's bytes,
        // at the moment Drive reads it — the whole point of taking the file over by rename.
        coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } answers {
            val staged = firstArg<File>()
            assertTrue("the staged file must exist during the upload", staged.exists())
            assertTrue(
                "the staged file must hold the recording's bytes",
                staged.readBytes().contentEquals(RECORDING_BYTES)
            )
            "drive-id"
        }
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val reported = withFailureCollector(uploader) {
            uploader.enqueue(sourceFile(), "My Recording.m4a")
        }

        coVerify(exactly = 1) {
            driveCloudStorage.uploadSavedRecording(any(), "My Recording.m4a")
        }
        assertTrue("a successful upload must report nothing", reported.isEmpty())
    }

    @Test
    fun `does not upload when the user turned cloud uploads off`() = runTest {
        every { appPreferences.cloudUploadMode } returns flowOf(CloudUploadMode.OFF)
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val reported = withFailureCollector(uploader) {
            uploader.enqueue(sourceFile(), "My Recording.m4a")
        }

        coVerify(exactly = 0) { driveCloudStorage.uploadSavedRecording(any(), any()) }
        assertTrue("a refusal must be silent", reported.isEmpty())
    }

    @Test
    fun `does not upload for a free user`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.FREE
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val reported = withFailureCollector(uploader) {
            uploader.enqueue(sourceFile(), "My Recording.m4a")
        }

        coVerify(exactly = 0) { driveCloudStorage.uploadSavedRecording(any(), any()) }
        // A refusal is not a failure: the user was never entitled to the copy, so nothing is said.
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `does not upload when Drive is not connected`() = runTest {
        every { driveCloudStorage.isInitialized } returns false
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

        uploader.enqueue(sourceFile(), "My Recording.m4a")
        advanceUntilIdle()

        coVerify(exactly = 0) { driveCloudStorage.uploadSavedRecording(any(), any()) }
    }

    @Test
    fun `does not upload over cellular when the user chose Wi-Fi only`() = runTest {
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

        uploader.enqueue(sourceFile(), "My Recording.m4a")
        advanceUntilIdle()

        coVerify(exactly = 0) { driveCloudStorage.uploadSavedRecording(any(), any()) }
    }

    @Test
    fun `waits for billing to answer rather than refusing a subscriber Play was slow about`() =
        runTest {
            statusResolved.value = false
            coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } returns "drive-id"
            val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

            uploader.enqueue(sourceFile(), "My Recording.m4a")
            advanceTimeBy(5_000)
            coVerify(exactly = 0) { driveCloudStorage.uploadSavedRecording(any(), any()) }

            statusResolved.value = true
            advanceUntilIdle()

            coVerify(exactly = 1) { driveCloudStorage.uploadSavedRecording(any(), any()) }
        }

    @Test
    fun `gives up silently when billing never answers`() = runTest {
        statusResolved.value = false
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val reported = withFailureCollector(uploader) {
            uploader.enqueue(sourceFile(), "My Recording.m4a")
        }

        coVerify(exactly = 0) { driveCloudStorage.uploadSavedRecording(any(), any()) }
        // Entitlement was never established, so this is a refusal rather than a failure.
        assertTrue(reported.isEmpty())
        assertTrue("no staged file may survive", stagingDir().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `retries a failing upload and reports nothing once one attempt succeeds`() = runTest {
        var attempts = 0
        coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } answers {
            attempts++
            if (attempts < 2) throw RuntimeException("transient") else "drive-id"
        }
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val reported = withFailureCollector(uploader) {
            uploader.enqueue(sourceFile(), "My Recording.m4a")
        }

        assertEquals(2, attempts)
        assertTrue(reported.isEmpty())
        verify(exactly = 0) { uploadFailureNotifier.notifyUploadFailed(any()) }
    }

    @Test
    fun `gives up after three attempts and reports the failure in app and as a notification`() =
        runTest {
            coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } throws
                RuntimeException("Drive is down")
            val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))
            val reported = withFailureCollector(uploader) {
                uploader.enqueue(sourceFile(), "My Recording.m4a")
            }

            coVerify(exactly = SavedRecordingUploader.MAX_ATTEMPTS) {
                driveCloudStorage.uploadSavedRecording(any(), any())
            }
            assertEquals(1, reported.size)
            verify(exactly = 1) { uploadFailureNotifier.notifyUploadFailed(any()) }
        }

    @Test
    fun `reports a Drive refusal that comes back as no file id`() = runTest {
        coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } returns null
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val reported = withFailureCollector(uploader) {
            uploader.enqueue(sourceFile(), "My Recording.m4a")
        }

        coVerify(exactly = SavedRecordingUploader.MAX_ATTEMPTS) {
            driveCloudStorage.uploadSavedRecording(any(), any())
        }
        assertEquals(1, reported.size)
    }

    /**
     * The regression this suite exists for. Two saves of the same selection produce the same
     * display name, so both failures carry identical text; a conflating `StateFlow` dropped the
     * second and the user was told about one of two lost backups.
     */
    @Test
    fun `reports both failures when two uploads of the same name fail`() = runTest {
        coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } throws
            RuntimeException("Drive is down")
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val reported = withFailureCollector(uploader) {
            uploader.enqueue(sourceFile("a.m4a"), "My Recording.m4a")
            uploader.enqueue(sourceFile("b.m4a"), "My Recording.m4a")
        }

        assertEquals("both failures must reach the user", 2, reported.size)
        assertEquals(reported[0], reported[1])
        verify(exactly = 2) { uploadFailureNotifier.notifyUploadFailed(any()) }
    }

    @Test
    fun `stops retrying when Drive rejects the grant`() = runTest {
        coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } throws httpError(401, "")
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val reported = withFailureCollector(uploader) {
            uploader.enqueue(sourceFile(), "My Recording.m4a")
        }

        // Asking again a second later cannot restore a revoked grant.
        coVerify(exactly = 1) { driveCloudStorage.uploadSavedRecording(any(), any()) }
        assertEquals(1, reported.size)
    }

    /**
     * A throttle is transient and, unlike the sync loop, this path has no later tick to pick it
     * up - so it is retried rather than treated as terminal, just not promptly.
     */
    @Test
    fun `keeps retrying a throttle, after a longer wait`() = runTest {
        coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } throws
            httpError(403, """{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}""")
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

        uploader.enqueue(sourceFile(), "My Recording.m4a")
        advanceUntilIdle()

        coVerify(exactly = SavedRecordingUploader.MAX_ATTEMPTS) {
            driveCloudStorage.uploadSavedRecording(any(), any())
        }
    }

    @Test
    fun `treats a plain 403 as a dead grant and stops`() = runTest {
        coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } throws
            httpError(403, """{"error":{"errors":[{"reason":"insufficientPermissions"}]}}""")
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

        uploader.enqueue(sourceFile(), "My Recording.m4a")
        advanceUntilIdle()

        coVerify(exactly = 1) { driveCloudStorage.uploadSavedRecording(any(), any()) }
    }

    @Test
    fun `stops retrying when the Drive account is out of space`() = runTest {
        coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } throws
            httpError(403, """{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""")
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

        uploader.enqueue(sourceFile(), "My Recording.m4a")
        advanceUntilIdle()

        coVerify(exactly = 1) { driveCloudStorage.uploadSavedRecording(any(), any()) }
    }

    @Test
    fun `stops retrying when the user leaves Wi-Fi part way through`() = runTest {
        coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } answers {
            every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
            throw RuntimeException("transient")
        }
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

        val reported = withFailureCollector(uploader) {
            uploader.enqueue(sourceFile(), "My Recording.m4a")
        }

        // The remaining attempts would have gone out over cellular against the user's setting.
        coVerify(exactly = 1) { driveCloudStorage.uploadSavedRecording(any(), any()) }
        // Losing the transport is not a choice the user made, so they are still told.
        assertEquals(1, reported.size)
    }

    /**
     * Turning uploads off mid-retry is the user declining the copy, so it ends silently - unlike
     * losing the transport, which is not a decision they made.
     */
    @Test
    fun `says nothing when the user turns uploads off part way through`() = runTest {
        val mode = MutableStateFlow(CloudUploadMode.WIFI_ONLY)
        every { appPreferences.cloudUploadMode } returns mode
        coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } answers {
            mode.value = CloudUploadMode.OFF
            throw RuntimeException("transient")
        }
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

        val reported = withFailureCollector(uploader) {
            uploader.enqueue(sourceFile(), "My Recording.m4a")
        }

        coVerify(exactly = 1) { driveCloudStorage.uploadSavedRecording(any(), any()) }
        assertTrue("declining the copy must be silent", reported.isEmpty())
    }

    /** The in-app half must survive the notification half failing, or a failure goes unreported. */
    @Test
    fun `still reports in app when the notification cannot be posted`() = runTest {
        coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } throws
            RuntimeException("Drive is down")
        every { uploadFailureNotifier.notifyUploadFailed(any()) } throws
            SecurityException("notifications are blocked")
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

        val reported = withFailureCollector(uploader) {
            uploader.enqueue(sourceFile(), "My Recording.m4a")
        }

        assertEquals(1, reported.size)
    }

    @Test
    fun `tells the user the same thing in the app and in the notification`() = runTest {
        coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } throws
            RuntimeException("Drive is down")
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

        val reported = withFailureCollector(uploader) {
            uploader.enqueue(sourceFile(), "My Recording.m4a")
        }

        verify(exactly = 1) { uploadFailureNotifier.notifyUploadFailed(reported.single()) }
    }

    @Test
    fun `runs one upload at a time however many saves arrive`() = runTest {
        var inFlight = 0
        var maxInFlight = 0
        coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } coAnswers {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            delay(1_000)
            inFlight--
            "drive-id"
        }
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

        uploader.enqueue(sourceFile("a.m4a"), "a.m4a")
        uploader.enqueue(sourceFile("b.m4a"), "b.m4a")
        uploader.enqueue(sourceFile("c.m4a"), "c.m4a")
        advanceUntilIdle()

        assertEquals("uploads must not run in parallel", 1, maxInFlight)
        coVerify(exactly = 3) { driveCloudStorage.uploadSavedRecording(any(), any()) }
    }

    @Test
    fun `takes the file out of the caller's hands so the save's cleanup cannot delete it`() =
        runTest {
            coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } returns "drive-id"
            val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))
            val source = sourceFile()

            uploader.enqueue(source, "My Recording.m4a")

            // The save's `finally` runs the moment enqueue returns, before the upload coroutine
            // has had a chance to start. The file it would delete is already gone from there.
            assertFalse("the source file must have been moved away", source.exists())

            advanceUntilIdle()
            coVerify { driveCloudStorage.uploadSavedRecording(any(), "My Recording.m4a") }
        }

    @Test
    fun `deletes the staged copy whatever the outcome`() = runTest {
        coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } throws
            RuntimeException("Drive is down")
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

        uploader.enqueue(sourceFile(), "My Recording.m4a")
        advanceUntilIdle()

        assertTrue(
            "no staged file may survive the upload",
            stagingDir().listFiles().orEmpty().isEmpty()
        )
    }

    @Test
    fun `deletes the staged copy when the upload is refused outright`() = runTest {
        every { appPreferences.cloudUploadMode } returns flowOf(CloudUploadMode.OFF)
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

        uploader.enqueue(sourceFile(), "My Recording.m4a")
        advanceUntilIdle()

        assertTrue(
            "a refused upload must not leave cache litter",
            stagingDir().listFiles().orEmpty().isEmpty()
        )
    }

    @Test
    fun `sweeps a staged file an earlier process death abandoned, keeping recent ones`() =
        runTest {
            coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } returns "drive-id"
            val staging = stagingDir().apply { mkdirs() }
            val abandoned = File(staging, "abandoned.m4a").apply {
                writeBytes(ByteArray(4))
                setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
            }
            val recent = File(staging, "recent.m4a").apply { writeBytes(ByteArray(4)) }

            val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))
            uploader.enqueue(sourceFile(), "My Recording.m4a")
            advanceUntilIdle()

            assertFalse("the abandoned staged file must be swept", abandoned.exists())
            assertTrue("a recent staged file belongs to a live upload", recent.exists())
        }

    private companion object {
        val RECORDING_BYTES = ByteArray(16) { it.toByte() }
    }
}
