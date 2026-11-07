package com.backrecorderapp.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.backrecorderapp.BackRecorderApp
import com.backrecorderapp.MainActivity
import com.backrecorderapp.R
import com.backrecorderapp.data.db.SegmentEntity
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.segment.SegmentManager
import com.backrecorderapp.data.waveform.WaveformRepository
import com.backrecorderapp.service.recorder.AudioRecorder
import com.backrecorderapp.service.recorder.AudioRecorderFactory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject

/**
 * Thrown when a segment's recorder will not start, so the rotation loop can tell "this service can
 * no longer record" apart from the IO and database failures it is expected to shrug off (#48).
 */
class SegmentStartException(cause: Throwable) :
    IllegalStateException("Could not start recording a new segment", cause)

@AndroidEntryPoint
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        private const val NOTIFICATION_ID = 1

        /** Distinct from [NOTIFICATION_ID]: the foreground notification is removed as we stop. */
        private const val FAILURE_NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.backrecorderapp.STOP_RECORDING"

        /**
         * How long a failed start waits before stopping the service.
         *
         * `startForegroundService` obliges the service to reach the foreground, and tearing it
         * down in the same breath makes the framework raise
         * `ForegroundServiceDidNotStartInTimeException` and kill the process — observed within
         * 40 ms of the start on an API 35 emulator, which is exactly the window a recorder that
         * cannot open the microphone lands in. Letting the foreground start settle first turns a
         * crash into an orderly stop, and on an error path nobody notices the delay.
         */
        private const val FOREGROUND_SETTLE_MS = 1_000L

        /** Twice the samples a healthy segment produces, so only a stuck buffer is ever capped. */
        private const val MAX_AMPLITUDE_SAMPLES =
            (2 * SegmentManager.SEGMENT_DURATION_MS / WaveformRepository.SAMPLE_INTERVAL_MS).toInt()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val flushCoordinator = FlushCoordinator()

        /**
         * Force the current in-progress segment to be finalized (MOOV atom written)
         * so it can be read by MediaExtractor. Suspends until the rotation completes.
         * Returns immediately if the service is not running.
         *
         * Throws [FlushAbandonedException] if the service is torn down before the
         * request can be served, so the caller gets an error rather than hanging.
         */
        suspend fun flushCurrentSegment() {
            if (!_isRunning.value) return
            flushCoordinator.requestFlush()
        }

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingService::class.java)
            context.stopService(intent)
        }
    }

    @Inject lateinit var segmentManager: SegmentManager
    @Inject lateinit var waveformRepository: WaveformRepository
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var audioRecorderFactory: AudioRecorderFactory

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rotationMutex = Mutex()

    /**
     * Written by the rotation coroutine and read by the amplitude coroutine, both on
     * `Dispatchers.Default` but not necessarily the same thread, and read again by `onDestroy` on
     * the main thread. `@Volatile` is what gives those reads a happens-before edge against the
     * write (#48).
     */
    @Volatile
    private var audioRecorder: AudioRecorder? = null

    private var currentSegment: SegmentEntity? = null
    private var currentWaveformFile: File? = null

    /** Always reached through [withAmplitudeBuffer]; never touched directly. */
    private val amplitudeBuffer = mutableListOf<Float>()
    private val amplitudeBufferLock = Any()

    private var amplitudeJob: Job? = null
    private var rotationJob: Job? = null
    private var flushJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Set at the very top of [onDestroy], before anything is cancelled, and read by
     * [startNewSegment] both before it opens a segment and again after it publishes the recorder.
     *
     * This is one half of the teardown handshake [onDestroy] describes — not a backstop for a
     * join, because teardown deliberately never joins.
     */
    @Volatile
    private var destroyed = false

    /**
     * The most recent `startId`, so a self-stop that loses a race against a fresh start does not
     * tear that start down: `stopSelf(startId)` is a no-op once a newer one has arrived.
     */
    @Volatile
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            serviceScope.launch {
                appPreferences.setWasRecordingBeforeShutdown(false)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        // Accept flush requests from the moment the UI can see the service as running —
        // the collector only comes up inside startRecordingLoop() below (issue #47).
        flushCoordinator.reopen()
        _isRunning.value = true

        serviceScope.launch {
            try {
                startRecordingLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The collector lives at the end of startRecordingLoop(), so a failure
                // before it means nothing will ever serve a queued request (issue #47).
                Log.e(TAG, "Recording loop failed to start", e)
                flushCoordinator.abandonAll(FlushAbandonedException())
                stopBecauseRecordingFailed()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        _isRunning.value = false
        // Leave the foreground explicitly rather than letting the service record be torn down
        // underneath it. A service that stops moments after `startForeground` — which is exactly
        // what a recorder that refuses to open now causes — otherwise risks the framework's
        // "startForegroundService() did not then call startForeground()" crash, and this also
        // takes the recording notification down at once instead of on teardown's schedule.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Fail every queued or in-flight flush before the jobs that would have served
        // them go away, so no caller is left suspended forever (issue #47).
        flushCoordinator.abandonAll(FlushAbandonedException())

        rotationJob?.cancel()
        flushJob?.cancel()
        amplitudeJob?.cancel()

        // Stopping used to race a rotation already past its own stop: it would go on to run
        // `startNewSegment()`, be cancelled at the next suspension point, and leave a started
        // recorder with no owner — the microphone held for the life of the process (#48).
        //
        // Cancelling is only a request, and *waiting* for it here is not an option: this is the
        // main thread, a cancelled rotation cannot abandon a native muxer call part-way, and a
        // blocking join measured in seconds is what the framework's foreground-service timeout
        // and the ANR watchdog both count against. So the two sides shake hands instead. Both
        // `destroyed` and `audioRecorder` are `@Volatile`, and each side writes its own before
        // reading the other's: this side sets `destroyed` above and reads `audioRecorder` here,
        // while `startNewSegment` publishes the recorder and then re-reads `destroyed`. At least
        // one of the two must observe the other, so a recorder started during teardown is always
        // stopped by somebody — and since `AudioRecorder.stop()` is idempotent, both doing it is
        // harmless.
        stopCurrentRecording()
        serviceScope.cancel()
        releaseWakeLock()
    }

    private suspend fun startRecordingLoop() {
        startNewSegment()
        startAmplitudeCapture()

        rotationJob = serviceScope.launch {
            while (isActive) {
                delay(SegmentManager.SEGMENT_DURATION_MS)
                try {
                    rotateSegment()
                } catch (e: SegmentStartException) {
                    // The service can no longer record. Carrying on would produce an empty file
                    // and a row every minute behind a notification that says it is recording.
                    Log.e(TAG, "Cannot open a new segment; stopping the service", e)
                    stopBecauseRecordingFailed()
                    return@launch
                } catch (e: CancellationException) {
                    // Must be rethrown, never swallowed by the catch below: it extends
                    // IllegalStateException, so a bare `catch (e: Exception)` would turn teardown
                    // into "rotation failed" and keep looping in a cancelled scope (#47).
                    throw e
                } catch (e: Exception) {
                    // One IO or database error used to cancel `rotationJob` outright, silently:
                    // the service kept recording into a single segment that was never finalized
                    // while `amplitudeBuffer` grew for hours (#48). Log and take the next tick.
                    Log.e(TAG, "Segment rotation failed; continuing with the next one", e)
                }
            }
        }

        flushJob = serviceScope.launch {
            flushCoordinator.serveRequests {
                try {
                    rotateSegment()
                } catch (e: SegmentStartException) {
                    // The old segment was finalized, so the caller's save can still use it — but
                    // the replacement never opened, so stop rather than pretend to record (#48).
                    Log.e(TAG, "Flush could not reopen a segment; stopping the service", e)
                    // Launched rather than awaited: the caller's flush must fail now, not after
                    // the settle delay below, and `SaveRecordingUseCase` bounds its own wait.
                    serviceScope.launch { stopBecauseRecordingFailed() }
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Flush rotation failed", e)
                    throw e
                }
            }
        }
    }

    /**
     * Opens the next segment. Throws [SegmentStartException] if the recorder will not start.
     *
     * The failure is surfaced rather than logged and swallowed (#48). Swallowing left
     * `audioRecorder` null while `currentSegment` stayed set, so every following minute wrote an
     * empty file and another database row while the user watched a notification that said the app
     * was recording.
     */
    private suspend fun startNewSegment() {
        // Teardown has begun; opening a segment now would leave one nothing is going to close.
        if (destroyed) return

        var reservedId: String? = null
        try {
            val newSegment = segmentManager.prepareSegment(System.currentTimeMillis())
            val segment = segmentManager.createSegmentEntry(newSegment)
            reservedId = segment.id

            // Published only once the row exists. Assigning the waveform path first left the
            // service pointing at a new segment's file while `currentSegment` still named the old
            // one if the insert failed, and the next rotation wrote one segment's amplitudes into
            // the other's file.
            currentSegment = segment
            currentWaveformFile = newSegment.waveformFile
            withAmplitudeBuffer { it.clear() }

            val recorder = audioRecorderFactory.create()
            recorder.start(newSegment.audioFile)
            audioRecorder = recorder

            // The other half of the teardown handshake described in `onDestroy`: publish the
            // recorder first, then re-read `destroyed`. If teardown began while this segment was
            // opening, close it here rather than leave it for an `onDestroy` that has already
            // looked. The segment keeps its row and its finalized file, exactly as an ordinary
            // stop would leave it.
            if (destroyed) stopCurrentRecording()
        } catch (e: CancellationException) {
            // Teardown, not a failure to record. Wrapping it would make the rotation loop stop the
            // service for "cannot open a segment" when the service is already going away.
            throw e
        } catch (e: Exception) {
            // Leave nothing half-open behind: the row and the empty file this reserved would
            // otherwise never be reclaimed, and an incomplete row matches every later range query.
            currentSegment = null
            currentWaveformFile = null
            reservedId?.let { id ->
                try {
                    segmentManager.discardSegment(id)
                } catch (discardFailure: CancellationException) {
                    throw discardFailure
                } catch (discardFailure: Exception) {
                    Log.e(TAG, "Failed to discard the segment that could not be started", discardFailure)
                }
            }
            throw SegmentStartException(e)
        }
    }

    /**
     * Runs [block] with exclusive access to [amplitudeBuffer].
     *
     * [rotateSegment] joins the amplitude coroutine before reading the buffer, but `onDestroy`
     * cannot join anything — it runs on the main thread — so its bare `cancel()` can leave the
     * coroutine mid-append while a surviving rotation reads. A plain `ArrayList` mutated from two
     * threads is the exact defect #48 item 6 names; a lock closes it on *every* path rather than
     * only the one that can afford to wait.
     */
    private inline fun <T> withAmplitudeBuffer(block: (MutableList<Float>) -> T): T =
        synchronized(amplitudeBufferLock) { block(amplitudeBuffer) }

    private fun startAmplitudeCapture() {
        amplitudeJob?.cancel()
        amplitudeJob = serviceScope.launch {
            while (isActive) {
                delay(WaveformRepository.SAMPLE_INTERVAL_MS)
                val amplitude = try {
                    audioRecorder?.maxAmplitude()?.let {
                        // Normalize to 0.0-1.0 range (max amplitude is ~32767 for 16-bit)
                        (it / 32767f).coerceIn(0f, 1f)
                    } ?: 0f
                } catch (e: Exception) {
                    0f
                }
                // Capped as a last resort: the buffer is normally drained and cleared by every
                // rotation, but before the rotation loop caught its own errors a stuck rotation
                // let it grow for hours (#48).
                withAmplitudeBuffer { buffer ->
                    if (buffer.size < MAX_AMPLITUDE_SAMPLES) buffer.add(amplitude)
                }
            }
        }
    }

    private suspend fun rotateSegment() = rotationMutex.withLock {
        val segmentToFinalize = currentSegment ?: return@withLock

        // Stop amplitude capture and *wait* for it. Cancellation alone is only a request: the
        // coroutine could still be between its delay and its `add`, so the `toList()` below raced
        // a plain `ArrayList` being mutated, and `startNewSegment`'s `clear()` raced it too (#48).
        amplitudeJob?.cancelAndJoin()
        amplitudeJob = null

        // Stop current recording
        stopCurrentRecording()

        // The housekeeping for the segment just closed. It runs in its own try because the next
        // segment must open whatever happens here: the recorder is already stopped at this point,
        // so letting a failed waveform write or a locked database skip the reopen would leave the
        // service alive and recording *nothing* until the next tick a minute later — behind a
        // notification saying it is recording. With the rotation loop now surviving errors, a
        // persistent failure (a full disk) would do that for hours (#48).
        val housekeepingFailure = try {
            val amplitudes = withAmplitudeBuffer { buffer ->
                buffer.toList().also { buffer.clear() }
            }
            currentWaveformFile?.let { file ->
                waveformRepository.saveAmplitudes(file, amplitudes)
            }
            segmentManager.finalizeSegment(segmentToFinalize.id, System.currentTimeMillis())
            segmentManager.pruneOldSegments()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }

        // Always reopen, on every path out of the housekeeping above.
        startNewSegment()
        startAmplitudeCapture()

        // Only now report the failure. Held back so the reopen above cannot be skipped, but not
        // swallowed: a flush that could not finalize its segment must fail its caller rather than
        // let `SaveRecordingUseCase` proceed as though the segment were complete.
        housekeepingFailure?.let { throw it }
    }

    /**
     * Stops the service because it cannot record, rather than leaving it in the foreground behind
     * a notification that says it is (#48).
     *
     * `_isRunning` drops immediately so the UI stops claiming to record; the actual stop waits out
     * [FOREGROUND_SETTLE_MS] so the framework does not kill the process for leaving the foreground
     * too soon after entering it.
     */
    private suspend fun stopBecauseRecordingFailed() {
        val startId = latestStartId
        _isRunning.value = false

        // Fail any save waiting on a flush now, rather than leaving it to time out against a
        // service that has already decided it cannot record (#47's handshake, #48's stop path).
        flushCoordinator.abandonAll(FlushAbandonedException())

        // Tell the user. A service that goes away silently leaves someone who started a background
        // recording hours ago to discover the gap when their save comes back empty, and #48's
        // defect 4 asks for the failure to be surfaced, not just acted on.
        notifyRecordingStopped()

        // Crash recovery must not restart a session that just failed; it would fail the same way.
        runCatching { appPreferences.setWasRecordingBeforeShutdown(false) }
            .exceptionOrNull()
            ?.let { Log.e(TAG, "Failed to clear the crash-recovery flag", it) }

        delay(FOREGROUND_SETTLE_MS)
        // Bound to this start, so a recording the user began during the settle above survives.
        stopSelf(startId)
    }

    private fun notifyRecordingStopped() {
        val notification = NotificationCompat.Builder(this, BackRecorderApp.RECOVERY_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_recording_failed_title))
            .setContentText(getString(R.string.notification_recording_failed_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    2,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(FAILURE_NOTIFICATION_ID, notification)
        }.exceptionOrNull()?.let { Log.e(TAG, "Could not post the recording-failed notification", it) }
    }

    private fun stopCurrentRecording() {
        try {
            audioRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder", e)
        }
        audioRecorder = null
    }

    private fun createNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, BackRecorderApp.RECORDING_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(getString(R.string.notification_recording_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop_recording), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BackRecorder::RecordingWakeLock"
        ).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}
