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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The saved-recording Drive copy (issue #84).
 *
 * Two properties matter more than the happy path and are what most of these pin: the upload never
 * runs for someone who is not entitled to it or who turned uploads off, and a file handed over is
 * always cleaned up afterwards - success, refusal or exhausted retries alike - because the caller
 * has already let go of it.
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
        every { context.getString(any(), *anyVararg()) } returns "upload failed"

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
        every { billingRepository.statusResolved } returns MutableStateFlow(true)
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

    /** A finished recording, in a directory the uploader does not own. */
    private fun sourceFile(name: String = "My Recording.m4a"): File {
        val dir = File(temporaryFolder.root, "source").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(ByteArray(16) { 1 }) }
    }

    @Test
    fun `uploads a saved recording to the Drive root under the name the user chose`() = runTest {
        coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } returns "drive-id"
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

        uploader.enqueue(sourceFile(), "My Recording.m4a")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            driveCloudStorage.uploadSavedRecording(any(), "My Recording.m4a")
        }
        assertNull(uploader.uploadFailure.value)
    }

    @Test
    fun `does not upload when the user turned cloud uploads off`() = runTest {
        every { appPreferences.cloudUploadMode } returns flowOf(CloudUploadMode.OFF)
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

        uploader.enqueue(sourceFile(), "My Recording.m4a")
        advanceUntilIdle()

        coVerify(exactly = 0) { driveCloudStorage.uploadSavedRecording(any(), any()) }
        assertNull(uploader.uploadFailure.value)
    }

    @Test
    fun `does not upload for a free user`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.FREE
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

        uploader.enqueue(sourceFile(), "My Recording.m4a")
        advanceUntilIdle()

        coVerify(exactly = 0) { driveCloudStorage.uploadSavedRecording(any(), any()) }
        // A refusal is not a failure: the user was never entitled to the copy, so nothing is said.
        assertNull(uploader.uploadFailure.value)
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
    fun `retries a failing upload and reports nothing once one attempt succeeds`() = runTest {
        var attempts = 0
        coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } answers {
            attempts++
            if (attempts < 2) throw RuntimeException("transient") else "drive-id"
        }
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

        uploader.enqueue(sourceFile(), "My Recording.m4a")
        advanceUntilIdle()

        assertEquals(2, attempts)
        assertNull(uploader.uploadFailure.value)
        verify(exactly = 0) { uploadFailureNotifier.notifyUploadFailed(any()) }
    }

    @Test
    fun `gives up after three attempts and reports the failure in app and as a notification`() =
        runTest {
            coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } throws
                RuntimeException("Drive is down")
            val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

            uploader.enqueue(sourceFile(), "My Recording.m4a")
            advanceUntilIdle()

            coVerify(exactly = SavedRecordingUploader.MAX_ATTEMPTS) {
                driveCloudStorage.uploadSavedRecording(any(), any())
            }
            assertNotNull(uploader.uploadFailure.value)
            verify(exactly = 1) { uploadFailureNotifier.notifyUploadFailed(any()) }
        }

    @Test
    fun `dismissing the failure clears it so a later save is not shown a stale message`() =
        runTest {
            coEvery { driveCloudStorage.uploadSavedRecording(any(), any()) } throws
                RuntimeException("Drive is down")
            val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

            uploader.enqueue(sourceFile(), "My Recording.m4a")
            advanceUntilIdle()
            uploader.dismissUploadFailure()

            assertNull(uploader.uploadFailure.value)
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

        val stagedFiles = File(temporaryFolder.root, "saved_uploads").listFiles().orEmpty()
        assertTrue("no staged file may survive the upload", stagedFiles.isEmpty())
    }

    @Test
    fun `deletes the staged copy when the upload is refused outright`() = runTest {
        every { appPreferences.cloudUploadMode } returns flowOf(CloudUploadMode.OFF)
        val uploader = buildUploader(CoroutineScope(StandardTestDispatcher(testScheduler)))

        uploader.enqueue(sourceFile(), "My Recording.m4a")
        advanceUntilIdle()

        val stagedFiles = File(temporaryFolder.root, "saved_uploads").listFiles().orEmpty()
        assertTrue("a refused upload must not leave cache litter", stagedFiles.isEmpty())
    }
}
