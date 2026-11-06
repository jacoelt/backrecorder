package com.backrecorderapp.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.backrecorderapp.MainActivity
import com.backrecorderapp.R
import com.backrecorderapp.data.billing.FakeBillingRepository
import com.backrecorderapp.data.billing.SubscriptionStatus
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.domain.GateResult
import com.backrecorderapp.domain.SubscriptionGate
import com.backrecorderapp.notification.NotificationChannels
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The premium boundary, end to end: Drive sync and the extended save range are refused at FREE
 * and granted at ACTIVE, through the real Hilt graph with only billing faked.
 *
 * Billing comes from [FakeBillingRepository] via `@TestInstallIn`, the same seam shape the
 * microphone uses. Nothing here simulates a purchase - the app only ever asks billing what the
 * subscription *is*, because purchases happen in the Play Store.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SubscriptionGateFlowTest {

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var billing: FakeBillingRepository
    @Inject lateinit var subscriptionGate: SubscriptionGate

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /**
     * Seeds between the Hilt rule and the compose rule: `NavGraph` fixes its start destination
     * from `disclaimerAccepted` on first composition, and the compose rule launches the activity
     * before any `@Before` runs. Written through [AppPreferences] rather than by deleting the
     * backing file, because the `preferencesDataStore` delegate caches in-process and a delete is
     * a silent no-op once a sibling test has read prefs.
     */
    @get:Rule(order = 1)
    val seedPreferences = object : ExternalResource() {
        override fun before() {
            hiltRule.inject()
            // HiltTestApplication replaces BackRecorderApp, so nothing has created the channels.
            NotificationChannels.createAll(
                InstrumentationRegistry.getInstrumentation().targetContext
            )
            runBlocking {
                appPreferences.setDisclaimerAccepted(true)
                appPreferences.setCloudUploadMode(CloudUploadMode.OFF)
                appPreferences.setDriveDisconnected(false)
            }
            // Every test states its own entitlement; starting from "billing has not answered"
            // keeps one test's status from leaking into the next through the @Singleton fake.
            billing.setUnresolved()
        }
    }

    @get:Rule(order = 2)
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

    private fun currentUploadMode(): CloudUploadMode =
        runBlocking { appPreferences.cloudUploadMode.first() }

    // --- Drive sync, through the settings toggle ---------------------------------------------

    @Test
    fun freeUserTurningOnCloudUploadIsRefusedAndOfferedAnUpgrade() {
        billing.setStatus(SubscriptionStatus.FREE)
        openSettings()

        composeRule.onNodeWithText(string(R.string.settings_cloud_wifi_only)).performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(string(R.string.upgrade_cloud_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(string(R.string.upgrade_cloud_title)).assertIsDisplayed()

        // The refusal has to reach the stored setting too: an upgrade dialog over a mode that
        // was quietly persisted would still leave the resumed sync loop eligible to run.
        assertEquals(CloudUploadMode.OFF, currentUploadMode())
    }

    @Test
    fun premiumUserTurningOnCloudUploadIsNotStoppedByTheGate() {
        billing.setStatus(SubscriptionStatus.ACTIVE)
        openSettings()

        composeRule.onNodeWithText(string(R.string.settings_cloud_wifi_only)).performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            currentUploadMode() == CloudUploadMode.WIFI_ONLY
        }
        assertEquals(CloudUploadMode.WIFI_ONLY, currentUploadMode())

        // Whether Drive authorization then succeeds is not this test's business - there is no
        // Google account on a CI emulator - but the gate must never have interrupted.
        composeRule.onAllNodesWithText(string(R.string.upgrade_cloud_title)).assertCountEquals(0)
    }

    // --- Extended recording (the full 24h save range) -----------------------------------------

    @Test
    fun theExtendedSaveRangeIsRefusedAtFreeAndGrantedAtActive() = runBlocking {
        // Well outside the 15-minute free window, and outside its one-minute grace.
        val now = System.currentTimeMillis()
        val startTime = now - 2 * 60 * 60 * 1000L

        billing.setStatus(SubscriptionStatus.FREE)
        assertTrue(
            "a free user must not reach the full buffer",
            subscriptionGate.checkSaveAccess(startTime, now) is GateResult.Blocked
        )

        billing.setStatus(SubscriptionStatus.ACTIVE)
        assertTrue(
            "a subscriber must reach the full buffer",
            subscriptionGate.checkSaveAccess(startTime, now) is GateResult.Allowed
        )
    }

    // --- The app-launch resume path -----------------------------------------------------------

    /**
     * `BackRecorderApp.resumeCloudSync()` restarts uploading at launch whenever the stored mode
     * is not OFF and the Drive grant is intact - the entry point most likely to leak premium
     * functionality, because no user action stands behind it.
     *
     * It deliberately does not consult the gate itself: at that moment billing has only just been
     * asked and still reports its unresolved FREE default, so refusing there would lock out a
     * paying subscriber and persisting OFF would erase their setting. The gate instead sits in
     * `CloudSyncManager.canSyncNow()`, which every tick of the resumed loop passes through and
     * which additionally waits on `statusResolved`.
     *
     * This asserts that contract against the real graph, in exactly the state a launch resume
     * leaves behind: the stored mode says sync, and the gate still refuses a FREE user - while
     * an unanswered billing query counts as neither entitlement nor refusal.
     */
    @Test
    fun theResumedSyncLoopIsStillGatedForAFreeUser() = runBlocking {
        appPreferences.setCloudUploadMode(CloudUploadMode.ALWAYS_ON)
        appPreferences.setDriveDisconnected(false)

        billing.setUnresolved()
        assertFalse(
            "an unanswered billing query must not be read as an answer",
            subscriptionGate.statusResolved.value
        )

        billing.setStatus(SubscriptionStatus.FREE)
        assertTrue(
            "a stored upload mode must not let a free user resume cloud sync",
            subscriptionGate.checkCloudAccess() is GateResult.Blocked
        )

        billing.setStatus(SubscriptionStatus.ACTIVE)
        assertTrue(
            "a subscriber's sync must resume",
            subscriptionGate.checkCloudAccess() is GateResult.Allowed
        )
    }
}
