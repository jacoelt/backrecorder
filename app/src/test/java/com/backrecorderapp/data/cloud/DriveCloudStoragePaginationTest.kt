package com.backrecorderapp.data.cloud

import com.google.api.client.util.DateTime
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.FileList
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Guards the `listStagingFiles()` pagination. A full 24h buffer is ~1440 one-minute segments,
 * so an unpaginated listing silently hides everything past the first page and cloud cleanup
 * never catches up. This regressed once during the rewrite - hence the dedicated test.
 */
class DriveCloudStoragePaginationTest {

    private lateinit var drive: Drive
    private lateinit var files: Drive.Files
    private lateinit var listRequest: Drive.Files.List
    private lateinit var authManager: DriveAuthManager
    private lateinit var storage: DriveCloudStorage

    private val pageTokens = mutableListOf<String?>()

    @Before
    fun setup() {
        drive = mockk()
        files = mockk()
        listRequest = mockk()
        authManager = mockk()

        every { authManager.drive } returns drive

        every { drive.files() } returns files
        every { files.list() } returns listRequest
        every { listRequest.setQ(any()) } returns listRequest
        every { listRequest.setFields(any()) } returns listRequest
        every { listRequest.setOrderBy(any()) } returns listRequest
        every { listRequest.setPageSize(any()) } returns listRequest

        val token = slot<String?>()
        every { listRequest.setPageToken(captureNullable(token)) } answers {
            pageTokens += token.captured
            listRequest
        }

        storage = DriveCloudStorage(authManager)
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

    @Test
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

    @Test
    fun `stops after a single page when no token is returned`() = runTest {
        val onlyPage = page(listOf("file_a", "file_b"), null)

        every { listRequest.execute() } returnsMany folderLookups() + listOf(onlyPage)

        storage.ensureFolderStructure()
        val result = storage.listStagingFiles()

        assertEquals(2, result.size)
        verify(exactly = 1) { listRequest.setPageSize(any()) }
    }

    @Test
    fun `treats an empty nextPageToken as the end of the listing`() = runTest {
        // Drive can answer with an empty string rather than omitting the field; looping on it
        // would re-request page one forever.
        val onlyPage = page(listOf("file_a"), "")

        every { listRequest.execute() } returnsMany folderLookups() + listOf(onlyPage)

        storage.ensureFolderStructure()
        val result = storage.listStagingFiles()

        assertEquals(1, result.size)
    }

    @Test
    fun `returns nothing when no account is authorized`() = runTest {
        every { authManager.drive } returns null

        val result = storage.listStagingFiles()

        assertEquals(emptyList<DriveFileInfo>(), result)
    }
}
