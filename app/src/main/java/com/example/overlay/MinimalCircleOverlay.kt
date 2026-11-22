package com.example.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.OcrScriptMode
import com.example.data.SupportedTargetLanguages
import com.example.engine.InlineBlockStatus
import com.example.engine.InlineScreenTranslationState
import com.example.engine.InlineTextBlock
import com.example.engine.ModelPackStatus
import com.example.engine.OcrAndLensEngine
import com.example.engine.OcrRegionResult
import com.example.engine.RecognizedTextLine
import com.example.engine.ScreenOcrAnalysis
import com.example.engine.TranslationState
import com.example.ui.theme.CoralError
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.InterFamily
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SpaceGroteskFamily
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

private sealed interface SelectionGeometry {
    data class Freehand(val points: List<Offset>, val isClosed: Boolean) : SelectionGeometry
    data class BoxRect(val left: Float, val top: Float, val right: Float, val bottom: Float) : SelectionGeometry
    data class LensWords(val range: TextSelectionRange) : SelectionGeometry
}

@Composable
fun MinimalCircleOverlay(
    screenshotBitmap: Bitmap,
    scriptMode: OcrScriptMode,
    initialTargetLanguageCode: String,
    hapticEnabled: Boolean,
    onTargetLanguageChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onExternalActionLaunched: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val screenImageBitmap = remember(screenshotBitmap) { screenshotBitmap.asImageBitmap() }

    var screenAnalysis by remember(screenshotBitmap) {
        mutableStateOf(ScreenOcrAnalysis())
    }
    var pendingRefinedAnalysis by remember(screenshotBitmap) {
        mutableStateOf<ScreenOcrAnalysis?>(null)
    }
    var isScanningInitialWords by remember(screenshotBitmap) {
        mutableStateOf(true)
    }
    var isRunningMoE by remember(screenshotBitmap) {
        mutableStateOf(false)
    }
    var isMoEApplied by remember(screenshotBitmap) {
        mutableStateOf(false)
    }
    var moeJob by remember(screenshotBitmap) {
        mutableStateOf<Job?>(null)
    }
    var hasEnrichedInline by remember(screenshotBitmap) {
        mutableStateOf(false)
    }
    val fullScreenLines = screenAnalysis.lines
    val fullScreenWords = screenAnalysis.words
    val fullScreenBlocks = screenAnalysis.blocks

    var selection by remember { mutableStateOf<SelectionGeometry?>(null) }
    var isDrawing by remember { mutableStateOf(false) }
    var isDraggingTextHandle by remember { mutableStateOf(false) }

    var croppedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var lastCropRect by remember { mutableStateOf<Rect?>(null) }
    var ocrResult by remember { mutableStateOf<OcrRegionResult?>(null) }
    var editableText by remember { mutableStateOf("") }
    var isAnalyzingOcr by remember { mutableStateOf(false) }

    var selectedTargetLang by remember(initialTargetLanguageCode) {
        mutableStateOf(initialTargetLanguageCode)
    }
    var translationState by remember { mutableStateOf<TranslationState>(TranslationState.Idle) }
    var translationJob by remember { mutableStateOf<Job?>(null) }
    var copiedLabel by remember { mutableStateOf<String?>(null) }

    // Lens-style Inline Screen Translation mode state
    var isInlineTranslateMode by remember { mutableStateOf(false) }
    var showOriginalScreen by remember { mutableStateOf(false) }
    var inlineTranslationState by remember { mutableStateOf(InlineScreenTranslationState()) }
    var inlineTranslationJob by remember { mutableStateOf<Job?>(null) }
    var focusedInlineBlock by remember { mutableStateOf<InlineTextBlock?>(null) }

    val packStatuses by OcrAndLensEngine.packStatusFlow.collectAsState()
    val hasGoogleTranslateApp = remember(context) {
        OcrAndLensEngine.isGoogleTranslateAppInstalled(context)
    }

    // Ultra-fast Latin first pass (~80-160ms), then warm MoE weights asynchronously in background without running MoE OCR yet
    LaunchedEffect(screenshotBitmap) {
        isScanningInitialWords = true
        isRunningMoE = false
        isMoEApplied = false
        hasEnrichedInline = false
        pendingRefinedAnalysis = null
        OcrAndLensEngine.prewarmLatin(context)
        val fastLatin = OcrAndLensEngine.analyzeFullScreenFastLatin(screenshotBitmap)
        if (!isMoEApplied && !isRunningMoE) {
            if (selection !is SelectionGeometry.LensWords && !isDraggingTextHandle) {
                screenAnalysis = fastLatin
            } else {
                pendingRefinedAnalysis = fastLatin
            }
        }
        isScanningInitialWords = false
        OcrAndLensEngine.prewarmMoEOnInvoke(context, delayMs = 0L)
    }

    // Clear copied indicator quickly
    LaunchedEffect(copiedLabel) {
        if (copiedLabel != null) {
            delay(1500)
            copiedLabel = null
        }
    }

    fun copyToClipboard(label: String, value: String) {
        if (value.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("QuickCircle", value))
        copiedLabel = label
    }

    fun runTranslation(textToTranslate: String, sourceHint: String?, targetLang: String) {
        translationJob?.cancel()
        if (textToTranslate.isBlank()) {
            translationState = TranslationState.Idle
            return
        }
        translationJob = scope.launch {
            OcrAndLensEngine.translateText(
                text = textToTranslate,
                sourceLangCodeHint = sourceHint,
                targetLangCode = targetLang
            ) { state ->
                translationState = state
            }
        }
    }

    fun runInlineScreenTranslation(targetLang: String) {
        inlineTranslationJob?.cancel()
        inlineTranslationJob = scope.launch {
            val baseAnalysis = pendingRefinedAnalysis?.also {
                screenAnalysis = it
                pendingRefinedAnalysis = null
                hasEnrichedInline = false
            } ?: screenAnalysis

            val readyAnalysis = if (!hasEnrichedInline && baseAnalysis.blocks.isNotEmpty()) {
                OcrAndLensEngine.enrichAnalysisForInlineTranslate(screenshotBitmap, baseAnalysis).also {
                    screenAnalysis = it
                    hasEnrichedInline = true
                }
            } else {
                baseAnalysis
            }

            OcrAndLensEngine.translateBlocksInline(
                blocks = readyAnalysis.blocks,
                targetLangCode = targetLang,
                dominantLangHint = readyAnalysis.dominantLanguageCode
            ) { state ->
                inlineTranslationState = state
            }
        }
    }

    fun applyLensWordSelection(range: TextSelectionRange, runOcrTranslationNow: Boolean) {
        val activeWords = screenAnalysis.words
        val activeBlocks = screenAnalysis.blocks
        selection = SelectionGeometry.LensWords(range)
        if (!runOcrTranslationNow || activeWords.isEmpty()) return

        val selectedWords = selectedWordsForRange(activeWords, range)
        val text = buildSelectedText(activeWords, range)
        val bounds = computeSelectionBitmapBounds(activeWords, range) ?: return

        val padPx = (8 * density.density).toInt()
        val l = (bounds.left - padPx).coerceIn(0, (screenshotBitmap.width - 1).coerceAtLeast(0))
        val t = (bounds.top - padPx).coerceIn(0, (screenshotBitmap.height - 1).coerceAtLeast(0))
        val r = (bounds.right + padPx).coerceIn((l + 1).coerceAtMost(screenshotBitmap.width), screenshotBitmap.width)
        val b = (bounds.bottom + padPx).coerceIn((t + 1).coerceAtMost(screenshotBitmap.height), screenshotBitmap.height)

        lastCropRect = Rect(l, t, r, b)
        croppedBitmap = Bitmap.createBitmap(
            screenshotBitmap,
            l,
            t,
            (r - l).coerceAtLeast(1),
            (b - t).coerceAtLeast(1)
        )
        editableText = text
        isAnalyzingOcr = false

        val firstBlockId = selectedWords.firstOrNull()?.blockId
        val blockLangHint = activeBlocks.firstOrNull { it.id == firstBlockId }?.detectedLanguageCode
        val lineCount = selectedWords.map { it.lineIndex }.distinct().size.coerceAtLeast(1)

        scope.launch {
            val resolvedLang = if (text.length >= 4) {
                OcrAndLensEngine.detectLanguageCode(text) ?: blockLangHint
            } else {
                blockLangHint ?: OcrAndLensEngine.detectLanguageCode(text)
            }
            ocrResult = OcrRegionResult(
                extractedText = text,
                lineCount = lineCount,
                detectedLanguageCode = resolvedLang,
                detectedLanguageName = resolvedLang?.let { OcrAndLensEngine.formatLanguageName(it) }
            )
            if (text.isNotBlank()) {
                runTranslation(text, resolvedLang, selectedTargetLang)
            }
        }
    }

    fun completeRegionSelection(
        canvasWidth: Float,
        canvasHeight: Float,
        leftPx: Float,
        topPx: Float,
        rightPx: Float,
        bottomPx: Float
    ) {
        if (canvasWidth <= 0f || canvasHeight <= 0f) return
        if (hapticEnabled) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }

        val scaleX = screenshotBitmap.width / canvasWidth
        val scaleY = screenshotBitmap.height / canvasHeight
        val padPx = with(density) { 8.dp.toPx() }

        val bmpLeft = ((leftPx - padPx) * scaleX).toInt().coerceIn(0, (screenshotBitmap.width - 1).coerceAtLeast(0))
        val bmpTop = ((topPx - padPx) * scaleY).toInt().coerceIn(0, (screenshotBitmap.height - 1).coerceAtLeast(0))
        val bmpRight = ((rightPx + padPx) * scaleX).toInt().coerceIn((bmpLeft + 1).coerceAtMost(screenshotBitmap.width), screenshotBitmap.width)
        val bmpBottom = ((bottomPx + padPx) * scaleY).toInt().coerceIn((bmpTop + 1).coerceAtMost(screenshotBitmap.height), screenshotBitmap.height)

        val cropRect = Rect(bmpLeft, bmpTop, bmpRight, bmpBottom)
        val crop = Bitmap.createBitmap(
            screenshotBitmap,
            cropRect.left,
            cropRect.top,
            cropRect.width().coerceAtLeast(1),
            cropRect.height().coerceAtLeast(1)
        )

        lastCropRect = cropRect
        croppedBitmap = crop
        isAnalyzingOcr = true
        translationState = TranslationState.Idle

        scope.launch {
            val result = OcrAndLensEngine.extractRegionOcr(
                context = context,
                cropBitmap = crop,
                cropRectInScreen = cropRect,
                fullScreenLines = screenAnalysis.lines,
                scriptMode = scriptMode,
                useMoE = isMoEApplied
            )
            ocrResult = result
            editableText = result.extractedText
            isAnalyzingOcr = false

            if (result.extractedText.isNotBlank()) {
                runTranslation(result.extractedText, result.detectedLanguageCode, selectedTargetLang)
            }
        }
    }

    fun resetSelection() {
        selection = null
        croppedBitmap = null
        lastCropRect = null
        ocrResult = null
        editableText = ""
        isAnalyzingOcr = false
        isDraggingTextHandle = false
        translationJob?.cancel()
        translationState = TranslationState.Idle
        focusedInlineBlock = null
        pendingRefinedAnalysis?.let {
            screenAnalysis = it
            pendingRefinedAnalysis = null
            hasEnrichedInline = false
        }
    }

    fun triggerMoEScan() {
        if (isRunningMoE) return
        moeJob?.cancel()
        isRunningMoE = true
        val effectiveMode = if (scriptMode == OcrScriptMode.LATIN) OcrScriptMode.AUTO_MOE else scriptMode
        moeJob = scope.launch {
            try {
                OcrAndLensEngine.analyzeFullScreenProgressive(context, screenshotBitmap, effectiveMode) { next, isFinal ->
                    val lockWordIndices = selection is SelectionGeometry.LensWords || isDraggingTextHandle
                    if (!lockWordIndices) {
                        screenAnalysis = next
                        hasEnrichedInline = false
                    } else {
                        pendingRefinedAnalysis = next
                    }
                    if (isInlineTranslateMode && next.blocks.isNotEmpty()) {
                        runInlineScreenTranslation(selectedTargetLang)
                    }
                    if (isFinal) {
                        isMoEApplied = true
                        isRunningMoE = false
                        if (isInlineTranslateMode && next.blocks.isEmpty()) {
                            inlineTranslationState = InlineScreenTranslationState(targetLangCode = selectedTargetLang)
                        }
                        val activeCrop = croppedBitmap
                        val activeRect = lastCropRect
                        if (activeCrop != null && activeRect != null &&
                            selection != null && selection !is SelectionGeometry.LensWords
                        ) {
                            isAnalyzingOcr = true
                            val reRead = OcrAndLensEngine.extractRegionOcr(
                                context = context,
                                cropBitmap = activeCrop,
                                cropRectInScreen = activeRect,
                                fullScreenLines = next.lines,
                                scriptMode = effectiveMode,
                                useMoE = true
                            )
                            ocrResult = reRead
                            editableText = reRead.extractedText
                            isAnalyzingOcr = false
                            if (reRead.extractedText.isNotBlank()) {
                                runTranslation(reRead.extractedText, reRead.detectedLanguageCode, selectedTargetLang)
                            }
                        }
                    }
                }
            } finally {
                isRunningMoE = false
            }
        }
    }

    fun activateInlineTranslateMode() {
        isInlineTranslateMode = true
        showOriginalScreen = false
        focusedInlineBlock = null
        resetSelection()
        if (isMoEApplied) {
            runInlineScreenTranslation(selectedTargetLang)
        } else if (!isRunningMoE) {
            triggerMoEScan()
        }
    }

    fun toggleInlineTranslateMode() {
        if (isInlineTranslateMode) {
            isInlineTranslateMode = false
            showOriginalScreen = false
            focusedInlineBlock = null
            inlineTranslationJob?.cancel()
        } else {
            activateInlineTranslateMode()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val canvasWidthPx = constraints.maxWidth.toFloat()
        val canvasHeightPx = constraints.maxHeight.toFloat()
        val strokeWidthPx = with(density) { 3.dp.toPx() }
        val boxCornerPx = with(density) { 10.dp.toPx() }
        val minBoxHalfHeightPx = with(density) { 22.dp.toPx() }
        val tapSlopPx = with(density) { 12.dp.toPx() }

        // 1. Frozen original screenshot at 100% crispness
        Image(
            bitmap = screenImageBitmap,
            contentDescription = "Frozen screen capture for selection",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Lens-style word selection highlights OR pure white circle cutout for objects
        if (!isInlineTranslateMode) {
            val latestWords by rememberUpdatedState(fullScreenWords)
            val latestCanvasW by rememberUpdatedState(canvasWidthPx)
            val latestCanvasH by rememberUpdatedState(canvasHeightPx)

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .pointerInput(screenshotBitmap) {
                        detectTapGestures { tapOffset ->
                            val w = latestCanvasW
                            val h = latestCanvasH
                            if (w <= 0f || h <= 0f) return@detectTapGestures
                            val wordsSnapshot = latestWords
                            val bitmapX = tapOffset.x * screenshotBitmap.width / w
                            val bitmapY = tapOffset.y * screenshotBitmap.height / h
                            val hitPad = with(density) { 12.dp.toPx() } *
                                (screenshotBitmap.width / w)

                            val tappedWord = hitTestWord(wordsSnapshot, bitmapX, bitmapY, hitPad)
                            if (tappedWord != null) {
                                if (hapticEnabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                applyLensWordSelection(
                                    TextSelectionRange(tappedWord.globalIndex, tappedWord.globalIndex),
                                    runOcrTranslationNow = true
                                )
                            } else {
                                // Tap an object/image region to create an instant circle around tap point
                                val radius = with(density) { 54.dp.toPx() }
                                val l = (tapOffset.x - radius).coerceAtLeast(0f)
                                val t = (tapOffset.y - radius).coerceAtLeast(0f)
                                val r = (tapOffset.x + radius).coerceAtMost(w)
                                val b = (tapOffset.y + radius).coerceAtMost(h)
                                selection = SelectionGeometry.BoxRect(l, t, r, b)
                                completeRegionSelection(w, h, l, t, r, b)
                            }
                        }
                    }
                    .pointerInput(screenshotBitmap) {
                        var currentPoints = mutableListOf<Offset>()
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                isDrawing = true
                                croppedBitmap = null
                                lastCropRect = null
                                ocrResult = null
                                translationJob?.cancel()
                                translationState = TranslationState.Idle
                                currentPoints = mutableListOf(startOffset)
                                selection = SelectionGeometry.Freehand(currentPoints.toList(), isClosed = false)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val pt = change.position
                                val last = currentPoints.lastOrNull()
                                if (last == null || (pt - last).getDistance() >= 4f) {
                                    currentPoints.add(pt)
                                    selection = SelectionGeometry.Freehand(currentPoints.toList(), isClosed = false)
                                }
                            },
                            onDragEnd = {
                                isDrawing = false
                                val w = latestCanvasW
                                val h = latestCanvasH
                                val wordsSnapshot = latestWords
                                if (currentPoints.isEmpty() || w <= 0f || h <= 0f) {
                                    resetSelection()
                                    return@detectDragGestures
                                }

                                val sx = w / screenshotBitmap.width
                                val sy = h / screenshotBitmap.height
                                val matchedWords = if (currentPoints.size >= 2) {
                                    matchTextBrushToWordRange(
                                        screenPoints = currentPoints,
                                        words = wordsSnapshot,
                                        sx = sx,
                                        sy = sy,
                                        density = density
                                    )
                                } else null

                                if (matchedWords != null) {
                                    if (hapticEnabled) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    applyLensWordSelection(matchedWords, runOcrTranslationNow = true)
                                    return@detectDragGestures
                                }

                                var minX = Float.MAX_VALUE
                                var minY = Float.MAX_VALUE
                                var maxX = Float.MIN_VALUE
                                var maxY = Float.MIN_VALUE
                                for (p in currentPoints) {
                                    minX = min(minX, p.x)
                                    minY = min(minY, p.y)
                                    maxX = max(maxX, p.x)
                                    maxY = max(maxY, p.y)
                                }

                                val width = maxX - minX
                                val height = maxY - minY
                                if (currentPoints.size < 3 || (width < tapSlopPx && height < tapSlopPx)) {
                                    val firstPt = currentPoints.first()
                                    val hitPad = with(density) { 12.dp.toPx() } / sx.coerceAtLeast(0.001f)
                                    val hitWord = hitTestWord(wordsSnapshot, firstPt.x / sx, firstPt.y / sy, hitPad)
                                    if (hitWord != null) {
                                        applyLensWordSelection(
                                            TextSelectionRange(hitWord.globalIndex, hitWord.globalIndex),
                                            runOcrTranslationNow = true
                                        )
                                    } else {
                                        resetSelection()
                                    }
                                    return@detectDragGestures
                                }

                                if (height < minBoxHalfHeightPx && width > minBoxHalfHeightPx * 1.4f) {
                                    val cy = (minY + maxY) / 2f
                                    val l = minX.coerceAtLeast(0f)
                                    val t = (cy - minBoxHalfHeightPx).coerceAtLeast(0f)
                                    val r = maxX.coerceAtMost(w)
                                    val b = (cy + minBoxHalfHeightPx).coerceAtMost(h)
                                    selection = SelectionGeometry.BoxRect(l, t, r, b)
                                    completeRegionSelection(w, h, l, t, r, b)
                                } else {
                                    selection = SelectionGeometry.Freehand(currentPoints.toList(), isClosed = true)
                                    completeRegionSelection(
                                        w,
                                        h,
                                        minX.coerceAtLeast(0f),
                                        minY.coerceAtLeast(0f),
                                        maxX.coerceAtMost(w),
                                        maxY.coerceAtMost(h)
                                    )
                                }
                            },
                            onDragCancel = {
                                isDrawing = false
                                resetSelection()
                            }
                        )
                    }
            ) {
                val activeSel = selection
                // Keep frozen screen crisp & unobstructed before touching; deepen backdrop on active cutouts
                val tintAlpha = when (activeSel) {
                    is SelectionGeometry.LensWords -> 0.44f
                    null -> 0.22f
                    else -> 0.68f
                }
                drawRect(color = Color.Black.copy(alpha = tintAlpha))

                // Whisper-subtle Lens baseline underlines on indexed words so selectable text is obvious without clutter
                if ((activeSel == null || activeSel is SelectionGeometry.LensWords) && fullScreenWords.isNotEmpty()) {
                    val sx = size.width / screenshotBitmap.width
                    val sy = size.height / screenshotBitmap.height
                    val underlineStroke = 1.2.dp.toPx()
                    for (line in fullScreenLines) {
                        val b = line.boundingBox
                        val y = b.bottom * sy + 1.5f
                        drawLine(
                            color = Color.White.copy(alpha = 0.28f),
                            start = Offset(b.left * sx, y),
                            end = Offset(b.right * sx, y),
                            strokeWidth = underlineStroke,
                            cap = StrokeCap.Round
                        )
                    }
                }

                when (activeSel) {
                    is SelectionGeometry.LensWords -> {
                        val sx = size.width / screenshotBitmap.width
                        val sy = size.height / screenshotBitmap.height
                        val pillCorner = 5.dp.toPx()
                        val padX = 3.dp.toPx()
                        val padY = 2.dp.toPx()
                        val chosenWords = selectedWordsForRange(fullScreenWords, activeSel.range)
                        for (word in chosenWords) {
                            val b = word.boundingBox
                            val l = b.left * sx - padX
                            val t = b.top * sy - padY
                            val w = b.width() * sx + padX * 2f
                            val h = b.height() * sy + padY * 2f
                            val pillPath = Path().apply {
                                addRoundRect(
                                    RoundRect(
                                        left = l,
                                        top = t,
                                        right = l + w,
                                        bottom = t + h,
                                        cornerRadius = CornerRadius(pillCorner, pillCorner)
                                    )
                                )
                            }
                            drawPath(
                                path = pillPath,
                                color = Color.Transparent,
                                blendMode = BlendMode.Clear
                            )
                            drawPath(
                                path = pillPath,
                                color = ElectricCyan.copy(alpha = 0.28f)
                            )
                            drawPath(
                                path = pillPath,
                                color = ElectricCyan.copy(alpha = 0.90f),
                                style = Stroke(width = 1.3.dp.toPx())
                            )
                        }
                    }

                    is SelectionGeometry.Freehand -> {
                        if (activeSel.points.size >= 2) {
                            val openPath = Path().apply {
                                moveTo(activeSel.points.first().x, activeSel.points.first().y)
                                for (i in 1 until activeSel.points.size) {
                                    lineTo(activeSel.points[i].x, activeSel.points[i].y)
                                }
                            }
                            val closedPath = Path().apply {
                                addPath(openPath)
                                close()
                            }
                            drawPath(
                                path = closedPath,
                                color = Color.Transparent,
                                blendMode = BlendMode.Clear
                            )
                            val activePath = if (activeSel.isClosed) closedPath else openPath
                            drawPath(
                                path = activePath,
                                color = ElectricCyan.copy(alpha = 0.45f),
                                style = Stroke(
                                    width = strokeWidthPx * 2.4f,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                            drawPath(
                                path = activePath,
                                color = Color.White,
                                style = Stroke(
                                    width = strokeWidthPx,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }

                    is SelectionGeometry.BoxRect -> {
                        val boxPath = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    left = activeSel.left,
                                    top = activeSel.top,
                                    right = activeSel.right,
                                    bottom = activeSel.bottom,
                                    cornerRadius = CornerRadius(boxCornerPx, boxCornerPx)
                                )
                            )
                        }
                        drawPath(
                            path = boxPath,
                            color = Color.Transparent,
                            blendMode = BlendMode.Clear
                        )
                        drawPath(
                            path = boxPath,
                            color = ElectricCyan.copy(alpha = 0.45f),
                            style = Stroke(width = strokeWidthPx * 2f)
                        )
                        drawPath(
                            path = boxPath,
                            color = Color.White,
                            style = Stroke(width = strokeWidthPx)
                        )
                    }

                    null -> Unit
                }
            }

            // Draggable Lens Teardrop Selection Handles when words are selected
            val lensSel = selection as? SelectionGeometry.LensWords
            if (lensSel != null && fullScreenWords.isNotEmpty()) {
                val chosenWords = selectedWordsForRange(fullScreenWords, lensSel.range)
                if (chosenWords.isNotEmpty()) {
                    val sx = canvasWidthPx / screenshotBitmap.width
                    val sy = canvasHeightPx / screenshotBitmap.height
                    val startWord = chosenWords.first()
                    val endWord = chosenWords.last()

                    LensTeardropHandle(
                        anchorOffset = Offset(
                            startWord.boundingBox.left * sx,
                            startWord.boundingBox.bottom * sy
                        ),
                        isStartHandle = true,
                        canvasWidthPx = canvasWidthPx,
                        canvasHeightPx = canvasHeightPx,
                        onDragDeltaScreen = { screenPt ->
                            val nearest = findNearestWordForHandle(
                                words = fullScreenWords,
                                bitmapX = screenPt.x / sx,
                                bitmapY = screenPt.y / sy
                            )
                            val latestRange = (selection as? SelectionGeometry.LensWords)?.range ?: lensSel.range
                            if (nearest != null) {
                                val endBound = latestRange.maxIndex
                                val nextRange = TextSelectionRange(
                                    startIndex = min(nearest.globalIndex, endBound),
                                    endIndex = endBound
                                )
                                if (nextRange != latestRange) {
                                    if (hapticEnabled) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    applyLensWordSelection(nextRange, runOcrTranslationNow = false)
                                }
                            }
                        },
                        onDragStateChanged = { dragging ->
                            isDraggingTextHandle = dragging
                            if (!dragging) {
                                val latest = (selection as? SelectionGeometry.LensWords)?.range
                                if (latest != null) {
                                    applyLensWordSelection(latest, runOcrTranslationNow = true)
                                }
                            }
                        },
                        testTagName = "lens_start_handle"
                    )

                    LensTeardropHandle(
                        anchorOffset = Offset(
                            endWord.boundingBox.right * sx,
                            endWord.boundingBox.bottom * sy
                        ),
                        isStartHandle = false,
                        canvasWidthPx = canvasWidthPx,
                        canvasHeightPx = canvasHeightPx,
                        onDragDeltaScreen = { screenPt ->
                            val nearest = findNearestWordForHandle(
                                words = fullScreenWords,
                                bitmapX = screenPt.x / sx,
                                bitmapY = screenPt.y / sy
                            )
                            val latestRange = (selection as? SelectionGeometry.LensWords)?.range ?: lensSel.range
                            if (nearest != null) {
                                val startBound = latestRange.minIndex
                                val nextRange = TextSelectionRange(
                                    startIndex = startBound,
                                    endIndex = max(nearest.globalIndex, startBound)
                                )
                                if (nextRange != latestRange) {
                                    if (hapticEnabled) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    applyLensWordSelection(nextRange, runOcrTranslationNow = false)
                                }
                            }
                        },
                        onDragStateChanged = { dragging ->
                            isDraggingTextHandle = dragging
                            if (!dragging) {
                                val latest = (selection as? SelectionGeometry.LensWords)?.range
                                if (latest != null) {
                                    applyLensWordSelection(latest, runOcrTranslationNow = true)
                                }
                            }
                        },
                        testTagName = "lens_end_handle"
                    )
                }
            }
        } else {
            // 2b. Full-screen Lens Inline Translation Layer
            InlineTranslatedBlocksLayer(
                blocks = fullScreenBlocks,
                translationState = inlineTranslationState,
                bitmapWidth = screenshotBitmap.width,
                bitmapHeight = screenshotBitmap.height,
                showOriginal = showOriginalScreen,
                selectedBlockId = focusedInlineBlock?.id,
                onBlockTapped = { block, _ ->
                    focusedInlineBlock = if (focusedInlineBlock?.id == block.id) null else block
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 3. Bottom-right one-handed thumb controls (top of screen stays completely empty)
        val showFloatingBottomRightDock = !isInlineTranslateMode &&
            (croppedBitmap == null || isDrawing || isDraggingTextHandle)

        AnimatedVisibility(
            visible = showFloatingBottomRightDock,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selection != null) {
                    Surface(
                        color = Color(0xD9121216),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { resetSelection() }
                            .testTag("overlay_reset_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Reset selection",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Reset",
                                color = Color.White,
                                fontFamily = SpaceGroteskFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.5.sp
                            )
                        }
                    }
                }

                // On-demand MoE Multi-Script button
                Surface(
                    color = when {
                        isMoEApplied -> Color(0xD916303D)
                        isRunningMoE -> Color(0xD91B2230)
                        else -> Color(0xD9121216)
                    },
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(
                        1.dp,
                        when {
                            isMoEApplied -> ElectricCyan
                            isRunningMoE -> ElectricCyan.copy(alpha = 0.7f)
                            else -> Color(0x44FFFFFF)
                        }
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(enabled = !isRunningMoE) { triggerMoEScan() }
                        .testTag("overlay_moe_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (isRunningMoE) {
                            CircularProgressIndicator(
                                color = ElectricCyan,
                                strokeWidth = 1.8.dp,
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Icon(
                                imageVector = if (isMoEApplied) Icons.Rounded.Check else Icons.Rounded.Language,
                                contentDescription = "Run multi-script MoE OCR",
                                tint = ElectricCyan,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = if (isRunningMoE) "MoE…" else "MoE",
                            color = if (isMoEApplied) ElectricCyan else Color.White,
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp,
                            maxLines = 1
                        )
                    }
                }

                // Translate button (automatically invokes MoE to cover all non-Latin scripts)
                Surface(
                    color = Color(0xD9121216),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, Color(0x44FFFFFF)),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { activateInlineTranslateMode() }
                        .testTag("overlay_inline_translate_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Translate,
                            contentDescription = "Translate screen inline",
                            tint = ElectricCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Translate",
                            color = Color.White,
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp
                        )
                    }
                }

                Surface(
                    color = Color(0xD9121216),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Color(0x33FFFFFF))
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("overlay_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close overlay",
                            tint = Color.White
                        )
                    }
                }
            }
        }

        // 4a. Inline Screen Translation Controls Bar (lower-right thumb controls included)
        AnimatedVisibility(
            visible = isInlineTranslateMode,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            InlineTranslateBottomBar(
                selectedTargetLang = selectedTargetLang,
                packStatuses = packStatuses,
                hasGoogleTranslateApp = hasGoogleTranslateApp,
                inlineTranslationState = inlineTranslationState,
                isRunningMoE = isRunningMoE,
                showOriginal = showOriginalScreen,
                focusedBlock = focusedInlineBlock,
                fallbackScreenText = fullScreenBlocks.joinToString("\n") { it.text },
                dominantLangHint = screenAnalysis.dominantLanguageCode,
                copiedLabel = copiedLabel,
                onToggleShowOriginal = { showOriginalScreen = !showOriginalScreen },
                onExitInlineMode = { toggleInlineTranslateMode() },
                onDismissOverlay = onDismiss,
                onSelectTargetLanguage = { code ->
                    selectedTargetLang = code
                    onTargetLanguageChanged(code)
                    runInlineScreenTranslation(code)
                },
                onRetryInline = {
                    if (!isMoEApplied && !isRunningMoE) {
                        triggerMoEScan()
                    } else {
                        runInlineScreenTranslation(selectedTargetLang)
                    }
                },
                onCopy = { label, value -> copyToClipboard(label, value) },
                onWebSearch = { query ->
                    if (OcrAndLensEngine.launchWebSearch(context, query)) {
                        onExternalActionLaunched()
                    }
                },
                onOpenGoogleTranslate = { textToOpen, srcHint ->
                    if (
                        OcrAndLensEngine.launchGoogleTranslateFallback(
                            context = context,
                            text = textToOpen,
                            targetLangCode = selectedTargetLang,
                            sourceLangCodeHint = srcHint
                        )
                    ) {
                        onExternalActionLaunched()
                    }
                }
            )
        }

        // 4b. Compact High-Efficiency Results Sheet (OCR, Translation, Lens & Web Search)
        AnimatedVisibility(
            visible = croppedBitmap != null && !isDrawing && !isDraggingTextHandle && !isInlineTranslateMode,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            croppedBitmap?.let { crop ->
                CompactLookupSheet(
                    croppedBitmap = crop,
                    ocrResult = ocrResult,
                    editableText = editableText,
                    onEditableTextChange = { updated ->
                        editableText = updated
                    },
                    isAnalyzingOcr = isAnalyzingOcr,
                    isRunningMoE = isRunningMoE,
                    isMoEApplied = isMoEApplied,
                    canSelectAllWords = fullScreenWords.isNotEmpty() &&
                        (selection as? SelectionGeometry.LensWords)?.range !=
                        TextSelectionRange(0, fullScreenWords.lastIndex),
                    onSelectAllWords = {
                        if (fullScreenWords.isNotEmpty()) {
                            applyLensWordSelection(
                                TextSelectionRange(0, fullScreenWords.lastIndex),
                                runOcrTranslationNow = true
                            )
                        }
                    },
                    onResetSelection = { resetSelection() },
                    onTriggerMoE = { triggerMoEScan() },
                    onDismissOverlay = onDismiss,
                    selectedTargetLang = selectedTargetLang,
                    packStatuses = packStatuses,
                    hasGoogleTranslateApp = hasGoogleTranslateApp,
                    translationState = translationState,
                    copiedLabel = copiedLabel,
                    onCopy = { label, text -> copyToClipboard(label, text) },
                    onSelectTargetLanguage = { langCode ->
                        selectedTargetLang = langCode
                        onTargetLanguageChanged(langCode)
                        if (editableText.isNotBlank()) {
                            runTranslation(
                                editableText,
                                ocrResult?.detectedLanguageCode,
                                langCode
                            )
                        }
                    },
                    onRetranslate = {
                        runTranslation(
                            editableText,
                            ocrResult?.detectedLanguageCode,
                            selectedTargetLang
                        )
                    },
                    onSwitchToInlineTranslate = { activateInlineTranslateMode() },
                    onLensLookup = {
                        if (OcrAndLensEngine.launchGoogleLensLookup(context, crop)) {
                            onExternalActionLaunched()
                        }
                    },
                    onWebSearch = { query ->
                        if (OcrAndLensEngine.launchWebSearch(context, query)) {
                            onExternalActionLaunched()
                        }
                    },
                    onWebTranslate = { text ->
                        if (
                            OcrAndLensEngine.launchGoogleTranslateFallback(
                                context = context,
                                text = text,
                                targetLangCode = selectedTargetLang,
                                sourceLangCodeHint = ocrResult?.detectedLanguageCode
                            )
                        ) {
                            onExternalActionLaunched()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun CompactLookupSheet(
    croppedBitmap: Bitmap,
    ocrResult: OcrRegionResult?,
    editableText: String,
    onEditableTextChange: (String) -> Unit,
    isAnalyzingOcr: Boolean,
    isRunningMoE: Boolean,
    isMoEApplied: Boolean,
    canSelectAllWords: Boolean,
    onSelectAllWords: () -> Unit,
    onResetSelection: () -> Unit,
    onTriggerMoE: () -> Unit,
    onDismissOverlay: () -> Unit,
    selectedTargetLang: String,
    packStatuses: Map<String, ModelPackStatus>,
    hasGoogleTranslateApp: Boolean,
    translationState: TranslationState,
    copiedLabel: String?,
    onCopy: (label: String, text: String) -> Unit,
    onSelectTargetLanguage: (String) -> Unit,
    onRetranslate: () -> Unit,
    onSwitchToInlineTranslate: () -> Unit,
    onLensLookup: () -> Unit,
    onWebSearch: (String) -> Unit,
    onWebTranslate: (String) -> Unit
) {
    val cropPreview = remember(croppedBitmap) { croppedBitmap.asImageBitmap() }
    val scrollState = rememberScrollState()

    Surface(
        color = Color(0xFF101014),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        border = BorderStroke(1.dp, Color(0xFF282832)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .navigationBarsPadding()
            .testTag("compact_lookup_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Drag/sheet indicator
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3A3A46))
            )

            // Bottom-right reachable utility controls (Reset / MoE / Translate / Close)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color(0xFF1E2029),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { onResetSelection() }
                            .testTag("overlay_reset_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Reset selection",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Reset",
                                color = Color.White,
                                fontFamily = SpaceGroteskFamily,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Surface(
                        color = if (isMoEApplied) Color(0xFF16303D) else Color(0xFF1E2029),
                        shape = RoundedCornerShape(999.dp),
                        border = if (isMoEApplied) BorderStroke(1.dp, ElectricCyan) else null,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(enabled = !isRunningMoE) { onTriggerMoE() }
                            .testTag("overlay_moe_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isRunningMoE) {
                                CircularProgressIndicator(
                                    color = ElectricCyan,
                                    strokeWidth = 1.8.dp,
                                    modifier = Modifier.size(13.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = if (isMoEApplied) Icons.Rounded.Check else Icons.Rounded.Language,
                                    contentDescription = "Run multi-script MoE OCR",
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Text(
                                text = if (isRunningMoE) "MoE…" else "MoE",
                                color = if (isMoEApplied) ElectricCyan else Color.White,
                                fontFamily = SpaceGroteskFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Surface(
                        color = Color(0xFF1E2029),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { onSwitchToInlineTranslate() }
                            .testTag("overlay_inline_translate_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Translate,
                                contentDescription = "Translate screen inline",
                                tint = ElectricCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Translate",
                                color = Color.White,
                                fontFamily = SpaceGroteskFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismissOverlay,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E2029))
                            .testTag("overlay_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close overlay",
                            tint = Color.White
                        )
                    }
                }
            }

            // Primary Action Row: Crop preview + Google Lens + Web Search
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Image(
                    bitmap = cropPreview,
                    contentDescription = "Cropped selection preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                )

                Button(
                    onClick = onLensLookup,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF0B0B0E)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("lens_lookup_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ImageSearch,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Google Lens",
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = { onWebSearch(editableText) },
                    enabled = editableText.isNotBlank(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White,
                        disabledContentColor = Color(0xFF5A5A66)
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (editableText.isNotBlank()) ElectricCyan else Color(0xFF2B2B36)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("web_search_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = if (editableText.isNotBlank()) ElectricCyan else Color(0xFF5A5A66),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Web Search",
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // OCR Extraction Tonal Block (clean, borderless surface separation)
            Surface(
                color = Color(0xFF191B23),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = ocrResult?.detectedLanguageName ?: "Selection Text",
                                color = Color(0xFFD1D5DB),
                                fontFamily = SpaceGroteskFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.5.sp
                            )
                            ocrResult?.let { res ->
                                if (res.lineCount > 0) {
                                    Text(
                                        text = "${res.lineCount}L",
                                        color = ElectricCyan,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 10.5.sp
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (canSelectAllWords) {
                                Surface(
                                    color = Color(0xFF252834),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .clickable { onSelectAllWords() }
                                        .testTag("select_all_words_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.SelectAll,
                                            contentDescription = "Select all text on screen",
                                            tint = ElectricCyan,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "All",
                                            color = Color.White,
                                            fontFamily = SpaceGroteskFamily,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }

                            if (editableText.isNotBlank()) {
                                Surface(
                                    color = if (copiedLabel == "ocr") Color(0xFF14532D) else Color(0xFF252834),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .clickable { onCopy("ocr", editableText) }
                                        .testTag("copy_ocr_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (copiedLabel == "ocr") Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                            contentDescription = "Copy extracted text",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = if (copiedLabel == "ocr") "Copied" else "Copy",
                                            color = Color.White,
                                            fontFamily = SpaceGroteskFamily,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (isAnalyzingOcr) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Reading text on device…",
                                color = Color(0xFFD1D5DB),
                                fontFamily = InterFamily,
                                fontSize = 13.sp
                            )
                        }
                    } else if (editableText.isBlank()) {
                        Text(
                            text = "No text detected in selection. Tap Google Lens above for visual lookup.",
                            color = Color(0xFF9CA3AF),
                            fontFamily = InterFamily,
                            fontSize = 13.sp
                        )
                    } else {
                        BasicTextField(
                            value = editableText,
                            onValueChange = onEditableTextChange,
                            textStyle = TextStyle(
                                color = Color.White,
                                fontFamily = InterFamily,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(ElectricCyan),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ocr_editable_field")
                        )
                    }
                }
            }

            // On-Device Instant Translation Strip (borderless tonal container)
            if (editableText.isNotBlank()) {
                Surface(
                    color = Color(0xFF191B23),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val translateLabel = if (hasGoogleTranslateApp) "Google Translate" else "Translate Web"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Translate,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "ML KIT TRANSLATE",
                                    color = Color(0xFF9CA3AF),
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 11.sp
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(
                                    color = Color(0xFF252530),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .clickable { onRetranslate() }
                                        .testTag("retranslate_button")
                                ) {
                                    Text(
                                        text = "Refresh",
                                        color = Color.White,
                                        fontFamily = SpaceGroteskFamily,
                                        fontSize = 11.5.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                Surface(
                                    color = Color(0xFF252530),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .clickable { onWebTranslate(editableText) }
                                        .testTag("web_translate_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Translate Web",
                                            color = Color.White,
                                            fontFamily = SpaceGroteskFamily,
                                            fontSize = 11.5.sp
                                        )
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                            contentDescription = "Open $translateLabel",
                                            tint = ElectricCyan,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Compact Target Language Chips with Offline Pack Indicators
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SupportedTargetLanguages.forEach { lang ->
                                val selected = lang.code == selectedTargetLang
                                val st = packStatuses[lang.code] ?: ModelPackStatus.NOT_DOWNLOADED
                                Surface(
                                    color = if (selected) Color.White else Color(0xFF22222B),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .clickable { onSelectTargetLanguage(lang.code) }
                                        .testTag("lang_chip_${lang.code}")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        when (st) {
                                            ModelPackStatus.DOWNLOADING -> {
                                                CircularProgressIndicator(
                                                    color = if (selected) Color(0xFF0B0B0E) else ElectricCyan,
                                                    strokeWidth = 1.6.dp,
                                                    modifier = Modifier.size(9.dp)
                                                )
                                            }
                                            ModelPackStatus.DOWNLOADED -> {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(EmeraldActive)
                                                )
                                            }
                                            ModelPackStatus.ERROR -> {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(CoralError)
                                                )
                                            }
                                            ModelPackStatus.NOT_DOWNLOADED -> Unit
                                        }
                                        Text(
                                            text = lang.shortCode,
                                            color = if (selected) Color(0xFF0B0B0E) else Color(0xFFD1D5DB),
                                            fontFamily = JetBrainsMonoFamily,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 11.5.sp
                                        )
                                    }
                                }
                            }
                        }

                        when (val tState = translationState) {
                            is TranslationState.DownloadingModel -> {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "Downloading ${tState.engineLabel} pack: ${tState.missingPacksLabel} (~30 MB each)…",
                                        color = Color(0xFFD1D5DB),
                                        fontFamily = InterFamily,
                                        fontSize = 12.5.sp
                                    )
                                    LinearProgressIndicator(
                                        color = ElectricCyan,
                                        trackColor = Color(0xFF2B2B36),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedButton(
                                        onClick = { onWebTranslate(editableText) },
                                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.7f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag("fallback_google_translate_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                            contentDescription = null,
                                            tint = ElectricCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "Use $translateLabel Now",
                                            fontFamily = SpaceGroteskFamily,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 12.5.sp
                                        )
                                    }
                                }
                            }

                            TranslationState.Translating -> {
                                Text(
                                    text = "Translating locally…",
                                    color = Color(0xFFD1D5DB),
                                    fontFamily = InterFamily,
                                    fontSize = 13.sp
                                )
                            }

                            is TranslationState.SameLanguage -> {
                                Text(
                                    text = "Text is already in ${tState.languageName}. Pick another target chip above to translate.",
                                    color = Color(0xFF9CA3AF),
                                    fontFamily = InterFamily,
                                    fontSize = 12.5.sp
                                )
                            }

                            is TranslationState.Success -> {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = tState.translatedText,
                                        color = Color.White,
                                        fontFamily = InterFamily,
                                        fontSize = 14.5.sp,
                                        lineHeight = 20.sp
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Surface(
                                            color = if (copiedLabel == "tr") Color(0xFF14532D) else Color(0xFF252530),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .clickable { onCopy("tr", tState.translatedText) }
                                                .testTag("copy_translation_button")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (copiedLabel == "tr") Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                                    contentDescription = "Copy translation",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Text(
                                                    text = if (copiedLabel == "tr") "Copied" else "Copy Translation",
                                                    color = Color.White,
                                                    fontFamily = SpaceGroteskFamily,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }

                                        Surface(
                                            color = Color(0xFF252530),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .clickable { onWebSearch(tState.translatedText) }
                                                .testTag("search_translation_button")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Search,
                                                    contentDescription = "Search translated text",
                                                    tint = ElectricCyan,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Text(
                                                    text = "Search",
                                                    color = Color.White,
                                                    fontFamily = SpaceGroteskFamily,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }

                                        Surface(
                                            color = Color(0xFF252530),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .clickable { onSwitchToInlineTranslate() }
                                                .testTag("apply_inline_translate_button")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Translate,
                                                    contentDescription = "Replace inline on screen",
                                                    tint = ElectricCyan,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Text(
                                                    text = "Inline",
                                                    color = Color.White,
                                                    fontFamily = SpaceGroteskFamily,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            is TranslationState.Error -> {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = tState.message,
                                        color = Color(0xFFFCA5A5),
                                        fontFamily = InterFamily,
                                        fontSize = 12.5.sp
                                    )
                                    if (tState.canFallbackToGoogleTranslate) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = onRetranslate,
                                                border = BorderStroke(1.dp, Color(0xFF3A3A46)),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                                shape = RoundedCornerShape(10.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .heightIn(min = 48.dp)
                                                    .testTag("retry_offline_translate_button")
                                            ) {
                                                Text(
                                                    text = "Retry Offline",
                                                    fontFamily = SpaceGroteskFamily,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            Button(
                                                onClick = { onWebTranslate(editableText) },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = ElectricCyan,
                                                    contentColor = Color.Black
                                                ),
                                                shape = RoundedCornerShape(10.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                                modifier = Modifier
                                                    .weight(1.3f)
                                                    .heightIn(min = 48.dp)
                                                    .testTag("error_fallback_translate_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Spacer(Modifier.width(5.dp))
                                                Text(
                                                    text = translateLabel,
                                                    fontFamily = SpaceGroteskFamily,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 12.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            TranslationState.Idle -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineTranslateBottomBar(
    selectedTargetLang: String,
    packStatuses: Map<String, ModelPackStatus>,
    hasGoogleTranslateApp: Boolean,
    inlineTranslationState: InlineScreenTranslationState,
    isRunningMoE: Boolean,
    showOriginal: Boolean,
    focusedBlock: InlineTextBlock?,
    fallbackScreenText: String,
    dominantLangHint: String?,
    copiedLabel: String?,
    onToggleShowOriginal: () -> Unit,
    onExitInlineMode: () -> Unit,
    onDismissOverlay: () -> Unit,
    onSelectTargetLanguage: (String) -> Unit,
    onRetryInline: () -> Unit,
    onCopy: (label: String, value: String) -> Unit,
    onWebSearch: (String) -> Unit,
    onOpenGoogleTranslate: (text: String, sourceHint: String?) -> Unit
) {
    val translateLabel = if (hasGoogleTranslateApp) "Google Translate" else "Translate Web"
    val translatedCount = inlineTranslationState.blockStates.values.count {
        it is InlineBlockStatus.Translated
    }

    Surface(
        color = Color(0xF2101014),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        border = BorderStroke(1.dp, Color(0xFF282832)),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .testTag("inline_translate_bar")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (isRunningMoE || inlineTranslationState.isTranslating) {
                        CircularProgressIndicator(
                            color = ElectricCyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Translate,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Text(
                        text = when {
                            isRunningMoE ->
                                "Scanning scripts…"
                            inlineTranslationState.isDownloadingPacks ->
                                "Downloading ${inlineTranslationState.missingPacksLabel}…"
                            inlineTranslationState.isTranslating ->
                                "Translating ($translatedCount)…"
                            inlineTranslationState.errorMessage != null ->
                                "Offline pack unavailable"
                            translatedCount > 0 ->
                                "$translatedCount translated"
                            else ->
                                "Same language"
                        },
                        color = Color.White,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = if (showOriginal) ElectricCyan else Color(0xFF252530),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { onToggleShowOriginal() }
                            .testTag("toggle_original_text_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (showOriginal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = "Toggle original screen text",
                                tint = if (showOriginal) Color.Black else Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = if (showOriginal) "Translated" else "Original",
                                color = if (showOriginal) Color.Black else Color.White,
                                fontFamily = SpaceGroteskFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Surface(
                        color = ElectricCyan,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { onExitInlineMode() }
                            .testTag("overlay_inline_translate_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Translate,
                                contentDescription = "Return to selection mode",
                                tint = Color.Black,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Select",
                                color = Color.Black,
                                fontFamily = SpaceGroteskFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismissOverlay,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF252530))
                            .testTag("overlay_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close overlay",
                            tint = Color.White
                        )
                    }
                }
            }

            if (inlineTranslationState.isDownloadingPacks) {
                LinearProgressIndicator(
                    color = ElectricCyan,
                    trackColor = Color(0xFF2B2B36),
                    modifier = Modifier.fillMaxWidth()
                )
                val targetText = focusedBlock?.text?.ifBlank { null } ?: fallbackScreenText
                if (targetText.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            onOpenGoogleTranslate(
                                targetText,
                                focusedBlock?.detectedLanguageCode ?: dominantLangHint
                            )
                        },
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("inline_downloading_fallback_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Use $translateLabel Now",
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.5.sp
                        )
                    }
                }
            }

            inlineTranslationState.errorMessage?.let { err ->
                Text(
                    text = err,
                    color = Color(0xFFFCA5A5),
                    fontFamily = InterFamily,
                    fontSize = 12.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRetryInline,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Retry Offline", fontSize = 12.sp, color = Color.White)
                    }
                    Button(
                        onClick = {
                            val targetText = focusedBlock?.text?.ifBlank { null } ?: fallbackScreenText
                            if (targetText.isNotBlank()) {
                                onOpenGoogleTranslate(
                                    targetText,
                                    focusedBlock?.detectedLanguageCode ?: dominantLangHint
                                )
                            }
                        },
                        enabled = (focusedBlock?.text?.isNotBlank() == true) || fallbackScreenText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1.3f)
                            .heightIn(min = 48.dp)
                            .testTag("inline_error_fallback_button")
                    ) {
                        Text(translateLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Focused block inspector when user taps an inline-translated block
            if (focusedBlock != null) {
                val st = inlineTranslationState.blockStates[focusedBlock.id]
                val translatedStr = (st as? InlineBlockStatus.Translated)?.text
                Surface(
                    color = Color(0xFF17171D),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF2B2B36)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (translatedStr != null) {
                            Text(
                                text = translatedStr,
                                color = Color.White,
                                fontFamily = InterFamily,
                                fontSize = 13.5.sp
                            )
                        }
                        Text(
                            text = focusedBlock.text,
                            color = Color(0xFF9CA3AF),
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.5.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val copyValue = translatedStr ?: focusedBlock.text
                            Surface(
                                color = if (copiedLabel == "inline") Color(0xFF14532D) else Color(0xFF252530),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable { onCopy("inline", copyValue) }
                            ) {
                                Text(
                                    text = if (copiedLabel == "inline") "Copied" else "Copy",
                                    color = Color.White,
                                    fontFamily = SpaceGroteskFamily,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                            Surface(
                                color = Color(0xFF252530),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable { onWebSearch(copyValue) }
                            ) {
                                Text(
                                    text = "Search",
                                    color = ElectricCyan,
                                    fontFamily = SpaceGroteskFamily,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                            Surface(
                                color = Color(0xFF252530),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable {
                                    onOpenGoogleTranslate(focusedBlock.text, focusedBlock.detectedLanguageCode)
                                }
                            ) {
                                Text(
                                    text = translateLabel,
                                    color = Color.White,
                                    fontFamily = SpaceGroteskFamily,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Target Language Chips for instant inline switching
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SupportedTargetLanguages.forEach { lang ->
                    val selected = lang.code == selectedTargetLang
                    val st = packStatuses[lang.code] ?: ModelPackStatus.NOT_DOWNLOADED
                    Surface(
                        color = if (selected) Color.White else Color(0xFF22222B),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .clickable { onSelectTargetLanguage(lang.code) }
                            .testTag("inline_lang_chip_${lang.code}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                        ) {
                            when (st) {
                                ModelPackStatus.DOWNLOADING -> {
                                    CircularProgressIndicator(
                                        color = if (selected) Color(0xFF0B0B0E) else ElectricCyan,
                                        strokeWidth = 1.6.dp,
                                        modifier = Modifier.size(9.dp)
                                    )
                                }
                                ModelPackStatus.DOWNLOADED -> {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(EmeraldActive)
                                    )
                                }
                                ModelPackStatus.ERROR -> {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(CoralError)
                                    )
                                }
                                ModelPackStatus.NOT_DOWNLOADED -> Unit
                            }
                            Text(
                                text = lang.shortCode,
                                color = if (selected) Color(0xFF0B0B0E) else Color(0xFFD1D5DB),
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
