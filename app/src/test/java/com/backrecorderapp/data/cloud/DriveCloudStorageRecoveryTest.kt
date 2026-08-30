package com.backrecorderapp.data.cloud

import android.util.Log
import com.google.api.client.http.HttpResponseException
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.FileList
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.google.api.services.drive.model.File as DriveFile

/**
 * Guards where recovered recordings are put, and what happens when a move only partly succeeds.
 *
 * The recovery folder must be a sibling of `staging`, not a child of it: anything inside staging
 * is fair game for `listStagingFiles()` and the 24h buffer cleanup, so a recovery folder placed
 * there is deleted - with every recording in it - one day after the user is told those recordings
 * were preserved.
 */
class DriveCloudStorageRecoveryTest {

    private companion object {
        private const val TEST_TIMEOUT_MS = 5_000L
        private const val ROOT_ID = "root-folder-id"
        private const val STAGING_ID = "staging-folder-id"
        private const val RECOVERY_ID = "recovery-folder-id"
    }

    private lateinit var drive: Drive
    private lateinit var files: Drive.Files
    private lateinit var listRequest: Drive.Files.List
    private lateinit var createRequest: Drive.Files.Create
    private lateinit var getRequest: Drive.Files.Get
    private lateinit var updateRequest: Drive.Files.Update
    private lateinit var authManager: DriveAuthManager
    private lateinit var storage: DriveCloudStorage

    private val createdFolder = slot<DriveFile>()
    private val addedParents = mutableListOf<String>()
    private val removedParents = mutableListOf<String>()

    private fun folderPage(id: String?): FileList = FileList().apply {
        files = if (id == null) emptyList() else listOf(DriveFile().apply { this.id = id })
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        drive = mockk()
        files = mockk()
        listRequest = mockk()
        createRequest = mockk()
        getRequest = mockk()
        updateRequest = mockk()
        authManager = mockk()

        every { authManager.drive } returns drive
        every { authManager.grantGeneration } returns 1

        every { drive.files() } returns files
        every { files.list() } returns listRequest
        every { listRequest.setQ(any()) } returns listRequest
        every { listRequest.setFields(any()) } returns listRequest
        // Root and staging already exist; the recovery folder does not, so it gets created.
        every { listRequest.execute() } returnsMany listOf(
            folderPage(ROOT_ID),
            folderPage(STAGING_ID),
            folderPage(null)
        )

        every { files.create(capture(createdFolder)) } returns createRequest
        every { createRequest.setFields(any()) } returns createRequest
        every { createRequest.execute() } returns DriveFile().apply { id = RECOVERY_ID }

        every { files.get(any()) } returns getRequest
        every { getRequest.setFields(any()) } returns getRequest

        every { files.update(any(), null) } returns updateRequest
        val added = slot<String>()
        every { updateRequest.setAddParents(capture(added)) } answers {
            addedParents += added.captured
            updateRequest
        }
        val removed = slot<String>()
        every { updateRequest.setRemoveParents(capture(removed)) } answers {
            removedParents += removed.captured
            updateRequest
        }
        every { updateRequest.execute() } returns DriveFile()

        storage = DriveCloudStorage(authManager)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `creates the recovery folder outside staging`() = runTest {
        every { getRequest.execute() } returns DriveFile().apply { parents = listOf(STAGING_ID) }

        val handled = storage.moveToRecoveryFolder(listOf("file_a"), "recovery_2026-08-30_10-00")

        assertEquals(1, handled)
        assertEquals("recovery_2026-08-30_10-00", createdFolder.captured.name)
        assertEquals(
            "the recovery folder must be a sibling of staging, not a child of it",
            listOf(ROOT_ID),
            createdFolder.captured.parents
        )
        assertEquals(listOf(RECOVERY_ID), addedParents)
        assertEquals(listOf(STAGING_ID), removedParents)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `counts a parentless file as handled so it cannot block completion forever`() = runTest {
        // Reported as un-handled it would keep the caller at "incomplete", which re-runs the
        // whole stale scan on every pass and never lets cleanup resume.
        every { getRequest.execute() } returns DriveFile().apply { parents = null }

        val handled = storage.moveToRecoveryFolder(listOf("file_a"), "recovery_x")

        assertEquals(1, handled)
        assertTrue("nothing should have been moved", addedParents.isEmpty())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `keeps going after a per-file failure`() = runTest {
        every { getRequest.execute() } returnsMany listOf(
            DriveFile().apply { parents = listOf(STAGING_ID) },
            DriveFile().apply { parents = listOf(STAGING_ID) }
        )
        val notFound = mockk<HttpResponseException>()
        every { notFound.statusCode } returns 404
        every { notFound.content } returns "notFound"
        every { updateRequest.execute() } throws notFound andThen DriveFile()

        val handled = storage.moveToRecoveryFolder(listOf("file_a", "file_b"), "recovery_x")

        assertEquals("the second file must still be attempted", 1, handled)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `stops the whole run on an auth failure instead of burning through every file`() =
        runTest {
            every { getRequest.execute() } returns
                DriveFile().apply { parents = listOf(STAGING_ID) }
            val forbidden = mockk<HttpResponseException>()
            every { forbidden.statusCode } returns 403
            every { forbidden.content } returns "insufficientPermissions"
            every { updateRequest.execute() } throws forbidden

            var thrown: HttpResponseException? = null
            try {
                storage.moveToRecoveryFolder(listOf("file_a", "file_b"), "recovery_x")
            } catch (e: HttpResponseException) {
                thrown = e
            }

            assertEquals("the auth failure must reach the sync loop", 403, thrown?.statusCode)
        }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `does nothing when no account is authorized`() = runTest {
        every { authManager.drive } returns null

        val handled = storage.moveToRecoveryFolder(listOf("file_a"), "recovery_x")

        assertEquals(0, handled)
    }
}
