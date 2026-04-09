package com.backrecorderapp.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "segments")
data class SegmentEntity(
    @PrimaryKey
    val id: String,
    val filePath: String,
    val waveformPath: String,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val isComplete: Boolean = false,
    val cloudUploaded: Boolean = false,
    val cloudFileId: String? = null
)
