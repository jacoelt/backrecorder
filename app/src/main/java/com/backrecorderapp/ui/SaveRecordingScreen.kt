package com.backrecorderapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.backrecorderapp.R
import com.backrecorderapp.ui.models.SaveRecordingViewModel

@Composable
fun SaveRecordingScreen(
    viewModel: SaveRecordingViewModel,
    onSaveConfirmed: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    SaveRecordingContent(
        availableDurationMinutes = uiState.availableDurationMinutes,
        selectedDurationMinutes = uiState.selectedDurationMinutes,
        presetDurationsMinutes = uiState.presetDurationsMinutes,
        onPresetSelected = viewModel::onPresetSelected,
        onDurationChanged = viewModel::onDurationChanged,
        onSaveClicked = {
            onSaveConfirmed(viewModel.getSelectedDurationMinutes())
        },
        onCancelClicked = onDismiss
    )
}

@Composable
fun SaveRecordingContent(
    availableDurationMinutes: Int,
    selectedDurationMinutes: Int,
    presetDurationsMinutes: List<Int>,
    onPresetSelected: (Int) -> Unit,
    onDurationChanged: (Int) -> Unit,
    onSaveClicked: () -> Unit,
    onCancelClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column (
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {

        // Title
        Text(
            text = stringResource(id = R.string.save_recording_title),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Fake waveform preview
        FakeWaveformPreview(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Current selection text
        Text(
            text = stringResource(
                id = R.string.save_last_minutes,
                selectedDurationMinutes
            ),
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Preset buttons
        Row (
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            presetDurationsMinutes.forEach { duration ->
                OutlinedButton (
                    onClick = { onPresetSelected(duration) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(
                            id = R.string.minutes_short,
                            duration
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Duration slider (start handle only)
        Text(
            text = stringResource(
                id = R.string.available_minutes,
                availableDurationMinutes
            ),
            style = MaterialTheme.typography.bodySmall
        )

        Slider(
            value = selectedDurationMinutes.toFloat(),
            onValueChange = { value ->
                onDurationChanged(value.toInt())
            },
            valueRange = 1f..availableDurationMinutes.toFloat(),
            steps = availableDurationMinutes - 1
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton (onClick = onCancelClicked) {
                Text(text = stringResource(id = R.string.save_recording_cancel))
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button (onClick = onSaveClicked) {
                Text(text = stringResource(id = R.string.save_recording_save))
            }
        }
    }
}
