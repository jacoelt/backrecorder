package com.backrecorderapp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.backrecorderapp.R

/**
 * The Upgrade affordance, which has to be able to say it is busy.
 *
 * Both entry points reach Play twice per upgrade - once for the plans, once for the sheet - and
 * each leg can take seconds. Shared rather than repeated because the two screens must not drift
 * apart on that; see [BusyButton] for why the busy state is not optional.
 */
@Composable
fun UpgradeButton(
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BusyButton(
        busy = busy,
        text = stringResource(R.string.upgrade_button),
        busyStateDescription = stringResource(R.string.upgrade_loading_plans),
        onClick = onClick,
        modifier = modifier
    )
}
