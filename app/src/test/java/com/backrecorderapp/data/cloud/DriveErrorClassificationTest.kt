package com.backrecorderapp.data.cloud

import com.google.api.client.http.HttpHeaders
import com.google.api.client.http.HttpResponseException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three predicates both cloud paths branch on, tested directly rather than only through
 * `CloudSyncManager`'s delegates.
 *
 * The distinction they draw is the whole point: 403 is how Drive reports a lost scope, a throttle
 * *and* a full account, and the three want opposite handling - reconnect, back off, and stop.
 */
class DriveErrorClassificationTest {

    private fun error(status: Int, content: String?) =
        HttpResponseException.Builder(status, "failed", HttpHeaders())
            .setContent(content)
            .build()

    @Test
    fun `401 is always an authorization failure`() {
        assertTrue(isDriveAuthFailure(error(401, null)))
    }

    @Test
    fun `a 403 that is neither throttling nor a full account is a lost scope`() {
        assertTrue(isDriveAuthFailure(error(403, """{"reason":"insufficientPermissions"}""")))
    }

    @Test
    fun `a throttling 403 is not an authorization failure`() {
        val throttled = error(403, """{"reason":"userRateLimitExceeded"}""")
        assertTrue(isDriveRateLimited(throttled))
        assertFalse(isDriveAuthFailure(throttled))
    }

    @Test
    fun `a full-account 403 is not an authorization failure`() {
        val full = error(403, """{"reason":"storageQuotaExceeded"}""")
        assertTrue(isDriveQuotaExceeded(full))
        assertFalse(isDriveAuthFailure(full))
    }

    @Test
    fun `reasons are matched whatever their casing`() {
        assertTrue(isDriveRateLimited(error(403, """{"reason":"RATELIMITEXCEEDED"}""")))
        assertTrue(isDriveQuotaExceeded(error(403, """{"reason":"StorageQuotaExceeded"}""")))
    }

    @Test
    fun `a body-less response is neither throttled nor out of space`() {
        assertFalse(isDriveRateLimited(error(403, null)))
        assertFalse(isDriveQuotaExceeded(error(403, null)))
    }

    @Test
    fun `an ordinary server error is none of the three`() {
        val serverError = error(500, "boom")
        assertFalse(isDriveAuthFailure(serverError))
        assertFalse(isDriveRateLimited(serverError))
        assertFalse(isDriveQuotaExceeded(serverError))
    }
}
