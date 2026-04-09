package com.backrecorderapp.ui.disclaimer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.backrecorderapp.R

@Composable
fun DisclaimerScreen(
    onAccepted: () -> Unit,
    onDeclined: () -> Unit,
    readOnly: Boolean = false,
    viewModel: DisclaimerViewModel = hiltViewModel()
) {
    val scrollState = rememberScrollState()
    val hasScrolledToBottom by remember {
        derivedStateOf {
            val maxScroll = scrollState.maxValue
            maxScroll == 0 || scrollState.value >= maxScroll - 50
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.disclaimer_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = stringResource(R.string.disclaimer_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (readOnly) {
                OutlinedButton(
                    onClick = onDeclined,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Close")
                }
            } else {
                if (!hasScrolledToBottom) {
                    Text(
                        text = "Please scroll to the bottom to read the full disclaimer",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Button(
                    onClick = {
                        viewModel.acceptDisclaimer()
                        onAccepted()
                    },
                    enabled = hasScrolledToBottom,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(
                        text = stringResource(R.string.disclaimer_accept),
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onDeclined,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.disclaimer_decline))
                }
            }
        }
    }
}
