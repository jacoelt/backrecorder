package com.backrecorderapp.data.cloud

import android.util.Log
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Which Google account an access token actually belongs to. */
data class DriveAccountIdentity(
    /** Drive `permissionId`: stable for the account even if its address changes. */
    val accountId: String,
    val email: String?
)

/**
 * Resolves the account behind an access token.
 *
 * A seam rather than a private method so the grant state machine can be tested without a
 * network round-trip.
 */
interface DriveAccountIdentityResolver {
    /** Returns null when the account could not be determined, including on network failure. */
    suspend fun resolve(accessToken: String): DriveAccountIdentity?
}

/**
 * Asks Drive itself who the token belongs to, via `about.get`.
 *
 * Deliberately the token's own view of its identity rather than whatever account Play Services
 * reports: the question being answered is "whose Drive will this token write to", and only Drive
 * can answer that. `about.get` is permitted under the narrow `drive.file` scope.
 *
 * Builds a throwaway client bound to exactly the token passed in, so resolution never touches -
 * or depends on - the live session. That ordering matters: the caller has to be able to check
 * the identity *before* adopting a token, not after publishing it to the rest of the app.
 */
class DriveAboutIdentityResolver(
    private val ioDispatcher: CoroutineDispatcher
) : DriveAccountIdentityResolver {

    companion object {
        private const val TAG = "DriveIdentity"
        private const val APPLICATION_NAME = "Back Recorder"
    }

    override suspend fun resolve(accessToken: String): DriveAccountIdentity? =
        withContext(ioDispatcher) {
            try {
                val initializer = HttpRequestInitializer { request ->
                    request.headers.setAuthorization("Bearer $accessToken")
                }

                val user = Drive.Builder(
                    NetHttpTransport(),
                    GsonFactory.getDefaultInstance(),
                    initializer
                )
                    .setApplicationName(APPLICATION_NAME)
                    .build()
                    .about()
                    .get()
                    .setFields("user(emailAddress,permissionId)")
                    .execute()
                    .user

                val accountId = user?.permissionId
                if (accountId.isNullOrEmpty()) {
                    Log.w(TAG, "Drive returned no permissionId for the current token")
                    return@withContext null
                }
                DriveAccountIdentity(accountId, user.emailAddress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Treated as "unknown", never as "different account" - see DriveAuthManager,
                // which must not tear down a working grant over a failed lookup.
                Log.w(TAG, "Could not resolve the Drive account for this token", e)
                null
            }
        }
}
