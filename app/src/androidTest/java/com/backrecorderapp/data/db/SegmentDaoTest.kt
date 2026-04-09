package com.backrecorderapp.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SegmentDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: SegmentDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        dao = database.segmentDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertAndRetrieveSegment() = runTest {
        val segment = SegmentEntity(
            id = "test-1",
            filePath = "/test/segment.m4a",
            waveformPath = "/test/waveform.bin",
            startTime = 1000L,
            endTime = 61000L,
            durationMs = 60000L,
            isComplete = true
        )

        dao.insert(segment)
        val retrieved = dao.getById("test-1")

        assertNotNull(retrieved)
        assertEquals("test-1", retrieved!!.id)
        assertEquals(1000L, retrieved.startTime)
        assertEquals(true, retrieved.isComplete)
    }

    @Test
    fun getSegmentsInRange() = runTest {
        val segments = listOf(
            makeSegment("1", startTime = 1000, endTime = 61000),
            makeSegment("2", startTime = 61000, endTime = 121000),
            makeSegment("3", startTime = 121000, endTime = 181000),
            makeSegment("4", startTime = 181000, endTime = 241000)
        )
        segments.forEach { dao.insert(it) }

        // Query range that overlaps segments 2 and 3
        val result = dao.getSegmentsInRange(70000, 150000)

        assertEquals(2, result.size)
        assertEquals("2", result[0].id)
        assertEquals("3", result[1].id)
    }

    @Test
    fun getSegmentsOlderThan() = runTest {
        dao.insert(makeSegment("old", startTime = 1000, endTime = 61000))
        dao.insert(makeSegment("new", startTime = 500000, endTime = 560000))

        val old = dao.getSegmentsOlderThan(100000)
        assertEquals(1, old.size)
        assertEquals("old", old[0].id)
    }

    @Test
    fun deleteSegmentsOlderThan() = runTest {
        dao.insert(makeSegment("old", startTime = 1000, endTime = 61000))
        dao.insert(makeSegment("new", startTime = 500000, endTime = 560000))

        dao.deleteSegmentsOlderThan(100000)

        assertNull(dao.getById("old"))
        assertNotNull(dao.getById("new"))
    }

    @Test
    fun getSegmentsPendingUpload() = runTest {
        dao.insert(makeSegment("uploaded", startTime = 1000, endTime = 61000, cloudUploaded = true))
        dao.insert(makeSegment("pending", startTime = 61000, endTime = 121000, cloudUploaded = false))

        val pending = dao.getSegmentsPendingUpload()
        assertEquals(1, pending.size)
        assertEquals("pending", pending[0].id)
    }

    @Test
    fun getAllSegmentsFlow() = runTest {
        dao.insert(makeSegment("1", startTime = 2000, endTime = 62000))
        dao.insert(makeSegment("2", startTime = 1000, endTime = 61000))

        val segments = dao.getAllSegments().first()
        assertEquals(2, segments.size)
        // Should be ordered by startTime ASC
        assertEquals("2", segments[0].id)
        assertEquals("1", segments[1].id)
    }

    @Test
    fun updateSegment() = runTest {
        val segment = makeSegment("1", startTime = 1000, endTime = 0, isComplete = false)
        dao.insert(segment)

        dao.update(segment.copy(endTime = 61000, durationMs = 60000, isComplete = true))

        val updated = dao.getById("1")!!
        assertEquals(61000L, updated.endTime)
        assertTrue(updated.isComplete)
    }

    @Test
    fun getLatestCompleteSegment() = runTest {
        dao.insert(makeSegment("1", startTime = 1000, endTime = 61000))
        dao.insert(makeSegment("2", startTime = 61000, endTime = 121000))
        dao.insert(makeSegment("3", startTime = 121000, endTime = 0, isComplete = false))

        val latest = dao.getLatestCompleteSegment()
        assertNotNull(latest)
        assertEquals("2", latest!!.id)
    }

    private fun makeSegment(
        id: String,
        startTime: Long,
        endTime: Long,
        isComplete: Boolean = true,
        cloudUploaded: Boolean = false
    ) = SegmentEntity(
        id = id,
        filePath = "/test/$id.m4a",
        waveformPath = "/test/$id.bin",
        startTime = startTime,
        endTime = endTime,
        durationMs = if (endTime > 0) endTime - startTime else 0,
        isComplete = isComplete,
        cloudUploaded = cloudUploaded
    )
}
