package com.backrecorderapp.ui

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * `MainScreen` applies the scaffold's inset padding *inside* its scroll container, so the
 * collapsing top bar translates the content instead of remeasuring the scroll viewport (#71).
 * Nothing in Compose's semantics tree can see a viewport remeasure, so what is pinned here is the
 * half that a wrong version of that move would break: at rest the content must still clear the
 * bar rather than start at the top of the window. Dropping `paddingValues` altogether passes a
 * visual glance on a gesture-navigation device and fails this.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MainScreenInsetsTest {

    @Inject lateinit var appPreferences: AppPreferences

    private val preferences = PreferenceSnapshot()

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

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
            appPreferences.setDisclaimerAccepted(true)
        }
    }

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun scrolledContentStartsBelowTheTopAppBar() {
        FirstLaunchScreens.awaitMainScreen(composeRule)

        val settingsBottom = composeRule
            .onNodeWithContentDescription("Settings")
            .getUnclippedBoundsInRoot()
            .bottom
        val firstContentTop = composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.disclaimer_reminder))
            .getUnclippedBoundsInRoot()
            .top

        assertTrue(
            "First content row at $firstContentTop overlaps the top bar ending at $settingsBottom",
            firstContentTop >= settingsBottom
        )
    }
}
