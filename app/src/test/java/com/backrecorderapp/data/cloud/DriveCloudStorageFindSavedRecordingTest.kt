package com.backrecorderapp.data.cloud

import android.util.Log
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test

/**
 * `findSavedRecording` is what stops a retried saved-recording upload duplicating on Drive (#92).
 * It must ask for the copy *this* run created and nothing else: too loose and it reports another
 * recording's file as this one's backup, which loses a backup rather than a duplicate.
 */
class DriveCloudStorageFindSavedRecordingTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var drive: Drive
    private lateinit var files: Drive.Files
    private lateinit var listRequest: Drive.Files.List
    private lateinit var authManager: DriveAuthManager
    private lateinit var storage: DriveCloudStorage

    private val queries = mutableListOf<String>()
    private val responses = ArrayDeque<FileList>()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0

        drive = mockk()
        files = mockk()
        listRequest = mockk()
        authManager = mockk()

        every { authManager.drive } returns drive
        every { authManager.grantGeneration } returns 1

        every { drive.files() } returns files
        every { files.list() } returns listRequest
        every { listRequest.setFields(any()) } returns listRequest

        val query = slot<String>()
        every { listRequest.setQ(capture(query)) } answers {
            queries += query.captured
            listRequest
        }
        every { listRequest.execute() } answers { responses.removeFirst() }

        storage = DriveCloudStorage(authManager)

        // The two folder lookups ensureFolderStructure() makes before any of this runs.
        responses += fileList(listOf(entry("root-id")))
        responses += fileList(listOf(entry("staging-id")))
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun entry(id: String) =
        com.google.api.services.drive.model.File().apply { this.id = id }

    private fun fileList(entries: List<com.google.api.services.drive.model.File>) =
        FileList().apply { this.files = entries }

    /**
     * The other end of the handshake: a probe that asks for a run id finds nothing unless the
     * upload wrote that id onto the file, and a silent probe is a duplicate on every retry.
     */
    @Test
    fun `stamps the upload run's id onto the file it creates`() = runTest {
        val createRequest = mockk<Drive.Files.Create>()
        val metadata = slot<com.google.api.services.drive.model.File>()
        every { files.create(capture(metadata), any()) } returns createRequest
        every { createRequest.setFields(any()) } returns createRequest
        every { createRequest.execute() } returns entry("new-id")

        val uploaded = temporaryFolder.newFile("recording.m4a").apply { writeBytes(ByteArray(8)) }
        storage.uploadSavedRecording(uploaded, "My Recording.m4a", RUN_ID)

        assertEquals(mapOf("uploadRunId" to RUN_ID), metadata.captured.appProperties)
        assertEquals("My Recording.m4a", metadata.captured.name)
        assertEquals(listOf("root-id"), metadata.captured.parents)
    }

    @Test
    fun `finds the copy this upload run created`() = runTest {
        responses += fileList(listOf(entry("already-there")))

        assertEquals("already-there", storage.findSavedRecording(RUN_ID))
    }

    /**
     * The run id is the whole identity. Name and size would not do: the name is whatever the user
     * typed in the picker, and at a fixed bitrate with minute-quantised selections two different
     * recordings of equal duration can share a byte length.
     */
    @Test
    fun `asks for this run's id, inside the saved-recording folder, untrashed`() = runTest {
        responses += fileList(emptyList())

        storage.findSavedRecording(RUN_ID)

        val query = queries.last()
        assertTrue(query, query.contains("appProperties has { key='uploadRunId'"))
        assertTrue(query, query.contains("value='$RUN_ID' }"))
        assertTrue(query, query.contains("'root-id' in parents"))
        assertTrue(query, query.contains("trashed = false"))
    }

    @Test
    fun `reports no match when this run has nothing on Drive`() = runTest {
        responses += fileList(emptyList())

        assertNull(storage.findSavedRecording(RUN_ID))
    }

    @Test
    fun `reports no match when Drive answers with no file list at all`() = runTest {
        responses += FileList()

        assertNull(storage.findSavedRecording(RUN_ID))
    }

    /** A quote would otherwise break out of the literal and widen the query. */
    @Test
    fun `escapes a quote in the run id`() = runTest {
        responses += fileList(emptyList())

        storage.findSavedRecording("""run'id""")

        assertTrue(queries.last(), queries.last().contains("""run\'id"""))
    }

    private companion object {
        const val RUN_ID = "0d1f1e9c-0000-4000-8000-00000000abcd"
    }
}
