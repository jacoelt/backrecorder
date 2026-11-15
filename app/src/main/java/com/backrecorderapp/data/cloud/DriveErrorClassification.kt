package com.backrecorderapp.data.cloud

import com.google.api.client.http.HttpResponseException

/**
 * How Drive's error responses are read, in one place.
 *
 * Shared by the sync loop and the saved-recording upload: both have to tell a transient failure
 * worth retrying from a terminal one, and two copies of this would eventually disagree about
 * which 403 means what.
 */

/** Reasons Drive reports throttling with - transient, and wanting a back-off rather than a prompt. */
private val RATE_LIMIT_REASONS = listOf("rateLimitExceeded", "userRateLimitExceeded")

/** Also a 403, but terminal: retrying cannot help until the user frees space. */
private const val QUOTA_EXCEEDED_REASON = "storageQuotaExceeded"

/**
 * Drive answers 401 when the token is rejected and 403 when the grant no longer covers the scope;
 * both mean the user must re-authorize. But 403 is also how Drive reports throttling and a full
 * account, neither of which is an authorization problem.
 */
internal fun isDriveAuthFailure(e: HttpResponseException): Boolean =
    e.statusCode == 401 ||
        (e.statusCode == 403 && !isDriveRateLimited(e) && !isDriveQuotaExceeded(e))

internal fun isDriveRateLimited(e: HttpResponseException): Boolean {
    val content = e.content ?: return false
    return RATE_LIMIT_REASONS.any { content.contains(it, ignoreCase = true) }
}

internal fun isDriveQuotaExceeded(e: HttpResponseException): Boolean =
    e.content?.contains(QUOTA_EXCEEDED_REASON, ignoreCase = true) == true
