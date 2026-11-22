package com.example.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.drawToBitmap
import com.example.data.EdgeAnchor
import com.example.data.GestureZoneConfig
import com.example.data.TriggerMode
import com.example.overlay.MinimalCircleOverlay
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.InterFamily
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SpaceGroteskFamily
import kotlin.math.hypot
import kotlin.math.roundToInt

data class PinnedResizeResult(
    val offsetXFraction: Float?,
    val offsetYFraction: Float?,
    val widthDp: Float,
    val heightDp: Float
)

fun computeEdgePinnedResize(
    anchor: EdgeAnchor,
    pinnedLeftPx: Float,
    pinnedTopPx: Float,
    pinnedRightPx: Float,
    pinnedBottomPx: Float,
    startWidthDp: Float,
    startHeightDp: Float,
    totalDragPxX: Float,
    totalDragPxY: Float,
    frameW: Float,
    frameH: Float,
    scaleX: Float,
    scaleY: Float
): PinnedResizeResult {
    val safeScaleX = scaleX.coerceAtLeast(0.01f)
    val safeScaleY = scaleY.coerceAtLeast(0.01f)
    val maxWidthDp = (frameW * 0.90f / safeScaleX).coerceIn(18f, 320f)
    val maxHeightDp = (frameH * 0.85f / safeScaleY).coerceIn(18f, 320f)

    return when (anchor) {
        EdgeAnchor.LEFT_EDGE -> {
            // Grip is bottom-right: keep left edge & top edge pinned
            val maxAllowedH = ((frameH - pinnedTopPx) / safeScaleY).coerceIn(18f, maxHeightDp)
            val newW = (startWidthDp + totalDragPxX / safeScaleX).coerceIn(18f, maxWidthDp)
            val newH = (startHeightDp + totalDragPxY / safeScaleY).coerceIn(18f, maxAllowedH)
            val newZoneH = newH * safeScaleY
            val denomY = (frameH - newZoneH).coerceAtLeast(1f)
            val nextOffY = (pinnedTopPx / denomY).coerceIn(0f, 1f)
            PinnedResizeResult(null, nextOffY, newW, newH)
        }

        EdgeAnchor.RIGHT_EDGE -> {
            // Grip is bottom-left: keep right edge & top edge pinned
            val maxAllowedW = (pinnedRightPx / safeScaleX).coerceIn(18f, maxWidthDp)
            val maxAllowedH = ((frameH - pinnedTopPx) / safeScaleY).coerceIn(18f, maxHeightDp)
            val newW = (startWidthDp - totalDragPxX / safeScaleX).coerceIn(18f, maxAllowedW)
            val newH = (startHeightDp + totalDragPxY / safeScaleY).coerceIn(18f, maxAllowedH)
            val newZoneH = newH * safeScaleY
            val denomY = (frameH - newZoneH).coerceAtLeast(1f)
            val nextOffY = (pinnedTopPx / denomY).coerceIn(0f, 1f)
            PinnedResizeResult(null, nextOffY, newW, newH)
        }

        EdgeAnchor.BOTTOM_BAR -> {
            // Grip is top-right: keep left edge & bottom edge pinned
            val maxAllowedW = ((frameW - pinnedLeftPx) / safeScaleX).coerceIn(18f, maxWidthDp)
            val maxAllowedH = (pinnedBottomPx / safeScaleY).coerceIn(18f, maxHeightDp)
            val newW = (startWidthDp + totalDragPxX / safeScaleX).coerceIn(18f, maxAllowedW)
            val newH = (startHeightDp - totalDragPxY / safeScaleY).coerceIn(18f, maxAllowedH)
            val newZoneW = newW * safeScaleX
            val denomX = (frameW - newZoneW).coerceAtLeast(1f)
            // BOTTOM_BAR uses offsetYFraction as its horizontal position fraction
            val nextOffX = (pinnedLeftPx / denomX).coerceIn(0f, 1f)
            PinnedResizeResult(nextOffX, nextOffX, newW, newH)
        }

        EdgeAnchor.FREE -> {
            // Grip is bottom-right: keep left edge & top edge pinned
            val maxAllowedW = ((frameW - pinnedLeftPx) / safeScaleX).coerceIn(18f, maxWidthDp)
            val maxAllowedH = ((frameH - pinnedTopPx) / safeScaleY).coerceIn(18f, maxHeightDp)
            val newW = (startWidthDp + totalDragPxX / safeScaleX).coerceIn(18f, maxAllowedW)
            val newH = (startHeightDp + totalDragPxY / safeScaleY).coerceIn(18f, maxAllowedH)
            val newZoneW = newW * safeScaleX
            val newZoneH = newH * safeScaleY
            val denomX = (frameW - newZoneW).coerceAtLeast(1f)
            val denomY = (frameH - newZoneH).coerceAtLeast(1f)
            val nextOffX = (pinnedLeftPx / denomX).coerceIn(0f, 1f)
            val nextOffY = (pinnedTopPx / denomY).coerceIn(0f, 1f)
            PinnedResizeResult(nextOffX, nextOffY, newW, newH)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoneEditorScreen(
    config: GestureZoneConfig,
    onBack: () -> Unit,
    onSetAnchor: (EdgeAnchor) -> Unit,
    onUpdateGeometry: (
        anchor: EdgeAnchor?,
        offsetXFraction: Float?,
        offsetYFraction: Float?,
        widthDp: Float?,
        heightDp: Float?
    ) -> Unit,
    onSetTriggerMode: (TriggerMode) -> Unit,
    onSetHandleOpacity: (Float) -> Unit,
    onSetEdgeInsetDp: (Float) -> Unit,
    onSetExcludeSystemBack: (Boolean) -> Unit,
    onSetTargetLanguage: (String) -> Unit,
    onResetDefaults: () -> Unit
) {
    val rootView = LocalView.current
    val haptic = LocalHapticFeedback.current
    var isLiveTestMode by remember { mutableStateOf(false) }
    var testCaptureBitmap by remember { mutableStateOf<Bitmap?>(null) }

    BackHandler(enabled = testCaptureBitmap != null) {
        testCaptureBitmap = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("zone_editor_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back to dashboard"
                            )
                        }
                    },
                    title = {
                        Text(
                            text = "Gesture Zone Editor",
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    actions = {
                        TextButton(
                            onClick = onResetDefaults,
                            modifier = Modifier.testTag("zone_editor_reset_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.RestartAlt,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Reset",
                                fontFamily = SpaceGroteskFamily
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Mode toggle: Edit Zone vs Test Trigger
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = "${config.anchor.label} · ${config.widthDp.roundToInt()}×${config.heightDp.roundToInt()} dp",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (isLiveTestMode) EmeraldActive else MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(
                                1.dp,
                                if (isLiveTestMode) EmeraldActive else MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier
                                .heightIn(min = 38.dp)
                                .clickable { isLiveTestMode = !isLiveTestMode }
                                .testTag("toggle_test_trigger_mode")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (isLiveTestMode) Icons.Rounded.PlayArrow else Icons.Rounded.OpenWith,
                                    contentDescription = null,
                                    tint = if (isLiveTestMode) Color.Black else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (isLiveTestMode) "Testing Trigger" else "Test Trigger",
                                    fontFamily = SpaceGroteskFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.5.sp,
                                    color = if (isLiveTestMode) Color.Black else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // Interactive Drag-and-Resize Phone Viewport
                    InteractivePhoneViewport(
                        config = config,
                        isLiveTestMode = isLiveTestMode,
                        onUpdateGeometry = onUpdateGeometry,
                        onTriggerFired = {
                            if (config.hapticFeedback) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            try {
                                testCaptureBitmap = rootView.drawToBitmap()
                            } catch (_: Exception) {
                            }
                        }
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Text(
                                text = "Trigger zone",
                                fontFamily = InterFamily,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(AmberWarn)
                            )
                            Text(
                                text = "OS Assistant / Back area",
                                fontFamily = InterFamily,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = if (isLiveTestMode) {
                            "Perform \"${config.triggerMode.label}\" inside the green zone to test launch."
                        } else {
                            "Drag the zone to reposition or drag the corner grip to resize."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val bottomCornerRisk = (config.anchor == EdgeAnchor.BOTTOM_BAR && config.edgeInsetDp < 16f) ||
                        ((config.anchor == EdgeAnchor.LEFT_EDGE || config.anchor == EdgeAnchor.RIGHT_EDGE) && config.offsetYFraction > 0.76f) ||
                        (config.anchor == EdgeAnchor.FREE && config.offsetYFraction > 0.84f && (config.offsetXFraction < 0.22f || config.offsetXFraction > 0.78f))
                    val backGestureRisk = (config.anchor == EdgeAnchor.LEFT_EDGE || config.anchor == EdgeAnchor.RIGHT_EDGE) &&
                        (!config.excludeSystemBack || config.heightDp > 200f)

                    if (bottomCornerRisk || backGestureRisk) {
                        val warningMessage = buildString {
                            if (bottomCornerRisk) {
                                append("Near bottom-corner Assistant / nav gesture area. Raise Edge Inset (>= 16 dp), drag higher, or use Single Tap / Hold.")
                            }
                            if (backGestureRisk) {
                                if (isNotEmpty()) append(" ")
                                if (!config.excludeSystemBack) {
                                    append("Enable \"Block System Back in Zone\" to prevent OS Back swipes from intercepting this edge handle.")
                                } else {
                                    append("Android only excludes up to 200 dp vertically per edge from System Back; keep Height <= 200 dp for full protection.")
                                }
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = AmberWarn.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, AmberWarn.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("zone_conflict_warning")
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.WarningAmber,
                                    contentDescription = "Gesture conflict warning",
                                    tint = AmberWarn,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = warningMessage,
                                    fontFamily = InterFamily,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // Anchor Edge & Launch Gesture Tonal Section
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Edge anchor",
                                fontFamily = SpaceGroteskFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                EdgeAnchor.entries.forEach { anchor ->
                                    val selected = anchor == config.anchor
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                            .clickable { onSetAnchor(anchor) }
                                            .testTag("anchor_${anchor.name}")
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                                        ) {
                                            Text(
                                                text = anchor.label.replace(" Edge", ""),
                                                fontFamily = SpaceGroteskFamily,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                fontSize = 12.sp,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.onPrimary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }

                            Text(
                                text = "Launch gesture",
                                fontFamily = SpaceGroteskFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TriggerMode.entries.forEach { mode ->
                                    val selected = mode == config.triggerMode
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                            .clickable { onSetTriggerMode(mode) }
                                            .testTag("trigger_mode_${mode.name}")
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                                        ) {
                                            Text(
                                                text = mode.label,
                                                fontFamily = SpaceGroteskFamily,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                fontSize = 11.5.sp,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.onPrimary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Fine Dimension & Visibility Sliders
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DimensionSlider(
                                label = "Width",
                                valueText = "${config.widthDp.roundToInt()} dp",
                                value = config.widthDp,
                                valueRange = 18f..320f,
                                onValueChange = { w ->
                                    onUpdateGeometry(null, null, null, w, null)
                                }
                            )
                            DimensionSlider(
                                label = "Height",
                                valueText = "${config.heightDp.roundToInt()} dp",
                                value = config.heightDp,
                                valueRange = 18f..320f,
                                onValueChange = { h ->
                                    onUpdateGeometry(null, null, null, null, h)
                                }
                            )
                            DimensionSlider(
                                label = "Edge / Bottom Inset",
                                valueText = "${config.edgeInsetDp.roundToInt()} dp",
                                value = config.edgeInsetDp,
                                valueRange = 0f..48f,
                                onValueChange = onSetEdgeInsetDp
                            )
                            DimensionSlider(
                                label = "Handle Opacity",
                                valueText = if (config.handleOpacity <= 0.02f) {
                                    "0% (Invisible)"
                                } else {
                                    "${(config.handleOpacity * 100).roundToInt()}%"
                                },
                                value = config.handleOpacity,
                                valueRange = 0f..0.85f,
                                onValueChange = onSetHandleOpacity
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable { onSetExcludeSystemBack(!config.excludeSystemBack) }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "Block System Back in Zone",
                                        fontFamily = InterFamily,
                                        fontSize = 13.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Excludes up to 200 dp vertically from OS Back swipe",
                                        fontFamily = InterFamily,
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = config.excludeSystemBack,
                                    onCheckedChange = onSetExcludeSystemBack,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier.testTag("exclude_system_back_switch")
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        // Full-screen overlay when triggered from Test Trigger inside Zone Editor
        testCaptureBitmap?.let { bmp ->
            MinimalCircleOverlay(
                screenshotBitmap = bmp,
                scriptMode = config.scriptMode,
                initialTargetLanguageCode = config.targetLanguageCode,
                hapticEnabled = config.hapticFeedback,
                onTargetLanguageChanged = onSetTargetLanguage,
                onDismiss = { testCaptureBitmap = null }
            )
        }
    }
}

@Composable
private fun InteractivePhoneViewport(
    config: GestureZoneConfig,
    isLiveTestMode: Boolean,
    onUpdateGeometry: (
        anchor: EdgeAnchor?,
        offsetXFraction: Float?,
        offsetYFraction: Float?,
        widthDp: Float?,
        heightDp: Float?
    ) -> Unit,
    onTriggerFired: () -> Unit
) {
    val density = LocalDensity.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    BoxWithConstraints(
        modifier = Modifier
            .width(236.dp)
            .aspectRatio(390f / 760f)
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xFF0D0E12))
            .border(2.dp, Color(0xFF343644), RoundedCornerShape(26.dp))
            .testTag("zone_editor_viewport")
    ) {
        val frameW = constraints.maxWidth.toFloat()
        val frameH = constraints.maxHeight.toFloat()
        val scaleX = frameW / 390f
        val scaleY = frameH / 844f

        val minZonePx = with(density) { 16.dp.toPx() }
        val zoneW = (config.widthDp * scaleX).coerceIn(minZonePx, frameW * 0.9f)
        val zoneH = (config.heightDp * scaleY).coerceIn(minZonePx, frameH * 0.85f)
        val insetX = (config.edgeInsetDp * scaleX).coerceAtLeast(0f)
        val insetY = (config.edgeInsetDp * scaleY).coerceAtLeast(0f)

        val (zoneLeft, zoneTop) = when (config.anchor) {
            EdgeAnchor.LEFT_EDGE -> insetX.coerceIn(0f, (frameW - zoneW).coerceAtLeast(0f)) to ((frameH - zoneH) * config.offsetYFraction)
            EdgeAnchor.RIGHT_EDGE -> (frameW - zoneW - insetX).coerceIn(0f, (frameW - zoneW).coerceAtLeast(0f)) to ((frameH - zoneH) * config.offsetYFraction)
            EdgeAnchor.BOTTOM_BAR -> ((frameW - zoneW) * config.offsetYFraction) to (frameH - zoneH - insetY).coerceIn(0f, (frameH - zoneH).coerceAtLeast(0f))
            EdgeAnchor.FREE -> ((frameW - zoneW) * config.offsetXFraction) to ((frameH - zoneH) * config.offsetYFraction)
        }

        // Mock background app content inside the phone frame
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(56.dp)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF282A36))
            )
            CompactMultiScriptPreview(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
            repeat(3) { idx ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF171820),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(if (idx == 1) 0.55f else 0.72f)
                                .height(9.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2D2F3B))
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .height(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF21222C))
                        )
                    }
                }
            }
        }

        val accentPrimary = MaterialTheme.colorScheme.primary
        // OS System Gesture conflict guides (Back side edges & bottom-corner Assistant / nav strip)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val edgeBandW = 20f * scaleX
            val cornerSize = 54f * scaleX
            val navBarH = 22f * scaleY
            val backBandColor = if (!config.excludeSystemBack || config.heightDp > 200f) {
                AmberWarn.copy(alpha = 0.22f)
            } else {
                accentPrimary.copy(alpha = 0.10f)
            }

            // Left & right OS Back gesture edge bands above bottom corners
            drawRect(
                color = backBandColor,
                topLeft = Offset(0f, 36f * scaleY),
                size = Size(edgeBandW, (size.height - cornerSize - 36f * scaleY).coerceAtLeast(0f))
            )
            drawRect(
                color = backBandColor,
                topLeft = Offset(size.width - edgeBandW, 36f * scaleY),
                size = Size(edgeBandW, (size.height - cornerSize - 36f * scaleY).coerceAtLeast(0f))
            )

            // Bottom OS Home/Recents swipe strip
            drawRect(
                color = AmberWarn.copy(alpha = 0.14f),
                topLeft = Offset(cornerSize, size.height - navBarH),
                size = Size((size.width - cornerSize * 2f).coerceAtLeast(0f), navBarH)
            )

            // Bottom-left and bottom-right corner Assistant swipe regions
            val cornerFill = AmberWarn.copy(alpha = 0.26f)
            val cornerStroke = Stroke(width = 1.5f)
            drawRoundRect(
                color = cornerFill,
                topLeft = Offset(0f, size.height - cornerSize),
                size = Size(cornerSize, cornerSize),
                cornerRadius = CornerRadius(14f, 14f)
            )
            drawRoundRect(
                color = AmberWarn.copy(alpha = 0.55f),
                topLeft = Offset(0f, size.height - cornerSize),
                size = Size(cornerSize, cornerSize),
                cornerRadius = CornerRadius(14f, 14f),
                style = cornerStroke
            )
            drawRoundRect(
                color = cornerFill,
                topLeft = Offset(size.width - cornerSize, size.height - cornerSize),
                size = Size(cornerSize, cornerSize),
                cornerRadius = CornerRadius(14f, 14f)
            )
            drawRoundRect(
                color = AmberWarn.copy(alpha = 0.55f),
                topLeft = Offset(size.width - cornerSize, size.height - cornerSize),
                size = Size(cornerSize, cornerSize),
                cornerRadius = CornerRadius(14f, 14f),
                style = cornerStroke
            )
        }

        // Visual handle indicator inside zone (mirrors real overlay look)
        val handleAlpha = config.handleOpacity.coerceIn(0.06f, 0.85f)

        // Interactive Gesture Zone Box
        var lastTapTimeMs by remember { mutableStateOf(0L) }
        var holdRunnable by remember { mutableStateOf<Runnable?>(null) }

        Box(
            modifier = Modifier
                .offset { IntOffset(zoneLeft.roundToInt(), zoneTop.roundToInt()) }
                .size(
                    width = with(density) { zoneW.toDp() },
                    height = with(density) { zoneH.toDp() }
                )
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isLiveTestMode) {
                        EmeraldActive.copy(alpha = 0.25f)
                    } else {
                        accentPrimary.copy(alpha = 0.22f)
                    }
                )
                .border(
                    1.5.dp,
                    if (isLiveTestMode) EmeraldActive else accentPrimary,
                    RoundedCornerShape(8.dp)
                )
                .then(
                    if (isLiveTestMode) {
                        Modifier
                            .pointerInput(config.triggerMode, config.anchor) {
                                detectTapGestures(
                                    onPress = {
                                        if (config.triggerMode == TriggerMode.SHORT_HOLD) {
                                            val r = Runnable { onTriggerFired() }
                                            holdRunnable = r
                                            mainHandler.postDelayed(r, 150L)
                                            tryAwaitRelease()
                                            mainHandler.removeCallbacks(r)
                                            holdRunnable = null
                                        } else {
                                            tryAwaitRelease()
                                        }
                                    },
                                    onTap = {
                                        when (config.triggerMode) {
                                            TriggerMode.TAP -> onTriggerFired()
                                            TriggerMode.DOUBLE_TAP -> {
                                                val now = System.currentTimeMillis()
                                                if (now - lastTapTimeMs <= 300L) {
                                                    lastTapTimeMs = 0L
                                                    onTriggerFired()
                                                } else {
                                                    lastTapTimeMs = now
                                                }
                                            }
                                            else -> Unit
                                        }
                                    }
                                )
                            }
                            .pointerInput(config.triggerMode, config.anchor) {
                                var totalDx = 0f
                                var totalDy = 0f
                                var fired = false
                                val threshold = with(density) { 14.dp.toPx() }
                                detectDragGestures(
                                    onDragStart = {
                                        totalDx = 0f
                                        totalDy = 0f
                                        fired = false
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        if (fired || config.triggerMode != TriggerMode.SWIPE_IN) return@detectDragGestures
                                        totalDx += dragAmount.x
                                        totalDy += dragAmount.y
                                        val ok = when (config.anchor) {
                                            EdgeAnchor.LEFT_EDGE -> totalDx >= threshold
                                            EdgeAnchor.RIGHT_EDGE -> totalDx <= -threshold
                                            EdgeAnchor.BOTTOM_BAR -> totalDy <= -threshold
                                            EdgeAnchor.FREE -> hypot(totalDx, totalDy) >= threshold
                                        }
                                        if (ok) {
                                            fired = true
                                            onTriggerFired()
                                        }
                                    }
                                )
                            }
                    } else {
                        Modifier.pointerInput(config.anchor, zoneW, zoneH, frameW, frameH, insetX, insetY) {
                            var curLeft = zoneLeft
                            var curTop = zoneTop
                            var curAnchor = config.anchor
                            detectDragGestures(
                                onDragStart = {
                                    curLeft = zoneLeft
                                    curTop = zoneTop
                                    curAnchor = config.anchor
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    curLeft = (curLeft + dragAmount.x).coerceIn(0f, (frameW - zoneW).coerceAtLeast(1f))
                                    curTop = (curTop + dragAmount.y).coerceIn(0f, (frameH - zoneH).coerceAtLeast(1f))

                                    if (curAnchor != EdgeAnchor.FREE) {
                                        val centerX = curLeft + zoneW / 2f
                                        val centerY = curTop + zoneH / 2f
                                        curAnchor = when {
                                            centerY > frameH * 0.86f -> EdgeAnchor.BOTTOM_BAR
                                            centerX < frameW * 0.28f -> EdgeAnchor.LEFT_EDGE
                                            centerX > frameW * 0.72f -> EdgeAnchor.RIGHT_EDGE
                                            else -> curAnchor
                                        }
                                    }

                                    val denomX = (frameW - zoneW).coerceAtLeast(1f)
                                    val denomY = (frameH - zoneH).coerceAtLeast(1f)
                                    val nextOffsetX = (curLeft / denomX).coerceIn(0f, 1f)
                                    val nextOffsetY = if (curAnchor == EdgeAnchor.BOTTOM_BAR) {
                                        (curLeft / denomX).coerceIn(0f, 1f)
                                    } else {
                                        (curTop / denomY).coerceIn(0f, 1f)
                                    }
                                    onUpdateGeometry(curAnchor, nextOffsetX, nextOffsetY, null, null)
                                }
                            )
                        }
                    }
                )
                .testTag("zone_editor_draggable_handle")
        ) {
            // Pill preview inside zone
            Box(
                modifier = Modifier
                    .align(
                        when (config.anchor) {
                            EdgeAnchor.LEFT_EDGE -> Alignment.CenterStart
                            EdgeAnchor.RIGHT_EDGE -> Alignment.CenterEnd
                            EdgeAnchor.BOTTOM_BAR -> Alignment.BottomCenter
                            EdgeAnchor.FREE -> Alignment.Center
                        }
                    )
                    .padding(3.dp)
                    .then(
                        when (config.anchor) {
                            EdgeAnchor.LEFT_EDGE, EdgeAnchor.RIGHT_EDGE -> Modifier
                                .width(4.dp)
                                .fillMaxHeight(0.7f)
                            EdgeAnchor.BOTTOM_BAR -> Modifier
                                .fillMaxWidth(0.75f)
                                .height(4.dp)
                            EdgeAnchor.FREE -> Modifier.fillMaxSize(0.55f)
                        }
                    )
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = handleAlpha))
            )
        }

        // Direct Drag-to-Resize Corner Grip Handle (pins opposite edge so only the dragged corner moves)
        if (!isLiveTestMode) {
            val touchSize = 48.dp
            val touchHalfPx = with(density) { (touchSize / 2).toPx() }
            val gripX = when (config.anchor) {
                EdgeAnchor.RIGHT_EDGE -> (zoneLeft - touchHalfPx).coerceIn(0f, (frameW - touchHalfPx * 2).coerceAtLeast(0f))
                else -> (zoneLeft + zoneW - touchHalfPx).coerceIn(0f, (frameW - touchHalfPx * 2).coerceAtLeast(0f))
            }
            val gripY = when (config.anchor) {
                EdgeAnchor.BOTTOM_BAR -> (zoneTop - touchHalfPx).coerceIn(0f, (frameH - touchHalfPx * 2).coerceAtLeast(0f))
                else -> (zoneTop + zoneH - touchHalfPx).coerceIn(0f, (frameH - touchHalfPx * 2).coerceAtLeast(0f))
            }

            val latestConfig by rememberUpdatedState(config)
            val latestZoneRect by rememberUpdatedState(floatArrayOf(zoneLeft, zoneTop, zoneLeft + zoneW, zoneTop + zoneH))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset { IntOffset(gripX.roundToInt(), gripY.roundToInt()) }
                    .size(touchSize)
                    .pointerInput(config.anchor, frameW, frameH, scaleX, scaleY) {
                        var pinnedLeft = 0f
                        var pinnedTop = 0f
                        var pinnedRight = 0f
                        var pinnedBottom = 0f
                        var startW = 28f
                        var startH = 120f
                        var accumDx = 0f
                        var accumDy = 0f
                        detectDragGestures(
                            onDragStart = {
                                val r = latestZoneRect
                                pinnedLeft = r[0]
                                pinnedTop = r[1]
                                pinnedRight = r[2]
                                pinnedBottom = r[3]
                                startW = latestConfig.widthDp
                                startH = latestConfig.heightDp
                                accumDx = 0f
                                accumDy = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                accumDx += dragAmount.x
                                accumDy += dragAmount.y
                                val resized = computeEdgePinnedResize(
                                    anchor = latestConfig.anchor,
                                    pinnedLeftPx = pinnedLeft,
                                    pinnedTopPx = pinnedTop,
                                    pinnedRightPx = pinnedRight,
                                    pinnedBottomPx = pinnedBottom,
                                    startWidthDp = startW,
                                    startHeightDp = startH,
                                    totalDragPxX = accumDx,
                                    totalDragPxY = accumDy,
                                    frameW = frameW,
                                    frameH = frameH,
                                    scaleX = scaleX,
                                    scaleY = scaleY
                                )
                                onUpdateGeometry(
                                    null,
                                    resized.offsetXFraction,
                                    resized.offsetYFraction,
                                    resized.widthDp,
                                    resized.heightDp
                                )
                            }
                        )
                    }
                    .testTag("zone_editor_resize_grip")
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.5.dp, accentPrimary, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun DimensionSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontFamily = InterFamily,
                fontSize = 13.5.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = valueText,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}
