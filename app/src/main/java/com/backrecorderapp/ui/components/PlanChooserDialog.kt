package com.backrecorderapp.ui.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.backrecorderapp.R
import com.backrecorderapp.data.billing.BillingPeriod
import com.backrecorderapp.data.billing.SubscriptionPlan
import com.backrecorderapp.data.billing.SubscriptionPlans

/**
 * Lets the user pick a plan before Play's sheet opens.
 *
 * It exists because the subscription has two base plans and the hand-off can only send one offer
 * token. Sending whichever Play returned first was an arbitrary charge - possibly a year the user
 * never chose - so the choice is made here, explicitly, with the prices Play itself reports.
 *
 * Every figure shown comes from [SubscriptionPlans]: the price strings are Play's own localised
 * ones, and the saving is computed from its prices rather than written into the app, so it cannot
 * contradict what the Play Console charges.
 */
@Composable
fun PlanChooserDialog(
    plans: SubscriptionPlans,
    busy: Boolean,
    onPlanSelected: (SubscriptionPlan) -> Unit,
    onDismiss: () -> Unit
) {
    // Yearly is the better value when there is a saving to show, so it starts selected; with one
    // plan there is nothing to choose and it is simply the one.
    // Yearly when there is a saving to show it off with, otherwise simply the first plan. Total
    // rather than asserting on `yearly`: that it is non-null here follows from a rule in another
    // file, and this must not become a crash if that rule ever changes.
    val default = plans.yearly?.takeIf { plans.yearlySavingsPercent != null } ?: plans.plans.first()

    // rememberSaveable, and the period rather than the plan: orientation cannot be restricted at
    // this target on large screens, so a rotation here would otherwise silently put the selection
    // back to the default - and someone who picked monthly, rotated, then tapped Continue would
    // be sent to Play for a *year*. That is the exact harm this dialog exists to prevent.
    var selectedPeriod by rememberSaveable(plans) { mutableStateOf(default.period) }
    val selected = plans.plans.firstOrNull { it.period == selectedPeriod } ?: default

    AlertDialog(
        // Ignored while the hand-off runs: the dialog is the only thing on screen saying that a
        // tap is being acted on, and tapping away from it would not stop the purchase anyway.
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.plan_chooser_title)) },
        text = {
            Column(
                // Scrollable because `AlertDialog`'s text slot is not. In landscape, or at a
                // large font scale, or with a long localised trial line, the second row can fall
                // outside the dialog - and since yearly is preselected whenever it carries a
                // saving, the row that becomes unreachable is monthly. An unreachable option is
                // the wrong-plan charge this dialog exists to prevent.
                modifier = Modifier
                    .selectableGroup()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                plans.plans.forEach { plan ->
                    PlanRow(
                        plan = plan,
                        savingsPercent = plans.yearlySavingsPercent
                            ?.takeIf { plan.period == BillingPeriod.YEARLY },
                        selected = plan.period == selected.period,
                        // Changing the selection once Play has been asked for the other plan
                        // would show a choice that no longer decides anything.
                        enabled = !busy,
                        onSelect = { selectedPeriod = plan.period }
                    )
                }
            }
        },
        confirmButton = {
            BusyButton(
                busy = busy,
                text = stringResource(R.string.plan_chooser_continue),
                busyStateDescription = stringResource(R.string.plan_chooser_opening),
                onClick = { onPlanSelected(selected) }
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun PlanRow(
    plan: SubscriptionPlan,
    savingsPercent: Int?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row is the control, and the RadioButton itself is left unclickable so the
            // row is announced once rather than twice.
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)

        Column(modifier = Modifier.padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        when (plan.period) {
                            BillingPeriod.MONTHLY -> R.string.plan_period_monthly
                            BillingPeriod.YEARLY -> R.string.plan_period_yearly
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    // Yields its space to the badge instead of measuring first and squeezing it:
                    // the row is narrow once the dialog padding and the radio button are taken
                    // out, and a longer localised plan name would otherwise crush the badge.
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (savingsPercent != null) {
                    SavingsBadge(
                        percent = savingsPercent,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            Text(
                text = stringResource(
                    when (plan.period) {
                        BillingPeriod.MONTHLY -> R.string.plan_price_per_month
                        BillingPeriod.YEARLY -> R.string.plan_price_per_year
                    },
                    plan.formattedPrice
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (plan.freeTrialDays > 0) {
                Text(
                    text = stringResource(R.string.plan_free_trial, plan.freeTrialDays),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** The only reason to pick yearly, where someone comparing two raw prices will actually see it. */
@Composable
private fun SavingsBadge(percent: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Text(
            text = stringResource(R.string.plan_savings_badge, percent),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
