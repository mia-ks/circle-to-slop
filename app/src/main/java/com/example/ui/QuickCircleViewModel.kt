package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.EdgeAnchor
import com.example.data.GestureConfigRepository
import com.example.data.GestureZoneConfig
import com.example.data.OcrScriptMode
import com.example.data.TriggerMode
import com.example.engine.OcrAndLensEngine
import com.example.service.QuickCircleAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class QuickCircleViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = GestureConfigRepository(application.applicationContext)

    val config: StateFlow<GestureZoneConfig> = repository.configFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GestureZoneConfig()
    )

    private val _systemCheckFlag = MutableStateFlow(
        QuickCircleAccessibilityService.isAccessibilityEnabled(application.applicationContext)
    )

    val isAccessibilityEnabled: StateFlow<Boolean> = combine(
        QuickCircleAccessibilityService.isServiceConnected,
        _systemCheckFlag
    ) { connected, flag ->
        connected || flag
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = QuickCircleAccessibilityService.isAccessibilityEnabled(application.applicationContext)
    )

    init {
        viewModelScope.launch(Dispatchers.Default) {
            OcrAndLensEngine.prewarm(getApplication())
        }
    }

    fun refreshAccessibilityStatus() {
        _systemCheckFlag.value =
            QuickCircleAccessibilityService.isAccessibilityEnabled(getApplication())
    }

    fun setAnchor(anchor: EdgeAnchor) {
        viewModelScope.launch {
            repository.updateConfig { current ->
                when (anchor) {
                    EdgeAnchor.LEFT_EDGE, EdgeAnchor.RIGHT_EDGE -> {
                        val wasHorizontal = current.anchor == EdgeAnchor.BOTTOM_BAR
                        current.copy(
                            anchor = anchor,
                            widthDp = if (wasHorizontal) current.heightDp.coerceIn(20f, 96f) else current.widthDp.coerceIn(18f, 120f),
                            heightDp = if (wasHorizontal) current.widthDp.coerceIn(64f, 280f) else current.heightDp.coerceIn(48f, 320f)
                        )
                    }

                    EdgeAnchor.BOTTOM_BAR -> {
                        val wasVertical = current.anchor == EdgeAnchor.LEFT_EDGE || current.anchor == EdgeAnchor.RIGHT_EDGE
                        current.copy(
                            anchor = anchor,
                            widthDp = if (wasVertical) current.heightDp.coerceIn(72f, 320f) else current.widthDp.coerceIn(64f, 320f),
                            heightDp = if (wasVertical) current.widthDp.coerceIn(20f, 96f) else current.heightDp.coerceIn(18f, 110f)
                        )
                    }

                    EdgeAnchor.FREE -> {
                        current.copy(
                            anchor = anchor,
                            widthDp = current.widthDp.coerceAtLeast(44f),
                            heightDp = current.heightDp.coerceAtLeast(44f)
                        )
                    }
                }
            }
        }
    }

    fun updateZoneGeometry(
        anchor: EdgeAnchor? = null,
        offsetXFraction: Float? = null,
        offsetYFraction: Float? = null,
        widthDp: Float? = null,
        heightDp: Float? = null
    ) {
        viewModelScope.launch {
            repository.updateConfig { current ->
                current.copy(
                    anchor = anchor ?: current.anchor,
                    offsetXFraction = (offsetXFraction ?: current.offsetXFraction).coerceIn(0f, 1f),
                    offsetYFraction = (offsetYFraction ?: current.offsetYFraction).coerceIn(0f, 1f),
                    widthDp = (widthDp ?: current.widthDp).coerceIn(18f, 320f),
                    heightDp = (heightDp ?: current.heightDp).coerceIn(18f, 320f)
                )
            }
        }
    }

    fun setTriggerMode(mode: TriggerMode) {
        viewModelScope.launch {
            repository.updateConfig { it.copy(triggerMode = mode) }
        }
    }

    fun setHandleOpacity(opacity: Float) {
        viewModelScope.launch {
            repository.updateConfig { it.copy(handleOpacity = opacity.coerceIn(0f, 0.85f)) }
        }
    }

    fun setHapticFeedback(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateConfig { it.copy(hapticFeedback = enabled) }
        }
    }

    fun setTriggerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateConfig { it.copy(triggerEnabled = enabled) }
        }
    }

    fun setTargetLanguage(code: String) {
        viewModelScope.launch {
            repository.updateConfig { it.copy(targetLanguageCode = code) }
        }
    }

    fun setScriptMode(mode: OcrScriptMode) {
        viewModelScope.launch {
            repository.updateConfig { it.copy(scriptMode = mode) }
        }
    }

    fun resetDefaults() {
        viewModelScope.launch {
            repository.resetToDefault()
        }
    }
}
