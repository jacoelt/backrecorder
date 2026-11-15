package com.backrecorderapp.data.cloud

import android.content.Context
import android.util.Log
import com.backrecorderapp.R
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.di.ApplicationScope
import com.backrecorderapp.domain.GateResult
import com.backrecorderapp.domain.SubscriptionGate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts a copy of a recording the user has just saved locally into `BackRecorder/` on Drive
 * (issue #84).
 *
 * The local save is the product; this is a bonus copy. So nothing here can make a save slower,
 * fail it, or report an error for it: [enqueue] does not suspend, every precondition is evaluated
 * on the application scope afterwards, and a refusal to upload is silent. Only a failure *after*
 * the preconditions said yes is worth telling the user about - they were entitled to the copy and
 * did not get it.
 */
@Singleton
class SavedRecordingUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val driveCloudStorage: DriveCloudStorage,
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

        /**
         * Billing often has not answered yet when the user saves, and [SubscriptionGate.isPremium]
         * reports FREE until it does. Refusing on that would deny a paying subscriber the copy for
         * no better reason than Play being slow, so the upload waits - it is already asynchronous,
         * and nothing is on screen waiting for it.
         */
        private const val STATUS_RESOLVE_TIMEOUT_MS = 30_000L

        /** A staged file left behind by a process death is cache litter nothing else prunes. */
        private const val STALE_STAGED_FILE_AGE_MS = 24 * 60 * 60 * 1000L
    }

    private val _uploadFailure = MutableStateFlow<String?>(null)

    /** Set when an upload the user was entitled to ran out of attempts. */
    val uploadFailure: StateFlow<String?> = _uploadFailure.asStateFlow()

    fun dismissUploadFailure() {
        _uploadFailure.value = null
    }

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
                if (shouldUpload()) {
                    upload(staged, displayName)
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

    /**
     * The gate every new cloud entry point has to pass, plus the user's own transport choice:
     * someone who set uploads to Wi-Fi-only did not ask for this copy to go out over cellular.
     */
    private suspend fun shouldUpload(): Boolean {
        val mode = appPreferences.cloudUploadMode.first()
        if (mode == CloudUploadMode.OFF) return false

        val resolved = withTimeoutOrNull(STATUS_RESOLVE_TIMEOUT_MS) {
            subscriptionGate.statusResolved.first { it }
        }
        if (resolved == null) return false
        if (subscriptionGate.checkCloudAccess() is GateResult.Blocked) return false

        return driveCloudStorage.isInitialized && isCloudNetworkAvailable(context, mode)
    }

    private suspend fun upload(file: File, displayName: String) {
        val mode = appPreferences.cloudUploadMode.first()

        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                if (driveCloudStorage.uploadSavedRecording(file, displayName) != null) return
                Log.w(TAG, "Drive refused the upload of a saved recording (attempt $attempt)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Saved-recording upload attempt $attempt failed", e)
            }

            if (attempt < MAX_ATTEMPTS) {
                delay(RETRY_BASE_DELAY_MS * attempt)
                // Re-checked between attempts rather than once at the start: the retries span
                // several seconds, and a user who walked off Wi-Fi mid-way must not have the
                // remaining attempts go out over cellular.
                if (!isCloudNetworkAvailable(context, mode)) break
            }
        }

        reportFailure(displayName)
    }

    /**
     * The in-app message goes up first and unconditionally. The notification is the half that can
     * fail - permission, a dead channel - and it must not be able to take the message with it,
     * because that would turn a failed upload into a completely silent one.
     */
    /**
     * The in-app message goes up first and unconditionally. The notification is the half that can
     * fail - permission, a dead channel - and it must not be able to take the message with it,
     * because that would turn a failed upload into a completely silent one.
     */
    private fun reportFailure(displayName: String) {
        val text = context.getString(R.string.cloud_saved_upload_failed, displayName)
        _uploadFailure.value = text
        try {
            uploadFailureNotifier.notifyUploadFailed(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cannot post the upload-failure notification", e)
        }
    }
}
