package com.backrecorderapp.data.cloud

import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.tasks.Tasks
import com.google.api.services.drive.DriveScopes
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
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

    private lateinit var context: Context
    private lateinit var client: AuthorizationClient

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

        context = mockk(relaxed = true)
        client = mockk(relaxed = true)

        // The client is obtained through a static factory in the constructor.
        mockkStatic(Identity::class)
        every { Identity.getAuthorizationClient(any<Context>()) } returns client
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
        every { client.authorize(any()) } returns Tasks.forResult(result)
    }

    private fun answersWith(error: Throwable) {
        every { client.authorize(any()) } returns Tasks.forException(
            error as? Exception ?: IOException(error)
        )
    }

    private fun newManager() = DriveAuthManager(context)

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

        assertFalse(auth.handleAuthorizationResult(null))
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
}
