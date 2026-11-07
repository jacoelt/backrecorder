package com.backrecorderapp.data.segment

import android.content.Context
import com.backrecorderapp.data.db.SegmentDao
import com.backrecorderapp.data.db.SegmentEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SegmentManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var segmentDao: SegmentDao
    private lateinit var segmentManager: SegmentManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        segmentDao = mockk(relaxed = true)

        every { context.cacheDir } returns tempFolder.root

        segmentManager = SegmentManager(context, segmentDao)
    }

    @Test
    fun `createSegmentFile generates correct filename`() {
        val timestamp = 1712678400000L // known timestamp
        val file = segmentManager.createSegmentFile(timestamp, segmentManager.newSegmentId())

        assertTrue(file.name.startsWith("segment_"))
        assertTrue(file.name.endsWith(".m4a"))
    }

    @Test
    fun `createWaveformFile generates correct filename`() {
        val timestamp = 1712678400000L
        val file = segmentManager.createWaveformFile(timestamp, segmentManager.newSegmentId())

        assertTrue(file.name.startsWith("waveform_"))
        assertTrue(file.name.endsWith(".bin"))
    }

    /**
     * The regression guard for #46: a flush rotates a segment on demand, so a replacement can open
     * in the very millisecond its predecessor started. Second-resolution timestamps gave both the
     * same path, and the replacement's muxer truncated the audio just finalized.
     */
    @Test
    fun `two segments starting in the same millisecond get distinct paths`() {
        val startTime = 1712678400000L

        val firstId = segmentManager.newSegmentId()
        val secondId = segmentManager.newSegmentId()
        assertNotEquals("Segment ids must be unique", firstId, secondId)

        val firstAudio = segmentManager.createSegmentFile(startTime, firstId)
        val secondAudio = segmentManager.createSegmentFile(startTime, secondId)
        val firstWaveform = segmentManager.createWaveformFile(startTime, firstId)
        val secondWaveform = segmentManager.createWaveformFile(startTime, secondId)

        assertNotEquals(
            "Same-millisecond segments share an audio path",
            firstAudio.absolutePath,
            secondAudio.absolutePath
        )
        assertNotEquals(
            "Same-millisecond segments share a waveform path",
            firstWaveform.absolutePath,
            secondWaveform.absolutePath
        )
    }

    /** The same id must name the row and both files, or a row points at a file nothing wrote. */
    @Test
    fun `segment and waveform files are named from the segment id`() {
        val startTime = 1712678400000L
        val id = segmentManager.newSegmentId()

        assertTrue(segmentManager.createSegmentFile(startTime, id).name.contains(id))
        assertTrue(segmentManager.createWaveformFile(startTime, id).name.contains(id))
    }

    @Test
    fun `createSegmentEntry inserts into database`() = runTest {
        val slot = slot<SegmentEntity>()
        coEvery { segmentDao.insert(capture(slot)) } returns Unit

        val segment = segmentManager.createSegmentEntry(
            id = "given-id",
            filePath = "/test/segment.m4a",
            waveformPath = "/test/waveform.bin",
            startTime = 1000L
        )

        assertEquals("given-id", slot.captured.id)

        assertEquals("/test/segment.m4a", slot.captured.filePath)
        assertEquals(1000L, slot.captured.startTime)
        assertFalse(slot.captured.isComplete)
        assertEquals(0L, slot.captured.endTime)
    }

    @Test
    fun `finalizeSegment marks segment as complete`() = runTest {
        val existingSegment = SegmentEntity(
            id = "test-id",
            filePath = "/test/segment.m4a",
            waveformPath = "/test/waveform.bin",
            startTime = 1000L,
            endTime = 0L,
            durationMs = 0L,
            isComplete = false
        )

        coEvery { segmentDao.getById("test-id") } returns existingSegment

        val updateSlot = slot<SegmentEntity>()
        coEvery { segmentDao.update(capture(updateSlot)) } returns Unit

        segmentManager.finalizeSegment("test-id", 61000L)

        assertTrue(updateSlot.captured.isComplete)
        assertEquals(61000L, updateSlot.captured.endTime)
        assertEquals(60000L, updateSlot.captured.durationMs)
    }

    @Test
    fun `pruneOldSegments deletes segments older than 24h`() = runTest {
        val oldSegment = SegmentEntity(
            id = "old-id",
            filePath = tempFolder.newFile("old_segment.m4a").absolutePath,
            waveformPath = tempFolder.newFile("old_waveform.bin").absolutePath,
            startTime = 0L,
            endTime = 60000L,
            durationMs = 60000L,
            isComplete = true
        )

        coEvery { segmentDao.getSegmentsOlderThan(any()) } returns listOf(oldSegment)

        segmentManager.pruneOldSegments()

        coVerify { segmentDao.delete(oldSegment) }
    }

    @Test
    fun `getSegmentsInRange delegates to dao`() = runTest {
        val segments = listOf(
            SegmentEntity(
                id = "1", filePath = "", waveformPath = "",
                startTime = 1000, endTime = 61000, durationMs = 60000, isComplete = true
            )
        )
        coEvery { segmentDao.getSegmentsInRange(500, 62000) } returns segments

        val result = segmentManager.getSegmentsInRange(500, 62000)
        assertEquals(1, result.size)
        assertEquals("1", result[0].id)
    }

    @Test
    fun `segment duration constant is 1 minute`() {
        assertEquals(60_000L, SegmentManager.SEGMENT_DURATION_MS)
    }

    @Test
    fun `buffer duration constant is 24 hours`() {
        assertEquals(24 * 60 * 60 * 1000L, SegmentManager.BUFFER_DURATION_MS)
    }
}
