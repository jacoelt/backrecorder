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
    private var amplitudeBuffer = mutableListOf<Float>()
    private var amplitudeJob: Job? = null
    private var rotationJob: Job? = null
    private var flushJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Set at the top of [onDestroy], before anything is cancelled.
     *
     * The cancel-and-join below is the real guarantee that no segment is opened after teardown;
     * this is the backstop for the case where that join hits its timeout, so a rotation that
     * survives it still refuses to open a segment nothing would ever close.
     */
    @Volatile
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        val startTime = System.currentTimeMillis()
        val newSegment = segmentManager.prepareSegment(startTime)
        val audioFile = newSegment.audioFile
        currentWaveformFile = newSegment.waveformFile
        amplitudeBuffer.clear()

        val segment = segmentManager.createSegmentEntry(newSegment)
        currentSegment = segment

        try {
            val recorder = audioRecorderFactory.create()
            recorder.start(audioFile)
            audioRecorder = recorder

            // The other half of the teardown handshake described in `onDestroy`: publish the
            // recorder first, then re-read `destroyed`. If teardown began while this segment was
            // opening, close it here rather than leave it for an `onDestroy` that has already
            // looked. The segment keeps its row and its finalized file, exactly as an ordinary
            // stop would leave it.
            if (destroyed) stopCurrentRecording()
        } catch (e: Exception) {
            // Leave nothing half-open behind: the row and the empty file this reserved would
            // otherwise never be reclaimed, and an incomplete row matches every later range query.
            currentSegment = null
            currentWaveformFile = null
            runCatching { segmentManager.discardSegment(segment.id) }
                .exceptionOrNull()
                ?.let { Log.e(TAG, "Failed to discard the segment that could not be started", it) }
            throw SegmentStartException(e)
        }
    }

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
                // Capped: a rotation that fails leaves this buffer in place for the next segment,
                // and before the rotation loop caught its own errors that could run for hours
                // (#48). The cap is generous — twice a segment's worth — so a healthy segment is
                // never truncated.
                if (amplitudeBuffer.size < MAX_AMPLITUDE_SAMPLES) {
                    amplitudeBuffer.add(amplitude)
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

        // Safe now: the amplitude job has been joined, so nothing can be appending.
        currentWaveformFile?.let { file ->
            waveformRepository.saveAmplitudes(file, amplitudeBuffer.toList())
        }

        // Finalize segment in database
        segmentManager.finalizeSegment(segmentToFinalize.id, System.currentTimeMillis())

        // Prune old segments
        segmentManager.pruneOldSegments()

        // Start new segment and restart amplitude capture
        startNewSegment()
        startAmplitudeCapture()
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
        _isRunning.value = false
        delay(FOREGROUND_SETTLE_MS)
        stopSelf()
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
