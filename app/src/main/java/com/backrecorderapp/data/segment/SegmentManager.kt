package com.backrecorderapp.data.segment

import android.content.Context
import com.backrecorderapp.data.db.SegmentDao
import com.backrecorderapp.data.db.SegmentEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SegmentManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val segmentDao: SegmentDao
) {
    companion object {
        const val SEGMENT_DURATION_MS = 60_000L // 1 minute
        const val BUFFER_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val SEGMENT_DIR = "segments"
        private const val WAVEFORM_DIR = "waveforms"
        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    private val segmentDir: File
        get() = File(context.cacheDir, SEGMENT_DIR).also { it.mkdirs() }

    private val waveformDir: File
        get() = File(context.cacheDir, WAVEFORM_DIR).also { it.mkdirs() }

    fun createSegmentFile(startTime: Long): File {
        val timestamp = DATE_FORMAT.format(Date(startTime))
        return File(segmentDir, "segment_$timestamp.m4a")
    }

    fun createWaveformFile(startTime: Long): File {
        val timestamp = DATE_FORMAT.format(Date(startTime))
        return File(waveformDir, "waveform_$timestamp.bin")
    }

    suspend fun createSegmentEntry(filePath: String, waveformPath: String, startTime: Long): SegmentEntity {
        val segment = SegmentEntity(
            id = UUID.randomUUID().toString(),
            filePath = filePath,
            waveformPath = waveformPath,
            startTime = startTime,
            endTime = 0,
            durationMs = 0,
            isComplete = false
        )
        segmentDao.insert(segment)
        return segment
    }

    suspend fun finalizeSegment(segmentId: String, endTime: Long) {
        val segment = segmentDao.getById(segmentId) ?: return
        segmentDao.update(
            segment.copy(
                endTime = endTime,
                durationMs = endTime - segment.startTime,
                isComplete = true
            )
        )
    }

    suspend fun pruneOldSegments() {
        val cutoff = System.currentTimeMillis() - BUFFER_DURATION_MS
        val oldSegments = segmentDao.getSegmentsOlderThan(cutoff)
        for (segment in oldSegments) {
            File(segment.filePath).delete()
            File(segment.waveformPath).delete()
            segmentDao.delete(segment)
        }
    }

    suspend fun getSegmentsInRange(startTime: Long, endTime: Long): List<SegmentEntity> {
        return segmentDao.getSegmentsInRange(startTime, endTime)
    }

    fun getAllSegments(): Flow<List<SegmentEntity>> {
        return segmentDao.getAllSegments()
    }

    /** Oldest first, capped: a full 24h backlog is ~1440 rows and a sync tick uploads a handful. */
    suspend fun getSegmentsPendingUpload(limit: Int): List<SegmentEntity> {
        return segmentDao.getSegmentsPendingUpload(limit)
    }

    suspend fun countSegmentsPendingUpload(): Int {
        return segmentDao.countSegmentsPendingUpload()
    }

    suspend fun markAsUploaded(segmentId: String, cloudFileId: String) {
        val segment = segmentDao.getById(segmentId) ?: return
        segmentDao.update(segment.copy(cloudUploaded = true, cloudFileId = cloudFileId))
    }
}
