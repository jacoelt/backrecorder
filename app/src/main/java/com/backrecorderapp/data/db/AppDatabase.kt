package com.backrecorderapp.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SegmentEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun segmentDao(): SegmentDao
}
