package com.backrecorderapp.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SegmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(segment: SegmentEntity)

    @Update
    suspend fun update(segment: SegmentEntity)

    @Delete
    suspend fun delete(segment: SegmentEntity)

    @Query("SELECT * FROM segments ORDER BY startTime ASC")
    fun getAllSegments(): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments ORDER BY startTime ASC")
    suspend fun getAllSegmentsList(): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE id = :id")
    suspend fun getById(id: String): SegmentEntity?

    @Query("SELECT * FROM segments WHERE startTime < :cutoffTime AND isComplete = 1")
    suspend fun getSegmentsOlderThan(cutoffTime: Long): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE startTime <= :endTime AND (endTime >= :startTime OR isComplete = 0) ORDER BY startTime ASC")
    suspend fun getSegmentsInRange(startTime: Long, endTime: Long): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE isComplete = 1 AND cloudUploaded = 0 ORDER BY startTime ASC")
    suspend fun getSegmentsPendingUpload(): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE isComplete = 1 ORDER BY startTime DESC LIMIT 1")
    suspend fun getLatestCompleteSegment(): SegmentEntity?

    @Query("SELECT * FROM segments WHERE isComplete = 1 ORDER BY startTime ASC LIMIT 1")
    suspend fun getOldestSegment(): SegmentEntity?

    @Query("SELECT COUNT(*) FROM segments WHERE isComplete = 1")
    suspend fun getCompleteSegmentCount(): Int

    @Query("DELETE FROM segments WHERE startTime < :cutoffTime AND isComplete = 1")
    suspend fun deleteSegmentsOlderThan(cutoffTime: Long)
}
