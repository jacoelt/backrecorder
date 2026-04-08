package com.backrecorderapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.backrecorderapp.ALPHA_DISABLED
import com.backrecorderapp.R
import com.backrecorderapp.services.RecordingService


@Composable
fun RecordingContent(
    startRecording: () -> Unit,
    stopRecording: () -> Unit,
    recordingStatus: RecordingService.RecordingStatus,
    currentRecordingDuration: Int,
    useGDrive: Boolean,
    onToggleGDrive: (Boolean) -> Unit,
    isCloudAvailable: Boolean,
    onSaveClick: () -> Unit,
    formatDuration: (Int) -> String,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {

        // ===== Recording control (secondary) =====
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(
                        when (recordingStatus) {
                            RecordingService.RecordingStatus.RECORDING -> R.string.stop_recording
                            RecordingService.RecordingStatus.STOPPED -> R.string.start_recording
                            RecordingService.RecordingStatus.PAUSED_BY_CALL -> R.string.cannot_change_recording
                        }
                    ),
                    style = MaterialTheme.typography.bodyLarge
                )

                Switch(
                    checked = recordingStatus == RecordingService.RecordingStatus.RECORDING,
                    enabled = recordingStatus != RecordingService.RecordingStatus.PAUSED_BY_CALL,
                    onCheckedChange = { checked ->
                        if (checked) startRecording()
                        else stopRecording()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== Cloud option =====
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.save_to_gdrive),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (!isCloudAvailable) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.premium_required),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.alpha(ALPHA_DISABLED)
                        )
                    }
                }

                Switch(
                    checked = useGDrive,
                    onCheckedChange = {
                        onToggleGDrive(it)
                    },
                    modifier = Modifier.alpha(
                        if (isCloudAvailable) 1f else ALPHA_DISABLED
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ===== Save action (contextual) =====
        Button(
            onClick = onSaveClick,
            enabled = currentRecordingDuration > 0,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.save_recording))
        }

        if (currentRecordingDuration > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.current_duration,
                    formatDuration(currentRecordingDuration)
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .alpha(ALPHA_DISABLED)
            )
        }
    }
}
