package com.backrecorderapp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.backrecorderapp.MainActivity
import com.backrecorderapp.R
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The other half of the first-launch contract: once the disclaimer has been accepted it must not
 * come back.
 *
 * Deliberately starts from a *populated* preferences store rather than a clean one. A test that
 * only ever runs against a freshly wiped emulator can pass while the path an upgrading user takes
 * is broken, because every preference read falls through to its default and nothing exercises the
 * stored values. Seeding a second, unrelated preference alongside `disclaimerAccepted` means the
 * start-destination decision is made against a store that has real content in it.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ReturningLaunchFlowTest {

    @Inject lateinit var appPreferences: AppPreferences

    private var disclaimerWasAccepted = false
    private var previousUploadMode = CloudUploadMode.OFF

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /**
     * Ordered between the Hilt rule and the compose rule: the compose rule launches the activity,
     * and `NavGraph` fixes its start destination from `disclaimerAccepted` on first composition,
     * so a seed from `@Before` would arrive after the decision was already made.
     */
    @get:Rule(order = 1)
    val returningUserState = object : ExternalResource() {
        override fun before() {
            hiltRule.inject()
            runBlocking {
                disclaimerWasAccepted = appPreferences.disclaimerAccepted.first()
                previousUploadMode = appPreferences.cloudUploadMode.first()
                appPreferences.setDisclaimerAccepted(true)
                appPreferences.setCloudUploadMode(CloudUploadMode.WIFI_ONLY)
            }
        }
    }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        // DataStore outlives the test and later flow tests share it.
        runBlocking {
            appPreferences.setDisclaimerAccepted(disclaimerWasAccepted)
            appPreferences.setCloudUploadMode(previousUploadMode)
        }
    }

    @Test
    fun anAcceptedDisclaimerSendsAPopulatedInstallStraightToMainScreen() {
        val startRecording = composeRule.activity.getString(R.string.start_recording)

        composeRule.waitUntil(timeoutMillis = AWAIT_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(startRecording).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText(startRecording).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.disclaimer_title))
            .assertDoesNotExist()

        // The stored preference the start destination was read from is still the stored one.
        assertEquals(
            CloudUploadMode.WIFI_ONLY,
            runBlocking { appPreferences.cloudUploadMode.first() }
        )
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 30_000L
    }
}
