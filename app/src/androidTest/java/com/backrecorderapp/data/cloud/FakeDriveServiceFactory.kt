package com.backrecorderapp.data.cloud

import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the **real** Drive client, aimed at [FakeDriveServer] instead of googleapis.com.
 *
 * Only the endpoint changes. The transport, the JSON factory, the generated request classes and
 * the caller's initializer — which is where [DriveAuthManager] hangs its bearer token and its 401
 * refresh handler — are the production ones, so what the flow test exercises is the client the app
 * actually ships.
 *
 * The root URL is read per call rather than captured once: Hilt builds this before any test has
 * started the server, so the port is not known yet at construction time.
 */
@Singleton
class FakeDriveServiceFactory @Inject constructor(
    private val server: FakeDriveServer
) : DriveServiceFactory {

    override fun create(initializer: HttpRequestInitializer): Drive =
        Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), initializer)
            .setApplicationName("Back Recorder")
            .setRootUrl(server.rootUrl)
            .setServicePath("drive/v3/")
            .build()
}
