package com.backrecorderapp.di

import com.backrecorderapp.domain.ComplimentaryCodeValidator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Its own module, on the same pattern as [RecorderModule] and [BillingModule], so instrumented
 * tests can replace just this binding with a validator holding a code they know.
 *
 * The seam is what keeps real codes out of the repository: an end-to-end test of redemption has
 * to type a code that is accepted, and a code written down here is one anyone can redeem.
 */
@Module
@InstallIn(SingletonComponent::class)
object ComplimentaryModule {

    @Provides
    @Singleton
    fun provideComplimentaryCodeValidator(): ComplimentaryCodeValidator =
        ComplimentaryCodeValidator()
}
