package com.backrecorderapp.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.backrecorderapp.R
import com.backrecorderapp.domain.BlockReason
import com.backrecorderapp.ui.components.PlanChooserDialog
import com.backrecorderapp.ui.components.UpgradeButton
import com.backrecorderapp.ui.components.WaveformView
import com.backrecorderapp.util.TimeUtils
import com.backrecorderapp.util.findActivity
import com.backrecorderapp.util.messageRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // This view model outlives the screen - settings sits on top of it in the back stack - so a
    // plan load left running, or a chooser left open, is rendered again when the user comes back:
    // a dialog with no tap behind it, minutes later. A *configuration change* disposes too, and
    // must not be mistaken for leaving: the screen is coming straight back, and discarding the
    // chooser (or the load behind it) would make a rotation silently swallow the tap - which at
    // this target cannot be avoided by locking the orientation on large screens.
    val activity = context.findActivity()
    DisposableEffect(activity) {
        onDispose {
            if (activity?.isChangingConfigurations != true) viewModel.onScreenLeft()
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (audioGranted) {
            viewModel.toggleRecording()
        }
    }

    // File picker launcher
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mp4")
    ) { uri ->
        uri?.let { viewModel.saveRecording(it) }
    }

    // Show snackbar for messages
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(state.successMessage) {
        state.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissSuccess()
        }
    }

    // Launch file picker after subscription gate passes
    LaunchedEffect(state.launchSavePicker) {
        if (state.launchSavePicker) {
            val filename = "recording_${TimeUtils.formatForFilename(state.selectionStartTime)}.m4a"
            saveLauncher.launch(filename)
            viewModel.onSavePickerLaunched()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // safeDrawing, not the system-bars default, so display cutouts are cleared too.
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Back Recorder") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                // TopAppBarDefaults.windowInsets is system-bars-only, so the bar would still
                // sit under a landscape cutout while the body below it cleared one.
                windowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state.segments.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { viewModel.requestSave() },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    if (state.saveInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Save, contentDescription = "Save Recording")
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Disclaimer reminder
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                )
            ) {
                Text(
                    text = stringResource(R.string.disclaimer_reminder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Recovery banner
            state.recoveryMessage?.let { message ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        TextButton(onClick = { viewModel.dismissRecoveryMessage() }) {
                            Text("Dismiss")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Recording status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.isRecording) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                    )
                    Text(
                        text = "Recording",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        text = "Not recording",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Record / Stop button
            Button(
                onClick = {
                    if (state.isRecording) {
                        viewModel.toggleRecording()
                    } else {
                        // Check permissions
                        val permsNeeded = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
                        }

                        val allGranted = permsNeeded.all {
                            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                        }

                        if (allGranted) {
                            viewModel.toggleRecording()
                        } else {
                            permissionLauncher.launch(permsNeeded.toTypedArray())
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isRecording) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            ) {
                Icon(
                    imageVector = if (state.isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = if (state.isRecording) {
                        stringResource(R.string.stop_recording)
                    } else {
                        stringResource(R.string.start_recording)
                    },
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Waveform
            if (state.segments.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.select_time_range),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                TimePresetRow(
                    onLast5Min = { viewModel.selectLastDuration(5 * 60_000L) },
                    onLast15Min = { viewModel.selectLastDuration(15 * 60_000L) },
                    onLast1Hour = { viewModel.selectLastDuration(60 * 60_000L) },
                    onWholeBuffer = { viewModel.selectWholeBuffer() }
                )
                Spacer(modifier = Modifier.height(8.dp))

                WaveformView(
                    waveformData = state.waveformData,
                    bufferStartTime = state.bufferStartTime,
                    bufferEndTime = state.bufferEndTime,
                    selectionStartTime = state.selectionStartTime,
                    selectionEndTime = state.selectionEndTime,
                    onSelectionStartChanged = { viewModel.updateSelectionStart(it) },
                    onSelectionEndChanged = { viewModel.updateSelectionEnd(it) },
                    modifier = Modifier.fillMaxWidth()
                )
                // Clearance so the waveform (especially the end handle) can be
                // scrolled above the Save FAB in the bottom-right corner.
                Spacer(modifier = Modifier.height(88.dp))
            } else {
                Spacer(modifier = Modifier.height(48.dp))
                Text(
                    text = stringResource(R.string.no_recording_data),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    // Upgrade dialog
    if (state.showUpgradeDialog) {
        val title = when (state.upgradeReason) {
            BlockReason.SAVE_RANGE_EXCEEDS_FREE_LIMIT -> stringResource(R.string.upgrade_save_title)
            BlockReason.CLOUD_STORAGE_PREMIUM_ONLY -> stringResource(R.string.upgrade_cloud_title)
            null -> ""
        }
        val message = when (state.upgradeReason) {
            BlockReason.SAVE_RANGE_EXCEEDS_FREE_LIMIT -> stringResource(R.string.upgrade_save_message)
            BlockReason.CLOUD_STORAGE_PREMIUM_ONLY -> stringResource(R.string.upgrade_cloud_message)
            null -> ""
        }

        AlertDialog(
            onDismissRequest = { viewModel.dismissUpgradeDialog() },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                // The dialog stays up, with the button busy, until the plans arrive: asking Play
                // can take seconds, and closing onto a blank screen is the dead button again.
                UpgradeButton(
                    busy = state.plansLoading,
                    onClick = { viewModel.showPlanChooser() }
                )
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpgradeDialog() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    state.subscriptionPlans?.let { plans ->
        PlanChooserDialog(
            plans = plans,
            onPlanSelected = { plan ->
                viewModel.launchPurchaseFlow(context.findActivity(), plan)
            },
            onDismiss = { viewModel.dismissPlanChooser() }
        )
    }

    // Same dialog as the one in settings: both upgrade entry points report a refused hand-off
    // the same way.
    state.purchaseError?.let { failure ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPurchaseError() },
            title = { Text(stringResource(R.string.upgrade_error_title)) },
            text = { Text(stringResource(failure.messageRes())) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissPurchaseError() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

@Composable
private fun TimePresetRow(
    onLast5Min: () -> Unit,
    onLast15Min: () -> Unit,
    onLast1Hour: () -> Unit,
    onWholeBuffer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        AssistChip(
            onClick = onWholeBuffer,
            label = { Text(stringResource(R.string.preset_all)) }
        )
        AssistChip(
            onClick = onLast1Hour,
            label = { Text(stringResource(R.string.preset_1_hour)) }
        )
        AssistChip(
            onClick = onLast15Min,
            label = { Text(stringResource(R.string.preset_15_min)) }
        )
        AssistChip(
            onClick = onLast5Min,
            label = { Text(stringResource(R.string.preset_5_min)) }
        )
    }
}
