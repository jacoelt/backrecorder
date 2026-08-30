package com.backrecorderapp.data.cloud

import android.util.Log
import com.google.api.client.util.DateTime
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.FileList
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Guards the `listStagingFiles()` pagination. A full 24h buffer is ~1440 one-minute segments,
 * so an unpaginated listing silently hides everything past the first page and cloud cleanup
 * never catches up.
 *
 * Every test carries a timeout: the failure mode being guarded against is a loop that never
 * terminates, and MockK replays the last stubbed page forever, so a regression would hang the
 * build rather than fail it.
 */
class DriveCloudStoragePaginationTest {

    private companion object {
        private const val TEST_TIMEOUT_MS = 5_000L
    }

    private lateinit var drive: Drive
    private lateinit var files: Drive.Files
    private lateinit var listRequest: Drive.Files.List
    private lateinit var authManager: DriveAuthManager
    private lateinit var storage: DriveCloudStorage

    private val pageTokens = mutableListOf<String?>()
    private val queries = mutableListOf<String>()

    @Before
    fun setup() {
        // listStagingFiles() logs when it skips a malformed entry, and the unit-test android.jar
        // throws rather than returning a default.
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        drive = mockk()
        files = mockk()
        listRequest = mockk()
        authManager = mockk()

        every { authManager.drive } returns drive
        every { authManager.grantGeneration } returns 1

        every { drive.files() } returns files
        every { files.list() } returns listRequest
        every { listRequest.setFields(any()) } returns listRequest
        every { listRequest.setOrderBy(any()) } returns listRequest
        every { listRequest.setPageSize(any()) } returns listRequest

        val query = slot<String>()
        every { listRequest.setQ(capture(query)) } answers {
            queries += query.captured
            listRequest
        }

        val token = slot<String?>()
        every { listRequest.setPageToken(captureNullable(token)) } answers {
            pageTokens += token.captured
            listRequest
        }

        storage = DriveCloudStorage(authManager)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun page(ids: List<String>, nextToken: String?): FileList {
        val fileList = FileList()
        fileList.files = ids.mapIndexed { index, id ->
            com.google.api.services.drive.model.File().apply {
                this.id = id
                this.createdTime = DateTime(1_000L + index)
            }
        }
        fileList.nextPageToken = nextToken
        return fileList
    }

    /** Folder resolution issues two lookups (root, then staging) before any listing. */
    private fun folderLookups(): List<FileList> = listOf(
        page(listOf("root-folder-id"), null),
        page(listOf("staging-folder-id"), null)
    )

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `follows nextPageToken until the listing is exhausted`() = runTest {
        val firstPage = page((0 until 1000).map { "file_$it" }, "page-2")
        val secondPage = page((1000 until 1440).map { "file_$it" }, null)

        every { listRequest.execute() } returnsMany
            folderLookups() + listOf(firstPage, secondPage)

        storage.ensureFolderStructure()
        val result = storage.listStagingFiles()

        assertEquals(1440, result.size)
        assertEquals("file_0", result.first().id)
        assertEquals("file_1439", result.last().id)
        assertTrue(
            "second page must be fetched with the token from the first",
            pageTokens.contains("page-2")
        )
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `stops after a single page when no token is returned`() = runTest {
        val onlyPage = page(listOf("file_a", "file_b"), null)

        every { listRequest.execute() } returnsMany folderLookups() + listOf(onlyPage)

        storage.ensureFolderStructure()
        val result = storage.listStagingFiles()

        assertEquals(2, result.size)
        verify(exactly = 1) { listRequest.setPageSize(any()) }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `treats an empty nextPageToken as the end of the listing`() = runTest {
        // Drive can answer with an empty string rather than omitting the field; looping on it
        // would re-request page one forever.
        val onlyPage = page(listOf("file_a"), "")

        every { listRequest.execute() } returnsMany folderLookups() + listOf(onlyPage)

        storage.ensureFolderStructure()
        val result = storage.listStagingFiles()

        assertEquals(1, result.size)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `returns nothing when no account is authorized`() = runTest {
        every { authManager.drive } returns null

        val result = storage.listStagingFiles()

        assertEquals(emptyList<DriveFileInfo>(), result)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `excludes folders from the staging listing`() = runTest {
        // A recovery folder listed here would be treated as an aged segment and deleted along
        // with every recording inside it, so the exclusion has to be in the query itself.
        every { listRequest.execute() } returnsMany
            folderLookups() + listOf(page(listOf("file_a"), null))

        storage.ensureFolderStructure()
        storage.listStagingFiles()

        assertTrue(
            "staging query must exclude folders, was: ${queries.last()}",
            queries.last().contains("mimeType != 'application/vnd.google-apps.folder'")
        )
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `skips files with no createdTime rather than dating them to the epoch`() = runTest {
        val mixedPage = FileList().apply {
            files = listOf(
                com.google.api.services.drive.model.File().apply {
                    id = "dated"
                    createdTime = DateTime(1_000L)
                },
                com.google.api.services.drive.model.File().apply { id = "undated" }
            )
            nextPageToken = null
        }

        every { listRequest.execute() } returnsMany folderLookups() + listOf(mixedPage)

        storage.ensureFolderStructure()
        val result = storage.listStagingFiles()

        // An epoch default would make a freshly uploaded segment look older than the whole
        // buffer window, and cleanup would delete it on the very next pass.
        assertEquals(1, result.size)
        assertEquals("dated", result.first().id)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `handles a page with no files array`() = runTest {
        val emptyPage = FileList().apply {
            files = null
            nextPageToken = null
        }

        every { listRequest.execute() } returnsMany folderLookups() + listOf(emptyPage)

        storage.ensureFolderStructure()
        val result = storage.listStagingFiles()

        assertEquals(emptyList<DriveFileInfo>(), result)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `re-resolves the folders when the grant changes`() = runTest {
        // Folder ids belong to the account that granted access. Reusing them across a reconnect
        // would target the previous account's Drive, which the new grant cannot even see.
        every { listRequest.execute() } returnsMany folderLookups() + folderLookups()

        every { authManager.grantGeneration } returns 1
        val first = storage.ensureFolderStructure()

        every { authManager.grantGeneration } returns 2
        val second = storage.ensureFolderStructure()

        assertEquals("staging-folder-id", first?.stagingId)
        assertEquals("staging-folder-id", second?.stagingId)
        // Four lookups: two per resolution, so the second one really did go back to Drive
        // instead of handing back the previous account's cached ids.
        verify(exactly = 4) { listRequest.setFields("files(id)") }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `reuses the cached folders while the grant is unchanged`() = runTest {
        every { listRequest.execute() } returnsMany folderLookups()

        storage.ensureFolderStructure()
        storage.ensureFolderStructure()

        verify(exactly = 2) { listRequest.setFields("files(id)") }
    }
}
