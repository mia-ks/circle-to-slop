package com.example.engine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import com.example.data.OcrScriptMode
import com.example.data.SupportedTargetLanguages
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RecognizedTextWord(
    val globalIndex: Int,
    val lineIndex: Int,
    val blockId: Int,
    val text: String,
    val boundingBox: Rect
)

data class RecognizedTextLine(
    val text: String,
    val boundingBox: Rect,
    val lineIndex: Int = 0,
    val blockId: Int = 0,
    val words: List<RecognizedTextWord> = emptyList()
)

data class InlineTextBlock(
    val id: Int,
    val text: String,
    val boundingBox: Rect,
    val lineCount: Int,
    val averageLineHeightPx: Float,
    val detectedLanguageCode: String?,
    val backgroundColorArgb: Int,
    val foregroundColorArgb: Int
)

data class ScreenOcrAnalysis(
    val lines: List<RecognizedTextLine> = emptyList(),
    val words: List<RecognizedTextWord> = emptyList(),
    val blocks: List<InlineTextBlock> = emptyList(),
    val dominantLanguageCode: String? = null
)

sealed interface InlineBlockStatus {
    data object Translating : InlineBlockStatus
    data class Translated(val text: String, val sourceLangName: String) : InlineBlockStatus
    data object SameLanguage : InlineBlockStatus
    data class Error(val message: String) : InlineBlockStatus
}

data class InlineScreenTranslationState(
    val targetLangCode: String = "en",
    val isDownloadingPacks: Boolean = false,
    val missingPacksLabel: String = "",
    val isTranslating: Boolean = false,
    val blockStates: Map<Int, InlineBlockStatus> = emptyMap(),
    val errorMessage: String? = null
)

data class OcrRegionResult(
    val extractedText: String,
    val lineCount: Int,
    val detectedLanguageCode: String?,
    val detectedLanguageName: String?
)

enum class ModelPackStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    ERROR
}

sealed interface TranslationState {
    data object Idle : TranslationState
    data class DownloadingModel(
        val languagePairLabel: String,
        val missingPacksLabel: String,
        val engineLabel: String = "Google ML Kit Offline"
    ) : TranslationState
    data object Translating : TranslationState
    data class Success(val translatedText: String, val sourceLangName: String, val targetLangName: String) : TranslationState
    data class SameLanguage(val languageName: String) : TranslationState
    data class Error(
        val message: String,
        val canFallbackToGoogleTranslate: Boolean = true
    ) : TranslationState
}

object OcrAndLensEngine {
    private const val TAG = "OcrAndLensEngine"

    private val languageIdentifier by lazy {
        LanguageIdentification.getClient()
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeDownloads = ConcurrentHashMap<String, Deferred<Result<Unit>>>()

    private val _packStatusFlow = MutableStateFlow<Map<String, ModelPackStatus>>(
        SupportedTargetLanguages.associate { it.code to ModelPackStatus.NOT_DOWNLOADED }
    )
    val packStatusFlow: StateFlow<Map<String, ModelPackStatus>> = _packStatusFlow.asStateFlow()

    private val _packErrorFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val packErrorFlow: StateFlow<Map<String, String>> = _packErrorFlow.asStateFlow()

    fun prewarm(context: Context) {
        try {
            com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(context.applicationContext)
            languageIdentifier
            refreshDownloadedPacksAsync()
            MoEOcrRouter.prewarm(context.applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit prewarm skipped: ${e.message}")
        }
    }

    fun refreshDownloadedPacksAsync() {
        engineScope.launch {
            refreshDownloadedPacks()
        }
    }

    suspend fun refreshDownloadedPacks() = withContext(Dispatchers.IO) {
        try {
            val modelManager = RemoteModelManager.getInstance()
            val downloaded = modelManager.getDownloadedModels(TranslateRemoteModel::class.java).awaitTask()
            val downloadedCodes = downloaded.map { it.language.lowercase(Locale.ROOT) }.toSet()

            _packStatusFlow.update { current ->
                val allKeys = (SupportedTargetLanguages.map { it.code } + downloadedCodes + current.keys).toSet()
                allKeys.associateWith { code ->
                    val existing = current[code]
                    when {
                        code in downloadedCodes -> ModelPackStatus.DOWNLOADED
                        existing == ModelPackStatus.DOWNLOADING -> ModelPackStatus.DOWNLOADING
                        existing == ModelPackStatus.ERROR -> ModelPackStatus.ERROR
                        else -> ModelPackStatus.NOT_DOWNLOADED
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query downloaded ML Kit packs: ${e.message}")
        }
    }

    fun startPackDownload(langCode: String) {
        engineScope.launch {
            ensureLanguagePackDownloaded(langCode)
        }
    }

    suspend fun ensureLanguagePackDownloaded(langCode: String): Result<Unit> {
        val mlTag = resolveMlKitTag(langCode)
            ?: return Result.failure(IllegalArgumentException("Unsupported ML Kit language: $langCode"))

        val modelManager = try {
            RemoteModelManager.getInstance()
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "ML Kit model manager unavailable"
            _packStatusFlow.update { it + (mlTag to ModelPackStatus.ERROR) }
            _packErrorFlow.update { it + (mlTag to msg) }
            return Result.failure(e)
        }

        val model = TranslateRemoteModel.Builder(mlTag).build()
        val alreadyDownloaded = try {
            modelManager.isModelDownloaded(model).awaitTask()
        } catch (_: Exception) {
            false
        }
        if (alreadyDownloaded) {
            _packStatusFlow.update { it + (mlTag to ModelPackStatus.DOWNLOADED) }
            _packErrorFlow.update { it - mlTag }
            return Result.success(Unit)
        }

        // Reuse any already-running download for this language even across overlay open/close
        val existingDeferred = activeDownloads[mlTag]
        if (existingDeferred != null && existingDeferred.isActive) {
            return existingDeferred.await()
        }

        val deferred = engineScope.async {
            _packStatusFlow.update { it + (mlTag to ModelPackStatus.DOWNLOADING) }
            _packErrorFlow.update { it - mlTag }
            try {
                val conditions = DownloadConditions.Builder().build()
                modelManager.download(model, conditions).awaitTask()
                _packStatusFlow.update { it + (mlTag to ModelPackStatus.DOWNLOADED) }
                _packErrorFlow.update { it - mlTag }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed downloading ML Kit pack for $mlTag", e)
                val friendly = e.localizedMessage ?: "Download failed (check internet or Google Play Services)"
                _packStatusFlow.update { it + (mlTag to ModelPackStatus.ERROR) }
                _packErrorFlow.update { it + (mlTag to friendly) }
                Result.failure(e)
            } finally {
                activeDownloads.remove(mlTag)
            }
        }
        activeDownloads[mlTag] = deferred
        return deferred.await()
    }

    fun deleteLanguagePack(langCode: String) {
        val mlTag = resolveMlKitTag(langCode) ?: return
        engineScope.launch {
            try {
                val model = TranslateRemoteModel.Builder(mlTag).build()
                RemoteModelManager.getInstance().deleteDownloadedModel(model).awaitTask()
                _packStatusFlow.update { it + (mlTag to ModelPackStatus.NOT_DOWNLOADED) }
                _packErrorFlow.update { it - mlTag }
            } catch (e: Exception) {
                Log.e(TAG, "Failed deleting ML Kit pack for $mlTag", e)
            }
        }
    }

    private fun resolveMlKitTag(langCode: String): String? {
        val cleaned = langCode.trim()
        return TranslateLanguage.fromLanguageTag(cleaned)
            ?: TranslateLanguage.fromLanguageTag(cleaned.substringBefore("-"))
    }

    suspend fun recognizeFullScreenLines(
        context: Context,
        bitmap: Bitmap,
        scriptMode: OcrScriptMode
    ): List<RecognizedTextLine> = analyzeFullScreen(context, bitmap, scriptMode).lines

    suspend fun analyzeFullScreen(
        context: Context,
        bitmap: Bitmap,
        scriptMode: OcrScriptMode
    ): ScreenOcrAnalysis = withContext(Dispatchers.Default) {
        try {
            val candidates = MoEOcrRouter.recognizeWithMoE(context, bitmap, scriptMode)
            buildScreenAnalysisFromCandidates(bitmap, candidates)
        } catch (e: Exception) {
            Log.e(TAG, "Full-screen OCR analysis failed", e)
            ScreenOcrAnalysis()
        }
    }

    suspend fun translateBlocksInline(
        blocks: List<InlineTextBlock>,
        targetLangCode: String,
        dominantLangHint: String?,
        onState: (InlineScreenTranslationState) -> Unit
    ) {
        val mlTarget = resolveMlKitTag(targetLangCode)
        if (mlTarget == null) {
            onState(
                InlineScreenTranslationState(
                    targetLangCode = targetLangCode,
                    errorMessage = "Unsupported target language ($targetLangCode) for offline ML Kit."
                )
            )
            return
        }
        if (blocks.isEmpty()) {
            onState(InlineScreenTranslationState(targetLangCode = targetLangCode))
            return
        }

        val currentStates = mutableMapOf<Int, InlineBlockStatus>()
        val sourcePerBlock = mutableMapOf<Int, String>()
        val uniqueMissingSources = linkedSetOf<String>()

        // Resolve source language per block
        for (block in blocks) {
            if (!block.text.any { it.isLetter() }) {
                currentStates[block.id] = InlineBlockStatus.SameLanguage
                continue
            }
            val rawTag = block.detectedLanguageCode ?: detectLanguageCode(block.text) ?: dominantLangHint
            val mlSource = rawTag?.let { resolveMlKitTag(it) }
            when {
                mlSource == null -> {
                    currentStates[block.id] = InlineBlockStatus.SameLanguage
                }
                mlSource == mlTarget -> {
                    currentStates[block.id] = InlineBlockStatus.SameLanguage
                }
                else -> {
                    sourcePerBlock[block.id] = mlSource
                    currentStates[block.id] = InlineBlockStatus.Translating
                    uniqueMissingSources.add(mlSource)
                }
            }
        }

        if (sourcePerBlock.isEmpty()) {
            onState(
                InlineScreenTranslationState(
                    targetLangCode = targetLangCode,
                    blockStates = currentStates.toMap()
                )
            )
            return
        }

        // Check which ML Kit packs need downloading
        val modelManager = try {
            RemoteModelManager.getInstance()
        } catch (e: Exception) {
            onState(
                InlineScreenTranslationState(
                    targetLangCode = targetLangCode,
                    errorMessage = e.localizedMessage ?: "ML Kit unavailable"
                )
            )
            return
        }

        val missingTags = mutableListOf<String>()
        val allNeededTags = (uniqueMissingSources + mlTarget).distinct()
        for (tag in allNeededTags) {
            val downloaded = try {
                modelManager.isModelDownloaded(TranslateRemoteModel.Builder(tag).build()).awaitTask()
            } catch (_: Exception) {
                false
            }
            if (!downloaded) {
                missingTags.add(tag)
            } else {
                _packStatusFlow.update { it + (tag to ModelPackStatus.DOWNLOADED) }
            }
        }

        if (missingTags.isNotEmpty()) {
            val missingLabel = missingTags.joinToString(" + ") { formatLanguageName(it) }
            onState(
                InlineScreenTranslationState(
                    targetLangCode = targetLangCode,
                    isDownloadingPacks = true,
                    missingPacksLabel = missingLabel,
                    isTranslating = true,
                    blockStates = currentStates.toMap()
                )
            )
            for (tag in missingTags) {
                val res = ensureLanguagePackDownloaded(tag)
                if (res.isFailure) {
                    val reason = res.exceptionOrNull()?.localizedMessage ?: "network or Play Services unavailable"
                    onState(
                        InlineScreenTranslationState(
                            targetLangCode = targetLangCode,
                            isDownloadingPacks = false,
                            isTranslating = false,
                            blockStates = currentStates.toMap(),
                            errorMessage = "Could not download ${formatLanguageName(tag)} offline pack ($reason)."
                        )
                    )
                    return
                }
            }
        }

        onState(
            InlineScreenTranslationState(
                targetLangCode = targetLangCode,
                isDownloadingPacks = false,
                isTranslating = true,
                blockStates = currentStates.toMap()
            )
        )

        // Reuse one Translator instance per unique source language for speed and low memory
        val translators = mutableMapOf<String, com.google.mlkit.nl.translate.Translator>()
        try {
            for ((blockId, mlSource) in sourcePerBlock) {
                val block = blocks.firstOrNull { it.id == blockId } ?: continue
                val translator = translators.getOrPut(mlSource) {
                    Translation.getClient(
                        TranslatorOptions.Builder()
                            .setSourceLanguage(mlSource)
                            .setTargetLanguage(mlTarget)
                            .build()
                    )
                }
                try {
                    val translated = translator.translate(block.text).awaitTask().trim()
                    currentStates[blockId] = InlineBlockStatus.Translated(
                        text = translated.ifBlank { block.text },
                        sourceLangName = formatLanguageName(mlSource)
                    )
                } catch (e: Exception) {
                    currentStates[blockId] = InlineBlockStatus.Error(
                        e.localizedMessage ?: "Translation failed"
                    )
                }
                onState(
                    InlineScreenTranslationState(
                        targetLangCode = targetLangCode,
                        isDownloadingPacks = false,
                        isTranslating = true,
                        blockStates = currentStates.toMap()
                    )
                )
            }
            onState(
                InlineScreenTranslationState(
                    targetLangCode = targetLangCode,
                    isDownloadingPacks = false,
                    isTranslating = false,
                    blockStates = currentStates.toMap()
                )
            )
        } finally {
            translators.values.forEach {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    suspend fun extractRegionOcr(
        context: Context,
        cropBitmap: Bitmap,
        cropRectInScreen: Rect,
        fullScreenLines: List<RecognizedTextLine>,
        scriptMode: OcrScriptMode
    ): OcrRegionResult = withContext(Dispatchers.Default) {
        // 1. Run high-precision MoE OCR directly on the cropped bitmap (upscale small crops for crispness)
        val workingBitmap = if (cropBitmap.width < 540 || cropBitmap.height < 220) {
            val scale = 2.0f
            Bitmap.createScaledBitmap(
                cropBitmap,
                (cropBitmap.width * scale).toInt().coerceAtLeast(1),
                (cropBitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            cropBitmap
        }

        val directTextLines = try {
            MoEOcrRouter.recognizeWithMoE(context, workingBitmap, scriptMode).map { it.text }
        } catch (e: Exception) {
            Log.e(TAG, "Cropped OCR failed", e)
            emptyList()
        } finally {
            if (workingBitmap !== cropBitmap) {
                workingBitmap.recycle()
            }
        }

        // 2. Also collect lines from the full screen whose center lies inside cropRectInScreen
        val intersectedLines = fullScreenLines
            .filter { line ->
                val cx = line.boundingBox.centerX()
                val cy = line.boundingBox.centerY()
                cropRectInScreen.contains(cx, cy) || overlapRatio(line.boundingBox, cropRectInScreen) >= 0.45f
            }
            .map { it.text }

        val chosenLines = when {
            directTextLines.isNotEmpty() -> directTextLines
            else -> intersectedLines
        }

        val combinedText = chosenLines.joinToString("\n").trim()
        val detectedCode = if (combinedText.isNotBlank()) {
            detectLanguageCode(combinedText)
        } else {
            null
        }
        val detectedName = detectedCode?.let { formatLanguageName(it) }

        OcrRegionResult(
            extractedText = combinedText,
            lineCount = chosenLines.size,
            detectedLanguageCode = detectedCode,
            detectedLanguageName = detectedName
        )
    }

    suspend fun detectLanguageCode(text: String): String? = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext null
        try {
            val tag = languageIdentifier.identifyLanguage(text).awaitTask()
            if (tag == "und") null else tag
        } catch (e: Exception) {
            Log.e(TAG, "Language ID failed", e)
            null
        }
    }

    suspend fun translateText(
        text: String,
        sourceLangCodeHint: String?,
        targetLangCode: String,
        onState: (TranslationState) -> Unit
    ) {
        if (text.isBlank()) {
            onState(TranslationState.Idle)
            return
        }

        val resolvedSourceTag = sourceLangCodeHint ?: detectLanguageCode(text) ?: "en"
        val mlSource = resolveMlKitTag(resolvedSourceTag)
        val mlTarget = resolveMlKitTag(targetLangCode)

        if (mlSource == null || mlTarget == null) {
            onState(
                TranslationState.Error(
                    message = "Language pair ($resolvedSourceTag -> $targetLangCode) is not supported offline by ML Kit.",
                    canFallbackToGoogleTranslate = true
                )
            )
            return
        }

        val sourceLabel = formatLanguageName(mlSource)
        val targetLabel = formatLanguageName(mlTarget)

        if (mlSource == mlTarget) {
            onState(TranslationState.SameLanguage(sourceLabel))
            return
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(mlSource)
            .setTargetLanguage(mlTarget)
            .build()
        val translator = Translation.getClient(options)

        try {
            val modelManager = RemoteModelManager.getInstance()
            val sourceDownloaded = modelManager.isModelDownloaded(
                TranslateRemoteModel.Builder(mlSource).build()
            ).awaitTask()
            val targetDownloaded = modelManager.isModelDownloaded(
                TranslateRemoteModel.Builder(mlTarget).build()
            ).awaitTask()

            _packStatusFlow.update { current ->
                current + mapOf(
                    mlSource to if (sourceDownloaded) ModelPackStatus.DOWNLOADED else (current[mlSource] ?: ModelPackStatus.NOT_DOWNLOADED),
                    mlTarget to if (targetDownloaded) ModelPackStatus.DOWNLOADED else (current[mlTarget] ?: ModelPackStatus.NOT_DOWNLOADED)
                )
            }

            if (!sourceDownloaded || !targetDownloaded) {
                val missingNames = buildList {
                    if (!sourceDownloaded) add(sourceLabel)
                    if (!targetDownloaded) add(targetLabel)
                }
                onState(
                    TranslationState.DownloadingModel(
                        languagePairLabel = "$sourceLabel -> $targetLabel",
                        missingPacksLabel = missingNames.joinToString(" + ")
                    )
                )
                if (!sourceDownloaded) {
                    val res = ensureLanguagePackDownloaded(mlSource)
                    if (res.isFailure) {
                        val reason = res.exceptionOrNull()?.localizedMessage ?: "network or Play Services unavailable"
                        onState(
                            TranslationState.Error(
                                message = "Could not download $sourceLabel offline pack ($reason).",
                                canFallbackToGoogleTranslate = true
                            )
                        )
                        return
                    }
                }
                if (!targetDownloaded) {
                    val res = ensureLanguagePackDownloaded(mlTarget)
                    if (res.isFailure) {
                        val reason = res.exceptionOrNull()?.localizedMessage ?: "network or Play Services unavailable"
                        onState(
                            TranslationState.Error(
                                message = "Could not download $targetLabel offline pack ($reason).",
                                canFallbackToGoogleTranslate = true
                            )
                        )
                        return
                    }
                }
            }

            onState(TranslationState.Translating)
            val translated = translator.translate(text).awaitTask()
            onState(
                TranslationState.Success(
                    translatedText = translated,
                    sourceLangName = sourceLabel,
                    targetLangName = targetLabel
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed", e)
            onState(
                TranslationState.Error(
                    message = e.localizedMessage ?: "Offline ML Kit translation failed.",
                    canFallbackToGoogleTranslate = true
                )
            )
        } finally {
            translator.close()
        }
    }

    fun launchGoogleLensLookup(context: Context, croppedBitmap: Bitmap): Boolean {
        return try {
            val cropsDir = File(context.cacheDir, "lens_crops").apply { mkdirs() }
            val cropFile = File(cropsDir, "quickcircle_lens_crop.png")
            FileOutputStream(cropFile).use { out ->
                croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cropFile
            )

            val pm = context.packageManager
            val baseIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Prefer dedicated Google Lens or Google Search app if installed
            val preferredPackages = listOf(
                "com.google.ar.lens",
                "com.google.android.googlequicksearchbox"
            )
            for (pkg in preferredPackages) {
                val targeted = Intent(baseIntent).setPackage(pkg)
                if (targeted.resolveActivity(pm) != null) {
                    context.startActivity(targeted)
                    return true
                }
            }

            // Fallback to system share sheet for any installed Lens / visual search app
            val chooser = Intent.createChooser(baseIntent, "Lookup selection with…").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Lens lookup", e)
            false
        }
    }

    fun launchWebSearch(context: Context, query: String): Boolean {
        if (query.isBlank()) return false
        return try {
            val url = "https://www.google.com/search?q=${Uri.encode(query.trim())}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open web search", e)
            false
        }
    }

    fun isGoogleTranslateAppInstalled(context: Context): Boolean {
        val pm = context.packageManager
        val viewCheck = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://translate.google.com/?sl=auto&tl=en&text=test&op=translate")
        ).setPackage(GOOGLE_TRANSLATE_PACKAGE)
        if (viewCheck.resolveActivity(pm) != null) return true

        val sendCheck = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(GOOGLE_TRANSLATE_PACKAGE)
        }
        return sendCheck.resolveActivity(pm) != null
    }

    fun launchGoogleTranslateFallback(
        context: Context,
        text: String,
        targetLangCode: String,
        sourceLangCodeHint: String? = null
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false

        val sl = sourceLangCodeHint?.substringBefore("-")?.lowercase(Locale.ROOT)?.ifBlank { "auto" } ?: "auto"
        val tl = targetLangCode.substringBefore("-").lowercase(Locale.ROOT).ifBlank { "en" }
        val translateUri = Uri.parse(
            "https://translate.google.com/?sl=${Uri.encode(sl)}&tl=${Uri.encode(tl)}&text=${Uri.encode(trimmed)}&op=translate"
        )

        val pm = context.packageManager

        // 1. Prefer Google Translate app deep link (preserves both source and target languages)
        try {
            val appViewIntent = Intent(Intent.ACTION_VIEW, translateUri).apply {
                setPackage(GOOGLE_TRANSLATE_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (appViewIntent.resolveActivity(pm) != null) {
                context.startActivity(appViewIntent)
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Google Translate app ACTION_VIEW failed: ${e.message}")
        }

        // 2. Fallback to Google Translate app ACTION_PROCESS_TEXT / ACTION_SEND
        try {
            val processTextIntent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_PROCESS_TEXT, trimmed)
                putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                setPackage(GOOGLE_TRANSLATE_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (processTextIntent.resolveActivity(pm) != null) {
                context.startActivity(processTextIntent)
                return true
            }

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, trimmed)
                setPackage(GOOGLE_TRANSLATE_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (sendIntent.resolveActivity(pm) != null) {
                context.startActivity(sendIntent)
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Google Translate app share intent failed: ${e.message}")
        }

        // 3. Final fallback: Google Translate in the system web browser
        return try {
            val browserIntent = Intent(Intent.ACTION_VIEW, translateUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Google Translate in browser", e)
            false
        }
    }

    fun launchWebTranslate(
        context: Context,
        text: String,
        targetLangCode: String,
        sourceLangCodeHint: String? = null
    ): Boolean = launchGoogleTranslateFallback(
        context = context,
        text = text,
        targetLangCode = targetLangCode,
        sourceLangCodeHint = sourceLangCodeHint
    )

    private const val GOOGLE_TRANSLATE_PACKAGE = "com.google.android.apps.translate"

    fun formatLanguageName(code: String): String {
        val clean = code.substringBefore("-").lowercase(Locale.ROOT)
        return SupportedTargetLanguages.firstOrNull { it.code == clean }?.label
            ?: Locale.forLanguageTag(clean).getDisplayLanguage(Locale.ENGLISH)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
                .ifBlank { clean.uppercase(Locale.ROOT) }
    }

    private suspend fun buildScreenAnalysisFromCandidates(
        bitmap: Bitmap,
        candidates: List<CandidateOcrLine>
    ): ScreenOcrAnalysis {
        if (candidates.isEmpty()) return ScreenOcrAnalysis()

        // Split fused reading-order lines into cohesive visual groups so paragraphs translate
        // as full sentences while separate buttons/cards stay isolated.
        val visualGroups = mutableListOf<List<CandidateOcrLine>>()
        var currentGroup = mutableListOf<CandidateOcrLine>()
        for (line in candidates) {
            val rect = line.boundingBox
            if (currentGroup.isEmpty()) {
                currentGroup.add(line)
            } else {
                val prevRect = currentGroup.last().boundingBox
                val avgH = currentGroup.map { it.boundingBox.height() }.average().toFloat().coerceAtLeast(8f)
                val curH = rect.height().toFloat().coerceAtLeast(8f)
                val vGap = (rect.top - prevRect.bottom).toFloat()
                val hOverlap = minOf(rect.right, prevRect.right) - maxOf(rect.left, prevRect.left)
                val minWidth = minOf(rect.width(), prevRect.width()).coerceAtLeast(1)
                val horizontallyAligned = hOverlap > -avgH * 1.5f && hOverlap.toFloat() / minWidth >= -0.25f
                val sameExpert = currentGroup.last().expert == line.expert
                val sizeRatio = curH / avgH
                val sameParagraph = vGap in (-avgH * 0.35f)..(avgH * 0.95f) &&
                    sizeRatio in 0.62f..1.6f &&
                    horizontallyAligned &&
                    sameExpert
                if (sameParagraph) {
                    currentGroup.add(line)
                } else {
                    visualGroups.add(currentGroup)
                    currentGroup = mutableListOf(line)
                }
            }
        }
        if (currentGroup.isNotEmpty()) {
            visualGroups.add(currentGroup)
        }

        val linesOut = mutableListOf<RecognizedTextLine>()
        val wordsOut = mutableListOf<RecognizedTextWord>()
        val blocksOut = mutableListOf<InlineTextBlock>()

        var nextLineIdx = 0
        var nextWordIdx = 0

        for ((blockId, groupLines) in visualGroups.withIndex()) {
            val blockBounds = Rect(groupLines.first().boundingBox)
            var heightSum = 0f
            val joinedPieces = mutableListOf<String>()

            for (candLine in groupLines) {
                val lineBox = candLine.boundingBox
                blockBounds.union(lineBox)
                heightSum += lineBox.height().toFloat()
                joinedPieces.add(candLine.text.trim())

                val lineWords = mutableListOf<RecognizedTextWord>()
                for (w in candLine.words) {
                    val word = RecognizedTextWord(
                        globalIndex = nextWordIdx++,
                        lineIndex = nextLineIdx,
                        blockId = blockId,
                        text = w.text,
                        boundingBox = w.boundingBox
                    )
                    lineWords.add(word)
                    wordsOut.add(word)
                }

                linesOut.add(
                    RecognizedTextLine(
                        text = candLine.text,
                        boundingBox = lineBox,
                        lineIndex = nextLineIdx,
                        blockId = blockId,
                        words = lineWords
                    )
                )
                nextLineIdx++
            }

            val blockText = joinLinesIntelligently(joinedPieces)
            val langTag = if (blockText.length >= 3) detectLanguageCode(blockText) else null
            val (bgArgb, fgArgb) = sampleBlockColors(bitmap, blockBounds)
            blocksOut.add(
                InlineTextBlock(
                    id = blockId,
                    text = blockText,
                    boundingBox = blockBounds,
                    lineCount = groupLines.size,
                    averageLineHeightPx = (heightSum / groupLines.size.coerceAtLeast(1)).coerceAtLeast(12f),
                    detectedLanguageCode = langTag,
                    backgroundColorArgb = bgArgb,
                    foregroundColorArgb = fgArgb
                )
            )
        }

        val fullScreenSample = blocksOut.take(8).joinToString(" ") { it.text }
        val dominantLang = if (fullScreenSample.isNotBlank()) detectLanguageCode(fullScreenSample) else null

        val enrichedBlocks = blocksOut.map { b ->
            if (b.detectedLanguageCode == null && dominantLang != null) {
                b.copy(detectedLanguageCode = dominantLang)
            } else {
                b
            }
        }

        return ScreenOcrAnalysis(
            lines = linesOut,
            words = wordsOut,
            blocks = enrichedBlocks,
            dominantLanguageCode = dominantLang
        )
    }

    private fun joinLinesIntelligently(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        for (i in lines.indices) {
            val part = lines[i]
            if (part.isBlank()) continue
            if (sb.isNotEmpty()) {
                val prevChar = sb[sb.length - 1]
                val nextChar = part[0]
                val bothCjk = isCjkChar(prevChar) && isCjkChar(nextChar)
                if (!bothCjk) sb.append(' ')
            }
            sb.append(part)
        }
        return sb.toString()
    }

    private fun isCjkChar(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3040..0x30FF || // Hiragana & Katakana
            code in 0x4E00..0x9FFF ||    // CJK Unified Ideographs
            code in 0x3400..0x4DBF ||
            code in 0xAC00..0xD7AF       // Hangul
    }

    private fun sampleBlockColors(bitmap: Bitmap, rect: Rect): Pair<Int, Int> {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 2 || h <= 2) return 0xFF141419.toInt() to 0xFFF8FAFC.toInt()

        val l = rect.left.coerceIn(0, w - 1)
        val t = rect.top.coerceIn(0, h - 1)
        val r = rect.right.coerceIn(l, w - 1)
        val b = rect.bottom.coerceIn(t, h - 1)

        val borderOut = 3
        val topY = (t - borderOut).coerceIn(0, h - 1)
        val botY = (b + borderOut).coerceIn(0, h - 1)
        val leftX = (l - borderOut).coerceIn(0, w - 1)
        val rightX = (r + borderOut).coerceIn(0, w - 1)
        val midX = ((l + r) / 2).coerceIn(0, w - 1)
        val midY = ((t + b) / 2).coerceIn(0, h - 1)
        val q1X = (l + (r - l) / 4).coerceIn(0, w - 1)
        val q3X = (l + (r - l) * 3 / 4).coerceIn(0, w - 1)

        val bgCoords = listOf(
            leftX to topY, q1X to topY, midX to topY, q3X to topY, rightX to topY,
            leftX to botY, q1X to botY, midX to botY, q3X to botY, rightX to botY,
            leftX to midY, rightX to midY
        )
        val rs = IntArray(bgCoords.size)
        val gs = IntArray(bgCoords.size)
        val bs = IntArray(bgCoords.size)
        for (i in bgCoords.indices) {
            val px = bitmap.getPixel(bgCoords[i].first, bgCoords[i].second)
            rs[i] = AndroidColor.red(px)
            gs[i] = AndroidColor.green(px)
            bs[i] = AndroidColor.blue(px)
        }
        rs.sort()
        gs.sort()
        bs.sort()
        val mid = bgCoords.size / 2
        val bgColor = AndroidColor.rgb(rs[mid], gs[mid], bs[mid])

        // Pick high-contrast foreground color by sampling interior pixels vs WCAG fallback
        var bestFg = bgColor
        var bestContrast = 1.0
        for (row in 1..3) {
            val sy = (t + (b - t) * row / 4).coerceIn(0, h - 1)
            for (col in 1..4) {
                val sx = (l + (r - l) * col / 5).coerceIn(0, w - 1)
                val px = AndroidColor.rgb(
                    AndroidColor.red(bitmap.getPixel(sx, sy)),
                    AndroidColor.green(bitmap.getPixel(sx, sy)),
                    AndroidColor.blue(bitmap.getPixel(sx, sy))
                )
                val c = ColorUtils.calculateContrast(px, bgColor)
                if (c > bestContrast) {
                    bestContrast = c
                    bestFg = px
                }
            }
        }

        val finalFg = if (bestContrast >= 4.5) {
            bestFg
        } else {
            val whiteContrast = ColorUtils.calculateContrast(0xFFF8FAFC.toInt(), bgColor)
            val darkContrast = ColorUtils.calculateContrast(0xFF0E1015.toInt(), bgColor)
            if (whiteContrast >= darkContrast) 0xFFF8FAFC.toInt() else 0xFF0E1015.toInt()
        }

        return bgColor to finalFg
    }

    private fun extractLinesFromMlKitResult(visionText: Text): List<RecognizedTextLine> {
        val lines = mutableListOf<RecognizedTextLine>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (line.text.isNotBlank()) {
                    lines.add(RecognizedTextLine(text = line.text, boundingBox = box))
                }
            }
        }
        return lines
    }

    private fun overlapRatio(a: Rect, b: Rect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersectionArea = (right - left) * (bottom - top).toFloat()
        val aArea = (a.width() * a.height()).coerceAtLeast(1).toFloat()
        return intersectionArea / aArea
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { exception ->
            if (cont.isActive) cont.resumeWithException(exception)
        }
        addOnCanceledListener {
            cont.cancel()
        }
    }
}
