package com.backrecorderapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.backrecorderapp.R


enum class PaywallReason {
    RECORDING_DURATION,
    CLOUD_BACKUP,
    GENERIC
}

data class PaywallContent(
    val title: Int,
    val freeLimitExplanation: Int
)

@Composable
fun paywallContentFor(reason: PaywallReason): PaywallContent {
    return when (reason) {
        PaywallReason.RECORDING_DURATION -> PaywallContent(
            title = R.string.paywall_title_long_recording,
            freeLimitExplanation = R.string.paywall_free_limit_recording
        )

        PaywallReason.CLOUD_BACKUP -> PaywallContent(
            title = R.string.paywall_title_cloud,
            freeLimitExplanation = R.string.paywall_free_limit_cloud
        )

        PaywallReason.GENERIC -> PaywallContent(
            title = R.string.paywall_title_generic,
            freeLimitExplanation = R.string.paywall_free_limit_generic
        )
    }
}

@Composable
fun PremiumPaywallDialog(
    reason: PaywallReason,
    yearlyPrice: String,
    monthlyPrice: String,
    trialEndDate: String,
    onStartTrial: () -> Unit,
    onDismiss: () -> Unit
) {
    val content = paywallContentFor(reason)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = stringResource(content.title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = stringResource(content.freeLimitExplanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                Column(
                    horizontalAlignment = Alignment.Start,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.paywall_premium_content_1))
                    Text(stringResource(R.string.paywall_premium_content_2))
                }

                Spacer(Modifier.height(20.dp))

                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.paywall_best_value),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Text(
                            text = yearlyPrice,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = monthlyPrice,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = onStartTrial,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.paywall_start_trial))
                }

                Spacer(Modifier.height(8.dp))

                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.paywall_continue_free))
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = stringResource(
                        R.string.paywall_trial_disclaimer,
                        trialEndDate
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
