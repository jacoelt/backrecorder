package com.backrecorderapp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.backrecorderapp.MainActivity
import com.backrecorderapp.R
import com.backrecorderapp.data.billing.FakeBillingRepository
import com.backrecorderapp.data.billing.SubscriptionStatus
import com.backrecorderapp.data.cloud.CloudSyncManager
import com.backrecorderapp.data.cloud.DriveAuthManager
import com.backrecorderapp.data.cloud.FakeDriveAuthorizationClient
import com.backrecorderapp.data.cloud.FakeDriveServer
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.testing.PreferenceSnapshot
import com.backrecorderapp.testing.awaitUntil
import com.backrecorderapp.testing.cleanUpRule
import com.backrecorderapp.testing.createNotificationChannels
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Connecting Drive from the settings screen (#102), against the real Hilt graph with only the
 * Play Services grant and the Drive backend faked.
 *
 * What no JVM test reaches: that a tap on the real button drives the real `DriveAuthManager`
 * through its session lock and identity lookup and comes back with the row changed — the whole
 * of what #102 reported as doing nothing. It also pins #80's promotion, since the two are one
 * action for the user: connecting *is* the request to upload, so `OFF` must not survive it.
 *
 * Billing is held at ACTIVE rather than complimentary: the diagnosis established that entitlement
 * is not what distinguishes the broken case, and a paid subscriber is the path with more of the
 * gate behind it.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DriveConnectFlowTest {

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var billing: FakeBillingRepository
    @Inject lateinit var driveAuthorization: FakeDriveAuthorizationClient
    @Inject lateinit var server: FakeDriveServer
    @Inject lateinit var driveAuthManager: DriveAuthManager
    @Inject lateinit var cloudSyncManager: CloudSyncManager

    private val preferences = PreferenceSnapshot()

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /**
     * The grant, the session and the sync loop all outlive this class — `FakeCloudModule` installs
     * the Drive fakes for every instrumented test — so a connect left standing would hand an
     * unrelated later test a Drive account it never asked for, and leave its loop polling a server
     * that has stopped. The orchestrator's `clearPackageData` hides that today; it should not be
     * the only thing that does.
     */
    @get:Rule(order = 1)
    val cleanUpAfterwards = cleanUpRule(hiltRule) {
        cloudSyncManager.stopSync()
        runBlocking { driveAuthManager.signOut() }
        server.stop()
        server.reset()
        driveAuthorization.reset()
        runBlocking { preferences.restoreAll() }
    }

    @get:Rule(order = 2)
    val seedPreferences = object : ExternalResource() {
        override fun before() {
            createNotificationChannels()
            // Without this the factory's root URL is still port 0, so the real identity lookup
            // the connect performs would hit nothing and the pin path would never run.
            server.start()
            billing.setStatus(SubscriptionStatus.ACTIVE)
            runBlocking {
                preferences.capture(
                    appPreferences.disclaimerAccepted,
                    appPreferences::setDisclaimerAccepted
                )
                preferences.capture(
                    appPreferences.cloudUploadMode,
                    appPreferences::setCloudUploadMode
                )
                preferences.capture(
                    appPreferences.driveDisconnected,
                    appPreferences::setDriveDisconnected
                )
                appPreferences.setDisclaimerAccepted(true)
                appPreferences.setCloudUploadMode(CloudUploadMode.OFF)
                // Seeded so the screen's init-time restoreSession() is skipped: this test is
                // about what the tap does, and an unrelated grant restore racing it would make
                // the row's change ambiguous about which call produced it.
                appPreferences.setDriveDisconnected(true)
            }
        }
    }

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun openSettings() {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithContentDescription("Settings")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(string(R.string.settings_cloud_storage))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun connectingDriveFlipsTheRowAndTurnsUploadsOn() {
        openSettings()

        // Asserted rather than assumed: a grant leaking past clearPackageData would otherwise
        // make the wait below pass on a row that was already connected before the tap.
        composeRule.onNodeWithText(string(R.string.settings_drive_not_connected))
            .performScrollTo().assertIsDisplayed()

        driveAuthorization.grantAccess()
        composeRule.onNodeWithText(string(R.string.settings_drive_connect))
            .performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(string(R.string.settings_drive_connected))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Polled, not read once: the row follows `isAuthorized`, which is published a suspending
        // step before the upload mode is written, so a single read here can still see OFF.
        runBlocking {
            awaitUntil("cloud upload mode was not promoted off OFF") {
                appPreferences.cloudUploadMode.first() == CloudUploadMode.WIFI_ONLY
            }
        }
    }
}
