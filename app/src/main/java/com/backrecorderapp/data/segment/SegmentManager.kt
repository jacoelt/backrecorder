package com.backrecorderapp.data.segment

import android.content.Context
import com.backrecorderapp.data.db.SegmentDao
import com.backrecorderapp.data.db.SegmentEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

        /**
         * Immutable, so it is safe to share: segment files are named from `Dispatchers.Default`,
         * where the `SimpleDateFormat` this replaces was a data race.
         */
        private val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
                .withZone(ZoneId.systemDefault())
    }

    private val segmentDir: File
        get() = File(context.cacheDir, SEGMENT_DIR).also { it.mkdirs() }

    private val waveformDir: File
        get() = File(context.cacheDir, WAVEFORM_DIR).also { it.mkdirs() }

    /**
     * The id a segment's row and both of its files share. Generate it before the files so their
     * names can carry it: a timestamp alone is only second-resolution, and a flush that rotates a
     * segment in the same second it started would otherwise hand the replacement the path of the
     * segment just finalized, whose audio its muxer then truncates (#46).
     */
    fun newSegmentId(): String = UUID.randomUUID().toString()

    /** Named from [segmentId], so two segments opened in the same second cannot share a path. */
    fun createSegmentFile(startTime: Long, segmentId: String): File {
        val timestamp = DATE_FORMAT.format(Instant.ofEpochMilli(startTime))
        return File(segmentDir, "segment_${timestamp}_$segmentId.m4a")
    }

    /** Named from [segmentId], so two segments opened in the same second cannot share a path. */
    fun createWaveformFile(startTime: Long, segmentId: String): File {
        val timestamp = DATE_FORMAT.format(Instant.ofEpochMilli(startTime))
        return File(waveformDir, "waveform_${timestamp}_$segmentId.bin")
    }

    suspend fun createSegmentEntry(
        id: String,
        filePath: String,
        waveformPath: String,
        startTime: Long
    ): SegmentEntity {
        val segment = SegmentEntity(
            id = id,
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

    suspend fun markAsUploaded(segmentId: String, cloudFileId: String) {
        val segment = segmentDao.getById(segmentId) ?: return
        segmentDao.update(segment.copy(cloudUploaded = true, cloudFileId = cloudFileId))
    }
}
