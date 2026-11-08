package com.backrecorderapp.util

import androidx.annotation.StringRes
import com.backrecorderapp.R
import com.backrecorderapp.data.billing.PurchaseFailure

/**
 * What to tell the user when the Upgrade button could not reach Play. Shared by both screens
 * that offer the upgrade, so the two cannot drift apart.
 */
@StringRes
fun PurchaseFailure.messageRes(): Int = when (this) {
    PurchaseFailure.BILLING_UNAVAILABLE -> R.string.upgrade_error_billing_unavailable
    PurchaseFailure.PRODUCT_UNAVAILABLE -> R.string.upgrade_error_product_unavailable
    PurchaseFailure.LAUNCH_FAILED -> R.string.upgrade_error_launch_failed
}
