package com.backrecorderapp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * First launch when the user declines the microphone.
 *
 * This is the class that proves both runtime grants are actually *requested*: it declines the real
 * platform dialogs and counts them, which `GrantPermissionRule` cannot do because it grants ahead
 * of the activity and no dialog is ever shown. The count alone would be weak evidence, so the
 * flow is also checked per permission through `shouldShowRequestPermissionRationale`, which only
 * becomes true once the user has seen and declined that specific permission.
 *
 * It depends on the orchestrator's `clearPackageData` for an un-granted start: runtime grants are
 * package-wide and last for the whole instrumentation run, so without that isolation
 * [FirstLaunchFlowTest]'s and `RecordingSegmentTest`'s `GrantPermissionRule` would leave the
 * permissions held, no dialog would appear, and this test would assert nothing. The precondition
 * is asserted rather than assumed so that losing the isolation fails loudly.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FirstLaunchPermissionDeniedTest {

    @Inject lateinit var appPreferences: AppPreferences

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /** Ordered ahead of the compose rule, which launches the activity. See [FirstLaunchFlowTest]. */
    @get:Rule(order = 1)
    val firstLaunchState = object : ExternalResource() {
        override fun before() {
            hiltRule.inject()
            NotificationChannels.createAll(context)
            runBlocking { appPreferences.setDisclaimerAccepted(false) }
        }
    }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        RecordingService.stop(context)
    }

    @Test
    fun decliningTheMicrophoneLeavesMainScreenUsableRatherThanDeadOrCrashed() {
        requestedPermissions.forEach {
            assertFalse(
                "Precondition: $it must not already be granted, or no dialog appears and this " +
                    "test proves nothing. Check the orchestrator and clearPackageData are enabled.",
                isGranted(it)
            )
        }

        FirstLaunchScreens.acceptDisclaimer(composeRule)
        FirstLaunchScreens.awaitIdleMainScreen(composeRule)

        val startRecording = composeRule.activity.getString(R.string.start_recording)
        composeRule.onNodeWithText(startRecording).performClick()

        assertEquals(
            "One dialog should be declined per requested permission",
            requestedPermissions.size,
            SystemPermissionDialog.denyAll(device, expected = requestedPermissions.size)
        )
        assertTrue(
            "A permission dialog is still on screen, occluding MainScreen",
            SystemPermissionDialog.noneRemaining(device)
        )

        // Independent, per-permission evidence that each one really was put to the user: the
        // rationale flag flips only once that specific permission has been seen and declined, and
        // clearPackageData guarantees this run's decline is the first (a second would clear it).
        requestedPermissions.forEach {
            assertFalse("Declining must not grant $it", isGranted(it))
            assertTrue(
                "$it was never actually presented to the user",
                composeRule.activity.shouldShowRequestPermissionRationale(it)
            )
        }

        // A declined microphone must leave a coherent screen: still on MainScreen, still idle, and
        // the record button still there to try again — not a crash and not a dead control.
        composeRule.waitForIdle()
        composeRule.onNodeWithText(FirstLaunchScreens.NOT_RECORDING).assertIsDisplayed()
        composeRule.onNodeWithText(startRecording).assertIsEnabled()
        assertFalse("A declined microphone must not start the service", RecordingService.isRunning.value)
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Mirrors what `MainScreen` asks for. Notifications are only a runtime permission from API 33,
     * so below that the microphone is the single dialog — derived rather than hardcoded, because
     * `minSdk` is 26 and a hardcoded 2 fails on an older emulator for no real reason.
     */
    private val requestedPermissions: List<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
}
