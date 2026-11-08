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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.backrecorderapp.R

/**
 * The Upgrade affordance, which has to be able to say it is busy.
 *
 * Both entry points reach Play twice per upgrade - once for the plans, once for the sheet - and
 * each leg can take seconds. The view models refuse a tap that arrives during either, so a button
 * that still looked live would swallow it silently: the dead button #72 and #74 exist to remove.
 * Shared rather than repeated because the two screens must not drift apart on that.
 */
@Composable
fun UpgradeButton(
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val loadingLabel = stringResource(R.string.upgrade_loading_plans)

    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier.semantics {
            // Without this a screen reader announces only a disabled "Upgrade to Premium", which
            // is indistinguishable from the feature being unavailable.
            if (busy) stateDescription = loadingLabel
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
        Text(stringResource(R.string.upgrade_button))
    }
}
