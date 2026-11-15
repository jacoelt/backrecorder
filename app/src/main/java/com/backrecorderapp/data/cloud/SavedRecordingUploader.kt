package com.backrecorderapp.data.cloud

import android.content.Context
import android.util.Log
import com.backrecorderapp.R
import com.backrecorderapp.service.FlushCoordinator
import com.google.api.client.http.HttpResponseException
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.di.ApplicationScope
import com.backrecorderapp.domain.GateResult
import com.backrecorderapp.domain.SubscriptionGate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts a copy of a recording the user has just saved locally into `BackRecorder/` on Drive
 * (issue #84).
 *
 * The local save is the product; this is a bonus copy. So nothing here can make a save slower,
 * fail it, or report an error for it: [enqueue] does not suspend and every precondition is
 * evaluated on the application scope afterwards.
 *
 * What the user is told turns on whether they lost something they were promised, not on how far
 * the attempt got. Declining - uploads off, no entitlement, no grant, or billing never answering
 * - is silent, because nothing was ever going to happen. Being offline with the setup complete
 * is reported even though no attempt was made, and so is a run that exhausted its attempts.
 */
@Singleton
class SavedRecordingUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val driveCloudStorage: DriveCloudStorage,
    private val driveAuthManager: DriveAuthManager,
    private val appPreferences: AppPreferences,
    private val subscriptionGate: SubscriptionGate,
    private val uploadFailureNotifier: UploadFailureNotifier,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    companion object {
        private const val TAG = "SavedRecordingUploader"

        private const val STAGING_DIR = "saved_uploads"

        /** Three attempts in total, per the behaviour agreed on issue #84. */
        internal const val MAX_ATTEMPTS = 3

        private const val RETRY_BASE_DELAY_MS = 2_000L

        /** Drive penalises a throttle retried promptly, so that case waits far longer. */
        private const val THROTTLED_RETRY_DELAY_MS = 15_000L

        /**
         * A backstop, not the real bound. `execute()` blocks, so this cannot interrupt a stalled
         * transfer - it only fires once the call returns on its own. What actually bounds one is
         * google-http-client's own 20s connect / 20s read defaults, which are left alone on
         * purpose: the read timeout is per socket read rather than per transfer, so a large
         * upload still making progress never trips it, and widening them would only weaken the
         * one thing stopping a half-open socket from holding [uploadMutex] indefinitely. This
         * catches the case of a transfer trickling just fast enough never to trip those.
         */
        private const val UPLOAD_TIMEOUT_MS = 10 * 60 * 1000L

        /**
         * Billing often has not answered yet when the user saves, and [SubscriptionGate.isPremium]
         * reports FREE until it does. Refusing on that would deny a paying subscriber the copy for
         * no better reason than Play being slow, so the upload waits - it is already asynchronous,
         * and nothing is on screen waiting for it. A complimentary grant (#98) satisfies the same
         * wait without Play answering at all.
         */
        private const val STATUS_RESOLVE_TIMEOUT_MS = 30_000L

        /** The grant is restored asynchronously at launch; the same reasoning applies to it. */
        private const val GRANT_RESTORE_TIMEOUT_MS = 30_000L

        /**
         * A staged file left behind by a process death is only reclaimed by the next save or by
         * the platform clearing the cache, so each stage sweeps what an earlier one abandoned.
         */
        private const val STALE_STAGED_FILE_AGE_MS = 24 * 60 * 60 * 1000L
    }

    /**
     * One upload at a time. Several saves in a row would otherwise each launch a full-file
     * transfer in parallel through the one Drive client, competing for bandwidth, holding an IO
     * thread each and pushing Drive toward `userRateLimitExceeded` - the reason the sync loop
     * caps its own uploads per tick rather than draining the backlog at once.
     */
    private val uploadMutex = Mutex()

    /**
     * **Unlimited `Channel`, never a `StateFlow`** - the same reasoning as [FlushCoordinator].
     * A channel buffers with no receiver, so a failure raised while the user is on another screen
     * is delivered when they come back rather than dropped; and because each send is an event
     * rather than a value, two failures carrying the *same* text are both reported. A conflating
     * `StateFlow` silently swallowed the second, which is precisely the "tell the user" this
     * whole path exists for.
     */
    private val failures = Channel<String>(Channel.UNLIMITED)

    /** Emits once per upload that the user was entitled to and that ran out of attempts. */
    val uploadFailures: Flow<String> = failures.receiveAsFlow()

    /**
     * Takes ownership of [file] and uploads it as [displayName], or deletes it when the upload is
     * not wanted.
     *
     * The file is *moved* out of the caller's hands synchronously. The save deletes its temporary
     * files in a `finally` the moment it returns, which would otherwise pull this one out from
     * under an upload that is still reading it.
     */
    fun enqueue(file: File, displayName: String) {
        val staged = stage(file) ?: return

        applicationScope.launch {
            try {
                // Deliberately outside `uploadMutex`: the preconditions touch no Drive request
                // state, and `statusResolved` is a shared flow every caller can await at once.
                // Holding the lock across that wait made several saves queue 30 seconds each,
                // serially, for an answer they would all have got from the same one.
                when (eligibility()) {
                    Eligibility.UPLOAD -> upload(staged, displayName)
                    Eligibility.BLOCKED -> reportFailure(displayName)
                    Eligibility.DECLINED -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only logged. [upload] owns everything the user is told, so reporting here too
                // would both double-report a failed upload and announce one to someone whose
                // entitlement could not even be established - which is a silent refusal.
                Log.e(TAG, "Saved-recording upload failed outright", e)
            } finally {
                staged.delete()
            }
        }
    }

    private fun stage(file: File): File? {
        return try {
            val dir = File(context.cacheDir, STAGING_DIR).apply { mkdirs() }
            pruneStaleStagedFiles(dir)
            val staged = File(dir, "${System.currentTimeMillis()}_${file.name}")
            if (file.renameTo(staged)) staged else file.copyTo(staged, overwrite = true)
        } catch (e: Exception) {
            // The save itself succeeded; losing the bonus copy is not worth surfacing.
            Log.w(TAG, "Could not stage a saved recording for upload", e)
            null
        }
    }

    private fun pruneStaleStagedFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_STAGED_FILE_AGE_MS
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    private enum class Eligibility { UPLOAD, BLOCKED, DECLINED }

    /**
     * Whether this copy should be uploaded, and - when it should not - whether that is worth
     * telling the user about.
     *
     * The split is the point, and it turns on whether the user lost something they were
     * promised. Uploads off, or no entitlement, is `DECLINED` and silent.
     *
     * **No Drive grant is also `DECLINED`**, and that is not obvious: an upload mode other than
     * OFF does *not* imply a connected account. `SettingsViewModel` writes the mode before asking
     * for consent and deliberately leaves it set when the user backs out of the sheet, so
     * mode-on-with-no-grant is a normal, persistent state in which the sync loop simply never
     * becomes active. Reporting it would mean a failure notification on every single save,
     * forever, for a copy the user never granted access for.
     *
     * Being offline is `BLOCKED` and reported: there the setup is complete and the backup the
     * user expected genuinely did not happen - and the identical condition discovered one
     * attempt later was already being reported, so staying silent here made the commonest
     * version of it the invisible one.
     */
    private suspend fun eligibility(): Eligibility {
        val mode = appPreferences.cloudUploadMode.first()
        if (mode == CloudUploadMode.OFF) return Eligibility.DECLINED

        val resolved = withTimeoutOrNull(STATUS_RESOLVE_TIMEOUT_MS) {
            subscriptionGate.statusResolved.first { it }
        }
        if (resolved == null) return Eligibility.DECLINED
        if (subscriptionGate.checkCloudAccess() is GateResult.Blocked) return Eligibility.DECLINED

        // Awaited, not read once, for the same reason billing is: at launch the grant is restored
        // asynchronously, so a connected user who saves in that window would otherwise be read as
        // having no grant at all and lose the copy with nothing to retry it.
        val authorized = withTimeoutOrNull(GRANT_RESTORE_TIMEOUT_MS) {
            driveAuthManager.isAuthorized.first { it }
        }
        if (authorized == null) return Eligibility.DECLINED

        return if (isCloudNetworkAvailable(context, mode)) {
            Eligibility.UPLOAD
        } else {
            Eligibility.BLOCKED
        }
    }

    private enum class Attempt { UPLOADED, RETRYABLE, THROTTLED, TERMINAL }

    private suspend fun upload(file: File, displayName: String) {
        for (attempt in 1..MAX_ATTEMPTS) {
            when (val outcome = attemptUpload(file, displayName, attempt)) {
                Attempt.UPLOADED -> return
                Attempt.TERMINAL -> break
                else -> {
                    if (attempt == MAX_ATTEMPTS) break
                    // Backoff sits *outside* the lock: making the next save wait out this one's
                    // retry delays is the head-of-line blocking the lock exists to bound, not
                    // to create. Drive punishes a throttle retried promptly, so that case waits
                    // considerably longer than an ordinary transport failure.
                    val base = if (outcome == Attempt.THROTTLED) {
                        THROTTLED_RETRY_DELAY_MS
                    } else {
                        RETRY_BASE_DELAY_MS
                    }
                    delay(base * attempt)

                    // Re-read rather than captured once: the retries span several seconds. A user
                    // who turns uploads off in that window has *declined* the copy, so the run
                    // ends silently; losing the transport is not a decision they made, so that
                    // one is still reported.
                    val mode = appPreferences.cloudUploadMode.first()
                    if (mode == CloudUploadMode.OFF) return
                    if (!isCloudNetworkAvailable(context, mode)) break
                }
            }
        }

        reportFailure(displayName)
    }

    /**
     * One transfer, under the lock.
     *
     * The lock covers the transfer alone so that exactly one saved recording is on the wire at a
     * time through the single Drive client. What stops a stalled transfer holding it - and every
     * save queued behind it - is the socket timeout on the Drive request itself, since a
     * coroutine timeout cannot interrupt the blocking call underneath.
     */
    private suspend fun attemptUpload(file: File, displayName: String, attempt: Int): Attempt =
        uploadMutex.withLock {
            try {
                val uploadedId = withTimeout(UPLOAD_TIMEOUT_MS) {
                    driveCloudStorage.uploadSavedRecording(file, displayName)
                }
                if (uploadedId != null) return@withLock Attempt.UPLOADED
                Log.w(TAG, "Drive refused the upload of a saved recording (attempt $attempt)")
                Attempt.RETRYABLE
            } catch (e: TimeoutCancellationException) {
                // Caught before CancellationException, which it extends - a bare catch below
                // would read a timed-out attempt as the caller going away.
                Log.w(TAG, "Saved-recording upload attempt $attempt timed out")
                Attempt.RETRYABLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpResponseException) {
                Log.e(TAG, "Saved-recording upload attempt $attempt failed", e)
                when {
                    // Neither a dead grant nor a full account can be fixed by asking again, so
                    // spending the remaining attempts on them only delays telling the user.
                    isDriveAuthFailure(e) || isDriveQuotaExceeded(e) -> Attempt.TERMINAL
                    // A throttle *is* transient, and unlike the sync loop there is no later tick
                    // to pick it up - so it is retried, just not promptly.
                    isDriveRateLimited(e) -> Attempt.THROTTLED
                    else -> Attempt.RETRYABLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Saved-recording upload attempt $attempt failed", e)
                Attempt.RETRYABLE
            }
        }

    /**
     * The in-app message goes up first and unconditionally, then the notification, wrapped so an
     * unexpected throw out of the builder or the notification service cannot take the message
     * with it and turn a failed upload into a completely silent one.
     *
     * Neither half is guaranteed: `notify` drops the notification silently when the user has not
     * granted POST_NOTIFICATIONS (this app only asks for it in the start-recording flow), and the
     * in-app message is only seen if a main screen is there to show it. They cover different
     * absences rather than backing each other up.
     */
    private fun reportFailure(displayName: String) {
        val text = context.getString(R.string.cloud_saved_upload_failed, displayName)
        failures.trySend(text)
        try {
            uploadFailureNotifier.notifyUploadFailed(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cannot post the upload-failure notification", e)
        }
    }
}
