package com.example.overlay

import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.InlineBlockStatus
import com.example.engine.InlineScreenTranslationState
import com.example.engine.InlineTextBlock
import com.example.engine.RecognizedTextWord
import com.example.ui.theme.InterFamily
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class TextSelectionRange(
    val startIndex: Int,
    val endIndex: Int
) {
    val minIndex: Int get() = min(startIndex, endIndex)
    val maxIndex: Int get() = max(startIndex, endIndex)
}

fun selectedWordsForRange(
    words: List<RecognizedTextWord>,
    range: TextSelectionRange?
): List<RecognizedTextWord> {
    if (range == null || words.isEmpty()) return emptyList()
    val lo = range.minIndex.coerceIn(0, words.lastIndex)
    val hi = range.maxIndex.coerceIn(lo, words.lastIndex)
    return words.subList(lo, hi + 1)
}

fun buildSelectedText(
    words: List<RecognizedTextWord>,
    range: TextSelectionRange?
): String {
    val chosen = selectedWordsForRange(words, range)
    if (chosen.isEmpty()) return ""
    val sb = StringBuilder()
    var prevLine = chosen.first().lineIndex
    var prevWordText = ""
    for (w in chosen) {
        if (sb.isNotEmpty()) {
            if (w.lineIndex != prevLine) {
                sb.append('\n')
            } else if (!isTightCjkPair(prevWordText.lastOrNull(), w.text.firstOrNull())) {
                sb.append(' ')
            }
        }
        sb.append(w.text)
        prevLine = w.lineIndex
        prevWordText = w.text
    }
    return sb.toString().trim()
}

fun computeSelectionBitmapBounds(
    words: List<RecognizedTextWord>,
    range: TextSelectionRange?
): Rect? {
    val chosen = selectedWordsForRange(words, range)
    if (chosen.isEmpty()) return null
    val union = Rect(chosen.first().boundingBox)
    for (i in 1 until chosen.size) {
        union.union(chosen[i].boundingBox)
    }
    return union
}

fun hitTestWord(
    words: List<RecognizedTextWord>,
    bitmapX: Float,
    bitmapY: Float,
    hitPadPx: Float
): RecognizedTextWord? {
    var bestWord: RecognizedTextWord? = null
    var bestDist = Float.MAX_VALUE
    for (w in words) {
        val r = w.boundingBox
        if (bitmapX >= (r.left - hitPadPx) &&
            bitmapX <= (r.right + hitPadPx) &&
            bitmapY >= (r.top - hitPadPx) &&
            bitmapY <= (r.bottom + hitPadPx)
        ) {
            val d = hypot(bitmapX - r.centerX(), bitmapY - r.centerY())
            if (d < bestDist) {
                bestDist = d
                bestWord = w
            }
        }
    }
    return bestWord
}

fun findNearestWordForHandle(
    words: List<RecognizedTextWord>,
    bitmapX: Float,
    bitmapY: Float
): RecognizedTextWord? {
    if (words.isEmpty()) return null
    var best = words.first()
    var bestScore = Float.MAX_VALUE
    for (w in words) {
        val r = w.boundingBox
        val clampedX = bitmapX.coerceIn(r.left.toFloat(), r.right.toFloat())
        val clampedY = bitmapY.coerceIn(r.top.toFloat(), r.bottom.toFloat())
        val dx = abs(bitmapX - clampedX)
        val dy = abs(bitmapY - clampedY)
        // Prioritize staying on the row under the user's finger
        val score = dx + dy * 2.8f
        if (score < bestScore) {
            bestScore = score
            best = w
        }
    }
    return best
}

fun matchTextBrushToWordRange(
    screenPoints: List<Offset>,
    words: List<RecognizedTextWord>,
    sx: Float,
    sy: Float,
    density: Density
): TextSelectionRange? {
    if (screenPoints.size < 2 || words.isEmpty() || sx <= 0f || sy <= 0f) return null

    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = Float.MIN_VALUE
    var maxY = Float.MIN_VALUE
    for (p in screenPoints) {
        minX = min(minX, p.x)
        minY = min(minY, p.y)
        maxX = max(maxX, p.x)
        maxY = max(maxY, p.y)
    }

    val spanW = maxX - minX
    val spanH = maxY - minY
    val startEndDist = (screenPoints.last() - screenPoints.first()).getDistance()
    val maxSpan = max(spanW, spanH).coerceAtLeast(1f)

    // Densify segments slightly so fast finger swipes never skip short words
    val sampledPoints = buildList {
        val stepPx = with(density) { 8.dp.toPx() }
        for (i in screenPoints.indices) {
            val cur = screenPoints[i]
            if (i > 0) {
                val prev = screenPoints[i - 1]
                val dist = (cur - prev).getDistance()
                val steps = (dist / stepPx).toInt().coerceIn(0, 12)
                for (s in 1 until steps) {
                    val t = s.toFloat() / steps.toFloat()
                    add(Offset(prev.x + (cur.x - prev.x) * t, prev.y + (cur.y - prev.y) * t))
                }
            }
            add(cur)
        }
    }

    val padX = with(density) { 12.dp.toPx() } / sx
    val padY = with(density) { 10.dp.toPx() } / sy

    val hitIndices = mutableListOf<Int>()
    var pointsOnWords = 0
    for (pt in sampledPoints) {
        val bx = pt.x / sx
        val by = pt.y / sy
        var hitThisPoint = false
        for (w in words) {
            val r = w.boundingBox
            if (bx >= (r.left - padX) &&
                bx <= (r.right + padX) &&
                by >= (r.top - padY) &&
                by <= (r.bottom + padY)
            ) {
                hitIndices.add(w.globalIndex)
                hitThisPoint = true
            }
        }
        if (hitThisPoint) pointsOnWords++
    }
    if (hitIndices.isEmpty()) return null

    // Open brush strokes (including multi-line diagonal drags) OR strokes that mostly trace text snap to words;
    // tall closed loops around empty/image regions stay as Freehand object cutouts.
    val isOpenBrushStroke = startEndDist > maxSpan * 0.34f
    val flatSweepHeight = with(density) { 48.dp.toPx() }
    val wordHitRatio = pointsOnWords.toFloat() / sampledPoints.size.coerceAtLeast(1)
    if (!isOpenBrushStroke && spanH > flatSweepHeight && wordHitRatio < 0.45f) {
        return null
    }

    val first = hitIndices.first()
    val last = hitIndices.last()
    return TextSelectionRange(min(first, last), max(first, last))
}

private fun isTightCjkPair(prev: Char?, next: Char?): Boolean {
    if (prev == null || next == null) return false
    return isCjkChar(prev) && isCjkChar(next)
}

private fun isCjkChar(ch: Char): Boolean {
    val c = ch.code
    return c in 0x3040..0x30FF ||
        c in 0x4E00..0x9FFF ||
        c in 0x3400..0x4DBF ||
        c in 0xAC00..0xD7AF
}

@Composable
fun LensTeardropHandle(
    anchorOffset: Offset,
    isStartHandle: Boolean,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    onDragDeltaScreen: (screenPosition: Offset) -> Unit,
    onDragStateChanged: (isDragging: Boolean) -> Unit,
    testTagName: String
) {
    val density = LocalDensity.current
    val touchBoxPx = with(density) { 48.dp.toPx() }
    val halfTouchPx = touchBoxPx / 2f

    // Position the teardrop tip at anchorOffset (bottom-left of start word or bottom-right of end word)
    val boxLeft = (anchorOffset.x - halfTouchPx).coerceIn(0f, (canvasWidthPx - touchBoxPx).coerceAtLeast(0f))
    val boxTop = (anchorOffset.y - with(density) { 6.dp.toPx() })
        .coerceIn(0f, (canvasHeightPx - touchBoxPx).coerceAtLeast(0f))

    val currentOnDragDelta by rememberUpdatedState(onDragDeltaScreen)
    val currentOnDragStateChanged by rememberUpdatedState(onDragStateChanged)
    val tipAlignYOffsetPx = with(density) { 14.dp.toPx() }
    val handleCoordsState = remember { androidx.compose.runtime.mutableStateOf<LayoutCoordinates?>(null) }

    Box(
        modifier = Modifier
            .offset { IntOffset(boxLeft.roundToInt(), boxTop.roundToInt()) }
            .size(48.dp)
            .onGloballyPositioned { coords ->
                handleCoordsState.value = coords
            }
            .pointerInput(isStartHandle) {
                detectDragGestures(
                    onDragStart = { localOffset ->
                        currentOnDragStateChanged(true)
                        val origin = handleCoordsState.value?.let {
                            if (it.isAttached) it.positionInParent() else Offset(boxLeft, boxTop)
                        } ?: Offset(boxLeft, boxTop)
                        currentOnDragDelta(
                            Offset(
                                origin.x + localOffset.x,
                                origin.y + localOffset.y - tipAlignYOffsetPx
                            )
                        )
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val origin = handleCoordsState.value?.let {
                            if (it.isAttached) it.positionInParent() else Offset(boxLeft, boxTop)
                        } ?: Offset(boxLeft, boxTop)
                        currentOnDragDelta(
                            Offset(
                                origin.x + change.position.x,
                                origin.y + change.position.y - tipAlignYOffsetPx
                            )
                        )
                    },
                    onDragEnd = {
                        currentOnDragStateChanged(false)
                    },
                    onDragCancel = {
                        currentOnDragStateChanged(false)
                    }
                )
            }
            .testTag(testTagName),
        contentAlignment = Alignment.TopCenter
    ) {
        val handleAccentColor = MaterialTheme.colorScheme.primary
        Canvas(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(22.dp)
        ) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val r = w * 0.36f
            val cy = h - r - 2f

            // Teardrop pointing to the text baseline corner
            val tipX = if (isStartHandle) cx + r * 0.72f else cx - r * 0.72f
            val tipY = 1.5f

            val path = Path().apply {
                moveTo(tipX, tipY)
                if (isStartHandle) {
                    cubicTo(cx + r * 0.95f, cy * 0.45f, cx + r, cy, cx + r, cy)
                    arcTo(
                        rect = androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = 225f,
                        forceMoveTo = false
                    )
                } else {
                    cubicTo(cx - r * 0.95f, cy * 0.45f, cx - r, cy, cx - r, cy)
                    arcTo(
                        rect = androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = -225f,
                        forceMoveTo = false
                    )
                }
                close()
            }

            drawPath(path = path, color = handleAccentColor, style = Fill)
            drawPath(
                path = path,
                color = Color.White,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

@Composable
fun InlineTranslatedBlocksLayer(
    blocks: List<InlineTextBlock>,
    translationState: InlineScreenTranslationState,
    bitmapWidth: Int,
    bitmapHeight: Int,
    showOriginal: Boolean,
    selectedBlockId: Int?,
    onBlockTapped: (InlineTextBlock, InlineBlockStatus?) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    AnimatedVisibility(
        visible = !showOriginal,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val canvasW = constraints.maxWidth.toFloat()
            val canvasH = constraints.maxHeight.toFloat()
            if (bitmapWidth <= 0 || bitmapHeight <= 0 || canvasW <= 0f || canvasH <= 0f) return@BoxWithConstraints

            val sx = canvasW / bitmapWidth
            val sy = canvasH / bitmapHeight
            val padH = with(density) { 4.dp.toPx() }
            val padV = with(density) { 2.dp.toPx() }

            for (block in blocks) {
                val status = translationState.blockStates[block.id] ?: continue
                if (status == InlineBlockStatus.SameLanguage) continue

                val hasPerspective = block.tiltZDeg != 0f || block.tiltXDeg != 0f || block.tiltYDeg != 0f
                val left: Float
                val top: Float
                val boxW: Float
                val boxH: Float
                if (hasPerspective) {
                    val centerX = block.boundingBox.centerX() * sx
                    val centerY = block.boundingBox.centerY() * sy
                    val desiredW = ((if (block.quadWidthPx > 0f) block.quadWidthPx else block.boundingBox.width().toFloat()) * sx + padH * 2f)
                        .coerceIn(28f, canvasW)
                    val desiredH = ((if (block.quadHeightPx > 0f) block.quadHeightPx else block.boundingBox.height().toFloat()) * sy + padV * 2f)
                        .coerceIn(18f, canvasH)
                    left = (centerX - desiredW * 0.5f).coerceIn(0f, (canvasW - 12f).coerceAtLeast(0f))
                    top = (centerY - desiredH * 0.5f).coerceIn(0f, (canvasH - 12f).coerceAtLeast(0f))
                    boxW = desiredW.coerceIn(24f, canvasW - left)
                    boxH = desiredH.coerceIn(18f, canvasH - top)
                } else {
                    val rawL = block.boundingBox.left * sx - padH
                    val rawT = block.boundingBox.top * sy - padV
                    val rawR = block.boundingBox.right * sx + padH
                    val rawB = block.boundingBox.bottom * sy + padV

                    left = rawL.coerceIn(0f, (canvasW - 12f).coerceAtLeast(0f))
                    top = rawT.coerceIn(0f, (canvasH - 12f).coerceAtLeast(0f))
                    boxW = (rawR - left).coerceIn(24f, canvasW - left)
                    boxH = (rawB - top).coerceIn(18f, canvasH - top)
                }

                val perspectiveModifier = if (hasPerspective) {
                    Modifier.graphicsLayer {
                        rotationZ = block.tiltZDeg
                        rotationX = block.tiltXDeg
                        rotationY = block.tiltYDeg
                        cameraDistance = 12f * density.density
                    }
                } else {
                    Modifier
                }

                val isSelected = selectedBlockId == block.id
                val bgColor = Color(block.backgroundColorArgb).copy(alpha = 1f)
                val fgColor = Color(block.foregroundColorArgb)
                val accentColor = MaterialTheme.colorScheme.primary

                when (status) {
                    is InlineBlockStatus.Translated -> {
                        // Fit font size cleanly to original box dimensions without clipping
                        val fittedSp = remember(status.text, boxW, boxH, block.averageLineHeightPx, sy) {
                            val baseSp = with(density) {
                                (block.averageLineHeightPx * sy * 0.76f).toSp().value
                            }.coerceIn(10f, 34f)
                            val innerW = (boxW - padH * 1.5f).roundToInt().coerceAtLeast(12)
                            val innerH = (boxH - padV).roundToInt().coerceAtLeast(10)

                            var candidate = baseSp
                            while (candidate > 8.5f) {
                                val layout = textMeasurer.measure(
                                    text = status.text,
                                    style = TextStyle(
                                        fontFamily = InterFamily,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = candidate.sp,
                                        lineHeight = (candidate * 1.18f).sp
                                    ),
                                    constraints = Constraints(maxWidth = innerW)
                                )
                                if (layout.size.height <= innerH && !layout.hasVisualOverflow) {
                                    break
                                }
                                candidate -= 1.0f
                            }
                            candidate.coerceAtLeast(8.5f)
                        }

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                                .size(
                                    width = with(density) { boxW.toDp() },
                                    height = with(density) { boxH.toDp() }
                                )
                                .then(perspectiveModifier)
                                .clip(RoundedCornerShape(6.dp))
                                .background(bgColor)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(
                                            width = 1.5.dp,
                                            color = accentColor,
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { onBlockTapped(block, status) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .testTag("inline_translated_block_${block.id}"),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = status.text,
                                color = fgColor,
                                fontFamily = InterFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = fittedSp.sp,
                                lineHeight = (fittedSp * 1.16f).sp,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    InlineBlockStatus.Translating -> {
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                                .size(
                                    width = with(density) { boxW.toDp() },
                                    height = with(density) { boxH.toDp() }
                                )
                                .then(perspectiveModifier)
                                .clip(RoundedCornerShape(6.dp))
                                .background(accentColor.copy(alpha = 0.20f))
                                .border(
                                    width = 1.dp,
                                    color = accentColor.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                        )
                    }

                    is InlineBlockStatus.Error -> {
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                                .size(
                                    width = with(density) { boxW.toDp() },
                                    height = with(density) { boxH.toDp() }
                                )
                                .then(perspectiveModifier)
                                .clip(RoundedCornerShape(6.dp))
                                .border(
                                    width = 1.dp,
                                    color = Color(0xFFEF4444).copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable { onBlockTapped(block, status) }
                        )
                    }

                    InlineBlockStatus.SameLanguage -> Unit
                }
            }
        }
    }
}
