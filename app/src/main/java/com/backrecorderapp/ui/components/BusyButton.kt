package com.backrecorderapp.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * A button that can say it is waiting on something, rather than going quiet.
 *
 * Every step of an upgrade crosses Play and can take seconds, and the view models refuse a tap
 * that arrives during one. A button that still looked live would swallow that tap silently, which
 * is the dead button #72 and #74 exist to remove - so the refusal has to be visible wherever it
 * can happen. Shared so the Upgrade affordance and the chooser's Continue cannot drift apart.
 */
@Composable
fun BusyButton(
    busy: Boolean,
    text: String,
    busyStateDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier.semantics {
            // Without this a screen reader announces only a disabled button, which is
            // indistinguishable from the feature being unavailable.
            if (busy) stateDescription = busyStateDescription
        }
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text)
    }
}
