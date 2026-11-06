package com.backrecorderapp.di

import com.backrecorderapp.service.recorder.AudioRecorderFactory
import com.backrecorderapp.service.recorder.MediaRecorderAudioRecorderFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the recorder seam. Kept in its own module so instrumented tests can replace exactly this
 * binding with `@TestInstallIn(replaces = [RecorderModule::class])` and leave the rest of
 * [AppModule] — the database, dispatchers, the flusher — untouched.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RecorderModule {

    @Binds
    @Singleton
    abstract fun bindAudioRecorderFactory(
        factory: MediaRecorderAudioRecorderFactory
    ): AudioRecorderFactory
}
