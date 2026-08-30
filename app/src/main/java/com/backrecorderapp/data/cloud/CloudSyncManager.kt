package com.backrecorderapp.data.cloud

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.app.NotificationCompat
import com.backrecorderapp.BackRecorderApp
import com.backrecorderapp.MainActivity
import com.backrecorderapp.R
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.data.segment.SegmentManager
import com.google.api.client.http.HttpResponseException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val driveCloudStorage: DriveCloudStorage,
    private val segmentManager: SegmentManager,
    private val appPreferences: AppPreferences
) {
    companion object {
        private const val TAG = "CloudSyncManager"
        private const val SYNC_INTERVAL_MS = 30_000L // Check every 30 seconds
        private const val RECOVERY_NOTIFICATION_ID = 100
        private const val STALE_THRESHOLD_HOURS = 1L
        private const val STALE_GAP_THRESHOLD_HOURS = 12L
        private const val HOUR_MS = 60 * 60 * 1000L

        /**
         * Uploading is capped per tick so a full 24h backlog (~1440 segments, ~1 GB) is drained
         * over several passes instead of one uninterruptible run that ignores every later
         * cancellation and transport change.
         */
        private const val MAX_UPLOADS_PER_TICK = 20

        /** Doubling per consecutive failure, up to this many doublings. */
        private const val MAX_BACKOFF_SHIFT = 5
        private const val MAX_BACKOFF_MS = 15 * 60 * 1000L

        /** Drive reports throttling and a full account with 403, the same status as scope loss. */
        private val RATE_LIMIT_REASONS = listOf(
            "rateLimitExceeded",
            "userRateLimitExceeded",
            "storageQuotaExceeded"
        )

        private val RECOVERY_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Atomic because `startSync()` is reachable from the main thread (Settings) and from
     * Dispatchers.IO (app launch) at once. A plain read-cancel-write let both callers see the
     * same old job, orphaning one loop that kept hitting Drive and that `stopSync()` could no
     * longer reach.
     */
    private val syncJob = AtomicReference<Job?>(null)

    private val _recoveryMessage = MutableStateFlow<String?>(null)
    val recoveryMessage: StateFlow<String?> = _recoveryMessage.asStateFlow()

    private val _needsReauth = MutableStateFlow(false)

    /** True once Drive rejected our credentials and the user has to grant access again. */
    val needsReauth: StateFlow<Boolean> = _needsReauth.asStateFlow()

    fun startSync() {
        val job = scope.launch { runSyncLoop() }
        syncJob.getAndSet(job)?.cancel()
    }

    fun stopSync() {
        syncJob.getAndSet(null)?.cancel()
        // Nothing is going to clear this later: without a running loop the flag would keep the
        // reconnect prompt on screen for an account the user has already disconnected.
        _needsReauth.value = false
    }

    /** Called once the user has re-granted access, so the prompt clears without waiting a tick. */
    fun onAuthorizationRestored() {
        _needsReauth.value = false
    }

    private suspend fun CoroutineScope.runSyncLoop() {
        // The account may connect after the loop starts (or drop out mid-run), so folder setup
        // and the stale-file scan are deferred until Drive is actually reachable rather than
        // done once up front.
        var sessionPrepared = false
        var consecutiveFailures = 0

        while (isActive) {
            var failed = false
            try {
                val mode = appPreferences.cloudUploadMode.first()

                // Cleanup and the stale scan paginate the whole staging folder, so they have to
                // respect the transport choice exactly as uploads do. Without this a Wi-Fi-only
                // user pays for a full listing over cellular every thirty seconds.
                if (mode != CloudUploadMode.OFF &&
                    driveCloudStorage.isInitialized &&
                    isNetworkAvailable(mode)
                ) {
                    if (!sessionPrepared) {
                        driveCloudStorage.ensureFolderStructure()
                        // Only count the session as prepared once the scan actually finished; a
                        // half-completed recovery move has to be retried, not skipped forever.
                        sessionPrepared = handleStaleFiles()
                        if (sessionPrepared) _needsReauth.value = false
                    }
                    syncPendingSegments(mode)
                    cleanupOldCloudSegments()
                } else {
                    sessionPrepared = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpResponseException) {
                failed = true
                sessionPrepared = false
                if (isAuthFailure(e)) {
                    // Consent was revoked or the grant expired; uploads stay queued until the
                    // user reconnects, so surface it rather than retry forever.
                    Log.w(TAG, "Drive authorization needs to be renewed", e)
                    _needsReauth.value = true
                } else {
                    Log.e(TAG, "Sync error", e)
                }
            } catch (e: Exception) {
                failed = true
                Log.e(TAG, "Sync error", e)
            }

            consecutiveFailures = if (failed) consecutiveFailures + 1 else 0
            delay(backoffMs(consecutiveFailures))
        }
    }

    /**
     * Backs off after consecutive failures, so a Drive outage, a quota block or a dead grant does
     * not mean replaying the entire backlog every thirty seconds indefinitely.
     */
    private fun backoffMs(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return SYNC_INTERVAL_MS
        val shift = minOf(consecutiveFailures, MAX_BACKOFF_SHIFT)
        return minOf(SYNC_INTERVAL_MS shl shift, MAX_BACKOFF_MS)
    }

    private suspend fun syncPendingSegments(mode: CloudUploadMode) {
        val pending = segmentManager.getSegmentsPendingUpload()
        var uploaded = 0

        for (segment in pending) {
            if (uploaded >= MAX_UPLOADS_PER_TICK) {
                Log.i(TAG, "Per-tick upload cap reached; ${pending.size - uploaded} still queued")
                return
            }
            // The transport can change part-way through a long backlog: a Wi-Fi-only user who
            // walks out of range must not finish the queue over cellular.
            if (!isNetworkAvailable(mode)) return

            val file = File(segment.filePath)
            if (!file.exists()) continue

            try {
                val cloudFileId = driveCloudStorage.uploadSegment(file)
                if (cloudFileId != null) {
                    segmentManager.markAsUploaded(segment.id, cloudFileId)
                    uploaded++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpResponseException) {
                // Auth and quota failures are not per-segment; let the loop flag or back off.
                if (isAuthFailure(e) || isRateLimited(e)) throw e
                Log.e(TAG, "Failed to upload segment ${segment.id}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload segment ${segment.id}", e)
                // Will retry on next cycle
            }
        }
    }

    private suspend fun cleanupOldCloudSegments() {
        val bufferCutoff = System.currentTimeMillis() - SegmentManager.BUFFER_DURATION_MS
        val cloudFiles = driveCloudStorage.listStagingFiles()

        for (file in cloudFiles.filter { it.createdTime < bufferCutoff }) {
            try {
                driveCloudStorage.deleteFile(file.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpResponseException) {
                if (isAuthFailure(e) || isRateLimited(e)) throw e
                Log.e(TAG, "Failed to delete cloud file ${file.id}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete cloud file ${file.id}", e)
            }
        }
    }

    /**
     * Returns true when the scan finished. A partial recovery move returns false so the caller
     * leaves the session unprepared and retries, instead of abandoning the remaining files.
     */
    private suspend fun handleStaleFiles(): Boolean {
        val cloudFiles = driveCloudStorage.listStagingFiles()
        if (cloudFiles.isEmpty()) return true

        val now = System.currentTimeMillis()
        val bufferStart = now - SegmentManager.BUFFER_DURATION_MS

        // Find files outside the 24h window
        val staleFiles = cloudFiles.filter { it.createdTime < bufferStart }
        if (staleFiles.isEmpty()) return true

        // Span and gap use min/max rather than first/last: the list is ordered by Drive, and a
        // single out-of-order entry would otherwise invert the span and force a false recovery.
        val oldest = staleFiles.minOf { it.createdTime }
        val newest = staleFiles.maxOf { it.createdTime }

        // Thresholds: more than an hour of audio outside the window, or the newest stale file
        // sitting more than 12h before the window starts.
        val staleSpanHours = (newest - oldest) / HOUR_MS
        val gapHours = (bufferStart - newest) / HOUR_MS

        val needsRecovery =
            staleSpanHours >= STALE_THRESHOLD_HOURS || gapHours >= STALE_GAP_THRESHOLD_HOURS

        if (!needsRecovery) {
            // Few stale files, just delete them
            for (file in staleFiles) {
                try {
                    driveCloudStorage.deleteFile(file.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete stale file ${file.id}", e)
                }
            }
            return true
        }

        val recoveryFolderName = "recovery_${RECOVERY_DATE_FORMAT.format(Date(now))}"
        // Auth and quota failures propagate: the loop must see them rather than have the move
        // silently abandoned with the session marked prepared.
        val moved = driveCloudStorage.moveToRecoveryFolder(
            staleFiles.map { it.id },
            recoveryFolderName
        )

        if (moved == 0) {
            Log.e(TAG, "Recovery move for $recoveryFolderName moved nothing")
            return false
        }

        _recoveryMessage.value = context.getString(R.string.recovery_banner, recoveryFolderName)
        showRecoveryNotification(recoveryFolderName)

        return moved == staleFiles.size
    }

    /**
     * Drive answers 401 when the token is rejected and 403 when the grant no longer covers the
     * scope; both mean the user must re-authorize. But 403 is also how Drive reports throttling
     * and a full account, which need a back-off rather than a misleading reconnect prompt.
     */
    private fun isAuthFailure(e: HttpResponseException): Boolean =
        e.statusCode == 401 || (e.statusCode == 403 && !isRateLimited(e))

    private fun isRateLimited(e: HttpResponseException): Boolean {
        val content = e.content ?: return false
        return RATE_LIMIT_REASONS.any { content.contains(it, ignoreCase = true) }
    }

    fun dismissRecoveryMessage() {
        _recoveryMessage.value = null
    }

    private fun showRecoveryNotification(recoveryFolderName: String) {
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, BackRecorderApp.RECOVERY_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.recovery_notification_title))
            .setContentText(context.getString(R.string.recovery_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(RECOVERY_NOTIFICATION_ID, notification)
    }

    private fun isNetworkAvailable(mode: CloudUploadMode): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return when (mode) {
            CloudUploadMode.WIFI_ONLY -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            CloudUploadMode.ALWAYS_ON -> capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            CloudUploadMode.OFF -> false
        }
    }
}
