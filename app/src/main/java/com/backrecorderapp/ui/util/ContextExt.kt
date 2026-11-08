package com.backrecorderapp.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * The [Activity] this context ultimately belongs to, or null.
 *
 * `LocalContext.current` is usually the activity, but not always: a theme or configuration
 * wrapper around it is just as valid, and a plain `as? Activity` on one of those is null. Every
 * call site here hands the result to Play Billing, where a null means a button that does
 * nothing at all - so unwrap rather than cast.
 */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
