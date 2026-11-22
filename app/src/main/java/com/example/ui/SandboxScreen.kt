package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.example.engine.OcrAndLensEngine
import com.example.overlay.MinimalCircleOverlay
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.InterFamily
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SpaceGroteskFamily
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun SandboxScreen(
    config: GestureZoneConfig,
    onBack: () -> Unit,
    onSetTargetLanguage: (String) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val rootView = LocalView.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var pickedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var overlayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showTriggerOverlay by remember { mutableStateOf(true) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            uri?.let {
                pickedBitmap = decodeSoftwareBitmap(context, it)
            }
        }
    )

    BackHandler(enabled = overlayBitmap != null) {
        overlayBitmap = null
    }

    fun captureSandboxAndLaunch() {
        OcrAndLensEngine.prewarmMoEOnInvoke(context)
        if (config.hapticFeedback) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        showTriggerOverlay = false
        mainHandler.postDelayed({
            try {
                overlayBitmap = rootView.drawToBitmap()
            } catch (_: Exception) {
            } finally {
                showTriggerOverlay = true
            }
        }, 32L)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0E))
    ) {
        // Test Content Area (Sample Tokyo cafe storefront OR user's picked photo)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("sandbox_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Test Arena",
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                    Text(
                        text = "Trigger: ${config.summaryLine()}",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.5.sp,
                        color = ElectricCyan
                    )
                }

                Spacer(Modifier.size(48.dp))
            }

            // Rich visual + multilingual sample card
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF101218))
            ) {
                val customBmp = pickedBitmap
                if (customBmp != null) {
                    Image(
                        bitmap = customBmp.asImageBitmap(),
                        contentDescription = "Selected photo for Circle to Search",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    MultiScriptTestBoard(modifier = Modifier.fillMaxSize())
                }
            }

            // Bottom Action Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    border = BorderStroke(1.dp, Color(0xFF343644)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("sandbox_pick_photo_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Pick Photo",
                        fontFamily = SpaceGroteskFamily,
                        color = Color.White
                    )
                }

                Button(
                    onClick = { captureSandboxAndLaunch() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF0B0B0E)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("sandbox_circle_now_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CropFree,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Circle Screen",
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Live In-App Custom Gesture Trigger Zone (matches user's exact Zone Editor config)
        if (showTriggerOverlay && overlayBitmap == null && config.triggerEnabled) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val screenW = constraints.maxWidth.toFloat()
                val screenH = constraints.maxHeight.toFloat()
                val zoneW = with(density) { config.widthDp.dp.toPx() }.coerceAtLeast(with(density) { 18.dp.toPx() })
                val zoneH = with(density) { config.heightDp.dp.toPx() }.coerceAtLeast(with(density) { 18.dp.toPx() })
                val insetPx = with(density) { config.edgeInsetDp.dp.toPx() }

                val (posX, posY) = when (config.anchor) {
                    EdgeAnchor.LEFT_EDGE -> insetPx.coerceIn(0f, (screenW - zoneW).coerceAtLeast(0f)) to ((screenH - zoneH) * config.offsetYFraction)
                    EdgeAnchor.RIGHT_EDGE -> (screenW - zoneW - insetPx).coerceIn(0f, (screenW - zoneW).coerceAtLeast(0f)) to ((screenH - zoneH) * config.offsetYFraction)
                    EdgeAnchor.BOTTOM_BAR -> ((screenW - zoneW) * config.offsetYFraction) to (screenH - zoneH - insetPx).coerceIn(0f, (screenH - zoneH).coerceAtLeast(0f))
                    EdgeAnchor.FREE -> ((screenW - zoneW) * config.offsetXFraction) to ((screenH - zoneH) * config.offsetYFraction)
                }

                var lastTapUpMs by remember { mutableStateOf(0L) }
                val swipeThresholdPx = with(density) { 16.dp.toPx() }
                val pillAlpha = config.handleOpacity.coerceIn(0.12f, 0.85f)

                Box(
                    modifier = Modifier
                        .offset { IntOffset(posX.roundToInt(), posY.roundToInt()) }
                        .size(width = config.widthDp.dp, height = config.heightDp.dp)
                        .pointerInput(config.triggerMode, config.anchor) {
                            detectTapGestures(
                                onPress = {
                                    if (config.triggerMode == TriggerMode.SHORT_HOLD) {
                                        val r = Runnable { captureSandboxAndLaunch() }
                                        mainHandler.postDelayed(r, 150L)
                                        tryAwaitRelease()
                                        mainHandler.removeCallbacks(r)
                                    } else {
                                        tryAwaitRelease()
                                    }
                                },
                                onTap = {
                                    when (config.triggerMode) {
                                        TriggerMode.TAP -> captureSandboxAndLaunch()
                                        TriggerMode.DOUBLE_TAP -> {
                                            val now = System.currentTimeMillis()
                                            if (now - lastTapUpMs <= 280L) {
                                                lastTapUpMs = 0L
                                                captureSandboxAndLaunch()
                                            } else {
                                                lastTapUpMs = now
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
                                    val triggered = when (config.anchor) {
                                        EdgeAnchor.LEFT_EDGE -> totalDx >= swipeThresholdPx
                                        EdgeAnchor.RIGHT_EDGE -> totalDx <= -swipeThresholdPx
                                        EdgeAnchor.BOTTOM_BAR -> totalDy <= -swipeThresholdPx
                                        EdgeAnchor.FREE -> hypot(totalDx, totalDy) >= swipeThresholdPx
                                    }
                                    if (triggered) {
                                        fired = true
                                        captureSandboxAndLaunch()
                                    }
                                }
                            )
                        }
                        .testTag("sandbox_live_trigger")
                ) {
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
                                        .width(5.dp)
                                        .fillMaxHeight(0.8f)
                                    EdgeAnchor.BOTTOM_BAR -> Modifier
                                        .fillMaxWidth(0.8f)
                                        .height(5.dp)
                                    EdgeAnchor.FREE -> Modifier.fillMaxSize(0.7f)
                                }
                            )
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = pillAlpha))
                            .border(1.dp, Color.Black.copy(alpha = 0.35f), CircleShape)
                    )
                }
            }
        }

        // Full-Screen Minimalist White-Circle / Black-Tint Overlay
        overlayBitmap?.let { bmp ->
            MinimalCircleOverlay(
                screenshotBitmap = bmp,
                scriptMode = config.scriptMode,
                initialTargetLanguageCode = config.targetLanguageCode,
                hapticEnabled = config.hapticFeedback,
                onTargetLanguageChanged = onSetTargetLanguage,
                onDismiss = { overlayBitmap = null }
            )
        }
    }
}

private fun decodeSoftwareBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }
        raw?.let {
            if (it.config == Bitmap.Config.ARGB_8888) it
            else it.copy(Bitmap.Config.ARGB_8888, false)
        }
    } catch (_: Exception) {
        null
    }
}
