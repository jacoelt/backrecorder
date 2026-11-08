package com.backrecorderapp.di

import com.backrecorderapp.data.billing.BillingClientFactory
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.billing.PlayBillingClientFactory
import com.backrecorderapp.data.billing.PlayBillingRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the entitlement seam. Its own module so instrumented tests can replace just this binding
 * with `@TestInstallIn(replaces = [BillingModule::class])`, exactly as [RecorderModule] is
 * replaced for the microphone.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BillingModule {

    @Binds
    @Singleton
    abstract fun bindBillingRepository(repository: PlayBillingRepository): BillingRepository

    @Binds
    @Singleton
    abstract fun bindBillingClientFactory(factory: PlayBillingClientFactory): BillingClientFactory
}
