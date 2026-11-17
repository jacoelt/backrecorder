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
import org.junit.Test

/**
 * `findSavedRecording` is what stops a retried saved-recording upload duplicating the file on
 * Drive (issue #92), so what it will and will not call a match is the whole of its value: too
 * loose and it reports a different recording as this one's backup, dropping the copy silently.
 */
class DriveCloudStorageFindSavedRecordingTest {

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
        responses += fileList(listOf(entry("root-id", null)))
        responses += fileList(listOf(entry("staging-id", null)))
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun entry(id: String, size: Long?) =
        com.google.api.services.drive.model.File().apply {
            this.id = id
            // setSize, not `size =`: the model extends AbstractMap, whose own `size` shadows it.
            setSize(size)
        }

    private fun fileList(entries: List<com.google.api.services.drive.model.File>) =
        FileList().apply { this.files = entries }

    @Test
    fun `finds a copy of the same name and size in the Drive root`() = runTest {
        responses += fileList(listOf(entry("already-there", 4_096L)))

        assertEquals("already-there", storage.findSavedRecording("My Recording.m4a", 4_096L))
        assertTrue(
            "the lookup must be confined to the saved-recording folder",
            queries.last().contains("'root-id' in parents")
        )
        assertTrue(
            "a name is only a match untrashed",
            queries.last().contains("trashed = false")
        )
    }

    @Test
    fun `ignores a file of the same name but a different size`() = runTest {
        responses += fileList(listOf(entry("an-older-recording", 128L)))

        assertNull(storage.findSavedRecording("My Recording.m4a", 4_096L))
    }

    @Test
    fun `ignores an entry Drive reports no size for`() = runTest {
        responses += fileList(listOf(entry("sizeless", null)))

        assertNull(storage.findSavedRecording("My Recording.m4a", 4_096L))
    }

    @Test
    fun `reports no match when the folder holds nothing of that name`() = runTest {
        responses += fileList(emptyList())

        assertNull(storage.findSavedRecording("My Recording.m4a", 4_096L))
    }

    /** A quote in the name would otherwise break out of the literal and match another folder. */
    @Test
    fun `escapes a quote in the recording's name`() = runTest {
        responses += fileList(emptyList())

        storage.findSavedRecording("Tom's Recording.m4a", 4_096L)

        assertTrue(queries.last().contains("""Tom\'s Recording.m4a"""))
    }
}
