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

        /** Immutable and thread-safe: segment files are named from `Dispatchers.Default`. */
        private val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
                .withZone(ZoneId.systemDefault())
    }

    private val segmentDir: File
        get() = File(context.cacheDir, SEGMENT_DIR).also { it.mkdirs() }

    private val waveformDir: File
        get() = File(context.cacheDir, WAVEFORM_DIR).also { it.mkdirs() }

    /**
     * A segment's id, start time and both of its file paths, minted together so they cannot
     * disagree. [createSegmentEntry] takes this whole object rather than loose strings, which is
     * what keeps a row's `filePath` pointing at the file its own id named.
     */
    data class NewSegment(
        val id: String,
        val startTime: Long,
        val audioFile: File,
        val waveformFile: File
    )

    /**
     * Reserves the id and both paths for a segment starting at [startTime].
     *
     * The filenames carry the id, not just the timestamp. A timestamp alone is second-resolution,
     * and a flush rotates a segment on demand: rotating one in the second it started would hand
     * the replacement the path of the segment just finalized, and its muxer would truncate that
     * audio to nothing while the finalized row still pointed there (#46).
     */
    fun prepareSegment(startTime: Long): NewSegment {
        val id = UUID.randomUUID().toString()
        val timestamp = DATE_FORMAT.format(Instant.ofEpochMilli(startTime))
        return NewSegment(
            id = id,
            startTime = startTime,
            audioFile = File(segmentDir, "segment_${timestamp}_$id.m4a"),
            waveformFile = File(waveformDir, "waveform_${timestamp}_$id.bin")
        )
    }

    suspend fun createSegmentEntry(newSegment: NewSegment): SegmentEntity {
        val segment = SegmentEntity(
            id = newSegment.id,
            filePath = newSegment.audioFile.absolutePath,
            waveformPath = newSegment.waveformFile.absolutePath,
            startTime = newSegment.startTime,
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

    /**
     * Removes a segment's row and both of its files.
     *
     * For a segment that was reserved but never actually recorded: `pruneOldSegments` only deletes
     * *complete* rows, so without this an abandoned segment's row and its empty `.m4a` would stay
     * in the database for good, and `getSegmentsInRange` matches every incomplete row forever.
     */
    suspend fun discardSegment(segmentId: String) {
        val segment = segmentDao.getById(segmentId) ?: return
        File(segment.filePath).delete()
        File(segment.waveformPath).delete()
        segmentDao.delete(segment)
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
