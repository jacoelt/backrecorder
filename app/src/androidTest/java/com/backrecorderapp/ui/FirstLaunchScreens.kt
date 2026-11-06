package com.backrecorderapp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.backrecorderapp.MainActivity
import com.backrecorderapp.R
import com.backrecorderapp.service.RecordingService
import org.junit.Assert.assertFalse

internal typealias MainComposeRule = AndroidComposeTestRule<*, MainActivity>

/** Screen steps shared by the first-launch tests. */
internal object FirstLaunchScreens {

    const val AWAIT_TIMEOUT_MS = 30_000L

    /** Hardcoded in `MainScreen`, which has no string resource for it. */
    const val NOT_RECORDING = "Not recording"

    private const val MAX_DISCLAIMER_SWIPES = 20

    /** Scrolls the disclaimer to the bottom — the accept button stays disabled until then. */
    fun acceptDisclaimer(rule: MainComposeRule) {
        awaitDisclaimer(rule)

        val accept = rule.activity.getString(R.string.disclaimer_accept)
        var swipes = 0
        while (!isEnabled(rule, accept) && swipes < MAX_DISCLAIMER_SWIPES) {
            rule.onNode(hasScrollAction()).performTouchInput { swipeUp() }
            rule.waitForIdle()
            swipes++
        }

        rule.onNodeWithText(accept).assertIsEnabled()
        rule.onNodeWithText(accept).performClick()
    }

    fun awaitDisclaimer(rule: MainComposeRule) {
        val title = rule.activity.getString(R.string.disclaimer_title)

        // The first frame is a spinner while DataStore loads, so wait for the screen itself.
        rule.waitUntil(timeoutMillis = AWAIT_TIMEOUT_MS) {
            rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(title).assertIsDisplayed()
    }

    fun awaitMainScreen(rule: MainComposeRule) {
        val startRecording = rule.activity.getString(R.string.start_recording)
        rule.waitUntil(timeoutMillis = AWAIT_TIMEOUT_MS) {
            rule.onAllNodesWithText(startRecording).fetchSemanticsNodes().isNotEmpty()
        }
    }

    fun awaitIdleMainScreen(rule: MainComposeRule) {
        awaitMainScreen(rule)
        val startRecording = rule.activity.getString(R.string.start_recording)
        rule.onNodeWithText(NOT_RECORDING).assertIsDisplayed()
        rule.onNodeWithText(startRecording).assertIsEnabled()
        assertFalse(
            "MainScreen's idle state is driven by RecordingService.isRunning",
            RecordingService.isRunning.value
        )
    }

    fun assertNoDisclaimer(rule: MainComposeRule) {
        rule.onNodeWithText(rule.activity.getString(R.string.disclaimer_title)).assertDoesNotExist()
    }

    private fun isEnabled(rule: MainComposeRule, text: String): Boolean =
        runCatching { rule.onNodeWithText(text).assertIsEnabled() }.isSuccess
}
