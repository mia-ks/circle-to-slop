package com.example.overlay

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SupportedTargetLanguages
import com.example.engine.InlineBlockStatus
import com.example.engine.InlineScreenTranslationState
import com.example.engine.InlineTextBlock
import com.example.engine.ModelPackStatus
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SpaceGroteskFamily
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val LIVE_FINGERPRINT_W = 32
internal const val LIVE_FINGERPRINT_H = 48

fun createScreenFingerprint(bitmap: Bitmap): IntArray {
    val scaled = Bitmap.createScaledBitmap(bitmap, LIVE_FINGERPRINT_W, LIVE_FINGERPRINT_H, false)
    val pixels = IntArray(LIVE_FINGERPRINT_W * LIVE_FINGERPRINT_H)
    try {
        scaled.getPixels(pixels, 0, LIVE_FINGERPRINT_W, 0, 0, LIVE_FINGERPRINT_W, LIVE_FINGERPRINT_H)
    } finally {
        if (scaled !== bitmap) {
            scaled.recycle()
        }
    }
    val luma = IntArray(pixels.size)
    for (i in pixels.indices) {
        val c = pixels[i]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        luma[i] = (r * 77 + g * 150 + b * 29) shr 8
    }
    return luma
}

fun computeFingerprintDiffFraction(
    previous: IntArray?,
    current: IntArray,
    width: Int = LIVE_FINGERPRINT_W,
    height: Int = LIVE_FINGERPRINT_H,
    excludeNormalized: RectF? = null,
    lumaDeltaThreshold: Int = 14
): Float {
    if (previous == null || previous.size != current.size || width <= 0 || height <= 0) return 1f
    val statusBarRows = (height * 0.045f).roundToInt().coerceAtLeast(1)
    var compared = 0
    var changed = 0

    for (y in statusBarRows until height) {
        val ny = (y + 0.5f) / height.toFloat()
        for (x in 0 until width) {
            if (excludeNormalized != null) {
                val nx = (x + 0.5f) / width.toFloat()
                if (excludeNormalized.contains(nx, ny)) continue
            }
            val idx = y * width + x
            compared++
            if (abs(previous[idx] - current[idx]) > lumaDeltaThreshold) {
                changed++
            }
        }
    }
    if (compared == 0) return 0f
    return changed.toFloat() / compared.toFloat()
}

fun filterBlocksExcludingRects(
    blocks: List<InlineTextBlock>,
    excludedRects: List<Rect>
): List<InlineTextBlock> {
    if (blocks.isEmpty() || excludedRects.isEmpty()) return blocks
    val validExclusions = excludedRects.filter { it.width() > 0 && it.height() > 0 }
    if (validExclusions.isEmpty()) return blocks

    return blocks.filter { block ->
        val box = block.boundingBox
        val cx = box.centerX()
        val cy = box.centerY()
        val blockArea = (box.width().toLong() * box.height().toLong()).coerceAtLeast(1L).toFloat()

        validExclusions.none { excl ->
            if (excl.contains(cx, cy)) return@none true
            val interL = max(box.left, excl.left)
            val interT = max(box.top, excl.top)
            val interR = min(box.right, excl.right)
            val interB = min(box.bottom, excl.bottom)
            if (interR <= interL || interB <= interT) return@none false
            val interArea = (interR - interL).toFloat() * (interB - interT).toFloat()
            (interArea / blockArea) >= 0.25f
        }
    }
}

@Composable
fun LiveTranslatedScreenLayer(
    blocks: List<InlineTextBlock>,
    translationState: InlineScreenTranslationState,
    bitmapWidth: Int,
    bitmapHeight: Int,
    isPaused: Boolean,
    isScrollSettling: Boolean
) {
    AnimatedVisibility(
        visible = !isPaused && !isScrollSettling && blocks.isNotEmpty(),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        InlineTranslatedBlocksLayer(
            blocks = blocks,
            translationState = translationState,
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            showOriginal = false,
            selectedBlockId = null,
            onBlockTapped = { _, _ -> },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun LiveTranslateHud(
    targetLangCode: String,
    isPaused: Boolean,
    isScanning: Boolean,
    translationState: InlineScreenTranslationState,
    packStatuses: Map<String, ModelPackStatus>,
    onTogglePause: () -> Unit,
    onSelectTargetLanguage: (String) -> Unit,
    onDragDelta: (dx: Float, dy: Float) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showLanguagePicker by remember { mutableStateOf(false) }
    val accentColor = MaterialTheme.colorScheme.primary
    val translatedCount = translationState.blockStates.values.count {
        it is InlineBlockStatus.Translated
    }
    val currentShortCode = SupportedTargetLanguages.firstOrNull {
        it.code == targetLangCode
    }?.shortCode ?: targetLangCode.uppercase()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .widthIn(max = 320.dp)
            .testTag("live_translate_hud")
    ) {
        val rootView = LocalView.current
        Surface(
            color = Color(0xEB101218),
            shape = RoundedCornerShape(999.dp),
            border = BorderStroke(
                1.dp,
                if (isPaused) AmberWarn.copy(alpha = 0.55f) else accentColor.copy(alpha = 0.55f)
            ),
            shadowElevation = 6.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .padding(start = 10.dp, top = 2.dp, end = 4.dp, bottom = 2.dp)
                    .pointerInput(Unit) {
                        val loc = IntArray(2)
                        var lastScreenX = 0f
                        var lastScreenY = 0f
                        detectDragGestures(
                            onDragStart = { localOffset ->
                                rootView.getLocationOnScreen(loc)
                                lastScreenX = loc[0] + localOffset.x
                                lastScreenY = loc[1] + localOffset.y
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                rootView.getLocationOnScreen(loc)
                                val curX = loc[0] + change.position.x
                                val curY = loc[1] + change.position.y
                                val dx = curX - lastScreenX
                                val dy = curY - lastScreenY
                                lastScreenX = curX
                                lastScreenY = curY
                                onDragDelta(dx, dy)
                            }
                        )
                    }
            ) {
                Icon(
                    imageVector = Icons.Rounded.DragIndicator,
                    contentDescription = "Drag Live Translate HUD",
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(16.dp)
                )

                val statusColor = when {
                    isPaused -> AmberWarn
                    else -> EmeraldActive
                }

                when {
                    translationState.isDownloadingPacks -> {
                        CircularProgressIndicator(
                            progress = { translationState.downloadProgress.coerceIn(0f, 1f) },
                            color = accentColor,
                            trackColor = Color(0xFF2B2B36),
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(13.dp)
                        )
                    }
                    !isPaused && (isScanning || translationState.isTranslating) -> {
                        CircularProgressIndicator(
                            color = EmeraldActive,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(13.dp)
                        )
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                    }
                }

                val statusText = when {
                    isPaused -> "Paused"
                    translationState.isDownloadingPacks ->
                        "DL ${(translationState.downloadProgress.coerceIn(0f, 1f) * 100f).roundToInt()}%"
                    translationState.errorMessage != null -> "Offline err"
                    translatedCount > 0 -> "Live · $translatedCount"
                    isScanning || translationState.isTranslating -> "Live…"
                    else -> "Live"
                }

                Text(
                    text = statusText,
                    color = Color.White,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .testTag("live_hud_status_text")
                )

                Surface(
                    color = Color(0xFF222531),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { showLanguagePicker = !showLanguagePicker }
                        .testTag("live_hud_lang_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Translate,
                            contentDescription = "Select target language",
                            tint = accentColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = currentShortCode,
                            color = Color.White,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp
                        )
                    }
                }

                IconButton(
                    onClick = onTogglePause,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("live_hud_pause_button")
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = if (isPaused) "Resume Live Translate" else "Pause Live Translate",
                        tint = if (isPaused) EmeraldActive else Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }

                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("live_hud_stop_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Stop Live Translate",
                        tint = Color(0xFFFCA5A5),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        AnimatedVisibility(visible = showLanguagePicker) {
            Surface(
                color = Color(0xF012141B),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF2C3040)),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SupportedTargetLanguages.forEach { lang ->
                        val selected = lang.code == targetLangCode
                        val packReady = packStatuses[lang.code] == ModelPackStatus.DOWNLOADED
                        Surface(
                            color = if (selected) accentColor else Color(0xFF222531),
                            shape = RoundedCornerShape(9.dp),
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .clickable {
                                    onSelectTargetLanguage(lang.code)
                                    showLanguagePicker = false
                                }
                                .testTag("live_hud_lang_chip_${lang.code}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                if (packReady) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (selected) MaterialTheme.colorScheme.onPrimary else EmeraldActive
                                            )
                                    )
                                }
                                Text(
                                    text = lang.shortCode,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.5.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
