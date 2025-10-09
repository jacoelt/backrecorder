package com.backrecorder

import android.Manifest.permission
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.backrecorder.services.AudioRecordingService
import com.backrecorder.ui.theme.BackRecorderTheme
import com.google.crypto.tink.config.TinkConfig
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private var recordingService: AudioRecordingService? = null
    private var isBound = false
    private val isRecording = mutableStateOf(false)
    private var saveCallback: ((Boolean) -> Unit)? = null
    private var duration = mutableIntStateOf(AudioRecordingService.DEFAULT_DURATION)
    private var currentRecordingDuration = mutableIntStateOf(0)
    private var totalWeight = mutableStateOf("")
    private lateinit var gDriveHelper: GDriveHelper
    private lateinit var settings: SettingsDataStore

    companion object {
        private const val TAG = "MainActivity"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioRecordingService.LocalBinder
            recordingService = binder.getService()
            isBound = true
            updateRecordingState()
            recordingService!!.registerCallbackCurrentDuration { updateCurrentRecordingDuration(it) }
            recordingService!!.registerGDriveHelper(gDriveHelper)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isBound = false
        }
    }

    private val saveAudioLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data?.data != null) {
            saveRecording(result.data!!.data!!, saveCallback)
        }
    }

    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startRecording(this.duration.intValue)
            } else {
                Toast.makeText(this, "Permission denied. Cannot record audio.", Toast.LENGTH_SHORT).show()
            }
        }

    private val authResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK || result.data != null) {
            gDriveHelper.handleAuthResponse(result.data)
        } else {
            Log.e("MainActivity", "Auth canceled or failed")
        }
    }

    override fun onStart() {
        super.onStart()
        TinkConfig.register()
        settings = SettingsDataStore.getInstance(this.applicationContext)
        gDriveHelper = GDriveHelper(this.applicationContext, authResultLauncher) { success -> afterGoogleSignIn(success) }

        lifecycleScope.launch {
            duration.intValue = settings.getRecordingDuration(AudioRecordingService.DEFAULT_DURATION)
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

        if (ContextCompat.checkSelfPermission(applicationContext, permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this@MainActivity,
                arrayOf(permission.READ_EXTERNAL_STORAGE, permission.WRITE_EXTERNAL_STORAGE),
                2222
            )
        }

        setContent {
            BackRecorderTheme {
                val useGDrive by settings.getUseGDriveFlow().collectAsState(initial = false)
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val audioFileName = "record_$timeStamp.ogg"

                var hasAcceptedTerms by remember { mutableStateOf<Boolean?>(null) }
                var showTerms by remember { mutableStateOf(false) }
                var mustAcceptTerms by remember { mutableStateOf(false) }

                // Load acceptance state
                LaunchedEffect(Unit) {
                    hasAcceptedTerms = settings.isTermsAccepted()
                    if (hasAcceptedTerms == false) {
                        showTerms = true
                        mustAcceptTerms = true
                    }
                }

                // Display either Terms screen or main content
                if (showTerms && mustAcceptTerms) {
                    TermsOfUseScreen(
                        onAccepted = {
                            hasAcceptedTerms = true
                            showTerms = false
                            mustAcceptTerms = false
                        },
                        onDeclined = { finish() },
                        readOnly = false
                    )

                } else if (showTerms && !mustAcceptTerms) {
                    TermsOfUseScreen(
                        onClosed = { showTerms = false },
                        readOnly = true
                    )

                } else {
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
                                settings.setRecordingDuration(this@MainActivity.duration.intValue)
                            }
                        },
                        useGDrive = useGDrive,
                        onToggleGDrive = { enabled ->
                            lifecycleScope.launch {
                                settings.setUseGDrive(enabled)
                                if (enabled) setupGDrive()
                            }
                        },
                        onShowTerms = { showTerms = true }
                    )
                }
            }
        }
    }

    private fun startRecording(duration: Int) {
        if (ContextCompat.checkSelfPermission(this, permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(this, AudioRecordingService::class.java)
            intent.putExtra(AudioRecordingService.DURATION_INTENT_KEY, duration)
            ContextCompat.startForegroundService(this, intent)
            this.isRecording.value = true
        } else {
            requestAudioPermissionLauncher.launch(permission.RECORD_AUDIO)
        }
    }

    private fun stopRecording() {
        Log.d("MainActivity", "stopRecording")
        recordingService?.stopRecording()
        isRecording.value = false
        stopService(Intent(this, AudioRecordingService::class.java))
    }

    private fun saveRecording(uri: Uri, callback: ((Boolean) -> Unit)?) {
        val audioFiles = recordingService?.getFileList() ?: return
        val mergedFile = File(getExternalFilesDir(null), "merged_audio.ogg")
        if (mergedFile.exists()) mergedFile.delete()

        mergeAudioFiles(audioFiles, mergedFile) { isSuccess: Boolean, tempFile: File ->
            fun cleanUp() {
                mergedFile.delete()
                tempFile.delete()
                callback?.invoke(isSuccess)
            }

            if (isSuccess) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(mergedFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                lifecycleScope.launch {
                    if (settings.getUseGDrive()) {
                        gDriveHelper.uploadFile(
                            mergedFile,
                            GDriveHelper.FolderType.FINAL,
                            fileName = "record_$timeStamp.ogg"
                        ) { cleanUp() }
                    } else cleanUp()
                }
            } else cleanUp()
        }
    }

    private fun mergeAudioFiles(inputFiles: MutableList<File>, outputFile: File, onComplete: (Boolean, File) -> Unit) {
        val tempFileList = File(outputFile.parent, "file_list.txt")
        if (tempFileList.exists()) tempFileList.delete()

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
        isRecording.value = recordingService?.isRecording() ?: false
    }

    private fun generateTotalWeightString(duration: Int): String {
        if (duration <= 0) return "0 B"
        var weight = AudioRecordingService.BIT_RATE.toDouble() / 8 * duration * 60 / 1024
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

    private fun setupGDrive() {
        gDriveHelper.launchSignIn()
    }

    private fun afterGoogleSignIn(success: Boolean) {
        if (success) gDriveHelper.setupDrive()
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
    onDurationChange: (duration: Int) -> Unit,
    useGDrive: Boolean,
    onToggleGDrive: (Boolean) -> Unit,
    onShowTerms: () -> Unit,
) {
    val context = LocalContext.current
    var showStopDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isRecording.value)
                stringResource(R.string.recording_started)
            else
                stringResource(R.string.recording_stopped),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Switch(
            checked = isRecording.value,
            enabled = duration.value > 0,
            onCheckedChange = { checked ->
                if (checked) startRecording(duration.value)
                else showStopDialog = true
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = duration.value.toString(),
            enabled = !isRecording.value,
            onValueChange = {
                duration.value = it.toIntOrNull() ?: 0
                onDurationChange(duration.value)
            },
            label = { Text(stringResource(R.string.max_duration)) },
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.calculated_weight, totalWeight.value),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { onSaveRecording { isSuccess ->
                if (!isSuccess)
                    Toast.makeText(context, R.string.error_on_recording_save, Toast.LENGTH_SHORT).show()
            } },
            enabled = isRecording.value
        ) {
            Text(text = pluralStringResource(R.plurals.save_recording, currentRecordingDuration.value, currentRecordingDuration.value))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Text(text = stringResource(R.string.setup_gdrive), modifier = Modifier.weight(1f))
            Switch(
                checked = useGDrive,
                onCheckedChange = onToggleGDrive,
                enabled = !isRecording.value
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onShowTerms) {
            Text(stringResource(R.string.view_terms))
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
                        onSaveRecording { if (it) stopRecording() }
                    }) { Text(stringResource(R.string.stop_recording_confirm_save)) }

                    TextButton(onClick = {
                        showStopDialog = false
                        stopRecording()
                    }) { Text(stringResource(R.string.stop_recording_confirm_discard)) }

                    TextButton(onClick = { showStopDialog = false }) {
                        Text(stringResource(R.string.stop_recording_confirm_cancel))
                    }
                }
            },
            dismissButton = {}
        )
    }
}
