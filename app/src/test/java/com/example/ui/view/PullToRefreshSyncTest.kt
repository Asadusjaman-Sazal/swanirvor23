package com.example.ui.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Guards the app's swipe-down-to-sync contract: the tab screens are wrapped in a pull-to-refresh
 * container whose child scrolls, and content that is too short to scroll must still start a refresh.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class PullToRefreshSyncTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `swiping down over short scrollable content triggers a refresh`() {
        var refreshed = false
        composeTestRule.setContent {
            PullToRefreshBox(isRefreshing = false, onRefresh = { refreshed = true }) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Short tab content")
                }
            }
        }

        // Swipe the full screen height: the pull threshold is relative to the container, so a short
        // drag over the text itself would never reach it.
        composeTestRule.onRoot().performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()

        assertTrue(refreshed)
    }
}
