package com.example.data

enum class EdgeAnchor(val label: String) {
    LEFT_EDGE("Left Edge"),
    RIGHT_EDGE("Right Edge"),
    BOTTOM_BAR("Bottom"),
    FREE("Free")
}

enum class TriggerMode(val label: String, val shortHint: String) {
    SWIPE_IN("Swipe In", "Swipe inward from zone"),
    TAP("Single Tap", "Tap zone once"),
    DOUBLE_TAP("Double Tap", "Quick double-tap zone"),
    SHORT_HOLD("Hold 150ms", "Brief touch & release")
}

enum class OcrScriptMode(val label: String) {
    AUTO_MOE("Auto MoE"),
    LATIN("Latin Fast"),
    JAPANESE_AND_LATIN("Japanese + Latin"),
    CHINESE_AND_LATIN("Chinese + Latin"),
    KOREAN_AND_LATIN("Korean + Latin"),
    DEVANAGARI_AND_LATIN("Devanagari + Latin")
}

data class TranslateLanguageOption(
    val code: String,
    val label: String,
    val shortCode: String
)

data class DynamicOcrPackOption(
    val code: String,
    val label: String,
    val shortCode: String,
    val sizeHint: String,
    val isPlayServicesMlKit: Boolean
)

val SupportedMlKitDynamicScripts = listOf(
    DynamicOcrPackOption("latin", "Latin (EN/ES/FR/DE)", "LAT", "Play Services", isPlayServicesMlKit = true),
    DynamicOcrPackOption("japanese", "Japanese (JA)", "JA", "Play Services", isPlayServicesMlKit = true),
    DynamicOcrPackOption("chinese", "Chinese (ZH)", "ZH", "Play Services", isPlayServicesMlKit = true),
    DynamicOcrPackOption("korean", "Korean (KO)", "KO", "Play Services", isPlayServicesMlKit = true),
    DynamicOcrPackOption("devanagari", "Devanagari (HI)", "HI", "Play Services", isPlayServicesMlKit = true)
)

val SupportedTesseractOcrPacks = listOf(
    DynamicOcrPackOption("ara", "Arabic", "AR", "~1.4 MB", isPlayServicesMlKit = false),
    DynamicOcrPackOption("rus", "Russian / Cyrillic", "RU", "~1.3 MB", isPlayServicesMlKit = false),
    DynamicOcrPackOption("ukr", "Ukrainian / Cyrillic", "UK", "~1.5 MB", isPlayServicesMlKit = false),
    DynamicOcrPackOption("ell", "Greek", "EL", "~0.6 MB", isPlayServicesMlKit = false),
    DynamicOcrPackOption("tha", "Thai", "TH", "~1.0 MB", isPlayServicesMlKit = false),
    DynamicOcrPackOption("heb", "Hebrew", "HE", "~0.6 MB", isPlayServicesMlKit = false),
    DynamicOcrPackOption("vie", "Vietnamese", "VI", "~0.5 MB", isPlayServicesMlKit = false)
)

val SupportedTargetLanguages = listOf(
    TranslateLanguageOption("en", "English", "EN"),
    TranslateLanguageOption("es", "Spanish", "ES"),
    TranslateLanguageOption("fr", "French", "FR"),
    TranslateLanguageOption("de", "German", "DE"),
    TranslateLanguageOption("ja", "Japanese", "JA"),
    TranslateLanguageOption("ko", "Korean", "KO"),
    TranslateLanguageOption("zh", "Chinese", "ZH"),
    TranslateLanguageOption("it", "Italian", "IT"),
    TranslateLanguageOption("pt", "Portuguese", "PT"),
    TranslateLanguageOption("ru", "Russian", "RU"),
    TranslateLanguageOption("ar", "Arabic", "AR"),
    TranslateLanguageOption("hi", "Hindi", "HI")
)

data class GestureZoneConfig(
    val anchor: EdgeAnchor = EdgeAnchor.RIGHT_EDGE,
    val offsetXFraction: Float = 0.96f,
    val offsetYFraction: Float = 0.44f,
    val widthDp: Float = 28f,
    val heightDp: Float = 120f,
    val edgeInsetDp: Float = 0f,
    val excludeSystemBack: Boolean = true,
    val triggerMode: TriggerMode = TriggerMode.SWIPE_IN,
    val handleOpacity: Float = 0.28f,
    val hapticFeedback: Boolean = true,
    val autoAnalyzeOnLift: Boolean = true,
    val targetLanguageCode: String = "en",
    val scriptMode: OcrScriptMode = OcrScriptMode.AUTO_MOE,
    val triggerEnabled: Boolean = true
) {
    fun summaryLine(): String {
        val w = widthDp.toInt()
        val h = heightDp.toInt()
        val insetSuffix = if (edgeInsetDp >= 1f) " · +${edgeInsetDp.toInt()}dp inset" else ""
        return "${anchor.label} · ${w}×${h} dp$insetSuffix · ${triggerMode.label}"
    }
}
