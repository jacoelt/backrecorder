package com.backrecorderapp.di

import com.backrecorderapp.domain.ComplimentaryCodeValidator
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** A code an instrumented test may type. Never one that is accepted by a shipping build. */
const val TEST_COMPLIMENTARY_CODE = "TEST-GIFT-CODE"

/**
 * Replaces [ComplimentaryModule] so the redemption flow can be driven end to end. The real
 * validator is still the one under test - only the digests it compares against are swapped, which
 * is what keeps the codes handed to real people out of this repository.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ComplimentaryModule::class])
object FakeComplimentaryModule {

    @Provides
    @Singleton
    fun provideComplimentaryCodeValidator(): ComplimentaryCodeValidator =
        ComplimentaryCodeValidator(
            accepted = setOf(ComplimentaryCodeValidator.digestOf(TEST_COMPLIMENTARY_CODE)),
            blocked = emptySet()
        )
}
