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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.backrecorderapp.R
import com.backrecorderapp.data.billing.SubscriptionStatus
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.domain.SubscriptionGate
import com.backrecorderapp.ui.components.PlanChooserDialog
import com.backrecorderapp.ui.components.UpgradeButton
import com.backrecorderapp.util.findActivity
import com.backrecorderapp.util.messageRes

/**
 * A tag rather than the label text: a Material 3 text field does not reliably expose its label in
 * the semantics tree, and the instrumented redemption test has to type into this exact field.
 */
const val REDEEM_CODE_FIELD_TEST_TAG = "redeem_code_field"

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
    val upgradeBusy by viewModel.upgradeBusy.collectAsState()
    val subscriptionPlans by viewModel.subscriptionPlans.collectAsState()
    val consentRequest by viewModel.consentRequest.collectAsState()
    val redeemDialogVisible by viewModel.redeemDialogVisible.collectAsState()
    val redeemOutcome by viewModel.redeemOutcome.collectAsState()
    val context = LocalContext.current

    // This view model is not always torn down on the way out: settings navigates *forward* to
    // the disclaimer, which keeps its back-stack entry alive, so an upgrade left in flight would
    // raise its chooser over this screen on the way back. A configuration change disposes too
    // and must not be mistaken for leaving - the screen is coming straight back.
    val activity = context.findActivity()
    DisposableEffect(activity) {
        onDispose {
            if (activity?.isChangingConfigurations != true) viewModel.onScreenLeft()
        }
    }

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
                    containerColor = if (
                        SubscriptionGate.isPremium(
                            state.subscriptionStatus,
                            state.complimentaryAccess
                        )
                    ) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
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
                        // A complimentary grant replaces the plan line rather than sitting beside
                        // it: the billing status underneath stays FREE for these users forever by
                        // design, and showing it would contradict the entitlement they have.
                        text = if (state.complimentaryAccess) {
                            stringResource(R.string.settings_complimentary_access)
                        } else {
                            when (state.subscriptionStatus) {
                                SubscriptionStatus.FREE -> "Free plan"
                                SubscriptionStatus.TRIAL -> stringResource(
                                    R.string.trial_active,
                                    state.trialDaysRemaining
                                )
                                SubscriptionStatus.ACTIVE -> "Premium"
                                SubscriptionStatus.EXPIRED -> "Subscription expired"
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.complimentaryAccess) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_complimentary_access_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (state.subscriptionStatus == SubscriptionStatus.FREE ||
                        state.subscriptionStatus == SubscriptionStatus.EXPIRED
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        UpgradeButton(
                            busy = upgradeBusy,
                            onClick = { viewModel.showPlanChooser() }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.showRedeemDialog() }) {
                            Text(stringResource(R.string.settings_redeem_code))
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
                syncBlockedByPlan = state.driveSyncBlockedByPlan,
                showReauthPrompt = state.showDriveReauthPrompt,
                showStorageFull = state.showDriveStorageFull,
                onConnect = { viewModel.connectDrive() },
                onDisconnect = { viewModel.disconnectDrive() }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // The stored mode is kept on lapse (it is restored on re-subscribe), so it is dimmed
            // rather than reset: "Always on" at full strength repeats the claim the Drive row
            // above just stopped making. Still tappable - a tap is what raises the upgrade offer.
            if (state.cloudUploadBlockedByPlan) {
                Text(
                    text = stringResource(R.string.settings_cloud_upload_paused),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            CloudUploadOption(
                title = stringResource(R.string.settings_cloud_off),
                description = stringResource(R.string.settings_cloud_off_desc),
                selected = state.cloudUploadMode == CloudUploadMode.OFF,
                dimmed = false,
                onClick = { viewModel.setCloudUploadMode(CloudUploadMode.OFF) }
            )

            CloudUploadOption(
                title = stringResource(R.string.settings_cloud_wifi_only),
                description = stringResource(R.string.settings_cloud_wifi_desc),
                selected = state.cloudUploadMode == CloudUploadMode.WIFI_ONLY,
                dimmed = state.cloudUploadBlockedByPlan,
                onClick = { viewModel.setCloudUploadMode(CloudUploadMode.WIFI_ONLY) }
            )

            CloudUploadOption(
                title = stringResource(R.string.settings_cloud_always),
                description = stringResource(R.string.settings_cloud_always_desc),
                selected = state.cloudUploadMode == CloudUploadMode.ALWAYS_ON,
                dimmed = state.cloudUploadBlockedByPlan,
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
                // The dialog stays up, with the button busy, until the plans arrive: asking Play
                // can take seconds, and closing onto a blank screen is the dead button again.
                UpgradeButton(
                    busy = upgradeBusy,
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

    subscriptionPlans?.let { plans ->
        PlanChooserDialog(
            plans = plans,
            busy = upgradeBusy,
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

    if (redeemDialogVisible) {
        RedeemCodeDialog(
            rejected = redeemOutcome == RedeemOutcome.REJECTED,
            onRedeem = { viewModel.redeemCode(it) },
            onEdited = { viewModel.dismissRedeemOutcome() },
            onDismiss = { viewModel.dismissRedeemDialog() }
        )
    }

    // Shown outside the dialog, because acceptance closes it: the grant is the whole point and
    // it should be confirmed rather than inferred from the card changing behind it.
    if (redeemOutcome == RedeemOutcome.ACCEPTED && !redeemDialogVisible) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRedeemOutcome() },
            title = { Text(stringResource(R.string.settings_redeem_accepted_title)) },
            text = { Text(stringResource(R.string.settings_redeem_accepted_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissRedeemOutcome() }) {
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
private fun RedeemCodeDialog(
    rejected: Boolean,
    onRedeem: (String) -> Unit,
    onEdited: () -> Unit,
    onDismiss: () -> Unit
) {
    // Survives rotation: orientation cannot be restricted at this target on large screens, and a
    // re-created activity that silently emptied the field would make the code look rejected.
    var code by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_redeem_code)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_redeem_code_message))
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    // Clearing the rejection on edit is what makes a second refusal visible: the
                    // outcome is a conflated StateFlow value, so re-reporting REJECTED over a
                    // REJECTED that was never cleared recomposes to nothing and the tap looks
                    // like it was dropped.
                    onValueChange = { code = it; if (rejected) onEdited() },
                    modifier = Modifier.testTag(REDEEM_CODE_FIELD_TEST_TAG),
                    singleLine = true,
                    isError = rejected,
                    label = { Text(stringResource(R.string.settings_redeem_code_label)) }
                )
                if (rejected) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_redeem_rejected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onRedeem(code) },
                enabled = code.isNotBlank()
            ) {
                Text(stringResource(R.string.settings_redeem_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun DriveAccountRow(
    connected: Boolean,
    syncBlockedByPlan: Boolean,
    showReauthPrompt: Boolean,
    showStorageFull: Boolean,
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
            // A grant that outlived its subscription is still a grant, so the row says so rather
            // than claiming "Not connected" next to a Disconnect button - but it must not read as
            // working, in colour any more than in words.
            val uploadsWorking = connected && !syncBlockedByPlan
            Text(
                text = when {
                    !connected -> stringResource(R.string.settings_drive_not_connected)
                    syncBlockedByPlan ->
                        stringResource(R.string.settings_drive_connected_upgrade_required)
                    else -> stringResource(R.string.settings_drive_connected)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (uploadsWorking) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (showReauthPrompt) {
                Text(
                    text = stringResource(R.string.settings_drive_connect_prompt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (showStorageFull) {
                Text(
                    text = stringResource(R.string.settings_drive_storage_full),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        if (!connected || showReauthPrompt) {
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
    dimmed: Boolean,
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
            onClick = onClick,
            // Both slots, or the blocked options dim inconsistently: the selected one fades
            // while the other keeps a full-strength ring.
            colors = if (dimmed) {
                RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                RadioButtonDefaults.colors()
            }
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (dimmed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    Color.Unspecified
                }
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
