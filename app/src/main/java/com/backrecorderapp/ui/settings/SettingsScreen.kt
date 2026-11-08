package com.backrecorderapp.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.backrecorderapp.R
import com.backrecorderapp.data.billing.SubscriptionStatus
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.ui.components.PlanChooserDialog
import com.backrecorderapp.util.findActivity
import com.backrecorderapp.util.messageRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onShowDisclaimer: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val showUpgrade by viewModel.showUpgradeDialog.collectAsState()
    val driveError by viewModel.driveError.collectAsState()
    val purchaseError by viewModel.purchaseError.collectAsState()
    val plansLoading by viewModel.plansLoading.collectAsState()
    val subscriptionPlans by viewModel.subscriptionPlans.collectAsState()
    val consentRequest by viewModel.consentRequest.collectAsState()
    val context = LocalContext.current

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onConsentResult(result.data)
    }

    LaunchedEffect(consentRequest) {
        consentRequest?.let { pendingIntent ->
            viewModel.onConsentLaunched()
            consentLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
        }
    }

    Scaffold(
        // See MainScreen: safeDrawing rather than the system-bars default, for display cutouts.
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                windowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Subscription status
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (state.subscriptionStatus) {
                        SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIAL ->
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_subscription),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when (state.subscriptionStatus) {
                            SubscriptionStatus.FREE -> "Free plan"
                            SubscriptionStatus.TRIAL -> stringResource(
                                R.string.trial_active,
                                state.trialDaysRemaining
                            )
                            SubscriptionStatus.ACTIVE -> "Premium"
                            SubscriptionStatus.EXPIRED -> "Subscription expired"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.subscriptionStatus == SubscriptionStatus.FREE ||
                        state.subscriptionStatus == SubscriptionStatus.EXPIRED
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.showPlanChooser() },
                            enabled = !plansLoading
                        ) {
                            Text(stringResource(R.string.upgrade_button))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Cloud storage section
            Text(
                text = stringResource(R.string.settings_cloud_storage),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            DriveAccountRow(
                connected = state.driveConnected,
                needsReauth = state.driveNeedsReauth,
                storageFull = state.driveStorageFull,
                onConnect = { viewModel.connectDrive() },
                onDisconnect = { viewModel.disconnectDrive() }
            )

            Spacer(modifier = Modifier.height(8.dp))

            CloudUploadOption(
                title = stringResource(R.string.settings_cloud_off),
                description = stringResource(R.string.settings_cloud_off_desc),
                selected = state.cloudUploadMode == CloudUploadMode.OFF,
                onClick = { viewModel.setCloudUploadMode(CloudUploadMode.OFF) }
            )

            CloudUploadOption(
                title = stringResource(R.string.settings_cloud_wifi_only),
                description = stringResource(R.string.settings_cloud_wifi_desc),
                selected = state.cloudUploadMode == CloudUploadMode.WIFI_ONLY,
                onClick = { viewModel.setCloudUploadMode(CloudUploadMode.WIFI_ONLY) }
            )

            CloudUploadOption(
                title = stringResource(R.string.settings_cloud_always),
                description = stringResource(R.string.settings_cloud_always_desc),
                selected = state.cloudUploadMode == CloudUploadMode.ALWAYS_ON,
                onClick = { viewModel.setCloudUploadMode(CloudUploadMode.ALWAYS_ON) }
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Legal section
            Text(
                text = stringResource(R.string.settings_legal),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowDisclaimer() }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_view_disclaimer),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }

    // Upgrade dialog for cloud storage
    if (showUpgrade) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpgradeDialog() },
            title = { Text(stringResource(R.string.upgrade_cloud_title)) },
            text = { Text(stringResource(R.string.upgrade_cloud_message)) },
            confirmButton = {
                Button(onClick = {
                    viewModel.dismissUpgradeDialog()
                    viewModel.showPlanChooser()
                }) {
                    Text(stringResource(R.string.upgrade_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpgradeDialog() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // The plan is chosen here rather than guessed: the subscription has a monthly and a yearly
    // base plan, and only one offer token can be sent to Play.
    subscriptionPlans?.let { plans ->
        PlanChooserDialog(
            plans = plans,
            onPlanSelected = { plan ->
                viewModel.launchPurchaseFlow(context.findActivity(), plan)
            },
            onDismiss = { viewModel.dismissPlanChooser() }
        )
    }

    purchaseError?.let { failure ->
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

    driveError?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDriveError() },
            title = { Text(stringResource(R.string.settings_drive_account)) },
            text = {
                Text(
                    stringResource(
                        when (error) {
                            DriveError.SIGN_IN_FAILED -> R.string.settings_drive_sign_in_failed
                            DriveError.ACCOUNT_MISMATCH ->
                                R.string.settings_drive_account_mismatch
                        }
                    )
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.dismissDriveError() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

@Composable
private fun DriveAccountRow(
    connected: Boolean,
    needsReauth: Boolean,
    storageFull: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_drive_account),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = if (connected) {
                    stringResource(R.string.settings_drive_connected)
                } else {
                    stringResource(R.string.settings_drive_not_connected)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (connected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (needsReauth) {
                Text(
                    text = stringResource(R.string.settings_drive_connect_prompt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (storageFull) {
                Text(
                    text = stringResource(R.string.settings_drive_storage_full),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        if (!connected || needsReauth) {
            Button(onClick = onConnect) {
                Text(stringResource(R.string.settings_drive_connect))
            }
        } else {
            OutlinedButton(onClick = onDisconnect) {
                Text(stringResource(R.string.settings_drive_disconnect))
            }
        }
    }
}

@Composable
private fun CloudUploadOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
