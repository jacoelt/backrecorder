package com.backrecorderapp.di

import com.backrecorderapp.domain.ComplimentaryCodeValidator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Its own module so instrumented tests can swap in a validator holding a code they know; a real
 * code written into a test would be redeemable by anyone with the repository.
 */
@Module
@InstallIn(SingletonComponent::class)
object ComplimentaryModule {

    @Provides
    @Singleton
    fun provideComplimentaryCodeValidator(): ComplimentaryCodeValidator =
        ComplimentaryCodeValidator()
}
