package com.backrecorderapp.ui

import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
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
import com.backrecorderapp.data.billing.BillingPeriod
import com.backrecorderapp.data.billing.FakeBillingRepository
import com.backrecorderapp.data.billing.PurchaseFailure
import com.backrecorderapp.data.billing.SubscriptionStatus
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.domain.BlockReason
import com.backrecorderapp.domain.GateResult
import com.backrecorderapp.domain.SaveRecordingUseCase
import com.backrecorderapp.domain.SaveResult
import com.backrecorderapp.domain.SubscriptionGate
import com.backrecorderapp.testing.PreferenceSnapshot
import com.backrecorderapp.testing.cleanUpRule
import com.backrecorderapp.testing.createNotificationChannels
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
 * The premium boundary: Drive sync and the extended save range are refused at FREE and granted at
 * ACTIVE, through the real Hilt graph with only billing faked.
 *
 * Billing comes from [FakeBillingRepository] via `@TestInstallIn`, the same seam shape the
 * microphone uses. Nothing here simulates a purchase - the app only ever asks billing what the
 * subscription *is*, because purchases happen in the Play Store.
 *
 * Two of the three entry points are driven through their real caller: the cloud-upload toggle and
 * the Connect Drive button go through the settings UI, and the extended save range goes through
 * [SaveRecordingUseCase]. The remaining checks call [SubscriptionGate] directly, and say so.
 *
 * **Not covered here:** the app-launch cloud-sync resume end to end. See
 * [theCloudGateRefusesAFreeUserWhoseStoredModeSaysSync] for what is asserted instead and why.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SubscriptionGateFlowTest {

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var billing: FakeBillingRepository
    @Inject lateinit var subscriptionGate: SubscriptionGate
    @Inject lateinit var saveRecordingUseCase: SaveRecordingUseCase

    private val preferences = PreferenceSnapshot()

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /**
     * Injects, and restores the preferences this class seeds. See [cleanUpRule] for the ordering.
     *
     * The Hilt component - and with it the `@Singleton` billing fake - is rebuilt per test, but
     * the preference file is process-wide and shared with every other instrumented class, so the
     * upload mode is put back rather than left wherever a test happened to leave it.
     */
    @get:Rule(order = 1)
    val cleanUpAfterwards = cleanUpRule(hiltRule) {
        runBlocking { preferences.restoreAll() }
    }

    /**
     * Ordered ahead of the compose rule, which launches the activity. See [cleanUpRule].
     *
     * Written through [AppPreferences] rather than by deleting the backing file, because the
     * `preferencesDataStore` delegate caches in-process and a delete is a silent no-op once a
     * sibling test has read prefs.
     */
    @get:Rule(order = 2)
    val seedPreferences = object : ExternalResource() {
        override fun before() {
            createNotificationChannels()
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
                appPreferences.setDriveDisconnected(false)
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

    private fun awaitUpgradeDialog() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(string(R.string.upgrade_cloud_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(string(R.string.upgrade_cloud_title)).assertIsDisplayed()
    }

    /**
     * The settings screen carries its own Upgrade button in the Subscription section, so once a
     * dialog is up two nodes read "Upgrade to Premium". It is the dialog's one that is meant.
     */
    private fun tapUpgradeInDialog() {
        composeRule.onAllNodesWithText(string(R.string.upgrade_button))
            .filterToOne(hasAnyAncestor(isDialog()))
            .performClick()
    }

    private fun awaitPlanChooser() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(string(R.string.plan_chooser_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun currentUploadMode(): CloudUploadMode =
        runBlocking { appPreferences.cloudUploadMode.first() }

    // --- Drive sync, through the settings UI --------------------------------------------------

    @Test
    fun freeUserTurningOnCloudUploadIsRefusedAndOfferedAnUpgrade() {
        billing.setStatus(SubscriptionStatus.FREE)
        openSettings()

        composeRule.onNodeWithText(string(R.string.settings_cloud_wifi_only))
            .performScrollTo().performClick()

        awaitUpgradeDialog()

        // The refusal has to reach the stored setting too: an upgrade dialog over a mode that
        // was quietly persisted would still leave the resumed sync loop eligible to run.
        assertEquals(CloudUploadMode.OFF, currentUploadMode())
    }

    /**
     * Connect Drive is a second premium entry point, guarded separately from the mode toggle in
     * `SettingsViewModel.connectDrive()`. It is reachable while the mode is still OFF, so a gate
     * that only covered the toggle would let a free user hand over an account.
     */
    @Test
    fun freeUserConnectingDriveIsRefusedAndOfferedAnUpgrade() {
        billing.setStatus(SubscriptionStatus.FREE)
        openSettings()

        composeRule.onNodeWithText(string(R.string.settings_drive_connect))
            .performScrollTo().performClick()

        awaitUpgradeDialog()
        assertEquals(CloudUploadMode.OFF, currentUploadMode())
    }

    /**
     * The upgrade offer has to survive a hand-off Play refuses: the failure is reported, not
     * silent.
     */
    @Test
    fun anUpgradePlayCannotOfferIsReportedRatherThanSilentlyDropped() {
        billing.setStatus(SubscriptionStatus.FREE)
        billing.nextLaunchFails = PurchaseFailure.PRODUCT_UNAVAILABLE
        openSettings()

        composeRule.onNodeWithText(string(R.string.settings_cloud_wifi_only))
            .performScrollTo().performClick()
        awaitUpgradeDialog()
        tapUpgradeInDialog()
        awaitPlanChooser()
        composeRule.onNodeWithText(string(R.string.plan_chooser_continue)).performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(string(R.string.upgrade_error_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            string(R.string.upgrade_error_product_unavailable)
        ).assertIsDisplayed()
        assertEquals(1, billing.purchaseFlowLaunches)
    }

    /**
     * Issue #74, end to end: the user picks a plan and that plan is what reaches Play.
     *
     * The defect this replaces sent whichever offer Play happened to return first, so one tap
     * could commit someone to a year they never chose. Monthly is selected deliberately here
     * because the chooser *defaults* to yearly when there is a saving to show - picking the
     * non-default is the only way to prove the selection is read rather than assumed.
     */
    @Test
    fun theUpgradeHandsPlayTheExactPlanTheUserChose() {
        billing.setStatus(SubscriptionStatus.FREE)
        openSettings()

        composeRule.onNodeWithText(string(R.string.settings_cloud_wifi_only))
            .performScrollTo().performClick()
        awaitUpgradeDialog()
        tapUpgradeInDialog()
        awaitPlanChooser()

        // Play's own prices, and the saving computed from them rather than written into the app.
        composeRule.onNodeWithText("$2.99 per month").assertIsDisplayed()
        composeRule.onNodeWithText("$29.90 per year").assertIsDisplayed()
        composeRule.onNodeWithText("Save ~16%").assertIsDisplayed()

        composeRule.onNodeWithText(string(R.string.plan_period_monthly)).performClick()
        composeRule.onNodeWithText(string(R.string.plan_chooser_continue)).performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) { billing.purchaseFlowLaunches == 1 }
        assertEquals(BillingPeriod.MONTHLY, billing.lastPurchasedPlan?.period)
    }

    /** Plans Play will not name cannot be chosen between, so the refusal is shown instead. */
    @Test
    fun anUpgradeWhosePlansPlayWillNotNameIsReported() {
        billing.setStatus(SubscriptionStatus.FREE)
        billing.nextLoadFails = PurchaseFailure.BILLING_UNAVAILABLE
        openSettings()

        composeRule.onNodeWithText(string(R.string.settings_cloud_wifi_only))
            .performScrollTo().performClick()
        awaitUpgradeDialog()
        tapUpgradeInDialog()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(string(R.string.upgrade_error_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            string(R.string.upgrade_error_billing_unavailable)
        ).assertIsDisplayed()
        assertEquals(0, billing.purchaseFlowLaunches)
    }

    @Test
    fun premiumUserTurningOnCloudUploadIsNotStoppedByTheGate() {
        billing.setStatus(SubscriptionStatus.ACTIVE)
        openSettings()

        composeRule.onNodeWithText(string(R.string.settings_cloud_wifi_only))
            .performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            currentUploadMode() == CloudUploadMode.WIFI_ONLY
        }

        // Whether Drive authorization then succeeds is not this test's business - there is no
        // Google account on a CI emulator - but the gate must never have interrupted. Settle the
        // UI first, so a refusal posted just after the write is not missed.
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(string(R.string.upgrade_cloud_title)).assertCountEquals(0)
    }

    // --- Extended recording, through SaveRecordingUseCase -------------------------------------

    /**
     * Driven through the real caller rather than the gate, so deleting the gate check from
     * [SaveRecordingUseCase] fails this too.
     *
     * At ACTIVE the save gets past the gate and then stops on "no recorded data", because no
     * recording exists - that [SaveResult.Error] *is* the evidence the gate let it through, and it
     * needs no microphone.
     */
    @Test
    fun theExtendedSaveRangeIsRefusedAtFreeAndGrantedAtActive() = runBlocking {
        // Well outside the 15-minute free window, and outside its one-minute grace.
        val now = System.currentTimeMillis()
        val startTime = now - 2 * 60 * 60 * 1000L

        billing.setStatus(SubscriptionStatus.FREE)
        assertEquals(
            "a free user must not reach the full buffer",
            SaveResult.UpgradeRequired(BlockReason.SAVE_RANGE_EXCEEDS_FREE_LIMIT),
            saveRecordingUseCase.execute(startTime, now, Uri.EMPTY)
        )

        billing.setStatus(SubscriptionStatus.ACTIVE)
        assertTrue(
            "a subscriber must get past the gate and fail only for want of a recording",
            saveRecordingUseCase.execute(startTime, now, Uri.EMPTY) is SaveResult.Error
        )
    }

    // --- The app-launch resume path -----------------------------------------------------------

    /**
     * `BackRecorderApp.resumeCloudSync()` restarts uploading at launch whenever the stored mode is
     * not OFF and the Drive grant is intact - the entry point most likely to leak premium
     * functionality, because no user action stands behind it.
     *
     * It deliberately does not consult the gate itself: at that moment billing has only just been
     * asked and still reports its unresolved FREE default, so refusing there would lock out a
     * paying subscriber and persisting OFF would erase their setting. The gate instead sits in
     * `CloudSyncManager.canSyncNow()`, which every tick of the resumed loop passes through and
     * which additionally waits on `statusResolved`.
     *
     * **This asserts the gate, not the loop.** Driving the resumed loop itself would need a
     * controllable `DriveCloudStorage`: `canSyncNow()` returns false on `!isInitialized` before
     * the gate can matter, so on an emulator with no Drive grant the loop is inert either way and
     * any assertion on it would pass with the gate deleted. That test is owed once the Drive fake
     * lands (issue #42). What is checked here is the state a launch resume leaves behind - stored
     * mode says sync - and that the gate the loop consults still refuses a free user in it.
     */
    @Test
    fun theCloudGateRefusesAFreeUserWhoseStoredModeSaysSync() = runBlocking {
        appPreferences.setCloudUploadMode(CloudUploadMode.ALWAYS_ON)
        assertEquals(
            "precondition: the stored mode is the one a launch resume would act on",
            CloudUploadMode.ALWAYS_ON,
            currentUploadMode()
        )

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

        // TRIAL counts as premium in SubscriptionGate.isPremium(), so it is covered explicitly -
        // a gate narrowed to ACTIVE alone would silently cut off every trial user.
        billing.setStatus(SubscriptionStatus.TRIAL)
        assertTrue(
            "a trial user's sync must resume",
            subscriptionGate.checkCloudAccess() is GateResult.Allowed
        )

        billing.setStatus(SubscriptionStatus.ACTIVE)
        assertTrue(
            "a subscriber's sync must resume",
            subscriptionGate.checkCloudAccess() is GateResult.Allowed
        )
    }
}
