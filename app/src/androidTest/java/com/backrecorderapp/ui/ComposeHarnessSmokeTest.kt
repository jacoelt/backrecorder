package com.backrecorderapp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.backrecorderapp.MainActivity
import com.backrecorderapp.R
import com.backrecorderapp.data.preferences.AppPreferences
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
 * Fails loudly when the Compose UI test harness itself is broken, so the flow tests built on it
 * can trust their own failures.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ComposeHarnessSmokeTest {

    @Inject lateinit var appPreferences: AppPreferences

    private val preferences = PreferenceSnapshot()

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /** Injects, and puts the preference back for whichever test shares this process next. */
    @get:Rule(order = 1)
    val cleanUpAfterwards = cleanUpRule(hiltRule) {
        runBlocking { preferences.restoreAll() }
    }

    /**
     * Seeds ahead of the compose rule, which is what launches the activity. See [cleanUpRule] —
     * `NavGraph` fixes its start destination on first composition and `NavHost` keeps the graph
     * afterwards, so seeding later cannot re-route. Writing through [AppPreferences] rather than
     * deleting the backing file keeps the process-wide DataStore cache in step with the disk.
     */
    @get:Rule(order = 2)
    val seedPreferences = object : ExternalResource() {
        override fun before() = runBlocking {
            preferences.capture(
                appPreferences.disclaimerAccepted,
                appPreferences::setDisclaimerAccepted
            )
            appPreferences.setDisclaimerAccepted(false)
        }
    }

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainActivityRendersItsFirstScreen() {
        val disclaimerTitle = composeRule.activity.getString(R.string.disclaimer_title)

        // The first frame is a spinner while DataStore loads, so wait for the screen itself.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(disclaimerTitle).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText(disclaimerTitle).assertIsDisplayed()
    }
}
