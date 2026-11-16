package com.backrecorderapp.ui

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.height
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
import kotlin.math.abs

/**
 * `MainScreen` applies the scaffold's inset padding *inside* its scroll container, so the
 * collapsing top bar translates the content instead of remeasuring the scroll viewport (#71).
 *
 * Both halves of that are pinned here, and the first one is only reachable because a semantics
 * node reports the bounds of the coordinator its own modifier sits at, not of the whole layout
 * node (`SemanticsNode.findCoordinatorToGetBounds`): the scroll node's bounds therefore *are* the
 * viewport, and the two modifier orders put it in different places. Padding ahead of the scroll
 * starts the viewport below the bar; padding behind it leaves the viewport spanning the window.
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
    fun theScrollViewportSpansTheWholeWindow() {
        FirstLaunchScreens.awaitMainScreen(composeRule)

        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val viewport = composeRule.onNode(hasScrollAction()).getUnclippedBoundsInRoot()

        assertTrue(
            "Scroll viewport starts at ${viewport.top}, so the inset padding is ahead of the " +
                "scroll and the collapsing bar will resize it mid-gesture",
            abs(viewport.top.value) < 1f
        )
        assertTrue(
            "Scroll viewport is ${viewport.height} tall against a ${root.height} window",
            abs(viewport.height.value - root.height.value) < 1f
        )
    }

    /**
     * The other half of the move: dropping `paddingValues` rather than relocating it would leave
     * the viewport correct and the content under the bar.
     */
    @Test
    fun contentAtRestClearsTheTopAppBar() {
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
