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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

@AndroidEntryPoint
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.backrecorderapp.STOP_RECORDING"

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
    private var audioRecorder: AudioRecorder? = null
    private var currentSegment: SegmentEntity? = null
    private var currentWaveformFile: File? = null
    private var amplitudeBuffer = mutableListOf<Float>()
    private var amplitudeJob: Job? = null
    private var rotationJob: Job? = null
    private var flushJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

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

        _isRunning.value = true

        serviceScope.launch {
            startRecordingLoop()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        stopCurrentRecording()
        // Fail every queued or in-flight flush before the jobs that would have served
        // them go away, so no caller is left suspended forever (issue #47).
        flushCoordinator.abandonAll(FlushAbandonedException())
        rotationJob?.cancel()
        flushJob?.cancel()
        amplitudeJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
    }

    private suspend fun startRecordingLoop() {
        startNewSegment()
        startAmplitudeCapture()

        rotationJob = serviceScope.launch {
            while (isActive) {
                delay(SegmentManager.SEGMENT_DURATION_MS)
                rotateSegment()
            }
        }

        flushJob = serviceScope.launch {
            flushCoordinator.serveRequests {
                try {
                    rotateSegment()
                } catch (e: Exception) {
                    Log.e(TAG, "Flush rotation failed", e)
                    throw e
                }
            }
        }
    }

    private suspend fun startNewSegment() {
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
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
                amplitudeBuffer.add(amplitude)
            }
        }
    }

    private suspend fun rotateSegment() = rotationMutex.withLock {
        val segmentToFinalize = currentSegment ?: return@withLock

        // Stop amplitude capture first to avoid concurrent access to the buffer
        amplitudeJob?.cancel()
        amplitudeJob = null

        // Stop current recording
        stopCurrentRecording()

        // Save waveform data for completed segment (safe — amplitude job is stopped)
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
