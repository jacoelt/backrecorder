package com.backrecorderapp.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.backrecorderapp.MainActivity
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.testing.PreferenceSnapshot
import com.backrecorderapp.testing.cleanUpRule
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

    private val preferences = PreferenceSnapshot()

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /** Injects, and puts both seeded preferences back for the next test in this process. */
    @get:Rule(order = 1)
    val cleanUpAfterwards = cleanUpRule(hiltRule) {
        runBlocking { preferences.restoreAll() }
    }

    /** Ordered ahead of the compose rule, which launches the activity. See [cleanUpRule]. */
    @get:Rule(order = 2)
    val returningUserState = object : ExternalResource() {
        override fun before() = runBlocking {
            preferences.capture(
                appPreferences.disclaimerAccepted,
                appPreferences::setDisclaimerAccepted
            )
            preferences.capture(
                appPreferences.cloudUploadMode,
                appPreferences::setCloudUploadMode
            )
            appPreferences.setDisclaimerAccepted(true)
            appPreferences.setCloudUploadMode(CloudUploadMode.WIFI_ONLY)
        }
    }

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun anAcceptedDisclaimerSendsAPopulatedInstallStraightToMainScreen() {
        FirstLaunchScreens.awaitMainScreen(composeRule)
        FirstLaunchScreens.assertNoDisclaimer(composeRule)
    }
}
