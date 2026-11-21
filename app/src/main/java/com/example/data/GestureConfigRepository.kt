package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.quickCircleDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "quickcircle_prefs"
)

class GestureConfigRepository(private val context: Context) {

    private object Keys {
        val ANCHOR = stringPreferencesKey("anchor")
        val OFFSET_X = floatPreferencesKey("offset_x_fraction")
        val OFFSET_Y = floatPreferencesKey("offset_y_fraction")
        val WIDTH_DP = floatPreferencesKey("width_dp")
        val HEIGHT_DP = floatPreferencesKey("height_dp")
        val TRIGGER_MODE = stringPreferencesKey("trigger_mode")
        val HANDLE_OPACITY = floatPreferencesKey("handle_opacity")
        val HAPTIC = booleanPreferencesKey("haptic_feedback")
        val AUTO_ANALYZE = booleanPreferencesKey("auto_analyze_on_lift")
        val TARGET_LANG = stringPreferencesKey("target_language")
        val SCRIPT_MODE = stringPreferencesKey("script_mode")
        val TRIGGER_ENABLED = booleanPreferencesKey("trigger_enabled")
    }

    val configFlow: Flow<GestureZoneConfig> = context.quickCircleDataStore.data.map { prefs ->
        val anchor = prefs[Keys.ANCHOR]?.let { name ->
            EdgeAnchor.entries.firstOrNull { it.name == name }
        } ?: EdgeAnchor.RIGHT_EDGE

        val triggerMode = prefs[Keys.TRIGGER_MODE]?.let { name ->
            TriggerMode.entries.firstOrNull { it.name == name }
        } ?: TriggerMode.SWIPE_IN

        val scriptMode = prefs[Keys.SCRIPT_MODE]?.let { name ->
            OcrScriptMode.entries.firstOrNull { it.name == name }
        } ?: OcrScriptMode.JAPANESE_AND_LATIN

        GestureZoneConfig(
            anchor = anchor,
            offsetXFraction = (prefs[Keys.OFFSET_X] ?: 0.96f).coerceIn(0f, 1f),
            offsetYFraction = (prefs[Keys.OFFSET_Y] ?: 0.52f).coerceIn(0f, 1f),
            widthDp = (prefs[Keys.WIDTH_DP] ?: 28f).coerceIn(18f, 320f),
            heightDp = (prefs[Keys.HEIGHT_DP] ?: 136f).coerceIn(18f, 320f),
            triggerMode = triggerMode,
            handleOpacity = (prefs[Keys.HANDLE_OPACITY] ?: 0.28f).coerceIn(0f, 0.85f),
            hapticFeedback = prefs[Keys.HAPTIC] ?: true,
            autoAnalyzeOnLift = prefs[Keys.AUTO_ANALYZE] ?: true,
            targetLanguageCode = prefs[Keys.TARGET_LANG] ?: "en",
            scriptMode = scriptMode,
            triggerEnabled = prefs[Keys.TRIGGER_ENABLED] ?: true
        )
    }

    suspend fun updateConfig(transform: (GestureZoneConfig) -> GestureZoneConfig) {
        context.quickCircleDataStore.edit { prefs ->
            val current = GestureZoneConfig(
                anchor = prefs[Keys.ANCHOR]?.let { n -> EdgeAnchor.entries.firstOrNull { it.name == n } }
                    ?: EdgeAnchor.RIGHT_EDGE,
                offsetXFraction = prefs[Keys.OFFSET_X] ?: 0.96f,
                offsetYFraction = prefs[Keys.OFFSET_Y] ?: 0.52f,
                widthDp = prefs[Keys.WIDTH_DP] ?: 28f,
                heightDp = prefs[Keys.HEIGHT_DP] ?: 136f,
                triggerMode = prefs[Keys.TRIGGER_MODE]?.let { n -> TriggerMode.entries.firstOrNull { it.name == n } }
                    ?: TriggerMode.SWIPE_IN,
                handleOpacity = prefs[Keys.HANDLE_OPACITY] ?: 0.28f,
                hapticFeedback = prefs[Keys.HAPTIC] ?: true,
                autoAnalyzeOnLift = prefs[Keys.AUTO_ANALYZE] ?: true,
                targetLanguageCode = prefs[Keys.TARGET_LANG] ?: "en",
                scriptMode = prefs[Keys.SCRIPT_MODE]?.let { n -> OcrScriptMode.entries.firstOrNull { it.name == n } }
                    ?: OcrScriptMode.JAPANESE_AND_LATIN,
                triggerEnabled = prefs[Keys.TRIGGER_ENABLED] ?: true
            )
            val next = transform(current)
            prefs[Keys.ANCHOR] = next.anchor.name
            prefs[Keys.OFFSET_X] = next.offsetXFraction.coerceIn(0f, 1f)
            prefs[Keys.OFFSET_Y] = next.offsetYFraction.coerceIn(0f, 1f)
            prefs[Keys.WIDTH_DP] = next.widthDp.coerceIn(18f, 320f)
            prefs[Keys.HEIGHT_DP] = next.heightDp.coerceIn(18f, 320f)
            prefs[Keys.TRIGGER_MODE] = next.triggerMode.name
            prefs[Keys.HANDLE_OPACITY] = next.handleOpacity.coerceIn(0f, 0.85f)
            prefs[Keys.HAPTIC] = next.hapticFeedback
            prefs[Keys.AUTO_ANALYZE] = next.autoAnalyzeOnLift
            prefs[Keys.TARGET_LANG] = next.targetLanguageCode
            prefs[Keys.SCRIPT_MODE] = next.scriptMode.name
            prefs[Keys.TRIGGER_ENABLED] = next.triggerEnabled
        }
    }

    suspend fun resetToDefault() {
        updateConfig { GestureZoneConfig() }
    }
}
