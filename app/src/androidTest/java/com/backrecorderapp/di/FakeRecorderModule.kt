package com.backrecorderapp.di

import com.backrecorderapp.service.recorder.AudioRecorderFactory
import com.backrecorderapp.service.recorder.FakeAudioRecorderFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces [RecorderModule] for every instrumented test, so recording runs on
 * [FakeAudioRecorderFactory] instead of the microphone. Nothing else in the graph changes — the
 * database, the dispatchers and `RecordingFlusher` stay exactly as production wires them.
 *
 * A test reaches the fake by injecting [FakeAudioRecorderFactory] directly; the same `@Singleton`
 * instance backs the [AudioRecorderFactory] the service gets.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [RecorderModule::class])
abstract class FakeRecorderModule {

    @Binds
    @Singleton
    abstract fun bindAudioRecorderFactory(factory: FakeAudioRecorderFactory): AudioRecorderFactory
}
