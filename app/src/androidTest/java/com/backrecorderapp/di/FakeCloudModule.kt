package com.backrecorderapp.di

import com.backrecorderapp.data.cloud.DriveAuthorizationClient
import com.backrecorderapp.data.cloud.DriveServiceFactory
import com.backrecorderapp.data.cloud.FakeDriveAuthorizationClient
import com.backrecorderapp.data.cloud.FakeDriveServiceFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces [CloudModule] for every instrumented test: the grant comes from a fake, and the Drive
 * client points at the in-process fake backend.
 *
 * Nothing else in the Drive stack is replaced. `DriveAuthManager`'s state machine, the real Drive
 * client and `DriveCloudStorage`'s folder logic all run as they do in production — which is the
 * point, since the bugs this guards against (a stale folder id reused across accounts, a recovery
 * folder created inside `staging`) live in exactly that code.
 *
 * A test reaches either fake by injecting it directly; the same `@Singleton` instances back the
 * interfaces the app gets.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [CloudModule::class])
abstract class FakeCloudModule {

    @Binds
    @Singleton
    abstract fun bindDriveAuthorizationClient(
        client: FakeDriveAuthorizationClient
    ): DriveAuthorizationClient

    @Binds
    @Singleton
    abstract fun bindDriveServiceFactory(factory: FakeDriveServiceFactory): DriveServiceFactory
}
