package com.backrecorderapp.data.cloud

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the Google OAuth grant used for Drive uploads.
 *
 * Uses the Play Services Authorization API rather than the older GoogleSignIn flow the
 * pre-rewrite app had: GoogleSignIn (and with it GoogleAccountCredential, which needed an
 * Account) was removed in play-services-auth 22. The Authorization API instead hands back a
 * bare OAuth access token, so token lifetime is managed here - cached briefly, re-fetched
 * silently on expiry, and force-refreshed when Drive answers 401.
 *
 * Only the narrow drive.file scope is requested, which limits the app to files it created.
 */
@Singleton
class DriveAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DriveAuthManager"
        private const val APPLICATION_NAME = "Back Recorder"

        /**
         * Access tokens last an hour. Re-authorizing is a cheap silent call once consent is
         * granted, so expire early rather than risk racing the real expiry mid-upload.
         */
        private const val TOKEN_TTL_MS = 45 * 60 * 1000L
    }

    /** Outcome of asking for authorization without showing UI ourselves. */
    sealed interface AuthorizationOutcome {
        /** Consent already granted; Drive is usable now. */
        object Authorized : AuthorizationOutcome

        /** The user must approve; launch [pendingIntent] and report back the result. */
        data class ConsentRequired(val pendingIntent: PendingIntent) : AuthorizationOutcome

        object Failed : AuthorizationOutcome
    }

    private val driveScope = Scope(DriveScopes.DRIVE_FILE)

    private val authorizationClient = Identity.getAuthorizationClient(context)

    private val authorizationRequest: AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(driveScope))
            .build()

    private val _isAuthorized = MutableStateFlow(false)

    /** Whether Drive uploads are currently authorized. */
    val isAuthorized: StateFlow<Boolean> = _isAuthorized.asStateFlow()

    private val tokenMutex = Mutex()
    private var cachedToken: String? = null
    private var cachedTokenAt = 0L

    @Volatile
    private var driveService: Drive? = null

    /** The authorized Drive client, or null while unauthorized. */
    val drive: Drive? get() = driveService

    /**
     * Asks for the Drive grant. Returns [AuthorizationOutcome.Authorized] when consent already
     * exists (no UI shown), otherwise a pending intent the caller must launch.
     */
    suspend fun authorize(): AuthorizationOutcome {
        val result = try {
            authorizationClient.authorize(authorizationRequest).await()
        } catch (e: Exception) {
            Log.e(TAG, "Drive authorization request failed", e)
            clear()
            return AuthorizationOutcome.Failed
        }

        val pendingIntent = result.pendingIntent
        if (result.hasResolution() && pendingIntent != null) {
            return AuthorizationOutcome.ConsentRequired(pendingIntent)
        }

        return if (adopt(result)) AuthorizationOutcome.Authorized else AuthorizationOutcome.Failed
    }

    /**
     * Restores authorization on app start without ever showing UI. Returns false when the user
     * has not granted access yet, or revoked it since.
     */
    suspend fun restoreSession(): Boolean = authorize() is AuthorizationOutcome.Authorized

    /** Completes the flow with the data returned by the launched consent intent. */
    suspend fun handleAuthorizationResult(data: Intent?): Boolean {
        if (data == null) {
            clear()
            return false
        }
        return try {
            adopt(authorizationClient.getAuthorizationResultFromIntent(data))
        } catch (e: Exception) {
            Log.e(TAG, "Drive consent result could not be read", e)
            clear()
            false
        }
    }

    suspend fun signOut() = clear()

    private suspend fun adopt(result: AuthorizationResult): Boolean {
        val token = result.accessToken
        if (token.isNullOrEmpty()) {
            Log.w(TAG, "Authorization returned no access token")
            clear()
            return false
        }
        if (result.grantedScopes.none { it == driveScope.scopeUri }) {
            Log.w(TAG, "Drive scope was not granted")
            clear()
            return false
        }

        tokenMutex.withLock {
            cachedToken = token
            cachedTokenAt = SystemClock.elapsedRealtime()
        }

        if (driveService == null) {
            driveService = buildDriveService()
        }
        _isAuthorized.value = true
        return true
    }

    private suspend fun clear() {
        tokenMutex.withLock {
            cachedToken = null
            cachedTokenAt = 0L
        }
        driveService = null
        _isAuthorized.value = false
    }

    /**
     * Returns a usable access token, silently re-authorizing when the cached one is stale.
     * Null means the grant is gone and the user has to reconnect.
     */
    private suspend fun validToken(forceRefresh: Boolean): String? = tokenMutex.withLock {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedToken
        if (!forceRefresh && cached != null && now - cachedTokenAt < TOKEN_TTL_MS) {
            return@withLock cached
        }

        val result = try {
            authorizationClient.authorize(authorizationRequest).await()
        } catch (e: Exception) {
            Log.e(TAG, "Silent token refresh failed", e)
            return@withLock null
        }

        // Needing a resolution here means consent was revoked; the caller surfaces it as a
        // 401 from Drive and the UI prompts the user to reconnect.
        if (result.hasResolution()) return@withLock null

        val token = result.accessToken?.takeIf { it.isNotEmpty() } ?: return@withLock null
        cachedToken = token
        cachedTokenAt = now
        token
    }

    private fun buildDriveService(): Drive {
        // The initializer runs on the Drive client's own blocking IO threads, so bridging out
        // of suspend with runBlocking here never touches the main thread.
        val initializer = HttpRequestInitializer { request ->
            runBlocking { validToken(forceRefresh = false) }?.let { token ->
                request.headers.setAuthorization("Bearer $token")
            }

            request.setUnsuccessfulResponseHandler { httpRequest, response, supportsRetry ->
                if (response.statusCode == 401 && supportsRetry) {
                    val refreshed = runBlocking { validToken(forceRefresh = true) }
                    if (refreshed != null) {
                        httpRequest.headers.setAuthorization("Bearer $refreshed")
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
        }

        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), initializer)
            .setApplicationName(APPLICATION_NAME)
            .build()
    }
}
