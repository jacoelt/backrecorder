package com.backrecorderapp.di

import android.content.Context
import androidx.room.Room
import com.backrecorderapp.data.cloud.DriveAboutIdentityResolver
import com.backrecorderapp.data.cloud.DriveAccountIdentityResolver
import com.backrecorderapp.data.cloud.DriveServiceFactory
import com.backrecorderapp.data.db.AppDatabase
import com.backrecorderapp.data.db.SegmentDao
import com.backrecorderapp.domain.RecordingFlusher
import com.backrecorderapp.service.RecordingService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideDriveAccountIdentityResolver(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        driveServiceFactory: DriveServiceFactory
    ): DriveAccountIdentityResolver = DriveAboutIdentityResolver(ioDispatcher, driveServiceFactory)

    @Provides
    @Singleton
    fun provideRecordingFlusher(): RecordingFlusher = object : RecordingFlusher {
        override suspend fun flushCurrentSegment() {
            RecordingService.flushCurrentSegment()
        }
    }
}
