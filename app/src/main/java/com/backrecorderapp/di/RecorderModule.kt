package com.backrecorderapp.di

import com.backrecorderapp.service.recorder.AudioRecorderFactory
import com.backrecorderapp.service.recorder.MediaRecorderAudioRecorderFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the recorder seam. Its own module so instrumented tests can replace just this binding with
 * `@TestInstallIn(replaces = [RecorderModule::class])`.
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
