package com.backrecorderapp.di

import com.backrecorderapp.data.cloud.DriveAuthorizationClient
import com.backrecorderapp.data.cloud.DriveServiceFactory
import com.backrecorderapp.data.cloud.NetHttpDriveServiceFactory
import com.backrecorderapp.data.cloud.PlayServicesDriveAuthorizationClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the two Drive seams — who grants the token, and where the REST client points.
 *
 * Its own module, like [RecorderModule], so instrumented tests can replace exactly these two
 * bindings with `@TestInstallIn(replaces = [CloudModule::class])` and leave the rest of the Drive
 * stack — [com.backrecorderapp.data.cloud.DriveAuthManager]'s grant state machine, the real Drive
 * client, [com.backrecorderapp.data.cloud.DriveCloudStorage]'s folder logic — running for real.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CloudModule {

    @Binds
    @Singleton
    abstract fun bindDriveAuthorizationClient(
        client: PlayServicesDriveAuthorizationClient
    ): DriveAuthorizationClient

    @Binds
    @Singleton
    abstract fun bindDriveServiceFactory(
        factory: NetHttpDriveServiceFactory
    ): DriveServiceFactory
}
