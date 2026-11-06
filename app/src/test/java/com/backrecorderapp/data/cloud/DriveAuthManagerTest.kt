package com.backrecorderapp.data.cloud

import android.app.PendingIntent
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import com.backrecorderapp.data.preferences.AppPreferences
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Covers the grant state machine.
 *
 * Every failure mode here is silent by nature: the app reports "Connected" for a grant that is
 * gone, or throws away a working session because the network blinked. Both were real regressions
 * on this branch, so each is pinned by a test rather than a comment.
 */
class DriveAuthManagerTest {

    private lateinit var client: DriveAuthorizationClient
    private lateinit var driveServiceFactory: DriveServiceFactory
    private lateinit var preferences: AppPreferences

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        // elapsedRealtime backs the token TTL and is a real Android static.
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1_000L

        // Scope(...) validates its argument through GMS Preconditions, which calls TextUtils.
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } returns false

        client = mockk(relaxed = true)
        preferences = mockk(relaxed = true)
        // The Drive client itself is never called here: these tests cover the grant state
        // machine, and DriveSyncE2eTest covers the real client against a fake backend.
        driveServiceFactory = mockk(relaxed = true)
        every { driveServiceFactory.create(any()) } returns mockk<Drive>(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /** An authorization the API considers complete, with whatever scopes were granted. */
    private fun grant(
        token: String? = "token-1",
        scopes: List<String> = listOf(DriveScopes.DRIVE_FILE)
    ): AuthorizationResult = mockk(relaxed = true) {
        every { hasResolution() } returns false
        every { pendingIntent } returns null
        every { accessToken } returns token
        every { grantedScopes } returns scopes
    }

    /** An authorization that needs the user to approve it — i.e. the grant is not in place. */
    private fun consentNeeded(): AuthorizationResult = mockk(relaxed = true) {
        every { hasResolution() } returns true
        every { pendingIntent } returns mockk<PendingIntent>(relaxed = true)
    }

    private fun answersWith(result: AuthorizationResult) {
        coEvery { client.authorize(any()) } returns result
    }

    private fun answersWith(error: Throwable) {
        coEvery { client.authorize(any()) } throws (error as? Exception ?: IOException(error))
    }

    /** A resolver that always reports [identity] - null meaning "could not be determined". */
    private fun resolverFor(identity: DriveAccountIdentity?) =
        object : DriveAccountIdentityResolver {
            override suspend fun resolve(accessToken: String): DriveAccountIdentity? = identity
        }

    /**
     * By default nothing is pinned and the account cannot be resolved, which is the shape the
     * pre-pinning tests were written against.
     */
    private fun newManager(
        resolver: DriveAccountIdentityResolver = resolverFor(null),
        pinnedAccountId: String? = null,
        pinnedEmail: String? = null
    ): DriveAuthManager {
        every { preferences.driveAccountId } returns flowOf(pinnedAccountId)
        every { preferences.driveAccountEmail } returns flowOf(pinnedEmail)
        return DriveAuthManager(preferences, resolver, client, driveServiceFactory)
    }

    @Test
    fun `a complete grant authorizes and exposes a Drive client`() = runTest {
        answersWith(grant())
        val auth = newManager()

        val outcome = auth.authorize()

        assertEquals(DriveAuthManager.AuthorizationOutcome.Authorized, outcome)
        assertTrue(auth.isAuthorized.value)
        assertNotNull(auth.drive)
    }

    @Test
    fun `a grant without the drive scope is refused`() = runTest {
        // Accepting it would report Drive as connected while every upload 403s.
        answersWith(grant(scopes = listOf("https://www.googleapis.com/auth/userinfo.email")))
        val auth = newManager()

        val outcome = auth.authorize()

        assertEquals(DriveAuthManager.AuthorizationOutcome.Failed, outcome)
        assertFalse(auth.isAuthorized.value)
        assertNull(auth.drive)
    }

    @Test
    fun `a grant with no access token is refused`() = runTest {
        answersWith(grant(token = null))
        val auth = newManager()

        assertEquals(DriveAuthManager.AuthorizationOutcome.Failed, auth.authorize())
        assertFalse(auth.isAuthorized.value)
    }

    @Test
    fun `needing consent clears the session instead of reporting it as live`() = runTest {
        answersWith(grant())
        val auth = newManager()
        auth.authorize()
        assertTrue(auth.isAuthorized.value)

        // The user revoked access at Google; the next silent check needs consent again.
        answersWith(consentNeeded())
        val outcome = auth.authorize()

        assertTrue(outcome is DriveAuthManager.AuthorizationOutcome.ConsentRequired)
        // Without this the Settings row keeps offering Disconnect for a dead account and never
        // offers Connect.
        assertFalse(auth.isAuthorized.value)
        assertNull(auth.drive)
    }

    @Test
    fun `a transient failure does not tear down a working session`() = runTest {
        answersWith(grant())
        val auth = newManager()
        auth.authorize()
        val driveBefore = auth.drive

        // A network blip, not evidence the grant is gone. Dropping the session here left sync
        // dead for the rest of the process.
        answersWith(IOException("network"))
        val outcome = auth.authorize()

        assertEquals(DriveAuthManager.AuthorizationOutcome.Failed, outcome)
        assertTrue("the existing session must survive", auth.isAuthorized.value)
        assertEquals(driveBefore, auth.drive)
    }

    @Test
    fun `signOut drops the session`() = runTest {
        answersWith(grant())
        val auth = newManager()
        auth.authorize()

        auth.signOut()

        assertFalse(auth.isAuthorized.value)
        assertNull(auth.drive)
    }

    @Test
    fun `restoreSession reports whether the grant is usable`() = runTest {
        answersWith(grant())
        val auth = newManager()
        assertTrue(auth.restoreSession())

        answersWith(consentNeeded())
        assertFalse(auth.restoreSession())
    }

    @Test
    fun `a null consent result is treated as a failure and clears the session`() = runTest {
        answersWith(grant())
        val auth = newManager()
        auth.authorize()

        assertEquals(
            DriveAuthManager.AuthorizationOutcome.Failed,
            auth.handleAuthorizationResult(null)
        )
        assertFalse(auth.isAuthorized.value)
    }

    @Test
    fun `the grant generation changes whenever the grant does`() = runTest {
        // DriveCloudStorage keys its folder-id cache to this. If it did not move on a reconnect,
        // a different account's Drive would be addressed with the previous account's folder ids.
        answersWith(grant())
        val auth = newManager()
        val atStart = auth.grantGeneration

        auth.authorize()
        val afterConnect = auth.grantGeneration
        assertTrue("connecting must change the generation", afterConnect != atStart)

        auth.signOut()
        assertTrue("disconnecting must change it too", auth.grantGeneration != afterConnect)
    }

    @Test
    fun `a re-grant after sign-out changes the generation again`() = runTest {
        answersWith(grant())
        val auth = newManager()
        auth.authorize()
        auth.signOut()
        val afterSignOut = auth.grantGeneration

        answersWith(grant(token = "token-2"))
        auth.authorize()

        assertTrue(auth.grantGeneration != afterSignOut)
        assertTrue(auth.isAuthorized.value)
    }

    // --- Account pinning -------------------------------------------------------------------
    //
    // A silent re-authorization can resolve a different Google account on a device with several
    // signed in. Uploading a user's recordings into somebody else's Drive is not recoverable, so
    // the grant is pinned to one account and every adopted token is checked against it.

    @Test
    fun `a pinned account is set on the authorization request`() = runTest {
        // This is the mechanism that actually prevents drift; the identity check afterwards only
        // detects drift that got through. Without setAccount, Play Services is free to resolve
        // whichever account it considers current.
        val request = slot<AuthorizationRequest>()
        coEvery { client.authorize(capture(request)) } returns grant()

        val auth = newManager(
            resolver = resolverFor(DriveAccountIdentity("acct-1", "a@x.com")),
            pinnedAccountId = "acct-1",
            pinnedEmail = "a@x.com"
        )
        auth.authorize()

        // Only the presence of the account is asserted, not its address: android.accounts.Account
        // is a stub in the unit-test android.jar, so its fields read back null however it was
        // constructed. Presence is the part that distinguishes pinned from unpinned.
        assertNotNull(
            "the request must carry the pinned account",
            request.captured.account
        )
    }

    @Test
    fun `no account is pinned before one is known`() = runTest {
        // On the very first connect there is nothing to pin, and pinning a placeholder would
        // stop the account picker from ever appearing.
        val request = slot<AuthorizationRequest>()
        coEvery { client.authorize(capture(request)) } returns grant()

        newManager().authorize()

        assertNull(request.captured.account)
    }

    @Test
    fun `the first connect records the account the grant belongs to`() = runTest {
        answersWith(grant())
        val auth = newManager(resolver = resolverFor(DriveAccountIdentity("acct-1", "a@x.com")))

        assertEquals(DriveAuthManager.AuthorizationOutcome.Authorized, auth.authorize())
        // Without a recorded id there is nothing for a later grant to be checked against.
        coVerify { preferences.setDriveAccount("acct-1", "a@x.com") }
    }

    @Test
    fun `a grant resolving to a different account is refused and drops the session`() = runTest {
        answersWith(grant())
        val auth = newManager(
            resolver = resolverFor(DriveAccountIdentity("acct-2", "intruder@x.com")),
            pinnedAccountId = "acct-1",
            pinnedEmail = "a@x.com"
        )

        val outcome = auth.authorize()

        assertEquals(DriveAuthManager.AuthorizationOutcome.AccountMismatch, outcome)
        // The session must never be published: the sync loop watches isAuthorized, and one tick
        // is all it takes to upload a segment to the wrong Drive.
        assertFalse(auth.isAuthorized.value)
        assertNull(auth.drive)
        coVerify(exactly = 0) { preferences.setDriveAccount("acct-2", any()) }
    }

    @Test
    fun `a grant resolving to the pinned account is accepted`() = runTest {
        answersWith(grant())
        val auth = newManager(
            resolver = resolverFor(DriveAccountIdentity("acct-1", "a@x.com")),
            pinnedAccountId = "acct-1",
            pinnedEmail = "a@x.com"
        )

        assertEquals(DriveAuthManager.AuthorizationOutcome.Authorized, auth.authorize())
        assertTrue(auth.isAuthorized.value)
    }

    @Test
    fun `the same account under a new address stays connected`() = runTest {
        // Pinning is on the Drive permissionId precisely so a renamed account keeps working.
        answersWith(grant())
        val auth = newManager(
            resolver = resolverFor(DriveAccountIdentity("acct-1", "renamed@x.com")),
            pinnedAccountId = "acct-1",
            pinnedEmail = "a@x.com"
        )

        assertEquals(DriveAuthManager.AuthorizationOutcome.Authorized, auth.authorize())
        coVerify { preferences.setDriveAccount("acct-1", "renamed@x.com") }
    }

    @Test
    fun `an unresolvable account does not tear down a pinned session`() = runTest {
        // The lookup is a network call. Treating its failure as "different account" would kill
        // sync every time the connection blinked - the same mistake as dropping the session on a
        // failed refresh. Drift is prevented by the pin on the request itself.
        answersWith(grant())
        val auth = newManager(
            resolver = resolverFor(null),
            pinnedAccountId = "acct-1",
            pinnedEmail = "a@x.com"
        )

        assertEquals(DriveAuthManager.AuthorizationOutcome.Authorized, auth.authorize())
        assertTrue(auth.isAuthorized.value)
    }

    @Test
    fun `signing out forgets the pin so another account can be chosen`() = runTest {
        answersWith(grant())
        val auth = newManager(
            resolver = resolverFor(DriveAccountIdentity("acct-1", "a@x.com")),
            pinnedAccountId = "acct-1",
            pinnedEmail = "a@x.com"
        )
        auth.authorize()

        auth.signOut()

        // Keeping the pin would make every future connect an AccountMismatch, permanently
        // locking the user out of switching accounts.
        coVerify { preferences.setDriveAccount(null, null) }
    }

    @Test
    fun `a connect after sign-out pins whichever account is chosen`() = runTest {
        // The pin is cleared on sign-out, so the next connect starts from nothing pinned and
        // adopts the new account rather than refusing it.
        answersWith(grant())
        val auth = newManager(resolver = resolverFor(DriveAccountIdentity("acct-2", "b@x.com")))

        assertEquals(DriveAuthManager.AuthorizationOutcome.Authorized, auth.authorize())
        coVerify { preferences.setDriveAccount("acct-2", "b@x.com") }
    }
}
