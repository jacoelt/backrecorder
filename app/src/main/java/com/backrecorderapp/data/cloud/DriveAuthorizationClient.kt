package com.backrecorderapp.data.cloud

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam over the Play Services Authorization API.
 *
 * Exists so the grant can be driven from a test: `Identity.getAuthorizationClient` talks to Play
 * Services, which cannot complete a consent flow on a CI emulator. Production behaviour is
 * unchanged — [PlayServicesDriveAuthorizationClient] is the same two calls [DriveAuthManager] used
 * to make inline.
 */
interface DriveAuthorizationClient {
    suspend fun authorize(request: AuthorizationRequest): AuthorizationResult

    /** Reads the result out of the data returned by a launched consent intent. */
    fun resultFromIntent(data: Intent): AuthorizationResult
}

@Singleton
class PlayServicesDriveAuthorizationClient @Inject constructor(
    @ApplicationContext context: Context
) : DriveAuthorizationClient {

    private val delegate = Identity.getAuthorizationClient(context)

    override suspend fun authorize(request: AuthorizationRequest): AuthorizationResult =
        delegate.authorize(request).await()

    override fun resultFromIntent(data: Intent): AuthorizationResult =
        delegate.getAuthorizationResultFromIntent(data)
}
