package com.backrecorderapp.ui

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.backrecorderapp.MainActivity
import com.backrecorderapp.R
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.notification.NotificationChannels
import com.backrecorderapp.service.RecordingService
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The happy path of first launch on a fresh install: cold start → `DisclaimerScreen` → accept →
 * `MainScreen` idle → record.
 *
 * Preferences are **seeded, not wiped**. `preferencesDataStore` caches per process, so deleting the
 * backing file is a silent no-op once anything has read preferences; the value written through
 * [AppPreferences] is the one `NavGraph` will see. The seed runs in a rule ordered between the Hilt
 * rule and the compose rule because the compose rule is what launches the activity, and `NavGraph`
 * fixes its start destination from `disclaimerAccepted` on first composition — seeding from
 * `@Before` is already too late, and the test would silently start on `MainScreen` and assert
 * nothing.
 *
 * Permissions are granted **before** the activity launches, with [GrantPermissionRule]. That is not
 * only convenience: granting `RECORD_AUDIO` to an already-running process makes the platform
 * SIGKILL it, and under instrumentation that is the test run itself. Driving the grant dialog is
 * therefore only viable for the *denial* branch, which is
 * [FirstLaunchPermissionDeniedTest]'s job — that class is what proves both permissions are really
 * requested. The grants here are wiped between tests by the orchestrator's `clearPackageData`, so
 * they cannot leak into it.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FirstLaunchFlowTest {

    @Inject lateinit var appPreferences: AppPreferences

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private var disclaimerWasAccepted = false

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    @get:Rule(order = 2)
    val firstLaunchState = object : ExternalResource() {
        override fun before() {
            hiltRule.inject()
            // HiltTestApplication stands in for BackRecorderApp, so the channels it would have
            // created do not exist; posting the foreground notification to a missing channel kills
            // the process.
            NotificationChannels.createAll(context)
            runBlocking {
                disclaimerWasAccepted = appPreferences.disclaimerAccepted.first()
                appPreferences.setDisclaimerAccepted(false)
            }
        }
    }

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        RecordingService.stop(context)
        runBlocking { appPreferences.setDisclaimerAccepted(disclaimerWasAccepted) }
    }

    @Test
    fun firstLaunchGoesDisclaimerThenMainScreenAndRecordsOncePermissionsAreHeld() {
        FirstLaunchScreens.acceptDisclaimer(composeRule)
        FirstLaunchScreens.awaitIdleMainScreen(composeRule)

        assertTrue(
            "Accepting the disclaimer must persist, or it reappears on the next launch",
            runBlocking { appPreferences.disclaimerAccepted.first() }
        )

        // With both grants already held, MainScreen must go straight to recording rather than
        // asking again — the idle controls it landed on have to actually work.
        val stopRecording = composeRule.activity.getString(R.string.stop_recording)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.start_recording))
            .performClick()

        composeRule.waitUntil(timeoutMillis = FirstLaunchScreens.AWAIT_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(stopRecording).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("RecordingService should be running once recording starts", RecordingService.isRunning.value)

        composeRule.onNodeWithText(stopRecording).performClick()
    }
}

/**
 * Screen steps shared by the two first-launch tests, kept out of both so neither class has to
 * inherit from the other to reuse them.
 */
internal object FirstLaunchScreens {

    const val AWAIT_TIMEOUT_MS = 30_000L
    const val NOT_RECORDING = "Not recording"

    private const val MAX_DISCLAIMER_SWIPES = 20

    /** Scrolls the disclaimer to the bottom — the accept button stays disabled until then. */
    fun acceptDisclaimer(rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity>) {
        val title = rule.activity.getString(R.string.disclaimer_title)

        // The first frame is a spinner while DataStore loads, so wait for the screen itself.
        rule.waitUntil(timeoutMillis = AWAIT_TIMEOUT_MS) {
            rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(title).assertIsDisplayed()

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

    fun awaitIdleMainScreen(rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity>) {
        val startRecording = rule.activity.getString(R.string.start_recording)
        rule.waitUntil(timeoutMillis = AWAIT_TIMEOUT_MS) {
            rule.onAllNodesWithText(startRecording).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(NOT_RECORDING).assertIsDisplayed()
        rule.onNodeWithText(startRecording).assertIsEnabled()
        assertFalse(
            "MainScreen's idle state is driven by RecordingService.isRunning",
            RecordingService.isRunning.value
        )
    }

    private fun isEnabled(
        rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity>,
        text: String
    ): Boolean = runCatching { rule.onNodeWithText(text).assertIsEnabled() }.isSuccess
}
