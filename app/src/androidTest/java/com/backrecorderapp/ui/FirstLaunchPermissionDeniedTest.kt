package com.backrecorderapp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * First launch when the user declines the microphone.
 *
 * This is the class that proves both runtime grants are actually *requested*: it drives the real
 * platform dialogs and counts them, which `GrantPermissionRule` cannot do because it grants ahead
 * of the activity and no dialog is ever shown. The dialogs are system UI, outside Compose's
 * semantics tree, so [SystemPermissionDialog] reaches them through UI Automator.
 *
 * It only works because the module runs instrumented tests under the AndroidX Test Orchestrator
 * with `clearPackageData`: runtime grants are package-wide and survive for the whole of an
 * instrumentation run, so without that isolation `RecordingSegmentTest`'s and
 * [FirstLaunchFlowTest]'s `GrantPermissionRule` would leave `RECORD_AUDIO` granted, no dialog
 * would appear, and this test would pass while asserting nothing. Revoking in-process is not an
 * alternative — the platform SIGKILLs the app's processes on revoke, which under instrumentation
 * is the test run. The precondition is asserted rather than assumed so that losing the isolation
 * fails loudly.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FirstLaunchPermissionDeniedTest {

    @Inject lateinit var appPreferences: AppPreferences

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private var disclaimerWasAccepted = false

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /** Ordered ahead of the compose rule: it is what launches the activity. See [FirstLaunchFlowTest]. */
    @get:Rule(order = 1)
    val firstLaunchState = object : ExternalResource() {
        override fun before() {
            hiltRule.inject()
            NotificationChannels.createAll(context)
            runBlocking {
                disclaimerWasAccepted = appPreferences.disclaimerAccepted.first()
                appPreferences.setDisclaimerAccepted(false)
            }
        }
    }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        RecordingService.stop(context)
        runBlocking { appPreferences.setDisclaimerAccepted(disclaimerWasAccepted) }
    }

    @Test
    fun decliningTheMicrophoneLeavesMainScreenUsableRatherThanDeadOrCrashed() {
        assertFalse(
            "Precondition: the microphone grant must not be held, or no dialog appears and this " +
                "test proves nothing. Check that the orchestrator and clearPackageData are enabled.",
            isGranted(Manifest.permission.RECORD_AUDIO)
        )

        FirstLaunchScreens.acceptDisclaimer(composeRule)
        FirstLaunchScreens.awaitIdleMainScreen(composeRule)

        val startRecording = composeRule.activity.getString(R.string.start_recording)
        composeRule.onNodeWithText(startRecording).performClick()

        assertEquals(
            "Both RECORD_AUDIO and notifications must be requested on first launch",
            EXPECTED_PERMISSION_DIALOGS,
            SystemPermissionDialog.respond(device, allow = false, expected = EXPECTED_PERMISSION_DIALOGS)
        )

        // A declined microphone must leave a coherent screen: still on MainScreen, still idle, and
        // the record button still there to try again — not a crash and not a dead control.
        composeRule.waitForIdle()
        composeRule.onNodeWithText(FirstLaunchScreens.NOT_RECORDING).assertIsDisplayed()
        composeRule.onNodeWithText(startRecording).assertIsEnabled()
        assertFalse("A declined microphone must not start the service", RecordingService.isRunning.value)
        assertFalse("A declined dialog must not grant the permission", isGranted(Manifest.permission.RECORD_AUDIO))
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        // minSdk is 26, but every emulator this suite targets is API 33+, where MainScreen asks
        // for POST_NOTIFICATIONS alongside RECORD_AUDIO and the controller shows one dialog each.
        const val EXPECTED_PERMISSION_DIALOGS = 2
    }
}
