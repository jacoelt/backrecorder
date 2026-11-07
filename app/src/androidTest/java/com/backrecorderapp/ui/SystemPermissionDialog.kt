package com.backrecorderapp.ui

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * Declines the platform's runtime-permission dialogs, one per requested permission.
 *
 * They are drawn by the permission controller, in its own process and window, so Compose's
 * semantics tree cannot see them at all and only UI Automator can reach them.
 *
 * Deny-only on purpose. Granting `RECORD_AUDIO` through the dialog makes the platform SIGKILL the
 * app's processes, which under instrumentation is the test run itself, so a happy path has to
 * pre-grant with `GrantPermissionRule` instead (see [FirstLaunchFlowTest]) and there is no honest
 * caller for an "allow" branch.
 *
 * Selectors are matched by resource id, because the ids are stable across the dialog's many text
 * variants, and scoped to the controller package so a lookalike node elsewhere can never satisfy
 * them. The id is matched as a pattern over the whole resource name rather than a package/id pair
 * because the controller package differs between AOSP (`com.android.permissioncontroller`) and
 * Play images (`com.google.android.permissioncontroller`). Visible text is the fallback for an
 * image that renames the ids.
 */
object SystemPermissionDialog {

    /** The first dialog has to cold-start the permission controller; later ones are already warm. */
    private const val FIRST_DIALOG_TIMEOUT_MS = 30_000L
    private const val NEXT_DIALOG_TIMEOUT_MS = 10_000L
    private const val TEXT_FALLBACK_TIMEOUT_MS = 2_000L
    private const val DISMISSAL_TIMEOUT_MS = 10_000L
    private const val STALE_POLL_MS = 100L

    private val CONTROLLER_PACKAGE = Pattern.compile(".*permissioncontroller")
    private val DENY_ID = Pattern.compile(".*:id/permission_deny_button")
    private val PROMPT_ID = Pattern.compile(".*:id/(permission_message|permission_title|grant_dialog_title)")

    // Anchored so "Don't allow" is the only thing this can match, never an "Allow" button.
    private val DENY_TEXT = Pattern.compile("^(don.t allow|deny)$", Pattern.CASE_INSENSITIVE)

    /**
     * Declines up to [expected] dialogs in a row and returns how many it actually declined.
     *
     * A dialog only counts once the one on screen has demonstrably gone, so a tap the controller
     * swallowed cannot be counted twice against the same still-visible button. Without that the
     * return value would be a tap count rather than a dialog count, and a caller asserting on it
     * would be proving much less than it looks.
     */
    fun denyAll(device: UiDevice, expected: Int): Int {
        var answered = 0
        repeat(expected) { index ->
            val timeout = if (index == 0) FIRST_DIALOG_TIMEOUT_MS else NEXT_DIALOG_TIMEOUT_MS
            val denyButton = awaitDenyButton(device, timeout) ?: return answered
            val prompt = currentPrompt(device)

            denyButton.click()

            if (!awaitDismissal(device, denyButton, prompt)) return answered
            answered++
        }
        return answered
    }

    /** True once no permission dialog is left on screen — a leftover one would occlude the app. */
    fun noneRemaining(device: UiDevice): Boolean =
        device.wait(Until.gone(denySelector()), DISMISSAL_TIMEOUT_MS) == true

    private fun denySelector(): BySelector = By.res(DENY_ID).pkg(CONTROLLER_PACKAGE)

    /**
     * The text fallback gets a short budget of its own: on a real regression no dialog appears at
     * all, and spending the full timeout twice would double every failing run's wall time.
     */
    private fun awaitDenyButton(device: UiDevice, timeoutMillis: Long): UiObject2? =
        device.wait(Until.findObject(denySelector()), timeoutMillis)
            ?: device.wait(
                Until.findObject(By.text(DENY_TEXT).clickable(true).pkg(CONTROLLER_PACKAGE)),
                TEXT_FALLBACK_TIMEOUT_MS
            )

    /** The prompt's own wording, which is what distinguishes one permission's dialog from the next. */
    private fun currentPrompt(device: UiDevice): String? =
        device.findObject(By.res(PROMPT_ID).pkg(CONTROLLER_PACKAGE))?.text?.takeIf { it.isNotBlank() }

    private fun awaitDismissal(device: UiDevice, denyButton: UiObject2, prompt: String?): Boolean {
        if (prompt != null) {
            return device.wait(Until.gone(By.text(prompt).pkg(CONTROLLER_PACKAGE)), DISMISSAL_TIMEOUT_MS) == true
        }
        // No readable prompt on this image. Waiting for the deny button to *disappear* would be
        // wrong — the controller updates the same window in place between permissions, so that
        // button survives into the next dialog and the wait would time out on a dialog that was
        // in fact answered. Its node is replaced, though, so staleness is the honest signal.
        return awaitStale(denyButton)
    }

    private fun awaitStale(button: UiObject2): Boolean {
        val deadline = SystemClock.uptimeMillis() + DISMISSAL_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                button.text
            } catch (_: StaleObjectException) {
                return true
            }
            SystemClock.sleep(STALE_POLL_MS)
        }
        return false
    }
}
