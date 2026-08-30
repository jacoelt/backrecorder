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
import com.backrecorderapp.domain.GateResult
import com.backrecorderapp.domain.SubscriptionGate
import com.google.api.client.http.HttpResponseException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val appPreferences: AppPreferences,
    private val subscriptionGate: SubscriptionGate
) {
    companion object {
        private const val TAG = "CloudSyncManager"
        private const val SYNC_INTERVAL_MS = 30_000L // Check every 30 seconds
        private const val RECOVERY_NOTIFICATION_ID = 100
        private const val STALE_THRESHOLD_HOURS = 1L
        private const val STALE_GAP_THRESHOLD_HOURS = 12L
        private const val HOUR_MS = 60 * 60 * 1000L

        /**
         * Uploading is capped per tick so a full 24h backlog (~1440 segments, ~1 GB) drains over
         * several passes instead of one uninterruptible run that ignores every later cancellation
         * and transport change. At 20 per 30s a full backlog still clears in well under two hours,
         * far inside the 24h eviction horizon.
         */
        private const val MAX_UPLOADS_PER_TICK = 20

        /** Recovery moves are two write requests each, so they are capped harder than uploads. */
        private const val MAX_RECOVERY_MOVES_PER_TICK = 200

        /**
         * Cleanup paginates the whole staging folder, which is far too expensive to repeat every
         * 30s just to find the ~1 file per minute that ages out of the buffer.
         */
        private const val CLEANUP_EVERY_N_TICKS = 30

        /** Doubling per consecutive failure, up to this many doublings. */
        private const val MAX_BACKOFF_SHIFT = 5
        private const val MAX_BACKOFF_MS = 15 * 60 * 1000L

        /**
         * Drive reports throttling with 403, the same status as scope loss. These reasons are
         * transient and want a back-off, not a reconnect prompt.
         */
        private val RATE_LIMIT_REASONS = listOf("rateLimitExceeded", "userRateLimitExceeded")

        /** Also a 403, but terminal: retrying cannot help until the user frees space. */
        private const val QUOTA_EXCEEDED_REASON = "storageQuotaExceeded"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Atomic because `startSync()` is reachable from the main thread (Settings) and from
     * Dispatchers.IO (app launch) at once. A plain read-cancel-write let both callers see the
     * same old job, orphaning one loop that kept hitting Drive and that `stopSync()` could no
     * longer reach.
     */
    private val syncJob = AtomicReference<Job?>(null)

    /** SimpleDateFormat is not thread-safe, so it is per-instance and only touched by the loop. */
    private val recoveryDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)

    private val _recoveryMessage = MutableStateFlow<String?>(null)
    val recoveryMessage: StateFlow<String?> = _recoveryMessage.asStateFlow()

    private val _needsReauth = MutableStateFlow(false)

    /** True once Drive rejected our credentials and the user has to grant access again. */
    val needsReauth: StateFlow<Boolean> = _needsReauth.asStateFlow()

    private val _storageFull = MutableStateFlow(false)

    /**
     * True once Drive reports the account is out of space. Unlike throttling this cannot clear on
     * its own, so it is surfaced rather than quietly retried - otherwise uploads stop for good
     * while the buffer keeps evicting recordings that were never backed up.
     */
    val storageFull: StateFlow<Boolean> = _storageFull.asStateFlow()

    fun startSync() {
        // Started lazily so the reference swap happens first: launching eagerly let a concurrent
        // stopSync() clear the reference while this job was already running, leaving a loop that
        // nothing could ever cancel.
        val job = scope.launch(start = CoroutineStart.LAZY) { runSyncLoop() }
        syncJob.getAndSet(job)?.cancel()
        if (syncJob.get() === job) job.start() else job.cancel()
    }

    fun stopSync() {
        syncJob.getAndSet(null)?.cancel()
        // Nothing is going to clear these later: without a running loop they would keep stale
        // prompts on screen for an account the user has already disconnected.
        _needsReauth.value = false
        _storageFull.value = false
        _recoveryMessage.value = null
    }

    /** Called once the user has re-granted access, so the prompt clears without waiting a tick. */
    fun onAuthorizationRestored() {
        _needsReauth.value = false
    }

    private suspend fun CoroutineScope.runSyncLoop() {
        // The stale scan runs once per authorized session. It is deliberately NOT reset when the
        // network drops or the mode changes - only when the grant does - because stale files can
        // only appear at session start, and re-scanning on every connectivity flap costs a full
        // paginated listing each time.
        var staleScanDone = false
        var consecutiveFailures = 0
        var ticksSinceCleanup = CLEANUP_EVERY_N_TICKS

        while (isActive) {
            var failed = false
            try {
                val mode = appPreferences.cloudUploadMode.first()

                if (canSyncNow(mode)) {
                    if (!staleScanDone) {
                        driveCloudStorage.ensureFolderStructure()
                        staleScanDone = handleStaleFiles()
                        if (staleScanDone) {
                            _needsReauth.value = false
                        } else {
                            // A recovery move that did not finish must be retried, and must back
                            // off - otherwise it re-paginates the whole folder every 30 seconds.
                            failed = true
                        }
                    }

                    syncPendingSegments(mode)

                    // Only safe once the stale scan completed: cleanup deletes on the same
                    // "older than the buffer" predicate as the recovery scan, so running it after
                    // a partial move would permanently delete exactly the files still queued to
                    // be rescued.
                    if (staleScanDone && ++ticksSinceCleanup >= CLEANUP_EVERY_N_TICKS) {
                        cleanupOldCloudSegments()
                        ticksSinceCleanup = 0
                    }
                    if (!failed) _storageFull.value = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpResponseException) {
                failed = true
                when {
                    isQuotaExceeded(e) -> {
                        Log.w(TAG, "Drive is out of space; uploads are paused", e)
                        _storageFull.value = true
                    }
                    isAuthFailure(e) -> {
                        // Consent was revoked or the grant expired; uploads stay queued until the
                        // user reconnects, so surface it rather than retry forever.
                        Log.w(TAG, "Drive authorization needs to be renewed", e)
                        _needsReauth.value = true
                        staleScanDone = false
                    }
                    else -> Log.e(TAG, "Sync error", e)
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
     * Cloud sync is premium, and this is the one place that enforces it - every entry point runs
     * through this loop, so the check cannot be forgotten at a new one.
     *
     * A blocked gate only skips the tick; it never writes the preference. At process start
     * billing has not answered yet and reports FREE for everyone, so persisting OFF here would
     * wipe a paying subscriber's configuration on every launch.
     */
    private suspend fun canSyncNow(mode: CloudUploadMode): Boolean {
        if (mode == CloudUploadMode.OFF) return false
        if (!subscriptionGate.statusResolved.value) return false
        if (subscriptionGate.checkCloudAccess() is GateResult.Blocked) return false
        if (!driveCloudStorage.isInitialized) return false
        // Cleanup and the stale scan paginate the whole staging folder, so they respect the
        // transport choice exactly as uploads do.
        return isNetworkAvailable(mode)
    }

    /**
     * Backs off after consecutive failures, so a Drive outage, a quota block or a dead grant does
     * not mean replaying the entire backlog every thirty seconds indefinitely.
     */
    internal fun backoffMs(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return SYNC_INTERVAL_MS
        val shift = minOf(consecutiveFailures, MAX_BACKOFF_SHIFT)
        return minOf(SYNC_INTERVAL_MS shl shift, MAX_BACKOFF_MS)
    }

    private suspend fun syncPendingSegments(mode: CloudUploadMode) {
        val pending = segmentManager.getSegmentsPendingUpload(MAX_UPLOADS_PER_TICK)
        if (pending.isEmpty()) return

        for (segment in pending) {
            // The transport can change part-way through a long backlog: a Wi-Fi-only user who
            // walks out of range must not finish the queue over cellular.
            if (!isNetworkAvailable(mode)) return

            val file = File(segment.filePath)
            if (!file.exists()) continue

            try {
                val cloudFileId = driveCloudStorage.uploadSegment(file)
                if (cloudFileId != null) {
                    // Non-cancellable: losing this write after the upload succeeded would leave
                    // an orphan on Drive and upload the same segment again next tick.
                    withContext(NonCancellable) {
                        segmentManager.markAsUploaded(segment.id, cloudFileId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpResponseException) {
                // Auth and quota failures are not per-segment; let the loop flag or back off.
                if (isAuthFailure(e) || isRateLimited(e) || isQuotaExceeded(e)) throw e
                Log.e(TAG, "Failed to upload segment ${segment.id}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload segment ${segment.id}", e)
                // Will retry on next cycle
            }
        }

        val remaining = segmentManager.countSegmentsPendingUpload()
        if (remaining > 0) Log.i(TAG, "$remaining segment(s) still queued for upload")
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
                if (isAuthFailure(e) || isRateLimited(e) || isQuotaExceeded(e)) throw e
                Log.e(TAG, "Failed to delete an aged cloud file", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete an aged cloud file", e)
            }
        }
    }

    /**
     * Returns true when the scan finished. A partial recovery move returns false so the caller
     * retries it - and, critically, holds off cleanup, which would otherwise delete exactly the
     * files still waiting to be rescued.
     */
    private suspend fun handleStaleFiles(): Boolean {
        val cloudFiles = driveCloudStorage.listStagingFiles()
        if (cloudFiles.isEmpty()) return true

        val now = System.currentTimeMillis()
        val bufferStart = now - SegmentManager.BUFFER_DURATION_MS

        // Find files outside the 24h window
        val staleFiles = cloudFiles.filter { it.createdTime < bufferStart }
        if (staleFiles.isEmpty()) {
            appPreferences.setPendingRecoveryFolder(null)
            return true
        }

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
                    Log.e(TAG, "Failed to delete a stale file", e)
                }
            }
            return true
        }

        // Reuse the folder from an interrupted run. A fresh timestamp each pass would scatter one
        // recording session across several folders, and only the last would be named to the user.
        val existingFolder = appPreferences.pendingRecoveryFolder.first()
        val recoveryFolderName = existingFolder
            ?: "recovery_${recoveryDateFormat.format(Date(now))}".also {
                appPreferences.setPendingRecoveryFolder(it)
            }

        val batch = staleFiles.take(MAX_RECOVERY_MOVES_PER_TICK).map { it.id }
        // Auth and quota failures propagate: the loop must see them rather than have the move
        // silently abandoned with the scan marked complete.
        val handled = driveCloudStorage.moveToRecoveryFolder(batch, recoveryFolderName)

        if (handled == 0) {
            Log.e(TAG, "Recovery move for $recoveryFolderName moved nothing")
            return false
        }

        // Announced once per folder, not once per pass: a resumed move must not re-fire a
        // high-priority notification and resurrect a banner the user already dismissed.
        if (existingFolder == null) {
            _recoveryMessage.value = context.getString(R.string.recovery_banner, recoveryFolderName)
            showRecoveryNotification(recoveryFolderName)
        }

        val complete = handled == batch.size && batch.size == staleFiles.size
        if (complete) appPreferences.setPendingRecoveryFolder(null)
        return complete
    }

    /**
     * Drive answers 401 when the token is rejected and 403 when the grant no longer covers the
     * scope; both mean the user must re-authorize. But 403 is also how Drive reports throttling
     * and a full account, neither of which is an authorization problem.
     */
    internal fun isAuthFailure(e: HttpResponseException): Boolean =
        e.statusCode == 401 ||
            (e.statusCode == 403 && !isRateLimited(e) && !isQuotaExceeded(e))

    internal fun isRateLimited(e: HttpResponseException): Boolean {
        val content = e.content ?: return false
        return RATE_LIMIT_REASONS.any { content.contains(it, ignoreCase = true) }
    }

    internal fun isQuotaExceeded(e: HttpResponseException): Boolean =
        e.content?.contains(QUOTA_EXCEEDED_REASON, ignoreCase = true) == true

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
