package com.backrecorderapp.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.backrecorderapp.R

@Composable
fun TrialDialog(
    onOpenSubscriptions: () -> Unit,
    onDismiss: () -> Unit,
) {

    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(text = context.getString(R.string.trial_title)) },
        text = { Text(text = context.getString(R.string.trial_message)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onOpenSubscriptions()
                }
            ) {
                Text(text = context.getString(R.string.trial_button_subscribe))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = context.getString(R.string.trial_button_continue))
            }
        }
    )
}
