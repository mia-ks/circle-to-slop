package com.example

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.view.Display
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.EdgeAnchor
import com.example.data.GestureZoneConfig
import com.example.data.TriggerMode
import com.example.engine.CandidateOcrLine
import com.example.engine.CandidateOcrWord
import com.example.engine.InlineScreenTranslationState
import com.example.engine.InlineTextBlock
import com.example.engine.MoEOcrRouter
import com.example.engine.OcrAndLensEngine
import com.example.engine.OcrExpertKind
import com.example.engine.RecognizedTextWord
import com.example.engine.TranslationState
import com.example.overlay.LIVE_FINGERPRINT_H
import com.example.overlay.LIVE_FINGERPRINT_W
import com.example.overlay.TextSelectionRange
import com.example.overlay.buildSelectedText
import com.example.overlay.buildSmoothedFreehandPath
import com.example.overlay.computeFingerprintDiffFraction
import com.example.overlay.correctFreehandShape
import com.example.overlay.createScreenFingerprint
import com.example.overlay.filterBlocksExcludingRects
import com.example.overlay.hitTestWord
import com.example.service.doesTriggerOverlapIme
import com.example.service.isDeviceLockedOrAodState
import com.example.ui.computeEdgePinnedResize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("header_status_text").assertIsDisplayed()
        composeRule.onNodeWithTag("launch_sandbox_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("toggle_live_translate_button").performScrollTo().assertIsDisplayed()
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
        composeRule.onNodeWithText("Test Canvas").assertIsDisplayed()
        composeRule.onNodeWithText("Test Arena").assertDoesNotExist()
    }

    @Test
    fun `zone editor resize pins opposite edge while resizing height`() {
        // Right edge grip pulls down (+60px -> +60dp at scale=1) and left (-20px -> +20dp width):
        // top edge at pinnedTopPx=100f must stay pinned at y=100f when frameH=800f.
        val rightRes = computeEdgePinnedResize(
            anchor = EdgeAnchor.RIGHT_EDGE,
            pinnedLeftPx = 350f,
            pinnedTopPx = 100f,
            pinnedRightPx = 390f,
            pinnedBottomPx = 220f,
            startWidthDp = 40f,
            startHeightDp = 120f,
            totalDragPxX = -20f,
            totalDragPxY = 60f,
            frameW = 400f,
            frameH = 800f,
            scaleX = 1f,
            scaleY = 1f
        )
        assertEquals(60f, rightRes.widthDp, 0.01f)
        assertEquals(180f, rightRes.heightDp, 0.01f)
        val recomputedTopPx = (800f - rightRes.heightDp) * (rightRes.offsetYFraction ?: 0f)
        assertEquals(100f, recomputedTopPx, 0.5f)
    }

    @Test
    fun `smoothed freehand spline produces closed non-empty bounds`() {
        val open = buildSmoothedFreehandPath(
            listOf(Offset(10f, 10f), Offset(50f, 30f), Offset(90f, 80f)),
            isClosed = false
        )
        assertFalse(open.isEmpty)
        val closed = buildSmoothedFreehandPath(
            listOf(Offset(10f, 10f), Offset(80f, 15f), Offset(85f, 90f), Offset(12f, 85f)),
            isClosed = true
        )
        assertFalse(closed.isEmpty)
        assertTrue(buildSmoothedFreehandPath(emptyList(), isClosed = false).isEmpty)
    }

    @Test
    fun `greentext quote helpers strip markers for language id and translate line by line`() {
        val raw = ">je suis communiste\n>j'aime les communes\n>je n'aime pas les capitales\n>révolution"
        val cleaned = OcrAndLensEngine.cleanTextForLanguageId(raw)
        assertEquals(
            "je suis communiste j'aime les communes je n'aime pas les capitales révolution",
            cleaned
        )
        val lines = raw.split('\n')
        assertTrue(OcrAndLensEngine.shouldTranslateLineByLine(lines))
        assertEquals(Pair("> ", "je suis communiste"), OcrAndLensEngine.splitPrefixForTranslation(">je suis communiste"))
        assertEquals(Pair("", "bonjour"), OcrAndLensEngine.splitPrefixForTranslation("bonjour"))
    }

    @Test
    fun `moe router masks clean stage1 ml kit regions before tesseract`() {
        val cleanLatin = CandidateOcrLine(
            expert = OcrExpertKind.MLKIT_LATIN,
            text = "Boarding Gate B14",
            boundingBox = Rect(10, 10, 220, 40),
            words = listOf(CandidateOcrWord("Boarding", Rect(10, 10, 100, 40))),
            confidence = 95f
        )
        val garbledArabic = CandidateOcrLine(
            expert = OcrExpertKind.MLKIT_LATIN,
            text = "l|o *#",
            boundingBox = Rect(10, 60, 220, 95),
            words = listOf(CandidateOcrWord("l|o", Rect(10, 60, 90, 95))),
            confidence = 68f
        )
        val cleanJapanese = CandidateOcrLine(
            expert = OcrExpertKind.MLKIT_JAPANESE,
            text = "東京駅",
            boundingBox = Rect(10, 110, 140, 145),
            words = listOf(CandidateOcrWord("東京駅", Rect(10, 110, 140, 145))),
            confidence = 92f
        )

        val masked = MoEOcrRouter.collectCleanStage1MaskRects(listOf(cleanLatin, garbledArabic, cleanJapanese))
        assertEquals(2, masked.size)
        assertTrue(masked.contains(cleanLatin.boundingBox))
        assertTrue(masked.contains(cleanJapanese.boundingBox))
    }

    @Test
    fun `web search intents prefer google popup search before browser url`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intents = OcrAndLensEngine.buildWebSearchIntents(context, "QuickCircle OCR")
        assertTrue(intents.size >= 3)
        assertEquals(Intent.ACTION_WEB_SEARCH, intents[0].action)
        assertEquals("com.google.android.googlequicksearchbox", intents[0].`package`)
        assertEquals("QuickCircle OCR", intents[0].getStringExtra(SearchManager.QUERY))
        assertEquals(Intent.ACTION_WEB_SEARCH, intents[1].action)
        assertEquals(Intent.ACTION_VIEW, intents.last().action)
    }

    @Test
    fun `trigger yields to overlapping ime and suppresses on lockscreen or aod`() {
        val triggerRect = Rect(1000, 1500, 1080, 1750)
        val imeBounds = listOf(Rect(0, 1600, 1080, 2400))
        val nonOverlappingIme = listOf(Rect(0, 1900, 1080, 2400))

        assertTrue(doesTriggerOverlapIme(triggerRect, imeBounds, safetyMarginPx = 16))
        assertFalse(doesTriggerOverlapIme(triggerRect, nonOverlappingIme, safetyMarginPx = 16))

        assertTrue(isDeviceLockedOrAodState(isKeyguardLocked = true, isDeviceLocked = false, isInteractive = true, displayState = Display.STATE_ON))
        assertTrue(isDeviceLockedOrAodState(isKeyguardLocked = false, isDeviceLocked = true, isInteractive = true, displayState = Display.STATE_ON))
        assertTrue(isDeviceLockedOrAodState(isKeyguardLocked = false, isDeviceLocked = false, isInteractive = false, displayState = Display.STATE_ON))
        assertTrue(isDeviceLockedOrAodState(isKeyguardLocked = false, isDeviceLocked = false, isInteractive = true, displayState = Display.STATE_DOZE_SUSPEND))
        assertFalse(isDeviceLockedOrAodState(isKeyguardLocked = false, isDeviceLocked = false, isInteractive = true, displayState = Display.STATE_ON))
    }

    @Test
    fun `freehand shape correction regularizes wobbly circle without collapsing bounds`() {
        val cx = 200f
        val cy = 300f
        val baseR = 80f
        val noisyCircle = List(32) { i ->
            val theta = (i / 32f) * (2f * PI.toFloat())
            val wobble = if (i % 2 == 0) 1.12f else 0.88f
            Offset(cx + baseR * wobble * cos(theta), cy + baseR * wobble * sin(theta))
        }
        val corrected = correctFreehandShape(noisyCircle)
        assertTrue(corrected.points.size >= 32)
        val radii = corrected.points.map { hypot(it.x - cx, it.y - cy) }
        val maxDeviation = radii.maxOf { abs(it - baseR) }
        assertTrue("Corrected circle wobble ($maxDeviation) should be smoother than raw wobble (~9.6px)", maxDeviation < 5.5f)
        assertTrue(corrected.left < cx && corrected.right > cx)
        assertTrue(corrected.top < cy && corrected.bottom > cy)
    }

    @Test
    fun `perspective quad extraction recovers 3d tilt and stays zero on flat lines`() {
        val flatPose = MoEOcrRouter.computePerspectiveFromCornerPoints(
            listOf(Point(100, 100), Point(400, 100), Point(400, 140), Point(100, 140))
        )
        assertNotNull(flatPose)
        assertEquals(0f, flatPose!!.tiltZDeg, 0.01f)
        assertEquals(0f, flatPose.tiltXDeg, 0.01f)
        assertEquals(0f, flatPose.tiltYDeg, 0.01f)

        // Tilted counter-clockwise slightly (-5 deg Z), top narrower than bottom (+X tilt), left taller than right (-Y tilt)
        val tiltedPose = MoEOcrRouter.computePerspectiveFromCornerPoints(
            listOf(Point(120, 210), Point(410, 184), Point(422, 224), Point(106, 260))
        )
        assertNotNull(tiltedPose)
        assertTrue("Should detect negative rotationZ tilt", tiltedPose!!.tiltZDeg < -2.5f)
        assertTrue("Should detect positive rotationX depth tilt", tiltedPose.tiltXDeg > 3.0f)
        assertTrue("Should detect negative rotationY depth tilt", tiltedPose.tiltYDeg < -3.0f)
        assertTrue(tiltedPose.quadWidthPx > 250f)
        assertTrue(tiltedPose.quadHeightPx in 30f..65f)
    }

    @Test
    fun `download translation states carry queue label and bounded progress`() {
        val inline = InlineScreenTranslationState(
            targetLangCode = "en",
            isDownloadingPacks = true,
            missingPacksLabel = "French, Japanese, Spanish, Korean",
            downloadProgress = 0.64f
        )
        assertEquals("French, Japanese, Spanish, Korean", inline.missingPacksLabel)
        assertEquals(0.64f, inline.downloadProgress, 0.001f)

        val sheet = TranslationState.DownloadingModel(
            languagePairLabel = "Japanese -> English",
            missingPacksLabel = "Japanese, English",
            progress = 0.45f
        )
        assertEquals("Japanese, English", sheet.missingPacksLabel)
        assertEquals(0.45f, sheet.progress, 0.001f)
    }

    @Test
    fun `screen fingerprint diff ignores excluded hud region and detects content changes`() {
        fun paintRect(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top until bottom.coerceAtMost(bitmap.height)) {
                for (x in left until right.coerceAtMost(bitmap.width)) {
                    bitmap.setPixel(x, y, color)
                }
            }
        }

        val bmpA = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        val bmpB = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        val hudNorm = RectF(0.2f, 0.08f, 0.8f, 0.18f)
        // Paint only inside the excluded HUD region on bmpB -> diff must remain 0f
        paintRect(bmpB, 14, 9, 50, 16, Color.WHITE)

        val fpA = createScreenFingerprint(bmpA)
        val fpB = createScreenFingerprint(bmpB)
        val hudOnlyDiff = computeFingerprintDiffFraction(
            previous = fpA,
            current = fpB,
            width = LIVE_FINGERPRINT_W,
            height = LIVE_FINGERPRINT_H,
            excludeNormalized = hudNorm
        )
        assertEquals(0f, hudOnlyDiff, 0.001f)

        // Paint a large white card in main body -> diff must clearly exceed threshold
        paintRect(bmpB, 4, 28, 60, 76, Color.WHITE)
        val fpChanged = createScreenFingerprint(bmpB)
        val bodyDiff = computeFingerprintDiffFraction(
            previous = fpA,
            current = fpChanged,
            width = LIVE_FINGERPRINT_W,
            height = LIVE_FINGERPRINT_H,
            excludeNormalized = hudNorm
        )
        assertTrue("Expected content diff > 0.25, got $bodyDiff", bodyDiff > 0.25f)
        bmpA.recycle()
        bmpB.recycle()
    }

    @Test
    fun `live filter excludes blocks inside floating hud or keyboard and caches translations`() {
        val hudRect = Rect(200, 80, 780, 180)
        val imeRect = Rect(0, 1500, 1080, 2200)
        val blocks = listOf(
            InlineTextBlock(0, "Live · 3", Rect(240, 100, 460, 150), 1, 24f, "en", Color.BLACK, Color.WHITE),
            InlineTextBlock(1, "東京駅へようこそ", Rect(60, 420, 720, 500), 1, 32f, "ja", Color.BLACK, Color.WHITE),
            InlineTextBlock(2, "q w e r t y", Rect(40, 1650, 900, 1740), 1, 28f, "en", Color.BLACK, Color.WHITE)
        )

        val kept = filterBlocksExcludingRects(blocks, listOf(hudRect, imeRect))
        assertEquals(1, kept.size)
        assertEquals(1, kept.first().id)

        OcrAndLensEngine.clearLiveTranslationCache()
        OcrAndLensEngine.putCachedTranslation("ja", "en", "東京駅へようこそ", "Welcome to Tokyo Station")
        assertEquals(
            "Welcome to Tokyo Station",
            OcrAndLensEngine.getCachedTranslation("ja", "en", "東京駅へようこそ")
        )
        assertEquals(
            null,
            OcrAndLensEngine.getCachedTranslation("ja", "es", "東京駅へようこそ")
        )
    }
}
