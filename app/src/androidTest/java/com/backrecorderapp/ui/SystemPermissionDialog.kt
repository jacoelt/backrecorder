package com.backrecorderapp.ui

import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
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
 * variants, and scoped to the controller package so a lookalike node in another window can never
 * satisfy them. The id is matched as a pattern over the whole resource name rather than a
 * package/id pair because the controller package differs between AOSP
 * (`com.android.permissioncontroller`) and Play images (`com.google.android.permissioncontroller`).
 * Visible text is the fallback for an image that renames the ids.
 */
object SystemPermissionDialog {

    /** The first dialog has to cold-start the permission controller; later ones are already warm. */
    private const val FIRST_DIALOG_TIMEOUT_MS = 30_000L
    private const val NEXT_DIALOG_TIMEOUT_MS = 10_000L
    private const val DISMISSAL_TIMEOUT_MS = 10_000L

    private val CONTROLLER_PACKAGE = Pattern.compile(".*permissioncontroller")
    private val DENY_ID = Pattern.compile(".*:id/permission_deny_button")
    private val PROMPT_ID = Pattern.compile(".*:id/(permission_message|permission_title|grant_dialog_title)")

    // Anchored so "Don't allow" is the only thing this can match, never an "Allow" button.
    private val DENY_TEXT = Pattern.compile("^(don.t allow|deny)$", Pattern.CASE_INSENSITIVE)

    /**
     * Declines up to [expected] dialogs in a row and returns how many it actually declined.
     *
     * A dialog only counts once the prompt that was on screen has **gone**, so a tap that the
     * controller swallowed cannot be counted twice against the same still-visible button. Without
     * that check the return value would be a tap count rather than a dialog count, and a caller
     * asserting on it would be proving much less than it looks.
     */
    fun denyAll(device: UiDevice, expected: Int): Int {
        var answered = 0
        repeat(expected) { index ->
            val timeout = if (index == 0) FIRST_DIALOG_TIMEOUT_MS else NEXT_DIALOG_TIMEOUT_MS
            val denyButton = awaitDenyButton(device, timeout) ?: return answered
            val prompt = currentPrompt(device)

            denyButton.click()

            if (!awaitDismissal(device, prompt)) return answered
            answered++
        }
        return answered
    }

    /** True once no permission dialog is left on screen — a leftover one would occlude the app. */
    fun noneRemaining(device: UiDevice): Boolean =
        device.wait(Until.gone(denySelector()), DISMISSAL_TIMEOUT_MS) == true

    private fun denySelector(): BySelector = By.res(DENY_ID).pkg(CONTROLLER_PACKAGE)

    private fun awaitDenyButton(device: UiDevice, timeoutMillis: Long) =
        device.wait(Until.findObject(denySelector()), timeoutMillis)
            ?: device.wait(
                Until.findObject(By.text(DENY_TEXT).clickable(true).pkg(CONTROLLER_PACKAGE)),
                timeoutMillis
            )

    /** The prompt's own wording, which is what distinguishes one permission's dialog from the next. */
    private fun currentPrompt(device: UiDevice): String? =
        device.findObject(By.res(PROMPT_ID).pkg(CONTROLLER_PACKAGE))?.text

    private fun awaitDismissal(device: UiDevice, prompt: String?): Boolean {
        // Falling back to the deny button is weaker — it reappears for the next permission — but it
        // is all that is available on an image whose prompt carries none of the ids above.
        val gone = if (prompt != null) {
            Until.gone(By.text(prompt).pkg(CONTROLLER_PACKAGE))
        } else {
            Until.gone(denySelector())
        }
        return device.wait(gone, DISMISSAL_TIMEOUT_MS) == true
    }
}
