package com.backrecorder

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun TrialDialog(
    storeUrl: String,
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
                    // Open Play Store subscriptions page
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse(storeUrl)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
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
