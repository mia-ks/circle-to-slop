package com.example

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.example.data.OcrScriptMode
import com.example.overlay.MinimalCircleOverlay
import com.example.ui.theme.QuickCircleTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OverlayUxTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `overlay omits top status pill and places controls in bottom right`() {
        val dummyBitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)

        composeRule.setContent {
            QuickCircleTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MinimalCircleOverlay(
                        screenshotBitmap = dummyBitmap,
                        scriptMode = OcrScriptMode.AUTO_MOE,
                        initialTargetLanguageCode = "en",
                        hapticEnabled = false,
                        onTargetLanguageChanged = {},
                        onDismiss = {}
                    )
                }
            }
        }

        // Top-left status/instruction pill and X close button must not exist (system back dismisses)
        composeRule.onNodeWithTag("overlay_status_pill").assertDoesNotExist()
        composeRule.onNodeWithTag("overlay_close_button").assertDoesNotExist()

        // Bottom-right controls must be displayed in the lower-right quadrant, stacked vertically
        val rootBounds = composeRule.onRoot().getBoundsInRoot()
        val moeBounds = composeRule.onNodeWithTag("overlay_moe_button").assertIsDisplayed().getBoundsInRoot()
        val translateBounds = composeRule.onNodeWithTag("overlay_inline_translate_button").assertIsDisplayed().getBoundsInRoot()

        val midY = (rootBounds.top + rootBounds.bottom) / 2f
        val midX = (rootBounds.left + rootBounds.right) / 2f

        assertTrue("MoE button should be in lower half", moeBounds.top >= midY)
        assertTrue("Translate button should be in lower half", translateBounds.top >= midY)
        assertTrue("MoE button should be on right side", moeBounds.right >= midX)
        assertTrue("Translate button should be on right side", translateBounds.right >= midX)
        assertTrue(
            "Translate pill should be stacked vertically below MoE pill",
            translateBounds.top >= moeBounds.bottom
        )

        // Tapping Translate enters inline translation bar while keeping close button removed
        composeRule.onNodeWithTag("overlay_inline_translate_button").performClick()
        composeRule.onNodeWithTag("inline_translate_bar").assertIsDisplayed()
        composeRule.onNodeWithTag("overlay_close_button").assertDoesNotExist()
        composeRule.onNodeWithTag("overlay_status_pill").assertDoesNotExist()
    }
}
