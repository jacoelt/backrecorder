package com.backrecorderapp.ui

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * Drives the platform's runtime-permission dialogs.
 *
 * These are rendered by the permission controller, in its own process and window — Compose's
 * semantics tree ends at the app's own hierarchy, so [androidx.compose.ui.test] cannot see them
 * at all. UI Automator can, which is why the first-launch tests take a dependency on it.
 *
 * Matching is by resource id first, because the ids are stable across the dialog's many text
 * variants ("Allow", "While using the app", "Only this time"). The controller package name is not
 * stable, though — AOSP images use `com.android.permissioncontroller` and Play images
 * `com.google.android.permissioncontroller` — so the id is matched with a pattern over the whole
 * resource name rather than a package/id pair. Visible text is the fallback for anything that
 * renames the ids.
 */
object SystemPermissionDialog {

    private const val DIALOG_TIMEOUT_MS = 5_000L

    private val ALLOW_ID = Pattern.compile(
        ".*:id/(permission_allow_foreground_only_button|permission_allow_button|permission_allow_one_time_button)"
    )
    private val DENY_ID = Pattern.compile(".*:id/permission_deny_button")

    // Anchored so "Don't allow" can never satisfy the allow pattern.
    private val ALLOW_TEXT =
        Pattern.compile("^(while using the app|allow|only this time)$", Pattern.CASE_INSENSITIVE)
    private val DENY_TEXT =
        Pattern.compile("^(don.t allow|deny)$", Pattern.CASE_INSENSITIVE)

    /**
     * Answers up to [expected] dialogs in a row, allowing or denying each, and returns how many it
     * actually answered. A request for several permissions is shown as one dialog per permission,
     * so the count is how the caller proves that every grant it expected was really asked for.
     */
    fun respond(device: UiDevice, allow: Boolean, expected: Int): Int {
        var answered = 0
        repeat(expected) {
            if (!tapOnce(device, allow)) return answered
            answered++
            device.waitForIdle()
        }
        return answered
    }

    private fun tapOnce(device: UiDevice, allow: Boolean): Boolean {
        val id = if (allow) ALLOW_ID else DENY_ID
        device.wait(Until.findObject(By.res(id)), DIALOG_TIMEOUT_MS)?.let {
            it.click()
            return true
        }

        val text = if (allow) ALLOW_TEXT else DENY_TEXT
        device.wait(Until.findObject(By.text(text).clickable(true)), DIALOG_TIMEOUT_MS)?.let {
            it.click()
            return true
        }

        return false
    }
}
