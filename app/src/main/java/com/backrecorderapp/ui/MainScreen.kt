package com.backrecorderapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.backrecorderapp.R
import com.backrecorderapp.helpers.SubscriptionMode
import com.backrecorderapp.helpers.TimeHelper
import com.backrecorderapp.ui.model.MainViewModel

enum class MainScreenMode {
    RECORDING,
    SAVING
}
@Composable
fun MainScreen(
    startRecording: () -> Unit,
    stopRecording: () -> Unit,
    isRecording: MutableState<Boolean>,
    currentRecordingDuration: MutableState<Int>,
    startSaveRecording: (start: Int, end: Int) -> Boolean,
    useGDrive: Boolean,
    onToggleGDrive: (Boolean) -> Unit,
    onShowTerms: () -> Unit,
    onBannerClick: () -> Unit,
    trialEndDate: String,
    viewModel: MainViewModel,
    screenMode: MainScreenMode,
    onModeChange: (MainScreenMode) -> Unit,
) {
    val subscriptionMode by viewModel.subscriptionMode.collectAsState()
    val isCloudAvailable =
        subscriptionMode == SubscriptionMode.TRIAL ||
                subscriptionMode == SubscriptionMode.PREMIUM

    Box(modifier = Modifier.fillMaxSize()) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {

            // ===== Trial / Free banner =====
            if (subscriptionMode == SubscriptionMode.FREE || subscriptionMode == SubscriptionMode.TRIAL) {
                Text(
                    text = if (subscriptionMode == SubscriptionMode.FREE)
                        stringResource(R.string.free_banner)
                    else
                        stringResource(R.string.trial_banner, trialEndDate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 12.dp)
                        .clickable(enabled = subscriptionMode == SubscriptionMode.FREE) {
                            onBannerClick()
                        }
                )
            }

            // ===== Recording state (dominant surface) =====
            Surface(
                color = if (isRecording.value)
                    MaterialTheme.colorScheme.errorContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.large,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isRecording.value)
                            stringResource(R.string.recording_on)
                        else
                            stringResource(R.string.recording_idle),
                        style = MaterialTheme.typography.titleLarge
                    )

                    if (isRecording.value) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = TimeHelper.formatDuration(currentRecordingDuration.value),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ===== Main content (delegated) =====
            when (screenMode) {
                MainScreenMode.RECORDING -> {
                    RecordingContent(
                        startRecording = startRecording,
                        stopRecording = stopRecording,
                        isRecording = isRecording,
                        currentRecordingDuration = currentRecordingDuration,
                        useGDrive = useGDrive,
                        onToggleGDrive = onToggleGDrive,
                        isCloudAvailable = isCloudAvailable,
                        onSaveClick = {
                            onModeChange(MainScreenMode.SAVING)
                        },
                        formatDuration = { duration ->
                            TimeHelper.formatDuration(duration)
                        }
                    )
                }

                MainScreenMode.SAVING -> {
                    SavingContent(
                        maxMinutes = currentRecordingDuration.value,
                        onConfirmSave = { start, end ->
                            if (startSaveRecording(start, end)) {
                                onModeChange(MainScreenMode.RECORDING)
                            }
                        },
                        onCancel = {
                            onModeChange(MainScreenMode.RECORDING)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }

        // ===== Footer (legal) =====
        Text(
            text = stringResource(R.string.view_terms),
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .clickable { onShowTerms() }
        )
    }
}
