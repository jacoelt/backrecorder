package com.backrecorderapp.di

import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.billing.FakeBillingRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces [BillingModule] for every instrumented test, so entitlement comes from
 * [FakeBillingRepository] instead of a Play Billing connection that cannot resolve on an emulator.
 *
 * A test reaches the fake by injecting [FakeBillingRepository] directly; the same `@Singleton`
 * instance backs the [BillingRepository] that [com.backrecorderapp.domain.SubscriptionGate] and
 * the view models get.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [BillingModule::class])
abstract class FakeBillingModule {

    @Binds
    @Singleton
    abstract fun bindBillingRepository(repository: FakeBillingRepository): BillingRepository
}
