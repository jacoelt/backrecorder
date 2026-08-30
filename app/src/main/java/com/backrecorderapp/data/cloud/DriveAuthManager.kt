package com.backrecorderapp.data.cloud

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the Google OAuth grant used for Drive uploads.
 *
 * Uses the Play Services Authorization API rather than GoogleSignIn: GoogleSignIn - and with it
 * GoogleAccountCredential, which needed an Account - was removed in play-services-auth 22. The
 * Authorization API hands back a bare OAuth access token instead, so token lifetime is managed
 * here: cached briefly, re-fetched silently on expiry, force-refreshed when Drive answers 401.
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
    sealed class AuthorizationOutcome {
        /** Consent already granted; Drive is usable now. */
        data object Authorized : AuthorizationOutcome()

        /** The user must approve; launch [pendingIntent] and report back the result. */
        data class ConsentRequired(val pendingIntent: PendingIntent) : AuthorizationOutcome()

        data object Failed : AuthorizationOutcome()
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

    /**
     * Guards the whole authorize/adopt/clear sequence, so a transient failure in one caller
     * cannot wipe the session another caller just established.
     *
     * Lock order is [sessionMutex] then [tokenMutex], never the reverse. [validToken] is the one
     * path that takes [tokenMutex] alone: it runs inside the Drive client's request initializer,
     * which cannot wait on a session operation without deadlocking the request it is serving.
     */
    private val sessionMutex = Mutex()

    private val tokenMutex = Mutex()
    private var cachedToken: String? = null
    private var cachedTokenAt = 0L

    @Volatile
    private var driveService: Drive? = null

    private val generation = AtomicInteger(0)

    /**
     * Changes whenever the grant does. Anything caching account-scoped Drive state - folder ids
     * above all - must compare this before reusing it, instead of relying on callers to remember
     * to invalidate. A reconnect may be to a different account, whose Drive cannot see the ids.
     */
    val grantGeneration: Int get() = generation.get()

    /** The authorized Drive client, or null while unauthorized. */
    val drive: Drive? get() = driveService

    /**
     * Asks for the Drive grant. Returns [AuthorizationOutcome.Authorized] when consent already
     * exists (no UI shown), otherwise a pending intent the caller must launch.
     */
    suspend fun authorize(): AuthorizationOutcome = sessionMutex.withLock {
        val result = try {
            authorizationClient.authorize(authorizationRequest).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed request is not evidence the grant is gone - it is usually the network.
            // Tearing the session down here would kill an upload session that still works.
            Log.e(TAG, "Drive authorization request failed", e)
            return@withLock AuthorizationOutcome.Failed
        }

        val pendingIntent = result.pendingIntent
        if (result.hasResolution() && pendingIntent != null) {
            // Needing consent means the grant is gone until the user approves again. Drop the
            // session rather than leave it reported as live - otherwise the UI keeps offering
            // Disconnect for an account that can no longer upload anything.
            clearLocked()
            return@withLock AuthorizationOutcome.ConsentRequired(pendingIntent)
        }

        if (adoptLocked(result)) AuthorizationOutcome.Authorized else AuthorizationOutcome.Failed
    }

    /**
     * Restores authorization on app start without ever showing UI. Returns false when the user
     * has not granted access yet, or revoked it since.
     */
    suspend fun restoreSession(): Boolean = authorize() is AuthorizationOutcome.Authorized

    /** Completes the flow with the data returned by the launched consent intent. */
    suspend fun handleAuthorizationResult(data: Intent?): Boolean = sessionMutex.withLock {
        if (data == null) {
            clearLocked()
            return@withLock false
        }
        try {
            adoptLocked(authorizationClient.getAuthorizationResultFromIntent(data))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Drive consent result could not be read", e)
            clearLocked()
            false
        }
    }

    suspend fun signOut() = sessionMutex.withLock { clearLocked() }

    /** Caller must hold [sessionMutex]. */
    private suspend fun adoptLocked(result: AuthorizationResult): Boolean {
        val token = result.accessToken
        if (token.isNullOrEmpty()) {
            Log.w(TAG, "Authorization returned no access token")
            clearLocked()
            return false
        }
        if (!grantsDriveScope(result)) {
            Log.w(TAG, "Drive scope was not granted")
            clearLocked()
            return false
        }

        tokenMutex.withLock {
            cachedToken = token
            cachedTokenAt = SystemClock.elapsedRealtime()
        }

        if (driveService == null) {
            driveService = buildDriveService()
        }
        generation.incrementAndGet()
        _isAuthorized.value = true
        return true
    }

    /** Caller must hold [sessionMutex]. */
    private suspend fun clearLocked() {
        tokenMutex.withLock { resetTokenLocked() }
        invalidateSession()
    }

    /** Caller must hold [tokenMutex]. */
    private fun resetTokenLocked() {
        cachedToken = null
        cachedTokenAt = 0L
    }

    private fun invalidateSession() {
        driveService = null
        _isAuthorized.value = false
        generation.incrementAndGet()
    }

    private fun grantsDriveScope(result: AuthorizationResult): Boolean =
        result.grantedScopes.any { it == driveScope.scopeUri }

    /**
     * Returns a usable access token, silently re-authorizing when the cached one is stale.
     * Null means the grant is gone and the user has to reconnect.
     *
     * Every failure path drops the session. Leaving the cache populated would mean replaying a
     * token Drive has already rejected for the rest of the 45-minute TTL, while the UI still
     * reported the account as connected.
     */
    private suspend fun validToken(forceRefresh: Boolean): String? = tokenMutex.withLock {
        // Refuse to mint a token for a session that has been signed out. Without this a request
        // already in flight when the user disconnects would quietly re-authorize and complete.
        if (driveService == null) return@withLock null
        val entryGeneration = generation.get()

        val now = SystemClock.elapsedRealtime()
        val cached = cachedToken
        if (!forceRefresh && cached != null && now - cachedTokenAt < TOKEN_TTL_MS) {
            return@withLock cached
        }

        val result = try {
            authorizationClient.authorize(authorizationRequest).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed request is not evidence the grant is gone; it is usually the network.
            // Dropping the session here would null the Drive client from inside a live request
            // and leave sync dead for the rest of the process over one blip.
            Log.e(TAG, "Silent token refresh failed", e)
            return@withLock null
        }

        // The session may have been signed out while the request was in flight.
        if (driveService == null || generation.get() != entryGeneration) return@withLock null

        // These three DO mean the grant is gone, so the session goes with them.
        if (result.hasResolution()) return@withLock dropSessionLocked()

        // A refresh is a fresh grant decision, so re-check the scope exactly as adoptLocked
        // does: a grant narrowed since the last check would otherwise go unnoticed.
        if (!grantsDriveScope(result)) {
            Log.w(TAG, "Drive scope is no longer granted")
            return@withLock dropSessionLocked()
        }

        val token = result.accessToken?.takeIf { it.isNotEmpty() }
            ?: return@withLock dropSessionLocked()

        cachedToken = token
        cachedTokenAt = now
        token
    }

    /** Clears the session and returns null. Caller must hold [tokenMutex]. */
    private fun dropSessionLocked(): String? {
        resetTokenLocked()
        invalidateSession()
        return null
    }

    private fun buildDriveService(): Drive {
        val initializer = HttpRequestInitializer { request ->
            // The token bridge below blocks the calling thread, so a Drive call from the main
            // thread would ANR. Every call site goes through Dispatchers.IO; nothing enforced
            // that, so fail loudly if a new one ever does not.
            check(Looper.myLooper() != Looper.getMainLooper()) {
                "Drive requests must not run on the main thread"
            }

            val token = runBlocking { validToken(forceRefresh = false) }
                ?: throw IOException("No Drive access token available")
            request.headers.setAuthorization("Bearer $token")

            request.setUnsuccessfulResponseHandler { httpRequest, response, supportsRetry ->
                val refreshed = if (response.statusCode == 401 && supportsRetry) {
                    runBlocking { validToken(forceRefresh = true) }
                } else {
                    null
                }

                if (refreshed != null) {
                    httpRequest.headers.setAuthorization("Bearer $refreshed")
                }
                refreshed != null
            }
        }

        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), initializer)
            .setApplicationName(APPLICATION_NAME)
            .build()
    }
}
