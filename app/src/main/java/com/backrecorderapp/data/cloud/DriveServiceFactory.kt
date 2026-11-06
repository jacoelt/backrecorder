package com.backrecorderapp.data.cloud

import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the Drive REST client.
 *
 * A seam purely so instrumented tests can aim the same client at a fake backend instead of
 * googleapis.com. The client built here is the real, reflection-heavy Drive client either way:
 * the test swaps the endpoint, not the implementation, so request shapes and JSON parsing are
 * exercised for real.
 *
 * [initializer] carries the caller's auth and retry behaviour and must be passed through
 * untouched — it is where [DriveAuthManager] hangs its bearer token and its 401 refresh handler.
 */
interface DriveServiceFactory {
    fun create(initializer: HttpRequestInitializer): Drive
}

@Singleton
class NetHttpDriveServiceFactory @Inject constructor() : DriveServiceFactory {

    override fun create(initializer: HttpRequestInitializer): Drive =
        Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), initializer)
            .setApplicationName(APPLICATION_NAME)
            .build()

    private companion object {
        const val APPLICATION_NAME = "Back Recorder"
    }
}
