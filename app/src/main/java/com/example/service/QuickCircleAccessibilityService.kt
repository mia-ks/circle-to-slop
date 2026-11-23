package com.example.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import com.example.data.EdgeAnchor
import com.example.data.GestureConfigRepository
import com.example.data.GestureZoneConfig
import com.example.data.OcrScriptMode
import com.example.data.TriggerMode
import com.example.engine.InlineScreenTranslationState
import com.example.engine.InlineTextBlock
import com.example.engine.OcrAndLensEngine
import com.example.overlay.LIVE_FINGERPRINT_H
import com.example.overlay.LIVE_FINGERPRINT_W
import com.example.overlay.LiveTranslateHud
import com.example.overlay.LiveTranslatedScreenLayer
import com.example.overlay.MinimalCircleOverlay
import com.example.overlay.computeFingerprintDiffFraction
import com.example.overlay.createScreenFingerprint
import com.example.overlay.filterBlocksExcludingRects
import com.example.ui.theme.QuickCircleTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

internal fun doesTriggerOverlapIme(
    triggerRect: Rect,
    imeWindows: List<Rect>,
    safetyMarginPx: Int
): Boolean {
    if (triggerRect.width() <= 0 || triggerRect.height() <= 0) return false
    val expanded = Rect(
        triggerRect.left - safetyMarginPx,
        triggerRect.top - safetyMarginPx,
        triggerRect.right + safetyMarginPx,
        triggerRect.bottom + safetyMarginPx
    )
    return imeWindows.any { imeRect ->
        imeRect.width() > 0 && imeRect.height() > 0 && Rect.intersects(expanded, imeRect)
    }
}

internal fun isDeviceLockedOrAodState(
    isKeyguardLocked: Boolean,
    isDeviceLocked: Boolean,
    isInteractive: Boolean,
    displayState: Int?
): Boolean {
    if (isKeyguardLocked || isDeviceLocked || !isInteractive) return true
    if (displayState != null && displayState != Display.STATE_ON) return true
    return false
}

class QuickCircleAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: GestureConfigRepository
    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentConfig = GestureZoneConfig()
    private var triggerView: View? = null
    private var triggerLayoutParams: WindowManager.LayoutParams? = null

    private var overlayContainer: FrameLayout? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null
    private var overlayBackCallback: Any? = null
    private var isCapturing = false
    private var isScreenReceiverRegistered = false

    // Experimental Live Translate windows and state
    private var liveTranslationContainer: FrameLayout? = null
    private var liveTranslationLifecycleOwner: OverlayLifecycleOwner? = null
    private var liveHudContainer: FrameLayout? = null
    private var liveHudLifecycleOwner: OverlayLifecycleOwner? = null
    private var liveHudParams: WindowManager.LayoutParams? = null
    private var liveHudX = 0f
    private var liveHudY = 0f

    private val liveBlocksFlow = MutableStateFlow<List<InlineTextBlock>>(emptyList())
    private val liveInlineStateFlow = MutableStateFlow(InlineScreenTranslationState())
    private val liveBitmapSizeFlow = MutableStateFlow(0 to 0)
    private val livePausedFlow = MutableStateFlow(false)
    private val liveScanningFlow = MutableStateFlow(false)
    private val liveScrollSettlingFlow = MutableStateFlow(false)

    private var liveProcessJob: Job? = null
    private var currentLiveProcessToken = 0L
    private var lastLiveEmittedSignature: List<Pair<Int, String>>? = null
    private var liveCaptureInFlight = false
    private var lastLiveFingerprint: IntArray? = null
    private var lastLiveTargetLang: String? = null
    private var lastLiveScreenSize: Pair<Int, Int>? = null
    private var suppressLiveEventsUntilMs = 0L
    private var liveDebouncedCaptureRunnable: Runnable? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    dismissOverlay()
                    stopLiveTranslateInternal()
                    updateTriggerWindowVisibility()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    updateTriggerWindowVisibility()
                    mainHandler.postDelayed({ updateTriggerWindowVisibility() }, 250L)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        _isServiceConnected.value = true

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repository = GestureConfigRepository(applicationContext)

        registerScreenStateReceiver()

        // Prewarm only lightweight Latin OCR + pack status on connect
        serviceScope.launch(Dispatchers.Default) {
            OcrAndLensEngine.prewarmLatin(applicationContext)
        }

        serviceScope.launch {
            repository.configFlow.collect { config ->
                currentConfig = config
                if (config.triggerEnabled) {
                    ensureTriggerView(config)
                } else {
                    removeTriggerView()
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        val eventPkg = event.packageName?.toString()
        when (type) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                updateTriggerWindowVisibility()
                if (_isLiveTranslateActive.value) {
                    onLiveContentEvent(
                        eventType = type,
                        eventPkg = eventPkg
                    )
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (_isLiveTranslateActive.value) {
                    onLiveContentEvent(
                        eventType = type,
                        eventPkg = eventPkg
                    )
                }
            }
        }
    }

    override fun onInterrupt() {
        dismissOverlay()
        stopLiveTranslateInternal()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanupAll()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanupAll()
        super.onDestroy()
    }

    private fun registerScreenStateReceiver() {
        if (isScreenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(screenStateReceiver, filter)
            }
            isScreenReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering screen state receiver", e)
        }
    }

    private fun cleanupAll() {
        activeInstance = null
        _isServiceConnected.value = false
        if (isScreenReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: Exception) {
            }
            isScreenReceiverRegistered = false
        }
        stopLiveTranslateInternal()
        dismissOverlay()
        removeTriggerView()
        serviceScope.cancel()
    }

    private fun ensureTriggerView(config: GestureZoneConfig) {
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        val widthPx = (config.widthDp * density).roundToInt().coerceAtLeast((18f * density).roundToInt())
        val heightPx = (config.heightDp * density).roundToInt().coerceAtLeast((18f * density).roundToInt())

        val (xPos, yPos) = computeWindowPosition(config, screenW, screenH, widthPx, heightPx, density)

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = xPos
            y = yPos
        }

        val view = triggerView ?: createTriggerView().also { created ->
            created.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (v.width > 0 && v.height > 0) {
                    applySystemGestureExclusion(v, v.width, v.height, currentConfig, density)
                }
            }
            triggerView = created
            windowManager.addView(created, params)
        }

        triggerLayoutParams = params
        styleTriggerView(view, config, density)
        applySystemGestureExclusion(view, widthPx, heightPx, config, density)
        if (view.parent != null) {
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed updating trigger window layout", e)
            }
        }
        updateTriggerWindowVisibility()
    }

    private fun applySystemGestureExclusion(
        view: View,
        widthPx: Int,
        heightPx: Int,
        config: GestureZoneConfig,
        density: Float
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!config.excludeSystemBack) {
            view.systemGestureExclusionRects = emptyList()
            return
        }
        // Android caps side-edge gesture exclusion to 200dp vertically per edge; center if taller
        val maxExcludedHeightPx = (200f * density).roundToInt()
        val rectTop = if (heightPx > maxExcludedHeightPx) (heightPx - maxExcludedHeightPx) / 2 else 0
        val rectBottom = (rectTop + maxExcludedHeightPx).coerceAtMost(heightPx)
        view.systemGestureExclusionRects = listOf(Rect(0, rectTop, widthPx, rectBottom))
    }

    private fun computeWindowPosition(
        config: GestureZoneConfig,
        screenW: Int,
        screenH: Int,
        widthPx: Int,
        heightPx: Int,
        density: Float
    ): Pair<Int, Int> {
        val insetPx = (config.edgeInsetDp * density).roundToInt().coerceAtLeast(0)
        return when (config.anchor) {
            EdgeAnchor.LEFT_EDGE -> {
                val x = insetPx.coerceIn(0, (screenW - widthPx).coerceAtLeast(0))
                val y = ((screenH - heightPx) * config.offsetYFraction).roundToInt()
                    .coerceIn(0, (screenH - heightPx).coerceAtLeast(0))
                x to y
            }

            EdgeAnchor.RIGHT_EDGE -> {
                val x = (screenW - widthPx - insetPx).coerceIn(0, (screenW - widthPx).coerceAtLeast(0))
                val y = ((screenH - heightPx) * config.offsetYFraction).roundToInt()
                    .coerceIn(0, (screenH - heightPx).coerceAtLeast(0))
                x to y
            }

            EdgeAnchor.BOTTOM_BAR -> {
                val x = ((screenW - widthPx) * config.offsetYFraction).roundToInt()
                    .coerceIn(0, (screenW - widthPx).coerceAtLeast(0))
                val y = (screenH - heightPx - insetPx).coerceIn(0, (screenH - heightPx).coerceAtLeast(0))
                x to y
            }

            EdgeAnchor.FREE -> {
                val x = ((screenW - widthPx) * config.offsetXFraction).roundToInt()
                    .coerceIn(0, (screenW - widthPx).coerceAtLeast(0))
                val y = ((screenH - heightPx) * config.offsetYFraction).roundToInt()
                    .coerceIn(0, (screenH - heightPx).coerceAtLeast(0))
                x to y
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createTriggerView(): View {
        val view = FrameLayout(this)
        val density = resources.displayMetrics.density
        val swipeThresholdPx = 16f * density
        val tapSlopPx = 16f * density

        var downX = 0f
        var downY = 0f
        var downTimeMs = 0L
        var lastTapUpMs = 0L
        var triggeredInGesture = false
        var holdRunnable: Runnable? = null

        view.setOnTouchListener { _, event ->
            val mode = currentConfig.triggerMode
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downTimeMs = System.currentTimeMillis()
                    triggeredInGesture = false

                    if (mode == TriggerMode.SHORT_HOLD) {
                        val runnable = Runnable {
                            if (!triggeredInGesture) {
                                triggeredInGesture = true
                                onTriggerActivated()
                            }
                        }
                        holdRunnable = runnable
                        mainHandler.postDelayed(runnable, 150L)
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (triggeredInGesture) return@setOnTouchListener true
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                    if (mode == TriggerMode.SHORT_HOLD && dist > tapSlopPx) {
                        holdRunnable?.let { mainHandler.removeCallbacks(it) }
                        holdRunnable = null
                    }

                    if (mode == TriggerMode.SWIPE_IN) {
                        val swiped = when (currentConfig.anchor) {
                            EdgeAnchor.LEFT_EDGE -> dx >= swipeThresholdPx
                            EdgeAnchor.RIGHT_EDGE -> dx <= -swipeThresholdPx
                            EdgeAnchor.BOTTOM_BAR -> dy <= -swipeThresholdPx
                            EdgeAnchor.FREE -> dist >= swipeThresholdPx
                        }
                        if (swiped) {
                            triggeredInGesture = true
                            onTriggerActivated()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    holdRunnable?.let { mainHandler.removeCallbacks(it) }
                    holdRunnable = null

                    if (!triggeredInGesture) {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        val elapsed = System.currentTimeMillis() - downTimeMs

                        when (mode) {
                            TriggerMode.TAP -> {
                                if (dist <= tapSlopPx && elapsed < 500L) {
                                    triggeredInGesture = true
                                    onTriggerActivated()
                                }
                            }

                            TriggerMode.DOUBLE_TAP -> {
                                if (dist <= tapSlopPx) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastTapUpMs <= 280L) {
                                        lastTapUpMs = 0L
                                        triggeredInGesture = true
                                        onTriggerActivated()
                                    } else {
                                        lastTapUpMs = now
                                    }
                                }
                            }

                            TriggerMode.SWIPE_IN, TriggerMode.SHORT_HOLD -> Unit
                        }
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    holdRunnable?.let { mainHandler.removeCallbacks(it) }
                    holdRunnable = null
                    true
                }

                else -> false
            }
        }
        return view
    }

    private fun styleTriggerView(view: View, config: GestureZoneConfig, density: Float) {
        val container = view as? FrameLayout ?: return
        container.removeAllViews()

        val alphaInt = (config.handleOpacity.coerceIn(0f, 0.85f) * 255).roundToInt()
        if (alphaInt <= 2) {
            container.background = null
            return
        }

        val pill = View(this)
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f * density
            setColor(Color.argb(alphaInt, 255, 255, 255))
            setStroke((1f * density).roundToInt(), Color.argb((alphaInt * 0.45f).roundToInt(), 0, 0, 0))
        }
        pill.background = drawable

        val pillParams = when (config.anchor) {
            EdgeAnchor.LEFT_EDGE -> FrameLayout.LayoutParams(
                (5f * density).roundToInt(),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            ).apply {
                setMargins((3f * density).roundToInt(), (10f * density).roundToInt(), 0, (10f * density).roundToInt())
            }

            EdgeAnchor.RIGHT_EDGE -> FrameLayout.LayoutParams(
                (5f * density).roundToInt(),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                setMargins(0, (10f * density).roundToInt(), (3f * density).roundToInt(), (10f * density).roundToInt())
            }

            EdgeAnchor.BOTTOM_BAR -> FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (5f * density).roundToInt(),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                setMargins((12f * density).roundToInt(), 0, (12f * density).roundToInt(), (4f * density).roundToInt())
            }

            EdgeAnchor.FREE -> FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                val m = (4f * density).roundToInt()
                setMargins(m, m, m, m)
            }
        }
        container.addView(pill, pillParams)
    }

    private fun removeTriggerView() {
        triggerView?.let { v ->
            if (v.parent != null) {
                try {
                    windowManager.removeViewImmediate(v)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing trigger view", e)
                }
            }
        }
        triggerView = null
    }

    private fun isDeviceLockedOrAod(): Boolean {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val defaultDisplay = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        return isDeviceLockedOrAodState(
            isKeyguardLocked = keyguardManager?.isKeyguardLocked == true,
            isDeviceLocked = keyguardManager?.isDeviceLocked == true,
            isInteractive = powerManager?.isInteractive ?: true,
            displayState = defaultDisplay?.state
        )
    }

    private fun isImeOverlappingTrigger(): Boolean {
        val params = triggerLayoutParams ?: return false
        val triggerRect = Rect(
            params.x,
            params.y,
            params.x + params.width,
            params.y + params.height
        )
        val imeWindows = try {
            windows.orEmpty().mapNotNull { win ->
                if (win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    Rect().also { win.getBoundsInScreen(it) }
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
        if (imeWindows.isEmpty()) return false
        val safetyMarginPx = (8f * resources.displayMetrics.density).roundToInt()
        return doesTriggerOverlapIme(triggerRect, imeWindows, safetyMarginPx)
    }

    private fun updateTriggerWindowVisibility() {
        val view = triggerView ?: return
        val shouldHide = !currentConfig.triggerEnabled ||
            isCapturing ||
            overlayContainer != null ||
            isDeviceLockedOrAod() ||
            isImeOverlappingTrigger()
        val targetVisibility = if (shouldHide) View.GONE else View.VISIBLE
        if (view.visibility != targetVisibility) {
            view.visibility = targetVisibility
        }
    }

    private fun onTriggerActivated() {
        if (isDeviceLockedOrAod()) {
            updateTriggerWindowVisibility()
            return
        }
        OcrAndLensEngine.prewarmMoEOnInvoke(applicationContext)
        if (currentConfig.hapticFeedback) {
            performShortHaptic()
        }
        captureAndShowOverlay()
    }

    @Suppress("DEPRECATION")
    private fun performShortHaptic() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                mgr?.defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(18L)
                }
            }
        } catch (_: Exception) {
        }
    }

    fun captureAndShowOverlay() {
        if (isCapturing || overlayContainer != null) return
        if (isDeviceLockedOrAod()) {
            updateTriggerWindowVisibility()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(
                this,
                "Instant screen capture requires Android 11+. Use the In-App Sandbox on older Android.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (_isLiveTranslateActive.value) {
            stopLiveTranslateInternal()
        }

        isCapturing = true
        updateTriggerWindowVisibility()

        // Small frame delay ensures the trigger handle is invisible before screen capture
        mainHandler.postDelayed({
            if (isDeviceLockedOrAod()) {
                isCapturing = false
                updateTriggerWindowVisibility()
                return@postDelayed
            }
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        isCapturing = false
                        val buffer = screenshot.hardwareBuffer
                        val softwareBitmap = try {
                            val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            val copy = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap?.recycle()
                            copy
                        } catch (e: Exception) {
                            Log.e(TAG, "Bitmap conversion failed", e)
                            null
                        } finally {
                            buffer.close()
                        }

                        if (softwareBitmap != null && !isDeviceLockedOrAod()) {
                            showOverlay(softwareBitmap)
                        } else {
                            softwareBitmap?.recycle()
                            restoreTriggerVisibility()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        isCapturing = false
                        restoreTriggerVisibility()
                        Log.e(TAG, "takeScreenshot failed with errorCode=$errorCode")
                    }
                }
            )
        }, 32L)
    }

    private fun restoreTriggerVisibility() {
        updateTriggerWindowVisibility()
    }

    private fun showOverlay(screenshotBitmap: Bitmap) {
        if (overlayContainer != null) return

        val lifecycleOwner = OverlayLifecycleOwner()
        overlayLifecycleOwner = lifecycleOwner

        val container = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismissOverlay()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { dismissOverlay() }
            overlayBackCallback = callback
            container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                        callback
                    )
                }

                override fun onViewDetachedFromWindow(v: View) {
                    v.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
                }
            })
        }

        val composeView = ComposeView(this).apply {
            setContent {
                QuickCircleTheme(darkTheme = true) {
                    MinimalCircleOverlay(
                        screenshotBitmap = screenshotBitmap,
                        scriptMode = currentConfig.scriptMode,
                        initialTargetLanguageCode = currentConfig.targetLanguageCode,
                        hapticEnabled = currentConfig.hapticFeedback,
                        onTargetLanguageChanged = { code ->
                            serviceScope.launch {
                                repository.updateConfig { it.copy(targetLanguageCode = code) }
                            }
                        },
                        onDismiss = { dismissOverlay() },
                        onExternalActionLaunched = { dismissOverlay() },
                        onStartLiveTranslate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            {
                                dismissOverlay()
                                mainHandler.postDelayed({ startLiveTranslateInternal() }, 90L)
                            }
                        } else {
                            null
                        }
                    )
                }
            }
        }

        lifecycleOwner.attachToView(container)
        container.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        overlayContainer = container
        try {
            windowManager.addView(container, overlayParams)
            updateTriggerWindowVisibility()
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding overlay window", e)
            dismissOverlay()
        }
    }

    fun dismissOverlay() {
        overlayContainer?.let { container ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val callback = overlayBackCallback as? OnBackInvokedCallback
                if (callback != null) {
                    try {
                        container.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
                    } catch (_: Exception) {
                    }
                }
            }
            if (container.parent != null) {
                try {
                    windowManager.removeViewImmediate(container)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing overlay window", e)
                }
            }
        }
        overlayBackCallback = null
        overlayContainer = null
        overlayLifecycleOwner?.detach()
        overlayLifecycleOwner = null
        restoreTriggerVisibility()
    }

    private fun startLiveTranslateInternal(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(
                this,
                "Live Translate requires Android 11+.",
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
        if (isDeviceLockedOrAod()) return false
        if (_isLiveTranslateActive.value) return true

        dismissOverlay()
        OcrAndLensEngine.prewarmMoEOnInvoke(applicationContext, delayMs = 0L)

        liveBlocksFlow.value = emptyList()
        liveInlineStateFlow.value = InlineScreenTranslationState(targetLangCode = currentConfig.targetLanguageCode)
        livePausedFlow.value = false
        liveScanningFlow.value = false
        liveScrollSettlingFlow.value = false
        lastLiveFingerprint = null
        lastLiveTargetLang = null
        lastLiveScreenSize = null
        lastLiveEmittedSignature = null
        currentLiveProcessToken++

        attachLiveTranslationLayer()
        attachLiveHudWindow()
        _isLiveTranslateActive.value = true

        scheduleLiveCapture(delayMs = 150L, forceRefresh = true)
        return true
    }

    private fun stopLiveTranslateInternal() {
        _isLiveTranslateActive.value = false
        currentLiveProcessToken++
        liveProcessJob?.cancel()
        liveProcessJob = null
        liveDebouncedCaptureRunnable?.let { mainHandler.removeCallbacks(it) }
        liveDebouncedCaptureRunnable = null
        liveCaptureInFlight = false
        liveScanningFlow.value = false
        liveScrollSettlingFlow.value = false
        liveBlocksFlow.value = emptyList()
        lastLiveFingerprint = null
        lastLiveEmittedSignature = null
        detachLiveHudWindow()
        detachLiveTranslationLayer()
    }

    private fun attachLiveTranslationLayer() {
        if (liveTranslationContainer != null) return
        val lifecycleOwner = OverlayLifecycleOwner()
        liveTranslationLifecycleOwner = lifecycleOwner

        val container = FrameLayout(this)
        val composeView = ComposeView(this).apply {
            setContent {
                QuickCircleTheme(darkTheme = true) {
                    val blocks by liveBlocksFlow.collectAsState()
                    val trState by liveInlineStateFlow.collectAsState()
                    val bmpSize by liveBitmapSizeFlow.collectAsState()
                    val isPaused by livePausedFlow.collectAsState()
                    val isSettling by liveScrollSettlingFlow.collectAsState()

                    LiveTranslatedScreenLayer(
                        blocks = blocks,
                        translationState = trState,
                        bitmapWidth = bmpSize.first,
                        bitmapHeight = bmpSize.second,
                        isPaused = isPaused,
                        isScrollSettling = isSettling
                    )
                }
            }
        }

        lifecycleOwner.attachToView(container)
        container.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val layerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        liveTranslationContainer = container
        try {
            suppressLiveEventsUntilMs = SystemClock.uptimeMillis() + 260L
            windowManager.addView(container, layerParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding Live Translate layer", e)
            detachLiveTranslationLayer()
        }
    }

    private fun detachLiveTranslationLayer() {
        liveTranslationContainer?.let { container ->
            if (container.parent != null) {
                try {
                    windowManager.removeViewImmediate(container)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing Live Translate layer", e)
                }
            }
        }
        liveTranslationContainer = null
        liveTranslationLifecycleOwner?.detach()
        liveTranslationLifecycleOwner = null
    }

    private fun attachLiveHudWindow() {
        if (liveHudContainer != null) return
        val lifecycleOwner = OverlayLifecycleOwner()
        liveHudLifecycleOwner = lifecycleOwner

        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        val hudWidthEstimate = (250f * density).roundToInt()
        liveHudX = ((screenW - hudWidthEstimate) / 2f).coerceAtLeast(12f * density)
        liveHudY = (44f * density).coerceAtMost((screenH - 96f * density).coerceAtLeast(0f))

        val hudParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = liveHudX.roundToInt()
            y = liveHudY.roundToInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        liveHudParams = hudParams

        val container = FrameLayout(this)
        val composeView = ComposeView(this).apply {
            setContent {
                QuickCircleTheme(darkTheme = true) {
                    val isPaused by livePausedFlow.collectAsState()
                    val isScanning by liveScanningFlow.collectAsState()
                    val trState by liveInlineStateFlow.collectAsState()
                    val packStatuses by OcrAndLensEngine.packStatusFlow.collectAsState()
                    val cfg by repository.configFlow.collectAsState(initial = currentConfig)

                    LiveTranslateHud(
                        targetLangCode = cfg.targetLanguageCode,
                        isPaused = isPaused,
                        isScanning = isScanning,
                        translationState = trState,
                        packStatuses = packStatuses,
                        onTogglePause = {
                            val nextPaused = !livePausedFlow.value
                            livePausedFlow.value = nextPaused
                            suppressLiveEventsUntilMs = SystemClock.uptimeMillis() + 550L
                            liveTranslationContainer?.visibility =
                                if (nextPaused) View.GONE else View.VISIBLE
                            if (!nextPaused) {
                                scheduleLiveCapture(delayMs = 90L, forceRefresh = true)
                            }
                        },
                        onSelectTargetLanguage = { code ->
                            suppressLiveEventsUntilMs = SystemClock.uptimeMillis() + 550L
                            serviceScope.launch {
                                repository.updateConfig { it.copy(targetLanguageCode = code) }
                            }
                            lastLiveTargetLang = null
                            lastLiveEmittedSignature = null
                            scheduleLiveCapture(delayMs = 90L, forceRefresh = true)
                        },
                        onDragDelta = { dx, dy ->
                            val curW = liveHudContainer?.width?.takeIf { it > 0 } ?: hudWidthEstimate
                            val curH = liveHudContainer?.height?.takeIf { it > 0 } ?: (56f * density).roundToInt()
                            val maxX = (resources.displayMetrics.widthPixels - curW).toFloat().coerceAtLeast(0f)
                            val maxY = (resources.displayMetrics.heightPixels - curH).toFloat().coerceAtLeast(0f)
                            liveHudX = (liveHudX + dx).coerceIn(0f, maxX)
                            liveHudY = (liveHudY + dy).coerceIn(0f, maxY)
                            hudParams.x = liveHudX.roundToInt()
                            hudParams.y = liveHudY.roundToInt()
                            suppressLiveEventsUntilMs = SystemClock.uptimeMillis() + 500L
                            liveHudContainer?.let { view ->
                                if (view.parent != null) {
                                    try {
                                        windowManager.updateViewLayout(view, hudParams)
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        },
                        onStop = { stopLiveTranslateInternal() },
                        onRefreshNow = {
                            if (livePausedFlow.value) {
                                livePausedFlow.value = false
                                liveTranslationContainer?.visibility = View.VISIBLE
                            }
                            scheduleLiveCapture(delayMs = 40L, forceRefresh = true)
                        }
                    )
                }
            }
        }

        lifecycleOwner.attachToView(container)
        container.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        liveHudContainer = container
        try {
            suppressLiveEventsUntilMs = SystemClock.uptimeMillis() + 260L
            windowManager.addView(container, hudParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding Live Translate HUD", e)
            detachLiveHudWindow()
        }
    }

    private fun detachLiveHudWindow() {
        liveHudContainer?.let { container ->
            if (container.parent != null) {
                try {
                    windowManager.removeViewImmediate(container)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing Live Translate HUD", e)
                }
            }
        }
        liveHudContainer = null
        liveHudParams = null
        liveHudLifecycleOwner?.detach()
        liveHudLifecycleOwner = null
    }

    private fun onLiveContentEvent(eventType: Int, eventPkg: String?) {
        if (!_isLiveTranslateActive.value || livePausedFlow.value || isDeviceLockedOrAod()) return
        if (liveCaptureInFlight) return
        val now = SystemClock.uptimeMillis()
        if (now < suppressLiveEventsUntilMs) return

        // Never allow our own HUD spinner / chip recompositions or language strip scroll to re-trigger capture
        if (eventPkg == packageName &&
            (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED)
        ) {
            return
        }

        when (eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Hide stale floating boxes only while user actively scrolls, then re-capture when scroll settles
                liveScrollSettlingFlow.value = true
                scheduleLiveCapture(delayMs = 420L, forceRefresh = false)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                scheduleLiveCapture(delayMs = 360L, forceRefresh = false)
            }
            else -> {
                scheduleLiveCapture(delayMs = 600L, forceRefresh = false)
            }
        }
    }

    private fun scheduleLiveCapture(delayMs: Long, forceRefresh: Boolean) {
        if (!_isLiveTranslateActive.value || livePausedFlow.value) return
        liveDebouncedCaptureRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            captureAndProcessLiveFrame(forceRefresh = forceRefresh)
        }
        liveDebouncedCaptureRunnable = runnable
        if (delayMs <= 0L) {
            mainHandler.post(runnable)
        } else {
            mainHandler.postDelayed(runnable, delayMs)
        }
    }

    private fun getLiveHudScreenRect(): Rect? {
        val params = liveHudParams ?: return null
        val view = liveHudContainer ?: return null
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        return Rect(params.x, params.y, params.x + w, params.y + h)
    }

    private fun getImeWindowRects(): List<Rect> {
        return try {
            windows.orEmpty().mapNotNull { win ->
                if (win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    Rect().also { win.getBoundsInScreen(it) }
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun captureAndProcessLiveFrame(forceRefresh: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!_isLiveTranslateActive.value || livePausedFlow.value || liveCaptureInFlight) return
        if (isDeviceLockedOrAod() || overlayContainer != null || isCapturing) return

        liveCaptureInFlight = true
        val layer = liveTranslationContainer
        val needTempHide = layer != null &&
            liveBlocksFlow.value.isNotEmpty() &&
            !liveScrollSettlingFlow.value &&
            layer.visibility == View.VISIBLE

        suppressLiveEventsUntilMs = SystemClock.uptimeMillis() + 650L
        if (needTempHide) {
            layer?.visibility = View.INVISIBLE
        }

        val captureDelay = if (needTempHide) 28L else 0L
        mainHandler.postDelayed({
            if (!_isLiveTranslateActive.value || livePausedFlow.value || isDeviceLockedOrAod()) {
                liveCaptureInFlight = false
                if (needTempHide && _isLiveTranslateActive.value && !livePausedFlow.value) {
                    layer?.visibility = View.VISIBLE
                }
                return@postDelayed
            }

            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        liveCaptureInFlight = false
                        suppressLiveEventsUntilMs = SystemClock.uptimeMillis() + 600L
                        if (needTempHide && _isLiveTranslateActive.value && !livePausedFlow.value) {
                            layer?.visibility = View.VISIBLE
                        }

                        val buffer = screenshot.hardwareBuffer
                        val softwareBitmap = try {
                            val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            val copy = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap?.recycle()
                            copy
                        } catch (e: Exception) {
                            Log.e(TAG, "Live screenshot bitmap conversion failed", e)
                            null
                        } finally {
                            buffer.close()
                        }

                        if (softwareBitmap != null && _isLiveTranslateActive.value && !livePausedFlow.value) {
                            processCapturedLiveBitmap(softwareBitmap, forceRefresh)
                        } else {
                            softwareBitmap?.recycle()
                            liveScrollSettlingFlow.value = false
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        liveCaptureInFlight = false
                        liveScrollSettlingFlow.value = false
                        suppressLiveEventsUntilMs = SystemClock.uptimeMillis() + 450L
                        if (needTempHide && _isLiveTranslateActive.value && !livePausedFlow.value) {
                            layer?.visibility = View.VISIBLE
                        }
                        Log.w(TAG, "Live takeScreenshot failed errorCode=$errorCode")
                    }
                }
            )
        }, captureDelay)
    }

    private fun processCapturedLiveBitmap(bitmap: Bitmap, forceRefresh: Boolean) {
        val targetLang = currentConfig.targetLanguageCode
        val hudRect = getLiveHudScreenRect()
        val excludeNorm = hudRect?.let { r ->
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)
            RectF(
                (r.left / w).coerceIn(0f, 1f),
                (r.top / h).coerceIn(0f, 1f),
                (r.right / w).coerceIn(0f, 1f),
                (r.bottom / h).coerceIn(0f, 1f)
            )
        }

        val fingerprint = createScreenFingerprint(bitmap)
        val screenSize = bitmap.width to bitmap.height
        val diff = computeFingerprintDiffFraction(
            previous = lastLiveFingerprint,
            current = fingerprint,
            width = LIVE_FINGERPRINT_W,
            height = LIVE_FINGERPRINT_H,
            excludeNormalized = excludeNorm
        )

        val unchanged = !forceRefresh &&
            lastLiveTargetLang == targetLang &&
            lastLiveScreenSize == screenSize &&
            diff < 0.018f

        if (unchanged) {
            liveScrollSettlingFlow.value = false
            bitmap.recycle()
            return
        }

        lastLiveFingerprint = fingerprint
        lastLiveTargetLang = targetLang
        lastLiveScreenSize = screenSize
        lastLiveEmittedSignature = null

        val excludedRects = buildList {
            hudRect?.let { add(it) }
            addAll(getImeWindowRects())
        }

        val jobToken = ++currentLiveProcessToken
        liveProcessJob?.cancel()
        liveProcessJob = serviceScope.launch(Dispatchers.Default) {
            liveScanningFlow.value = true
            try {
                val effectiveMode = if (currentConfig.scriptMode == OcrScriptMode.LATIN) {
                    OcrScriptMode.AUTO_MOE
                } else {
                    currentConfig.scriptMode
                }

                OcrAndLensEngine.analyzeFullScreenProgressive(
                    applicationContext,
                    bitmap,
                    effectiveMode
                ) { analysis, isFinal ->
                    if (currentLiveProcessToken != jobToken) return@analyzeFullScreenProgressive

                    val filteredBlocks = filterBlocksExcludingRects(analysis.blocks, excludedRects)
                    val signature = filteredBlocks.map { it.id to it.text }
                    if (isFinal && signature == lastLiveEmittedSignature && signature.isNotEmpty()) {
                        liveScanningFlow.value = false
                        liveScrollSettlingFlow.value = false
                        return@analyzeFullScreenProgressive
                    }

                    val filteredAnalysis = analysis.copy(blocks = filteredBlocks)
                    if (filteredBlocks.isEmpty()) {
                        if (isFinal && currentLiveProcessToken == jobToken) {
                            lastLiveEmittedSignature = emptyList()
                            liveBitmapSizeFlow.value = bitmap.width to bitmap.height
                            liveBlocksFlow.value = emptyList()
                            liveInlineStateFlow.value = InlineScreenTranslationState(targetLangCode = targetLang)
                            liveScrollSettlingFlow.value = false
                            liveScanningFlow.value = false
                        }
                        return@analyzeFullScreenProgressive
                    }

                    val enriched = OcrAndLensEngine.enrichAnalysisForInlineTranslate(bitmap, filteredAnalysis)
                    if (currentLiveProcessToken != jobToken) return@analyzeFullScreenProgressive

                    OcrAndLensEngine.translateBlocksInline(
                        blocks = enriched.blocks,
                        targetLangCode = targetLang,
                        dominantLangHint = enriched.dominantLanguageCode,
                        useCache = true
                    ) { state ->
                        if (currentLiveProcessToken != jobToken) return@translateBlocksInline
                        suppressLiveEventsUntilMs = SystemClock.uptimeMillis() + 300L
                        lastLiveEmittedSignature = signature
                        liveBitmapSizeFlow.value = bitmap.width to bitmap.height
                        liveBlocksFlow.value = enriched.blocks
                        liveInlineStateFlow.value = state
                        liveScrollSettlingFlow.value = false
                    }

                    if (isFinal && currentLiveProcessToken == jobToken) {
                        liveScanningFlow.value = false
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.w(TAG, "Live Translate processing error: ${e.message}")
                }
            } finally {
                if (currentLiveProcessToken == jobToken) {
                    liveScanningFlow.value = false
                    liveScrollSettlingFlow.value = false
                }
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }

    companion object {
        private const val TAG = "QuickCircleA11y"

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _isLiveTranslateActive = MutableStateFlow(false)
        val isLiveTranslateActive: StateFlow<Boolean> = _isLiveTranslateActive.asStateFlow()

        @Volatile
        private var activeInstance: QuickCircleAccessibilityService? = null

        fun startLiveTranslate(): Boolean {
            return activeInstance?.startLiveTranslateInternal() ?: false
        }

        fun stopLiveTranslate() {
            activeInstance?.stopLiveTranslateInternal()
            if (activeInstance == null) {
                _isLiveTranslateActive.value = false
            }
        }

        fun toggleLiveTranslate(): Boolean {
            val instance = activeInstance ?: return false
            if (_isLiveTranslateActive.value) {
                instance.stopLiveTranslateInternal()
                return false
            }
            return instance.startLiveTranslateInternal()
        }

        fun triggerFromTile(): Boolean {
            val instance = activeInstance ?: return false
            if (instance.isDeviceLockedOrAod()) return false
            OcrAndLensEngine.prewarmMoEOnInvoke(instance.applicationContext)
            instance.mainHandler.postDelayed({
                instance.captureAndShowOverlay()
            }, 260L)
            return true
        }

        fun isAccessibilityEnabled(context: Context): Boolean {
            if (isServiceConnected.value) return true
            val expected = ComponentName(context, QuickCircleAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.split(':').any {
                ComponentName.unflattenFromString(it) == expected
            }
        }
    }
}
