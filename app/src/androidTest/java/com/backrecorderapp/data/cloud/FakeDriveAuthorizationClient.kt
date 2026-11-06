package com.backrecorderapp.data.cloud

import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.api.services.drive.DriveScopes
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stands in for the Play Services Authorization API, which cannot complete a consent flow on a CI
 * emulator.
 *
 * Deliberately a *thin* double: it only decides what one `authorize` call hands back. Everything
 * the grant state machine does with that — the 45-minute token cache, the scope re-check, the
 * account pin, `grantGeneration` — stays in the real [DriveAuthManager], because those are the
 * behaviours the flow test exists to cover.
 *
 * Every call mints a **new** token, so a force-refresh is observable at the server as a different
 * bearer value rather than having to be inferred from call counts.
 */
@Singleton
class FakeDriveAuthorizationClient @Inject constructor() : DriveAuthorizationClient {

    private val counter = AtomicInteger(0)
    private val issued = Collections.synchronizedList(mutableListOf<String>())

    /** Scopes the grant reports. Narrow it to prove the manager refuses an insufficient grant. */
    @Volatile
    var grantedScopes: List<String> = listOf(DriveScopes.DRIVE_FILE)

    /** When set, every `authorize` fails with it, as a network error would. */
    @Volatile
    var failure: Exception? = null

    /**
     * Whether a grant exists at all. **False by default**, because this module is installed in
     * every instrumented test and a fresh install has no Drive grant — a fake that authorized on
     * demand would silently make unrelated tests see a connected account.
     */
    @Volatile
    private var granted: Boolean = false

    /** Gives the fake a grant, so the next `authorize` succeeds. */
    fun grantAccess() {
        granted = true
    }

    /** Tokens handed out so far, oldest first. */
    fun issuedTokens(): List<String> = synchronized(issued) { issued.toList() }

    /** The token the manager is currently expected to be using. */
    fun currentToken(): String = issuedTokens().last()

    fun reset() {
        counter.set(0)
        issued.clear()
        grantedScopes = listOf(DriveScopes.DRIVE_FILE)
        failure = null
        granted = false
    }

    override suspend fun authorize(request: AuthorizationRequest): AuthorizationResult {
        failure?.let { throw it }
        // The same shape an unreachable Play Services gives: DriveAuthManager reports Failed and
        // keeps the session it had, rather than claiming a grant nobody asked for.
        if (!granted) throw IOException("No Drive grant; call grantAccess() first")
        val token = "fake-token-" + counter.incrementAndGet()
        issued += token
        return AuthorizationResult(
            null,
            token,
            null,
            grantedScopes,
            null,
            null
        )
    }

    override fun resultFromIntent(data: Intent): AuthorizationResult =
        throw UnsupportedOperationException(
            "The fake grant never needs consent, so no consent intent is ever launched"
        )
}
