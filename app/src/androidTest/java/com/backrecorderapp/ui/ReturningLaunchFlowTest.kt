package com.backrecorderapp.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.backrecorderapp.MainActivity
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The other half of the first-launch contract: an install that has already accepted the disclaimer
 * goes straight to `MainScreen`.
 *
 * Seeds a second, unrelated preference alongside `disclaimerAccepted` so the start-destination
 * decision is made against a store with more than the one key in it, rather than against a store
 * where every other read falls through to its default.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ReturningLaunchFlowTest {

    @Inject lateinit var appPreferences: AppPreferences

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /**
     * Ordered between the Hilt rule and the compose rule: the compose rule launches the activity,
     * and `NavGraph` fixes its start destination on first composition, so a seed from `@Before`
     * would arrive after the decision was already made.
     */
    @get:Rule(order = 1)
    val returningUserState = object : ExternalResource() {
        override fun before() {
            hiltRule.inject()
            runBlocking {
                appPreferences.setDisclaimerAccepted(true)
                appPreferences.setCloudUploadMode(CloudUploadMode.WIFI_ONLY)
            }
        }
    }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun anAcceptedDisclaimerSendsAPopulatedInstallStraightToMainScreen() {
        FirstLaunchScreens.awaitMainScreen(composeRule)
        FirstLaunchScreens.assertNoDisclaimer(composeRule)
    }
}
