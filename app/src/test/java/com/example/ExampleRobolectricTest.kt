package com.example

import android.content.Context
import android.graphics.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.EdgeAnchor
import com.example.data.GestureZoneConfig
import com.example.data.TriggerMode
import com.example.engine.OcrAndLensEngine
import com.example.engine.RecognizedTextWord
import com.example.overlay.TextSelectionRange
import com.example.overlay.buildSelectedText
import com.example.overlay.hitTestWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

        val insetCfg = cfg.copy(edgeInsetDp = 16f)
        assertEquals("Bottom · 140×32 dp · +16dp inset · Single Tap", insetCfg.summaryLine())
    }

    @Test
    fun `google translate fallback rejects blank text`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(OcrAndLensEngine.launchGoogleTranslateFallback(context, "   ", "en"))
    }

    @Test
    fun `lens word hit testing and range text joining work cleanly`() {
        val words = listOf(
            RecognizedTextWord(0, 0, 0, "Coffee", Rect(10, 10, 90, 40)),
            RecognizedTextWord(1, 0, 0, "Shop", Rect(100, 10, 160, 40)),
            RecognizedTextWord(2, 1, 0, "Open", Rect(10, 50, 70, 80)),
            RecognizedTextWord(3, 1, 0, "Daily", Rect(80, 50, 150, 80))
        )
        val hit = hitTestWord(words, 120f, 25f, 8f)
        assertNotNull(hit)
        assertEquals("Shop", hit?.text)
        assertEquals("Shop\nOpen Daily", buildSelectedText(words, TextSelectionRange(1, 3)))
        assertEquals("Coffee Shop", buildSelectedText(words, TextSelectionRange(1, 0)))
    }

    @Test
    fun `dashboard shows offline pack status and navigates to zone editor and sandbox`() {
        composeRule.onNodeWithTag("launch_sandbox_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("open_zone_editor_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("offline_pack_status_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("toggle_pack_manager_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("pack_download_ja").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("open_zone_editor_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("zone_editor_viewport").assertIsDisplayed()
        composeRule.onNodeWithTag("exclude_system_back_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("zone_editor_back_button").performClick()

        composeRule.onNodeWithTag("launch_sandbox_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("sandbox_circle_now_button").assertIsDisplayed()
    }
}
