package com.backrecorderapp.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.backrecorderapp.BackRecorderApp
import com.backrecorderapp.MainActivity
import com.backrecorderapp.R
import com.backrecorderapp.data.db.SegmentEntity
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.segment.SegmentManager
import com.backrecorderapp.data.waveform.WaveformRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.backrecorderapp.STOP_RECORDING"

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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mediaRecorder: MediaRecorder? = null
    private var currentSegment: SegmentEntity? = null
    private var currentWaveformFile: File? = null
    private var amplitudeBuffer = mutableListOf<Float>()
    private var amplitudeJob: Job? = null
    private var rotationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            serviceScope.launch {
                appPreferences.setRecordingEnabled(false)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        serviceScope.launch {
            startRecordingLoop()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCurrentRecording()
        rotationJob?.cancel()
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
    }

    private suspend fun startNewSegment() {
        val startTime = System.currentTimeMillis()
        val audioFile = segmentManager.createSegmentFile(startTime)
        val waveformFile = segmentManager.createWaveformFile(startTime)
        currentWaveformFile = waveformFile
        amplitudeBuffer.clear()

        val segment = segmentManager.createSegmentEntry(
            filePath = audioFile.absolutePath,
            waveformPath = waveformFile.absolutePath,
            startTime = startTime
        )
        currentSegment = segment

        try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                setAudioChannels(1)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
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
                    mediaRecorder?.maxAmplitude?.let {
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

    private suspend fun rotateSegment() {
        val segmentToFinalize = currentSegment ?: return

        // Stop current recording
        stopCurrentRecording()

        // Save waveform data for completed segment
        currentWaveformFile?.let { file ->
            waveformRepository.saveAmplitudes(file, amplitudeBuffer.toList())
        }

        // Finalize segment in database
        segmentManager.finalizeSegment(segmentToFinalize.id, System.currentTimeMillis())

        // Prune old segments
        segmentManager.pruneOldSegments()

        // Start new segment
        startNewSegment()
        startAmplitudeCapture()
    }

    private fun stopCurrentRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder", e)
        }
        mediaRecorder = null
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
