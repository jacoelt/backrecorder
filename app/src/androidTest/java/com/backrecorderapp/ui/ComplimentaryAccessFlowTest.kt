package com.backrecorderapp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.backrecorderapp.MainActivity
import com.backrecorderapp.R
import com.backrecorderapp.data.billing.FakeBillingRepository
import com.backrecorderapp.data.billing.SubscriptionStatus
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.di.TEST_COMPLIMENTARY_CODE
import com.backrecorderapp.domain.GateResult
import com.backrecorderapp.domain.SubscriptionGate
import com.backrecorderapp.ui.settings.REDEEM_CODE_FIELD_TEST_TAG
import com.backrecorderapp.testing.PreferenceSnapshot
import com.backrecorderapp.testing.cleanUpRule
import com.backrecorderapp.testing.createNotificationChannels
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Redeeming a complimentary code (#98) through the settings UI, against the real Hilt graph with
 * only billing and the accepted-digest set faked.
 *
 * The one thing a JVM test cannot reach: that the DataStore write, the gate's OR and what the
 * screen renders are the same entitlement. Billing is held at FREE throughout, because a
 * complimentary grant must never move it - a faked ACTIVE would send the acknowledge path hunting
 * for a purchase token that does not exist.
 *
 * The grant has no cleanup rule because it has no revoke API; isolation rests entirely on the
 * orchestrator's `clearPackageData`, which each test's precondition assertion also re-checks.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ComplimentaryAccessFlowTest {

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var billing: FakeBillingRepository
    @Inject lateinit var subscriptionGate: SubscriptionGate

    private val preferences = PreferenceSnapshot()

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val cleanUpAfterwards = cleanUpRule(hiltRule) {
        runBlocking { preferences.restoreAll() }
    }

    @get:Rule(order = 2)
    val seedPreferences = object : ExternalResource() {
        override fun before() {
            createNotificationChannels()
            // Billing is seeded here, not in the test body: the compose rule launches the
            // activity before the body runs, so a status set there would land after the first
            // composition had already read the unresolved FREE default.
            billing.setStatus(SubscriptionStatus.FREE)
            runBlocking {
                preferences.capture(
                    appPreferences.disclaimerAccepted,
                    appPreferences::setDisclaimerAccepted
                )
                appPreferences.setDisclaimerAccepted(true)
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

    private fun enterCode(code: String) {
        composeRule.onNodeWithText(string(R.string.settings_redeem_code))
            .performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(REDEEM_CODE_FIELD_TEST_TAG)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(REDEEM_CODE_FIELD_TEST_TAG).performTextInput(code)
        composeRule.onNodeWithText(string(R.string.settings_redeem_confirm)).performClick()
    }

    @Test
    fun redeemingAValidCodeUnlocksPremiumWhileBillingStaysFree() {
        openSettings()

        // Asserted, not assumed: without it the "Upgrade is gone" wait below would pass on a
        // button that was never there, and a grant leaked from an earlier run would go unseen.
        composeRule.onNodeWithText(string(R.string.upgrade_button))
            .performScrollTo().assertIsDisplayed()
        val before = System.currentTimeMillis()
        assertTrue(
            runBlocking {
                subscriptionGate.checkSaveAccess(before - (2 * 60 * 60 * 1000), before)
            } is GateResult.Blocked
        )

        enterCode(TEST_COMPLIMENTARY_CODE)

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(string(R.string.settings_complimentary_access))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(string(R.string.settings_complimentary_access))
            .assertIsDisplayed()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(string(R.string.upgrade_button))
                .fetchSemanticsNodes().isEmpty()
        }

        // The grant reaches the gate itself, not only the card above it.
        val now = System.currentTimeMillis()
        val result = runBlocking {
            subscriptionGate.checkSaveAccess(now - (2 * 60 * 60 * 1000), now)
        }
        assertTrue(result is GateResult.Allowed)
        assertTrue(runBlocking { subscriptionGate.isPremium() })
    }

    @Test
    fun anUnknownCodeIsRefusedAndGrantsNothing() {
        openSettings()

        enterCode("ZZZZ-ZZZZ-ZZZZ")

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(string(R.string.settings_redeem_rejected))
                .fetchSemanticsNodes().isNotEmpty()
        }

        val now = System.currentTimeMillis()
        val result = runBlocking {
            subscriptionGate.checkSaveAccess(now - (2 * 60 * 60 * 1000), now)
        }
        assertTrue(result is GateResult.Blocked)
    }
}
