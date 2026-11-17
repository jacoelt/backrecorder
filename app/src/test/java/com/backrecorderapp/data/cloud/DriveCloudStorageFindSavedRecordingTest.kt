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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What `findSavedRecording` will and will not call a match decides whether a retried upload
 * duplicates the recording on Drive (#92) or silently drops the backup.
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

    private suspend fun find(name: String = "My Recording.m4a", size: Long = 4_096L) =
        storage.findSavedRecording(name, size, RUN_STARTED_AT)

    @Test
    fun `finds a copy of the same name and size in the Drive root`() = runTest {
        responses += fileList(listOf(entry("already-there", 4_096L)))

        assertEquals("already-there", find())
        assertTrue(
            "the lookup must be confined to the saved-recording folder",
            queries.last().contains("'root-id' in parents")
        )
        assertTrue("a name is only a match untrashed", queries.last().contains("trashed = false"))
    }

    /**
     * The clause that makes a false match impossible rather than unlikely: name is user-typed
     * and repeatable, and at this app's fixed bitrate two recordings of equal duration can share
     * a byte length, so only recency ties the hit to the run that is asking.
     */
    @Test
    fun `only considers files created since the upload run began`() = runTest {
        responses += fileList(emptyList())

        find()

        // Compared as an instant, not as text: toStringRfc3339 writes the local UTC offset.
        val stamp = Regex("""createdTime > '([^']+)'""").find(queries.last())?.groupValues?.get(1)
        assertEquals(
            "the query must exclude anything that predates the run: ${queries.last()}",
            RUN_STARTED_AT,
            DateTime(stamp).value
        )
    }

    @Test
    fun `ignores a file of the same name but a different size`() = runTest {
        responses += fileList(listOf(entry("an-older-recording", 128L)))

        assertNull(find())
    }

    @Test
    fun `ignores an entry Drive reports no size for`() = runTest {
        responses += fileList(listOf(entry("sizeless", null)))

        assertNull(find())
    }

    /** The realistic shape once a name has been reused: scan past the mismatch, don't stop at it. */
    @Test
    fun `picks the matching size out of several entries of that name`() = runTest {
        responses += fileList(listOf(entry("wrong-size", 128L), entry("the-one", 4_096L)))

        assertEquals("the-one", find())
    }

    @Test
    fun `reports no match when the folder holds nothing of that name`() = runTest {
        responses += fileList(emptyList())

        assertNull(find())
    }

    /** A quote in the name would otherwise break out of the literal and match another folder. */
    @Test
    fun `escapes a quote in the recording's name`() = runTest {
        responses += fileList(emptyList())

        find(name = "Tom's Recording.m4a")

        assertTrue(queries.last().contains("""Tom\'s Recording.m4a"""))
    }

    private companion object {
        /** 2026-01-02T03:04:05Z, so the formatted instant is assertable. */
        const val RUN_STARTED_AT = 1_767_323_045_000L
    }
}
