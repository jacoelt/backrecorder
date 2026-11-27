package com.backrecorderapp.di

import android.content.Context
import androidx.room.Room
import com.backrecorderapp.data.cloud.DriveAboutIdentityResolver
import com.backrecorderapp.data.cloud.DriveAccountIdentityResolver
import com.backrecorderapp.data.cloud.DriveServiceFactory
import com.backrecorderapp.data.cloud.SystemUploadFailureNotifier
import com.backrecorderapp.data.cloud.UploadFailureNotifier
import com.backrecorderapp.data.db.AppDatabase
import com.backrecorderapp.data.db.SegmentDao
import com.backrecorderapp.domain.RecordingFlusher
import com.backrecorderapp.notification.SaveFailureNotifier
import com.backrecorderapp.notification.SystemSaveFailureNotifier
import com.backrecorderapp.service.RecordingService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /**
     * Never cancelled: it exists precisely for work that has to outlive its caller. Nothing
     * launched here can be stopped, so only work that ends on its own, with a worst case you can
     * name, belongs on it — a coroutine launched here runs until it finishes or the process dies.
     *
     * `DriveAuthManager.resolveIdentityWithinBudget` is the shape to copy: the caller's wait is
     * bounded, and the uninterruptible call it was waiting on is abandoned here to finish alone.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /**
     * Bound here rather than in [CloudModule], which instrumented tests replace wholesale: this
     * seam exists for JVM tests, and a flow test has no reason to lose the real notification.
     */
    @Provides
    @Singleton
    fun provideUploadFailureNotifier(
        notifier: SystemUploadFailureNotifier
    ): UploadFailureNotifier = notifier

    @Provides
    @Singleton
    fun provideSaveFailureNotifier(
        notifier: SystemSaveFailureNotifier
    ): SaveFailureNotifier = notifier

    @Provides
    @Singleton
    fun provideRecordingFlusher(): RecordingFlusher = object : RecordingFlusher {
        override suspend fun flushCurrentSegment() {
            RecordingService.flushCurrentSegment()
        }
    }
}
