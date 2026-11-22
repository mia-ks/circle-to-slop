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
import com.example.engine.CandidateOcrLine
import com.example.engine.CandidateOcrWord
import com.example.engine.MoEOcrRouter
import com.example.engine.OcrAndLensEngine
import com.example.engine.OcrExpertKind
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
    fun `google translate uri preserves text source and target languages`() {
        val uri = OcrAndLensEngine.buildGoogleTranslateWebUri(
            text = "東京駅へようこそ",
            targetLangCode = "en",
            sourceLangCodeHint = "ja"
        )
        assertEquals("translate.google.com", uri.host)
        assertEquals("ja", uri.getQueryParameter("sl"))
        assertEquals("en", uri.getQueryParameter("tl"))
        assertEquals("東京駅へようこそ", uri.getQueryParameter("text"))
        assertEquals("translate", uri.getQueryParameter("op"))

        val autoZhUri = OcrAndLensEngine.buildGoogleTranslateWebUri(
            text = "Привет мир",
            targetLangCode = "zh",
            sourceLangCodeHint = null
        )
        assertEquals("auto", autoZhUri.getQueryParameter("sl"))
        assertEquals("zh-CN", autoZhUri.getQueryParameter("tl"))
        assertEquals("Привет мир", autoZhUri.getQueryParameter("text"))
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

        val brushed = com.example.overlay.matchTextBrushToWordRange(
            screenPoints = listOf(
                androidx.compose.ui.geometry.Offset(30f, 25f),
                androidx.compose.ui.geometry.Offset(130f, 25f),
                androidx.compose.ui.geometry.Offset(120f, 65f)
            ),
            words = words,
            sx = 1f,
            sy = 1f,
            density = androidx.compose.ui.unit.Density(1f)
        )
        assertEquals(TextSelectionRange(0, 3), brushed)
    }

    @Test
    fun `moe ocr router fuses arabic cyrillic japanese and latin experts cleanly`() {
        val arabicBox = Rect(20, 20, 300, 60)
        val japaneseBox = Rect(20, 80, 300, 120)
        val latinBox = Rect(20, 140, 300, 180)

        val candidates = listOf(
            // Latin ML Kit misreads Arabic glyphs with junk symbols
            CandidateOcrLine(
                expert = OcrExpertKind.MLKIT_LATIN,
                text = "l|o *#",
                boundingBox = arabicBox,
                words = listOf(CandidateOcrWord("l|o", arabicBox)),
                confidence = 70f
            ),
            // Tesseract Arabic nails the exact same region
            CandidateOcrLine(
                expert = OcrExpertKind.TESSERACT,
                text = "مطار دبي الدولي",
                boundingBox = arabicBox,
                words = listOf(CandidateOcrWord("مطار", arabicBox)),
                confidence = 88f
            ),
            // ML Kit Japanese wins over Latin on Japanese box
            CandidateOcrLine(
                expert = OcrExpertKind.MLKIT_JAPANESE,
                text = "東京駅",
                boundingBox = japaneseBox,
                words = listOf(CandidateOcrWord("東京駅", japaneseBox)),
                confidence = 92f
            ),
            // ML Kit Latin wins over Tesseract on clean English text
            CandidateOcrLine(
                expert = OcrExpertKind.TESSERACT,
                text = "Boarding Gate B14",
                boundingBox = latinBox,
                words = listOf(CandidateOcrWord("Boarding", latinBox)),
                confidence = 82f
            ),
            CandidateOcrLine(
                expert = OcrExpertKind.MLKIT_LATIN,
                text = "Boarding Gate B14",
                boundingBox = latinBox,
                words = listOf(CandidateOcrWord("Boarding", latinBox)),
                confidence = 95f
            )
        )

        val fused = MoEOcrRouter.fuseMoECandidates(candidates)
        assertEquals(3, fused.size)
        assertEquals(OcrExpertKind.TESSERACT, fused[0].expert)
        assertEquals("مطار دبي الدولي", fused[0].text)
        assertEquals(OcrExpertKind.MLKIT_JAPANESE, fused[1].expert)
        assertEquals(OcrExpertKind.MLKIT_LATIN, fused[2].expert)
    }

    @Test
    fun `russian module does not confuse clean english ui text with cyrillic`() {
        val mixedHomoglyphBox = Rect(20, 20, 320, 60)
        val pureHomoglyphBox = Rect(20, 80, 240, 120)
        val realRussianBox = Rect(20, 140, 360, 180)

        val candidates = listOf(
            // Case 1: Tesseract-rus outputs mixed Latin+Cyrillic letters on English "Boarding Gate"
            CandidateOcrLine(
                expert = OcrExpertKind.TESSERACT,
                text = "Воаrding Gаtе",
                boundingBox = mixedHomoglyphBox,
                words = listOf(CandidateOcrWord("Воаrding", mixedHomoglyphBox)),
                confidence = 89f
            ),
            CandidateOcrLine(
                expert = OcrExpertKind.MLKIT_LATIN,
                text = "Boarding Gate",
                boundingBox = mixedHomoglyphBox,
                words = listOf(CandidateOcrWord("Boarding", mixedHomoglyphBox)),
                confidence = 95f
            ),
            // Case 2: Tesseract-rus coerces English "SETTINGS" into all-Cyrillic homoglyph lookalike
            CandidateOcrLine(
                expert = OcrExpertKind.TESSERACT,
                text = "ЗЕТТIНGС",
                boundingBox = pureHomoglyphBox,
                words = listOf(CandidateOcrWord("ЗЕТТIНGС", pureHomoglyphBox)),
                confidence = 78f
            ),
            CandidateOcrLine(
                expert = OcrExpertKind.MLKIT_LATIN,
                text = "SETTINGS",
                boundingBox = pureHomoglyphBox,
                words = listOf(CandidateOcrWord("SETTINGS", pureHomoglyphBox)),
                confidence = 96f
            ),
            // Case 3: Genuine Russian text mangled by ML Kit Latin wins cleanly for Tesseract-rus
            CandidateOcrLine(
                expert = OcrExpertKind.MLKIT_LATIN,
                text = "MockoBCKNN BoK3aJI",
                boundingBox = realRussianBox,
                words = listOf(CandidateOcrWord("MockoBCKNN", realRussianBox)),
                confidence = 76f
            ),
            CandidateOcrLine(
                expert = OcrExpertKind.TESSERACT,
                text = "Московский вокзал",
                boundingBox = realRussianBox,
                words = listOf(CandidateOcrWord("Московский", realRussianBox)),
                confidence = 89f
            )
        )

        val fused = MoEOcrRouter.fuseMoECandidates(candidates)
        assertEquals(3, fused.size)
        assertEquals(OcrExpertKind.MLKIT_LATIN, fused[0].expert)
        assertEquals("Boarding Gate", fused[0].text)
        assertEquals(OcrExpertKind.MLKIT_LATIN, fused[1].expert)
        assertEquals("SETTINGS", fused[1].text)
        assertEquals(OcrExpertKind.TESSERACT, fused[2].expert)
        assertEquals("Московский вокзал", fused[2].text)
    }

    @Test
    fun `dashboard shows offline pack status and navigates to zone editor and sandbox`() {
        composeRule.onNodeWithTag("launch_sandbox_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("open_zone_editor_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("offline_pack_status_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("toggle_pack_manager_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("pack_download_ja").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("toggle_ocr_pack_manager_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("tess_ocr_get_ara").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("tess_ocr_get_rus").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("open_zone_editor_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("zone_editor_viewport").assertIsDisplayed()
        composeRule.onNodeWithTag("exclude_system_back_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("zone_editor_back_button").performClick()

        composeRule.onNodeWithTag("launch_sandbox_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("sandbox_circle_now_button").assertIsDisplayed()
    }
}
