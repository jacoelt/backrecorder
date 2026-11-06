package com.backrecorderapp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.backrecorderapp.MainActivity
import com.backrecorderapp.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the Compose UI test harness works end to end: Hilt builds the test component, the
 * runner swaps in `HiltTestApplication`, `MainActivity` launches, and its Compose tree can be
 * queried. It asserts nothing interesting on purpose — its job is to fail loudly when the
 * harness itself is broken, so the flow tests built on it can trust their own failures.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ComposeHarnessSmokeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /**
     * Clears persisted preferences before the activity starts. `NavGraph` picks its start
     * destination from `disclaimerAccepted` the first time it composes and keeps it, so a device
     * where someone already accepted the disclaimer would land on a different screen. Ordered
     * ahead of the compose rule because that rule launches the activity as it applies.
     */
    @get:Rule(order = 1)
    val clearPreferences = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.filesDir, "datastore/app_preferences.preferences_pb").delete()
        }
    }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun mainActivityRendersItsFirstScreen() {
        val disclaimerTitle = composeRule.activity.getString(R.string.disclaimer_title)

        // The first frame is a spinner while DataStore loads, so wait for the screen itself.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(disclaimerTitle).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText(disclaimerTitle).assertIsDisplayed()
    }
}
