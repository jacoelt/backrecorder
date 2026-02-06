package com.backrecorderapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.backrecorderapp.ALPHA_DISABLED
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
    val isCloudEnabled = arrayOf(SubscriptionMode.TRIAL, SubscriptionMode.PREMIUM).contains(subscriptionMode)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        // ===== Trial Banner =====
        if (subscriptionMode == SubscriptionMode.FREE || subscriptionMode == SubscriptionMode.TRIAL) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (subscriptionMode == SubscriptionMode.FREE) {
                    Text(
                        text = stringResource(R.string.free_banner),
                        style = MaterialTheme.typography.bodyMedium.copy(),
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .clickable { onBannerClick() }
                    )
                }

                if (subscriptionMode == SubscriptionMode.TRIAL) {
                    Text(
                        text = stringResource(R.string.trial_banner, trialEndDate),
                        style = MaterialTheme.typography.bodyMedium.copy(),
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                    )
                }
            }
        }

        // ===== Header =====
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column (modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(1.dp))

                Text(
                    text = stringResource(R.string.subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .alpha(ALPHA_DISABLED)
                )

                Spacer(modifier = Modifier.height(3.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== Content =====
        when (screenMode) {
            MainScreenMode.RECORDING -> {
                RecordingContent(
                    startRecording = startRecording,
                    stopRecording = stopRecording,
                    isRecording = isRecording,
                    currentRecordingDuration = currentRecordingDuration,
                    useGDrive = useGDrive,
                    onToggleGDrive = onToggleGDrive,
                    isCloudEnabled = isCloudEnabled,
                    onSaveClick = { onModeChange(MainScreenMode.SAVING) },
                    formatDuration = { duration -> TimeHelper.formatDuration(duration) }
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
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.view_terms),
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable { onShowTerms() }
        )
    }
}
