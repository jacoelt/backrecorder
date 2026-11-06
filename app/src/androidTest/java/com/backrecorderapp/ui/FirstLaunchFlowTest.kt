package com.backrecorderapp.ui

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The happy path of first launch on a fresh install: cold start → `DisclaimerScreen` → accept →
 * `MainScreen` idle → record, and the disclaimer does not come back on the next launch.
 *
 * Preferences are **seeded, not wiped**: `preferencesDataStore` is a process-wide singleton with
 * its own cache, so writing through [AppPreferences] is the only way to be sure `NavGraph` sees
 * the value. The seed runs in a rule ordered before the compose rule because that rule is what
 * launches the activity, and `NavGraph` fixes its start destination on first composition — seeding
 * from `@Before` is already too late and the test would silently start on `MainScreen`. See
 * `ComposeHarnessSmokeTest` for the canonical rule shape.
 *
 * Permissions are granted **before** the activity launches, with [GrantPermissionRule]. That is
 * not just convenience: granting `RECORD_AUDIO` to an already-running process makes the platform
 * SIGKILL it, and under instrumentation that is the test run. Driving the real dialogs is
 * therefore only viable when declining, which is [FirstLaunchPermissionDeniedTest]'s job — that
 * class is what proves both permissions are actually requested. These grants cannot leak into it,
 * because the orchestrator clears package data between tests.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FirstLaunchFlowTest {

    @Inject lateinit var appPreferences: AppPreferences

    private val context: Context get() = ApplicationProvider.getApplicationContext()

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
            runBlocking { appPreferences.setDisclaimerAccepted(false) }
        }
    }

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        RecordingService.stop(context)
    }

    @Test
    fun firstLaunchGoesDisclaimerThenMainScreenAndRecordsOncePermissionsAreHeld() {
        FirstLaunchScreens.acceptDisclaimer(composeRule)
        FirstLaunchScreens.awaitIdleMainScreen(composeRule)

        // `acceptDisclaimer()` navigates synchronously while the view model writes the preference
        // on its own scope, so reaching MainScreen does not imply the write landed — poll for it.
        composeRule.waitUntil(timeoutMillis = FirstLaunchScreens.AWAIT_TIMEOUT_MS) {
            runBlocking { appPreferences.disclaimerAccepted.first() }
        }

        recordAndStop()
        assertDisclaimerDoesNotReturnOnRelaunch()
    }

    /** With both grants held, the idle controls must work without any dialog intervening. */
    private fun recordAndStop() {
        val stopRecording = composeRule.activity.getString(R.string.stop_recording)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.start_recording))
            .performClick()

        composeRule.waitUntil(timeoutMillis = FirstLaunchScreens.AWAIT_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(stopRecording).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText(stopRecording).performClick()

        // Waiting the service out keeps the stop half of the flow asserted, and stops the
        // orchestrator's `pm clear` racing a service that is still shutting down.
        composeRule.waitUntil(timeoutMillis = FirstLaunchScreens.AWAIT_TIMEOUT_MS) {
            !RecordingService.isRunning.value
        }
    }

    /**
     * The acceptance has to survive a relaunch, not merely reach DataStore. Recreating the activity
     * re-runs `NavGraph`'s start-destination decision against the stored value, which is the part a
     * preference assertion on its own cannot reach.
     */
    private fun assertDisclaimerDoesNotReturnOnRelaunch() {
        composeRule.activityRule.scenario.recreate()

        FirstLaunchScreens.awaitMainScreen(composeRule)
        FirstLaunchScreens.assertNoDisclaimer(composeRule)
    }
}
