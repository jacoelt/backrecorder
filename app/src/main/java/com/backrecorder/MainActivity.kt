package com.backrecorder

import android.Manifest.permission
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.backrecorder.services.AudioRecordingService
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.map
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.backrecorder.ui.theme.BackRecorderTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
val DURATION_KEY = intPreferencesKey("duration")

class MainActivity : ComponentActivity() {

    private lateinit var saveAudioLauncher: ActivityResultLauncher<Intent>
    private var recordingService: AudioRecordingService? = null
    private var isBound = false
    private val isRecording = mutableStateOf(false)
    private var saveCallback: ((Boolean) -> Unit)? = null
    private var duration = mutableIntStateOf(AudioRecordingService.DEFAULT_DURATION)
    private var currentRecordingDuration = mutableIntStateOf(0)
    private var totalWeight = mutableStateOf("")


//    companion object {
//    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioRecordingService.LocalBinder
            recordingService = binder.getService()
            isBound = true
            updateRecordingState()
            recordingService!!.registerCallbackCurrentDuration { updateCurrentRecordingDuration(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isBound = false
        }
    }

    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Permission granted, start recording
                startRecording(this.duration.intValue)
            } else {
                // Permission denied, show a message
                Toast.makeText(this, "Permission denied. Cannot record audio.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            duration.intValue = getSavedDuration()
            totalWeight.value = generateTotalWeightString(duration.intValue)
        }
        Intent(this, AudioRecordingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //Ask for permissions
        if (ContextCompat.checkSelfPermission(applicationContext, permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(permission.READ_EXTERNAL_STORAGE, permission.WRITE_EXTERNAL_STORAGE), 2222)
        } else if (ContextCompat.checkSelfPermission(applicationContext, permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(permission.READ_EXTERNAL_STORAGE, permission.WRITE_EXTERNAL_STORAGE), 2222)
        }

        saveAudioLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data?.data != null) {
                saveRecording(result.data!!.data!!, saveCallback)
            }
        }

        setContent {
            BackRecorderTheme {
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val audioFileName = "record_$timeStamp.ogg"

                // Passing callbacks as parameters to RecordingScreen
                RecordingScreen(
                    startRecording = { duration -> startRecording(duration) },
                    stopRecording = { stopRecording() },
                    isRecording = isRecording,
                    currentRecordingDuration,
                    duration,
                    totalWeight,
                    onSaveRecording = { callback: (Boolean) -> Unit ->
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            type = "audio/ogg"
                            putExtra(Intent.EXTRA_TITLE, audioFileName)
                        }

                        saveCallback = callback
                        saveAudioLauncher.launch(intent)
                    },
                    onDurationChange = {
                        this.duration.intValue = it
                        this.totalWeight.value = this.generateTotalWeightString(this.duration.intValue)

                        lifecycleScope.launch {
                            saveDuration()
                        }
                    }
                )
            }
        }
    }

    private fun startRecording(duration: Int) {
        if (ContextCompat.checkSelfPermission(this, permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            // Permission already granted, start recording
            val intent = Intent(this, AudioRecordingService::class.java)
            intent.putExtra(AudioRecordingService.DURATION_KEY, duration)
            ContextCompat.startForegroundService(this, intent)

            this.isRecording.value = true
        } else {
            // Request permission
            requestAudioPermissionLauncher.launch(permission.RECORD_AUDIO)
        }
    }

    private fun stopRecording() {
        Log.d("MainActivity", "stopRecording")
        recordingService?.stopRecording()
        isRecording.value = false

        // Stop the foreground service
        val intent = Intent(this, AudioRecordingService::class.java)
        stopService(intent)
    }

    private fun saveRecording(uri: Uri, callback: ((Boolean) -> Unit)?) {
        // Step 1: Get all the audio files (assuming they're stored in a directory)
        val audioFiles = recordingService?.getFileList() ?: return

        // Step 2: Merge the audio files into a single file
        val mergedFile = File(getExternalFilesDir(null), "merged_audio.ogg")
        if (mergedFile.exists()) { mergedFile.delete() }

        mergeAudioFiles(audioFiles, mergedFile) { isSuccess: Boolean, tempFile: File ->
            // Step 3: Write the file
            if (isSuccess) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(mergedFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } else {
                Log.e("FFmpeg", "File merge failed")
            }

            // Cleanup
            if (mergedFile.exists()) { mergedFile.delete() }
            if (tempFile.exists()) { tempFile.delete() }

            if (callback != null) {
                callback(isSuccess)
            }
        }
    }

    private fun mergeAudioFiles(inputFiles: MutableList<File>, outputFile: File, onComplete: (Boolean, File) -> Unit) {
        // Create a temporary file list
        val tempFileList = File(outputFile.parent, "file_list.txt")
        if (tempFileList.exists()) { tempFileList.delete() }

        tempFileList.bufferedWriter().use { writer ->
            inputFiles.forEach { file ->
                writer.write("file '${file.absolutePath}'\n")
            }
        }

        val command = "-f concat -safe 0 -i ${tempFileList.absolutePath} -c copy ${outputFile.absolutePath}"

        FFmpegKit.executeAsync(command) { session ->
            val returnCode = session.returnCode
            if (ReturnCode.isSuccess(returnCode)) {
                Log.d("FFmpeg", "Merging successful!")
                onComplete(true, tempFileList)
            } else {
                Log.e("FFmpeg", "Merging failed: ${session.failStackTrace}")
                onComplete(false, tempFileList)
            }
        }
    }

    private fun updateRecordingState() {
        // Update UI based on recording state
        isRecording.value = recordingService?.isRecording() ?: false
    }

    private fun generateTotalWeightString(duration: Int): String {
        if (duration <= 0) {
            return "0 B"
        }

        var weight = AudioRecordingService.BIT_RATE.toDouble() / 8 * duration * 60 / 1024 // Always at least in KB
        var unit = "KB"

        if (weight > 1024 * 1024) {
            weight /= 1024 * 1024
            unit = "GB"
        } else if (weight > 1024) {
            weight /= 1024
            unit = "MB"
        }

        return DecimalFormat("#.##").format(weight) + " " + unit
    }

    private fun updateCurrentRecordingDuration(duration: Int) {
        currentRecordingDuration.intValue = duration
    }

    private suspend fun saveDuration() {
        this.dataStore.edit { settings: MutablePreferences ->
            settings[DURATION_KEY] = this.duration.intValue
        }
    }

    private suspend fun getSavedDuration(): Int {
        return (this.dataStore.data.map { settings: Preferences ->
                settings[DURATION_KEY] ?: AudioRecordingService.DEFAULT_DURATION
            }).first()
    }
}


@Composable
fun RecordingScreen(
    startRecording: (Int) -> Unit,
    stopRecording: () -> Unit,
    isRecording: MutableState<Boolean>,
    currentRecordingDuration: MutableState<Int>,
    duration: MutableState<Int>,
    totalWeight: MutableState<String>,
    onSaveRecording: (callback: (Boolean) -> Unit) -> Unit,
    onDurationChange: (duration: Int) -> Unit
) {
    val context = LocalContext.current
    var showStopDialog by remember { mutableStateOf(false) }
    val weightString by remember { mutableStateOf(totalWeight) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Status message
        Text(
            text = if (isRecording.value) context.resources.getString(R.string.recording_started) else context.resources.getString(R.string.recording_stopped),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Switch to control recording
        Switch(
            checked = isRecording.value,
            enabled = duration.value > 0,
            onCheckedChange = { checked ->
                if (checked) {
                    startRecording(duration.value)
                } else {
                    showStopDialog = true
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // TextField for maximum duration
        OutlinedTextField(
            value = duration.value.toString(),
            enabled = !isRecording.value,
            onValueChange = {
                duration.value = if (it != "") it.toInt() else 0
                onDurationChange(duration.value)
            },
            label = { Text(stringResource(R.string.max_duration)) },
            keyboardOptions = KeyboardOptions.Default.copy(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Text(text = stringResource(R.string.calculated_weight, weightString.value), style = MaterialTheme.typography.titleMedium)

        Spacer(modifier = Modifier.height(16.dp))

        // Save Recording Button
        Button(
            onClick = { onSaveRecording { isSuccess: Boolean ->
                if (!isSuccess) {
                    Toast.makeText(context, context.resources.getString(R.string.error_on_recording_save), Toast.LENGTH_SHORT).show()
                }

            } },
            enabled = isRecording.value,
            ) {
            Text(text = pluralStringResource(R.plurals.save_recording, currentRecordingDuration.value, currentRecordingDuration.value))
        }
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text(stringResource(R.string.stop_recording_confirm_title)) },
            text = { Text(stringResource(R.string.stop_recording_confirm_text)) },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(onClick = {
                        showStopDialog = false
                        onSaveRecording { isSuccess: Boolean ->
                            if (isSuccess) {
                                stopRecording()
                            }
                        }
                    }) {
                        Text(stringResource(R.string.stop_recording_confirm_save))
                    }
                    TextButton(onClick = {
                        showStopDialog = false
                        stopRecording()
                    }) {
                        Text(stringResource(R.string.stop_recording_confirm_discard))
                    }
                    TextButton(onClick = { showStopDialog = false }) {
                        Text(stringResource(R.string.stop_recording_confirm_cancel))
                    }
                }
            },
            dismissButton = {}
        )
    }
}

