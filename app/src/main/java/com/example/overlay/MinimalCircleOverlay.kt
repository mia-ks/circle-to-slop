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
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.example.engine.MoEOcrRouter
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
    val fullScreenLines = screenAnalysis.lines
    val fullScreenWords = screenAnalysis.words
    val fullScreenBlocks = screenAnalysis.blocks

    var selection by remember { mutableStateOf<SelectionGeometry?>(null) }
    var isDrawing by remember { mutableStateOf(false) }
    var isDraggingTextHandle by remember { mutableStateOf(false) }

    var croppedBitmap by remember { mutableStateOf<Bitmap?>(null) }
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

    // Pre-scan full screen text (words + visual blocks + color sampling) & refresh pack cache
    LaunchedEffect(screenshotBitmap, scriptMode) {
        OcrAndLensEngine.refreshDownloadedPacksAsync()
        MoEOcrRouter.refreshAllOcrPacksAsync(context)
        screenAnalysis = OcrAndLensEngine.analyzeFullScreen(context, screenshotBitmap, scriptMode)
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
            OcrAndLensEngine.translateBlocksInline(
                blocks = screenAnalysis.blocks,
                targetLangCode = targetLang,
                dominantLangHint = screenAnalysis.dominantLanguageCode
            ) { state ->
                inlineTranslationState = state
            }
        }
    }

    // If user enabled Inline Translate before fullScreenAnalysis finished, trigger automatically once ready
    LaunchedEffect(isInlineTranslateMode, screenAnalysis.blocks) {
        if (isInlineTranslateMode && screenAnalysis.blocks.isNotEmpty() &&
            inlineTranslationState.blockStates.isEmpty() && !inlineTranslationState.isTranslating
        ) {
            runInlineScreenTranslation(selectedTargetLang)
        }
    }

    fun applyLensWordSelection(range: TextSelectionRange, runOcrTranslationNow: Boolean) {
        selection = SelectionGeometry.LensWords(range)
        if (!runOcrTranslationNow) return

        val selectedWords = selectedWordsForRange(fullScreenWords, range)
        val text = buildSelectedText(fullScreenWords, range)
        val bounds = computeSelectionBitmapBounds(fullScreenWords, range) ?: return

        val padPx = (8 * density.density).toInt()
        val l = (bounds.left - padPx).coerceIn(0, screenshotBitmap.width - 1)
        val t = (bounds.top - padPx).coerceIn(0, screenshotBitmap.height - 1)
        val r = (bounds.right + padPx).coerceIn(l + 1, screenshotBitmap.width)
        val b = (bounds.bottom + padPx).coerceIn(t + 1, screenshotBitmap.height)

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
        val blockLangHint = fullScreenBlocks.firstOrNull { it.id == firstBlockId }?.detectedLanguageCode
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

        val bmpLeft = ((leftPx - padPx) * scaleX).toInt().coerceIn(0, screenshotBitmap.width - 1)
        val bmpTop = ((topPx - padPx) * scaleY).toInt().coerceIn(0, screenshotBitmap.height - 1)
        val bmpRight = ((rightPx + padPx) * scaleX).toInt().coerceIn(bmpLeft + 1, screenshotBitmap.width)
        val bmpBottom = ((bottomPx + padPx) * scaleY).toInt().coerceIn(bmpTop + 1, screenshotBitmap.height)

        val cropRect = Rect(bmpLeft, bmpTop, bmpRight, bmpBottom)
        val crop = Bitmap.createBitmap(
            screenshotBitmap,
            cropRect.left,
            cropRect.top,
            cropRect.width().coerceAtLeast(1),
            cropRect.height().coerceAtLeast(1)
        )

        croppedBitmap = crop
        isAnalyzingOcr = true
        translationState = TranslationState.Idle

        scope.launch {
            val result = OcrAndLensEngine.extractRegionOcr(
                context = context,
                cropBitmap = crop,
                cropRectInScreen = cropRect,
                fullScreenLines = fullScreenLines,
                scriptMode = scriptMode
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
        ocrResult = null
        editableText = ""
        isAnalyzingOcr = false
        isDraggingTextHandle = false
        translationJob?.cancel()
        translationState = TranslationState.Idle
        focusedInlineBlock = null
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
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .pointerInput(screenshotBitmap, fullScreenWords) {
                        detectTapGestures { tapOffset ->
                            val bitmapX = tapOffset.x * screenshotBitmap.width / canvasWidthPx
                            val bitmapY = tapOffset.y * screenshotBitmap.height / canvasHeightPx
                            val hitPad = with(density) { 10.dp.toPx() } *
                                (screenshotBitmap.width / canvasWidthPx)

                            val tappedWord = hitTestWord(fullScreenWords, bitmapX, bitmapY, hitPad)
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
                                val r = (tapOffset.x + radius).coerceAtMost(canvasWidthPx)
                                val b = (tapOffset.y + radius).coerceAtMost(canvasHeightPx)
                                selection = SelectionGeometry.BoxRect(l, t, r, b)
                                completeRegionSelection(canvasWidthPx, canvasHeightPx, l, t, r, b)
                            }
                        }
                    }
                    .pointerInput(screenshotBitmap, fullScreenWords) {
                        var currentPoints = mutableListOf<Offset>()
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                isDrawing = true
                                croppedBitmap = null
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
                                if (currentPoints.size < 3) {
                                    return@detectDragGestures
                                }
                                val sx = canvasWidthPx / screenshotBitmap.width
                                val sy = canvasHeightPx / screenshotBitmap.height
                                val matchedWords = matchTextBrushToWordRange(
                                    screenPoints = currentPoints,
                                    words = fullScreenWords,
                                    sx = sx,
                                    sy = sy,
                                    density = density
                                )
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
                                if (width < tapSlopPx && height < tapSlopPx) {
                                    return@detectDragGestures
                                }

                                if (height < minBoxHalfHeightPx && width > minBoxHalfHeightPx * 1.4f) {
                                    val cy = (minY + maxY) / 2f
                                    val l = minX.coerceAtLeast(0f)
                                    val t = (cy - minBoxHalfHeightPx).coerceAtLeast(0f)
                                    val r = maxX.coerceAtMost(canvasWidthPx)
                                    val b = (cy + minBoxHalfHeightPx).coerceAtMost(canvasHeightPx)
                                    selection = SelectionGeometry.BoxRect(l, t, r, b)
                                    completeRegionSelection(canvasWidthPx, canvasHeightPx, l, t, r, b)
                                } else {
                                    selection = SelectionGeometry.Freehand(currentPoints.toList(), isClosed = true)
                                    completeRegionSelection(
                                        canvasWidthPx,
                                        canvasHeightPx,
                                        minX.coerceAtLeast(0f),
                                        minY.coerceAtLeast(0f),
                                        maxX.coerceAtMost(canvasWidthPx),
                                        maxY.coerceAtMost(canvasHeightPx)
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
                // Lighter tint for Lens word selection so surrounding context stays crisp; deeper tint for circle cutout
                val tintAlpha = when (activeSel) {
                    is SelectionGeometry.LensWords -> 0.42f
                    null -> if (fullScreenWords.isNotEmpty()) 0.34f else 0.74f
                    else -> 0.74f
                }
                drawRect(color = Color.Black.copy(alpha = tintAlpha))

                // Subtle Lens-like word pills before selection so selectable text is obvious without clutter
                if (activeSel == null && fullScreenWords.isNotEmpty()) {
                    val sx = size.width / screenshotBitmap.width
                    val sy = size.height / screenshotBitmap.height
                    val wordCorner = 4.dp.toPx()
                    val pad = 1.5.dp.toPx()
                    for (word in fullScreenWords) {
                        val b = word.boundingBox
                        val left = b.left * sx - pad
                        val top = b.top * sy - pad
                        val w = b.width() * sx + pad * 2f
                        val h = b.height() * sy + pad * 2f
                        drawRoundRect(
                            color = Color.Transparent,
                            topLeft = Offset(left, top),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(wordCorner, wordCorner),
                            blendMode = BlendMode.Clear
                        )
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.16f),
                            topLeft = Offset(left, top),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(wordCorner, wordCorner)
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
                                color = ElectricCyan.copy(alpha = 0.85f),
                                style = Stroke(width = 1.2.dp.toPx())
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
                            drawPath(
                                path = if (activeSel.isClosed) closedPath else openPath,
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

        // 3. Minimalist top controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color(0xCC121216),
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .then(
                        if (selection != null && !isInlineTranslateMode) {
                            Modifier.clickable { resetSelection() }
                        } else {
                            Modifier
                        }
                    )
                    .testTag("overlay_status_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (isInlineTranslateMode) {
                        Icon(
                            imageVector = Icons.Rounded.Translate,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = if (showOriginalScreen) "Original screen" else "Inline Translate",
                            color = Color.White,
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.5.sp
                        )
                    } else if (selection != null) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Clear selection",
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
                    } else {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(ElectricCyan)
                        )
                        Text(
                            text = "Tap text, swipe, or circle",
                            color = Color.White,
                            fontFamily = InterFamily,
                            fontSize = 12.5.sp
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val targetShort = SupportedTargetLanguages.firstOrNull {
                    it.code == selectedTargetLang
                }?.shortCode ?: selectedTargetLang.uppercase()

                Surface(
                    color = if (isInlineTranslateMode) ElectricCyan else Color(0xCC121216),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isInlineTranslateMode) ElectricCyan else Color(0x44FFFFFF)
                    ),
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clickable {
                            val next = !isInlineTranslateMode
                            isInlineTranslateMode = next
                            showOriginalScreen = false
                            focusedInlineBlock = null
                            if (next) {
                                resetSelection()
                                runInlineScreenTranslation(selectedTargetLang)
                            } else {
                                inlineTranslationJob?.cancel()
                            }
                        }
                        .testTag("overlay_inline_translate_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Translate,
                            contentDescription = "Toggle inline screen translate",
                            tint = if (isInlineTranslateMode) Color.Black else ElectricCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (isInlineTranslateMode) "Inline · $targetShort" else "Translate",
                            color = if (isInlineTranslateMode) Color.Black else Color.White,
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp
                        )
                    }
                }

                Surface(
                    color = Color(0xCC121216),
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

        // 4a. Inline Screen Translation Controls Bar
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
                showOriginal = showOriginalScreen,
                focusedBlock = focusedInlineBlock,
                fallbackScreenText = fullScreenBlocks.joinToString("\n") { it.text },
                dominantLangHint = screenAnalysis.dominantLanguageCode,
                copiedLabel = copiedLabel,
                onToggleShowOriginal = { showOriginalScreen = !showOriginalScreen },
                onSelectTargetLanguage = { code ->
                    selectedTargetLang = code
                    onTargetLanguageChanged(code)
                    runInlineScreenTranslation(code)
                },
                onRetryInline = { runInlineScreenTranslation(selectedTargetLang) },
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
                    onSwitchToInlineTranslate = {
                        isInlineTranslateMode = true
                        showOriginalScreen = false
                        resetSelection()
                        runInlineScreenTranslation(selectedTargetLang)
                    },
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
    canSelectAllWords: Boolean,
    onSelectAllWords: () -> Unit,
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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

            // OCR Extraction Card
            Surface(
                color = Color(0xFF17171D),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF282833)),
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
                                text = "EXTRACTED TEXT",
                                color = Color(0xFF9CA3AF),
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 11.sp
                            )
                            ocrResult?.detectedLanguageName?.let { lang ->
                                Surface(
                                    color = Color(0xFF23232D),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "$lang · ${ocrResult.lineCount}L",
                                        color = ElectricCyan,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 10.5.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (canSelectAllWords) {
                                Surface(
                                    color = Color(0xFF252530),
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
                                    color = if (copiedLabel == "ocr") Color(0xFF14532D) else Color(0xFF252530),
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
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 13.5.sp,
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

            // On-Device Instant Translation Card (when text exists)
            if (editableText.isNotBlank()) {
                Surface(
                    color = Color(0xFF17171D),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF282833)),
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
                                            text = if (hasGoogleTranslateApp) "Translate App" else "Web",
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
    showOriginal: Boolean,
    focusedBlock: InlineTextBlock?,
    fallbackScreenText: String,
    dominantLangHint: String?,
    copiedLabel: String?,
    onToggleShowOriginal: () -> Unit,
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
                    if (inlineTranslationState.isTranslating) {
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
                            inlineTranslationState.isDownloadingPacks ->
                                "Downloading ${inlineTranslationState.missingPacksLabel}…"
                            inlineTranslationState.isTranslating ->
                                "Replacing text inline ($translatedCount done)…"
                            inlineTranslationState.errorMessage != null ->
                                "Offline pack unavailable"
                            translatedCount > 0 ->
                                "$translatedCount block${if (translatedCount == 1) "" else "s"} replaced inline"
                            else ->
                                "All detected text matches target language"
                        },
                        color = Color.White,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    color = if (showOriginal) ElectricCyan else Color(0xFF252530),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable { onToggleShowOriginal() }
                        .testTag("toggle_original_text_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
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
