package com.backrecorderapp.data.cloud

import android.content.Context
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.segment.SegmentManager
import com.backrecorderapp.domain.SubscriptionGate
import com.google.api.client.http.HttpHeaders
import com.google.api.client.http.HttpResponseException
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the two pure decisions inside the sync loop: how a Drive error is classified, and how
 * long to wait before the next pass.
 *
 * These matter disproportionately because 403 is overloaded. Drive answers 403 for a revoked
 * scope, for throttling, and for a full account, and the three need opposite responses - prompt
 * the user, back off, or stop and say so. Getting it wrong either nags about reconnecting when
 * nothing is wrong with the grant, or silently stops backing up recordings.
 *
 * The loop itself is not driven here: it runs on an injected-free `Dispatchers.IO` scope with
 * real `delay()`s, so it cannot be advanced with virtual time as written.
 */
class CloudSyncClassificationTest {

    private lateinit var manager: CloudSyncManager

    @Before
    fun setup() {
        manager = CloudSyncManager(
            context = mockk<Context>(relaxed = true),
            driveCloudStorage = mockk(relaxed = true),
            segmentManager = mockk<SegmentManager>(relaxed = true),
            appPreferences = mockk<AppPreferences>(relaxed = true),
            subscriptionGate = mockk<SubscriptionGate>(relaxed = true)
        )
    }

    /**
     * A real exception rather than a mock, so the classification is checked against Drive's
     * actual `getContent()` behaviour instead of a stubbed value.
     */
    private fun httpError(status: Int, content: String? = null): HttpResponseException =
        HttpResponseException.Builder(status, "test", HttpHeaders())
            .setContent(content)
            .build()

    @Test
    fun `401 is an authorization failure`() {
        assertTrue(manager.isAuthFailure(httpError(401)))
    }

    @Test
    fun `plain 403 is an authorization failure`() {
        assertTrue(manager.isAuthFailure(httpError(403, "insufficientPermissions")))
    }

    @Test
    fun `403 rate limiting is not an authorization failure`() {
        // Prompting the user to reconnect here would be nonsense - the grant is fine.
        val e = httpError(403, """{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}""")
        assertFalse(manager.isAuthFailure(e))
        assertTrue(manager.isRateLimited(e))
        assertFalse(manager.isQuotaExceeded(e))
    }

    @Test
    fun `403 storage quota is neither an auth failure nor transient`() {
        // Treating a full Drive as throttling makes the app back off forever in silence while
        // the buffer keeps evicting recordings that were never uploaded.
        val e = httpError(403, """{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""")
        assertFalse(manager.isAuthFailure(e))
        assertFalse(manager.isRateLimited(e))
        assertTrue(manager.isQuotaExceeded(e))
    }

    @Test
    fun `a 500 is neither auth nor quota`() {
        val e = httpError(500, "backendError")
        assertFalse(manager.isAuthFailure(e))
        assertFalse(manager.isRateLimited(e))
        assertFalse(manager.isQuotaExceeded(e))
    }

    @Test
    fun `an error with no body is not mistaken for a quota problem`() {
        val e = httpError(403, null)
        assertTrue(manager.isAuthFailure(e))
        assertFalse(manager.isQuotaExceeded(e))
    }

    @Test
    fun `a healthy pass waits the normal interval`() {
        assertEquals(30_000L, manager.backoffMs(0))
    }

    @Test
    fun `the wait doubles per consecutive failure`() {
        assertEquals(60_000L, manager.backoffMs(1))
        assertEquals(120_000L, manager.backoffMs(2))
        assertEquals(240_000L, manager.backoffMs(3))
    }

    @Test
    fun `the wait is capped rather than growing without bound`() {
        val capped = manager.backoffMs(50)
        assertEquals(15 * 60 * 1000L, capped)
        assertTrue("backoff must never go negative or overflow", capped > 0)
    }
}
