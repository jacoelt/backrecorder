package com.backrecorderapp.ui

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.backrecorderapp.ALPHA_DISABLED
import com.backrecorderapp.R
import com.backrecorderapp.services.RecordingService

@Composable
fun RecordingScreen(
    startRecording: () -> Unit,
    stopRecording: () -> Unit,
    isRecording: MutableState<Boolean>,
    currentRecordingDuration: MutableState<Int>,
    duration: MutableState<Int?>,
    totalWeight: MutableState<String>,
    startSaveRecording: () -> Unit,
    onDurationChange: (duration: Int) -> Unit,
    useGDrive: Boolean,
    onToggleGDrive: (Boolean) -> Unit,
    onShowTerms: () -> Unit,
    onShowPremiumDialog: () -> Unit,
    trialDaysRemaining: Int,
    isSubscriptionActive: Boolean,
    isFullVersionActive: Boolean,
) {
    val context = LocalContext.current
    var showStopDialog by remember { mutableStateOf(false) }

    // ===== Format minutes to hh:mm =====
    @SuppressLint("DefaultLocale")
    fun formatMinutesToHHMM(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return String.format("%dh%02d", h, m)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // ===== Trial Banner =====
        if (!isSubscriptionActive) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.days_remaining,
                        trialDaysRemaining,
                        trialDaysRemaining
                    ),
                    modifier = Modifier.clickable { onShowPremiumDialog() }
                )
            }
        }

        // ===== Header =====
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.Center)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== Scrollable content =====
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // ===== Status card =====
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isRecording.value)
                            stringResource(R.string.recording_started)
                        else
                            stringResource(R.string.recording_stopped),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(
                            R.string.current_duration,
                            formatMinutesToHHMM(currentRecordingDuration.value)
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ===== Controls card =====
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val durationValue = duration.value ?: RecordingService.DEFAULT_DURATION
                    // Recording switch
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.enable_recording),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = isRecording.value,
                            onCheckedChange = { checked ->
                                if (durationValue > 0) {
                                    if (checked) startRecording()
                                    else showStopDialog = true
                                } else {
                                    Toast.makeText(
                                        context,
                                        R.string.error_no_duration,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier.alpha(if (durationValue > 0) 1f else ALPHA_DISABLED),
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stringResource(R.string.reminder, durationValue),
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .alpha(ALPHA_DISABLED)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // ===== Native NumberPicker =====
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.max_duration),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(modifier = Modifier.width(24.dp))

                        DurationPicker(
                            duration = if (isFullVersionActive) duration.value
                                ?: AudioRecordingService.DEFAULT_DURATION else AudioRecordingService.DEFAULT_DURATION,
                            onDurationChange = {
                                if (isFullVersionActive) {
                                    duration.value = it
                                    onDurationChange(it)
                                }
                            },
                            enabled = isFullVersionActive && !isRecording.value,
                            onDisabledClick = {
                                if (isRecording.value) {
                                    Toast.makeText(
                                        context,
                                        R.string.error_cannot_change_while_recording,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else if (!isFullVersionActive) {
                                    onShowPremiumDialog()
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Calculated weight
                    Text(
                        text = stringResource(R.string.calculated_weight, totalWeight.value),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ===== Actions card =====
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Save button
                    Button(
                        onClick = {
                            if (isRecording.value) {
                                startSaveRecording()
                            } else {
                                Toast.makeText(
                                    context,
                                    R.string.error_no_recording_in_progress,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (isRecording.value) 1f else ALPHA_DISABLED),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.save_recording,
                                formatMinutesToHHMM(currentRecordingDuration.value)
                            ),
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // GDrive toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(true) {
                                if (!isFullVersionActive) {
                                    onShowPremiumDialog()
                                }
                            },
                    ) {
                        Text(
                            text = stringResource(R.string.setup_gdrive),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = useGDrive,
                            onCheckedChange = {
                                if (isRecording.value) {
                                    Toast.makeText(
                                        context,
                                        R.string.error_cannot_change_while_recording,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else if (!isFullVersionActive) {
                                    onShowPremiumDialog()
                                } else {
                                    onToggleGDrive(it)
                                }
                            },
                            modifier = Modifier.alpha(if (isFullVersionActive && !isRecording.value) 1f else 0.5f),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== Terms of Service Link =====
        Text(
            text = stringResource(R.string.view_terms),
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable { onShowTerms() }
        )
    }

    // ===== Stop recording dialog =====
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
                        startSaveRecording()
                        stopRecording()
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