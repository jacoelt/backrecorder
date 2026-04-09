package com.backrecorderapp.di

import android.content.Context
import androidx.room.Room
import com.backrecorderapp.data.db.AppDatabase
import com.backrecorderapp.data.db.SegmentDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "backrecorder.db"
        ).build()
    }

    @Provides
    fun provideSegmentDao(database: AppDatabase): SegmentDao {
        return database.segmentDao()
    }
}
