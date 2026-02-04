package com.backrecorderapp.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.backrecorderapp.ALPHA_DISABLED
import com.backrecorderapp.R

@Composable
fun RecordingContent(
    startRecording: () -> Unit,
    stopRecordingRequest: () -> Unit,
    isRecording: MutableState<Boolean>,
    currentRecordingDuration: MutableState<Int>,
    useGDrive: Boolean,
    onToggleGDrive: (Boolean) -> Unit,
    onShowPremiumDialog: () -> Unit,
    isFullVersionActive: Boolean,
    onSaveClick: () -> Unit,
    formatDuration: (Int) -> String,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {

        // ===== Controls =====
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isRecording.value)
                            stringResource(R.string.recording_started)
                        else
                            stringResource(R.string.recording_stopped),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = isRecording.value,
                        onCheckedChange = { checked ->
                            if (checked) startRecording()
                            else stopRecordingRequest()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.reminder),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .alpha(ALPHA_DISABLED)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== GDrive =====
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!isFullVersionActive) onShowPremiumDialog()
                        }
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
                        modifier = Modifier.alpha(
                            if (isFullVersionActive && !isRecording.value) 1f else ALPHA_DISABLED
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== Save =====
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = onSaveClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (isRecording.value) 1f else ALPHA_DISABLED)
                ) {
                    Text(stringResource(R.string.save_recording))
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (currentRecordingDuration.value > 0)
                        stringResource(
                            R.string.current_duration,
                            formatDuration(currentRecordingDuration.value)
                        )
                    else stringResource(R.string.no_duration),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .alpha(ALPHA_DISABLED)
                )
            }
        }
    }
}
