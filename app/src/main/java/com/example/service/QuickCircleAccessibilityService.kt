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
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import androidx.compose.ui.platform.ComposeView
import com.example.data.EdgeAnchor
import com.example.data.GestureConfigRepository
import com.example.data.GestureZoneConfig
import com.example.data.TriggerMode
import com.example.engine.OcrAndLensEngine
import com.example.overlay.MinimalCircleOverlay
import com.example.ui.theme.QuickCircleTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    dismissOverlay()
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
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            updateTriggerWindowVisibility()
        }
    }

    override fun onInterrupt() {
        dismissOverlay()
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
                        onExternalActionLaunched = { dismissOverlay() }
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

    companion object {
        private const val TAG = "QuickCircleA11y"

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        @Volatile
        private var activeInstance: QuickCircleAccessibilityService? = null

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
