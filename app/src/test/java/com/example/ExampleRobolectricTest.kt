package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.EdgeAnchor
import com.example.data.GestureZoneConfig
import com.example.data.TriggerMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("QuickCircle", appName)
    }

    @Test
    fun `gesture zone config summary formats cleanly`() {
        val cfg = GestureZoneConfig(
            anchor = EdgeAnchor.BOTTOM_BAR,
            widthDp = 140f,
            heightDp = 32f,
            triggerMode = TriggerMode.TAP
        )
        assertEquals("Bottom · 140×32 dp · Single Tap", cfg.summaryLine())
    }

    @Test
    fun `dashboard navigates to zone editor and sandbox`() {
        composeRule.onNodeWithTag("launch_sandbox_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("open_zone_editor_button").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("open_zone_editor_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("zone_editor_viewport").assertIsDisplayed()
        composeRule.onNodeWithTag("zone_editor_back_button").performClick()

        composeRule.onNodeWithTag("launch_sandbox_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("sandbox_circle_now_button").assertIsDisplayed()
    }
}
