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
    val foregroundColorArgb: Int,
    val sourceLines: List<String> = emptyList()
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

    fun prewarmLatin(context: Context) {
        try {
            com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(context.applicationContext)
            languageIdentifier
            refreshDownloadedPacksAsync()
            MoEOcrRouter.prewarmLatinOnly(context.applicationContext)
        } catch (e: Throwable) {
            Log.w(TAG, "ML Kit Latin prewarm skipped: ${e.message}")
        }
    }

    fun prewarmMoEOnInvoke(context: Context, delayMs: Long = 160L) {
        try {
            com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(context.applicationContext)
            MoEOcrRouter.prewarmMoEOnInvoke(context.applicationContext, delayMs)
        } catch (e: Throwable) {
            Log.w(TAG, "MoE invoke prewarm skipped: ${e.message}")
        }
    }

    fun prewarm(context: Context) {
        prewarmLatin(context)
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

    /**
     * Ultra-fast first-pass Latin scan (~80-160ms): returns selectable words & lines immediately
     * using only the prewarmed Latin model without running MoE or blocking on LanguageID.
     */
    suspend fun analyzeFullScreenFastLatin(
        bitmap: Bitmap
    ): ScreenOcrAnalysis = withContext(Dispatchers.Default) {
        try {
            val candidates = MoEOcrRouter.recognizeFastLatin(bitmap)
            buildScreenAnalysisFromCandidates(
                bitmap = bitmap,
                candidates = candidates,
                enrichLanguagesAndColors = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Fast Latin full-screen OCR failed", e)
            ScreenOcrAnalysis()
        }
    }

    /**
     * Progressive screen OCR:
     * Emits Stage 1 (fast ML Kit word/line geometry without blocking on LanguageID/pixel sampling)
     * in ~100–250ms, then emits Stage 2 once any extra Tesseract scripts finish merging.
     */
    suspend fun analyzeFullScreenProgressive(
        context: Context,
        bitmap: Bitmap,
        scriptMode: OcrScriptMode,
        onUpdate: suspend (analysis: ScreenOcrAnalysis, isFinal: Boolean) -> Unit
    ) = withContext(Dispatchers.Default) {
        try {
            MoEOcrRouter.recognizeWithMoEProgressive(context, bitmap, scriptMode) { candidates, isFinal ->
                val fastAnalysis = buildScreenAnalysisFromCandidates(
                    bitmap = bitmap,
                    candidates = candidates,
                    enrichLanguagesAndColors = false
                )
                onUpdate(fastAnalysis, isFinal)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Progressive full-screen OCR failed", e)
            onUpdate(ScreenOcrAnalysis(), true)
        }
    }

    suspend fun analyzeFullScreen(
        context: Context,
        bitmap: Bitmap,
        scriptMode: OcrScriptMode
    ): ScreenOcrAnalysis = withContext(Dispatchers.Default) {
        try {
            val candidates = MoEOcrRouter.recognizeWithMoE(context, bitmap, scriptMode)
            buildScreenAnalysisFromCandidates(
                bitmap = bitmap,
                candidates = candidates,
                enrichLanguagesAndColors = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Full-screen OCR analysis failed", e)
            ScreenOcrAnalysis()
        }
    }

    /**
     * Computes perimeter-matched card colors and parallel language tags only when Inline Translate runs.
     */
    suspend fun enrichAnalysisForInlineTranslate(
        bitmap: Bitmap,
        analysis: ScreenOcrAnalysis
    ): ScreenOcrAnalysis = withContext(Dispatchers.Default) {
        if (analysis.blocks.isEmpty()) return@withContext analysis

        val langResults = kotlinx.coroutines.coroutineScope {
            analysis.blocks.map { block ->
                async(Dispatchers.Default) {
                    val cleanedForLang = cleanTextForLanguageId(block.text)
                    val existing = block.detectedLanguageCode
                    val lang = existing
                        ?: fastScriptLangHint(cleanedForLang)
                        ?: if (cleanedForLang.length >= 3) detectLanguageCode(cleanedForLang) else null
                    val colors = sampleBlockColors(bitmap, block.boundingBox)
                    Triple(block.id, lang, colors)
                }
            }.map { it.await() }
        }.associateBy { it.first }

        val fullScreenSample = analysis.blocks.take(8).joinToString(" ") { cleanTextForLanguageId(it.text) }.trim()
        val dominantLang = analysis.dominantLanguageCode
            ?: fastScriptLangHint(fullScreenSample)
            ?: if (fullScreenSample.isNotBlank()) detectLanguageCode(fullScreenSample) else null

        val updatedBlocks = analysis.blocks.map { block ->
            val info = langResults[block.id]
            val resolvedLang = info?.second ?: dominantLang
            val colors = info?.third
            block.copy(
                detectedLanguageCode = resolvedLang,
                backgroundColorArgb = colors?.first ?: block.backgroundColorArgb,
                foregroundColorArgb = colors?.second ?: block.foregroundColorArgb
            )
        }
        analysis.copy(blocks = updatedBlocks, dominantLanguageCode = dominantLang)
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

        // Resolve source language per block in parallel (using 0ms script heuristics on quote-cleaned text first)
        val detectedTags = kotlinx.coroutines.coroutineScope {
            blocks.map { block ->
                async(Dispatchers.Default) {
                    val cleaned = cleanTextForLanguageId(block.text)
                    val tag = if (!cleaned.any { it.isLetter() }) {
                        null
                    } else {
                        block.detectedLanguageCode
                            ?: fastScriptLangHint(cleaned)
                            ?: detectLanguageCode(cleaned)
                            ?: dominantLangHint
                    }
                    block.id to tag
                }
            }.map { it.await() }.toMap()
        }

        for (block in blocks) {
            if (!block.text.any { it.isLetter() }) {
                currentStates[block.id] = InlineBlockStatus.SameLanguage
                continue
            }
            val rawTag = detectedTags[block.id] ?: dominantLangHint
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
                    val translated = translatePreservingQuoteLines(
                        translator = translator,
                        rawText = block.text,
                        sourceLines = block.sourceLines
                    )
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
        scriptMode: OcrScriptMode,
        useMoE: Boolean = false
    ): OcrRegionResult = withContext(Dispatchers.Default) {
        // 1. Run fast Latin OCR (or full MoE when requested/explicit non-Latin script selected)
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

        val runFullMoE = useMoE || (scriptMode != OcrScriptMode.LATIN && scriptMode != OcrScriptMode.AUTO_MOE)
        val directTextLines = try {
            val candidates = if (runFullMoE) {
                MoEOcrRouter.recognizeWithMoE(context, workingBitmap, scriptMode)
            } else {
                MoEOcrRouter.recognizeFastLatin(workingBitmap)
            }
            candidates.map { it.text }
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
        val cleaned = cleanTextForLanguageId(text)
        if (cleaned.isBlank()) return@withContext null
        try {
            val tag = languageIdentifier.identifyLanguage(cleaned).awaitTask()
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
            val translated = translatePreservingQuoteLines(
                translator = translator,
                rawText = text,
                sourceLines = text.split('\n')
            )
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

    fun buildWebSearchIntents(context: Context, query: String): List<Intent> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val intents = mutableListOf<Intent>()

        // 1. Prefer Google App in-place / pop-up search sheet via ACTION_WEB_SEARCH
        intents.add(
            Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, trimmed)
                setPackage(GOOGLE_QUICK_SEARCH_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        // 2. Generic system ACTION_WEB_SEARCH (in-app / pop-up search handler)
        intents.add(
            Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, trimmed)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        // 3. Fallback to browser Google Search URL
        val url = "https://www.google.com/search?q=${Uri.encode(trimmed)}"
        val browserPkg = try {
            resolvePreferredBrowserPackage(context)
        } catch (_: Exception) {
            null
        }
        if (browserPkg != null) {
            intents.add(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    setPackage(browserPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
        intents.add(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        return intents
    }

    fun launchWebSearch(context: Context, query: String): Boolean {
        val candidates = buildWebSearchIntents(context, query)
        if (candidates.isEmpty()) return false
        val pm = context.packageManager
        for (intent in candidates) {
            try {
                if (intent.resolveActivity(pm) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Web search candidate failed (${intent.action}/${intent.`package`}): ${e.message}")
            }
        }
        return try {
            context.startActivity(candidates.last())
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

    fun buildGoogleTranslateWebUri(
        text: String,
        targetLangCode: String,
        sourceLangCodeHint: String? = null
    ): Uri {
        val safeText = text.trim().take(1800)
        val sl = normalizeTranslateWebLang(sourceLangCodeHint, fallback = "auto")
        val tl = normalizeTranslateWebLang(targetLangCode, fallback = "en")
        return Uri.Builder()
            .scheme("https")
            .authority("translate.google.com")
            .path("/")
            .appendQueryParameter("sl", sl)
            .appendQueryParameter("tl", tl)
            .appendQueryParameter("text", safeText)
            .appendQueryParameter("op", "translate")
            .build()
    }

    private fun normalizeTranslateWebLang(code: String?, fallback: String): String {
        val clean = code?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (clean.isBlank() || clean == "und" || clean == "auto") return fallback
        return when (clean) {
            "zh", "zh-hans", "zh-cn" -> "zh-CN"
            "zh-hant", "zh-tw", "zh-hk" -> "zh-TW"
            else -> clean.substringBefore("-")
        }
    }

    @Suppress("DEPRECATION")
    private fun resolvePreferredBrowserPackage(context: Context): String? {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))
            .addCategory(Intent.CATEGORY_BROWSABLE)

        fun isBrowserPkg(pkg: String?): Boolean {
            return !pkg.isNullOrBlank() &&
                pkg != "android" &&
                pkg != GOOGLE_TRANSLATE_PACKAGE &&
                pkg != GOOGLE_QUICK_SEARCH_PACKAGE &&
                !pkg.contains("resolver", ignoreCase = true)
        }

        val defaultMatch = pm.resolveActivity(probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        if (isBrowserPkg(defaultMatch)) {
            return defaultMatch
        }

        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.content.pm.PackageManager.MATCH_ALL
        } else {
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        }
        val candidates = pm.queryIntentActivities(probe, flags)
            .mapNotNull { it.activityInfo?.packageName }
            .filter { isBrowserPkg(it) }
        if (candidates.isNotEmpty()) {
            return KNOWN_BROWSER_PACKAGES.firstOrNull { it in candidates } ?: candidates.first()
        }

        for (pkg in KNOWN_BROWSER_PACKAGES) {
            val targetedProbe = Intent(probe).setPackage(pkg)
            if (targetedProbe.resolveActivity(pm) != null) {
                return pkg
            }
        }
        return null
    }

    fun launchGoogleTranslateFallback(
        context: Context,
        text: String,
        targetLangCode: String,
        sourceLangCodeHint: String? = null
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false

        val cleanSourceHint = sourceLangCodeHint
            ?: fastScriptLangHint(cleanTextForLanguageId(trimmed))

        val translateUri = buildGoogleTranslateWebUri(
            text = trimmed,
            targetLangCode = targetLangCode,
            sourceLangCodeHint = cleanSourceHint
        )

        // 1. Target an explicit browser package + Custom Tab overlay bundle so Android Verified
        // App Links NEVER intercept translate.google.com into the native Translate app (which drops sl/tl/text).
        val browserPkg = try {
            resolvePreferredBrowserPackage(context)
        } catch (_: Exception) {
            null
        }
        if (browserPkg != null) {
            try {
                val customTabsExtras = android.os.Bundle().apply {
                    putBinder("android.support.customtabs.extra.SESSION", null)
                }
                val browserIntent = Intent(Intent.ACTION_VIEW, translateUri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    setPackage(browserPkg)
                    putExtras(customTabsExtras)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Browser-targeted Translate Web failed ($browserPkg): ${e.message}")
            }
        }

        // 2. If Chrome/browsers aren't found cleanly, prefer native Translate PROCESS_TEXT / SEND
        // rather than un-packaged ACTION_VIEW when Google Translate app is installed (so text isn't wiped).
        val pm = context.packageManager
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
            Log.w(TAG, "Google Translate PROCESS_TEXT/SEND failed: ${e.message}")
        }

        // 3. Final generic VIEW fallback
        return try {
            val webIntent = Intent(Intent.ACTION_VIEW, translateUri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (webIntent.resolveActivity(pm) != null) {
                context.startActivity(webIntent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Google Translate", e)
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

    private const val GOOGLE_QUICK_SEARCH_PACKAGE = "com.google.android.googlequicksearchbox"
    private val KNOWN_BROWSER_PACKAGES = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.brave.browser",
        "org.mozilla.firefox",
        "com.sec.android.app.sbrowser",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.duckduckgo.mobile.android"
    )

    private const val GOOGLE_TRANSLATE_PACKAGE = "com.google.android.apps.translate"

    fun formatLanguageName(code: String): String {
        val clean = code.substringBefore("-").lowercase(Locale.ROOT)
        return SupportedTargetLanguages.firstOrNull { it.code == clean }?.label
            ?: Locale.forLanguageTag(clean).getDisplayLanguage(Locale.ENGLISH)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
                .ifBlank { clean.uppercase(Locale.ROOT) }
    }

    private fun fastScriptLangHint(text: String): String? {
        var kana = 0
        var hangul = 0
        var arabic = 0
        var devanagari = 0
        var han = 0
        var cyrillic = 0
        var latin = 0
        for (ch in text) {
            val c = ch.code
            when {
                c in 0x3040..0x30FF -> kana++
                c in 0xAC00..0xD7AF -> hangul++
                c in 0x0600..0x06FF || c in 0x0750..0x077F -> arabic++
                c in 0x0900..0x097F -> devanagari++
                c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF -> han++
                c in 0x0400..0x04FF -> cyrillic++
                ch.isLetter() && c < 0x0250 -> latin++
            }
        }
        return when {
            kana > 0 -> "ja"
            hangul > 0 -> "ko"
            arabic > 0 -> "ar"
            devanagari > 0 -> "hi"
            han > 0 && latin == 0 -> "zh"
            cyrillic > 0 && text.length <= 5 -> "ru"
            else -> null
        }
    }

    private suspend fun buildScreenAnalysisFromCandidates(
        bitmap: Bitmap,
        candidates: List<CandidateOcrLine>,
        enrichLanguagesAndColors: Boolean = true
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
            val cleanedForLang = cleanTextForLanguageId(blockText)
            val langTag = if (enrichLanguagesAndColors && cleanedForLang.length >= 3) {
                fastScriptLangHint(cleanedForLang) ?: detectLanguageCode(cleanedForLang)
            } else {
                fastScriptLangHint(cleanedForLang)
            }
            val (bgArgb, fgArgb) = if (enrichLanguagesAndColors) {
                sampleBlockColors(bitmap, blockBounds)
            } else {
                0xFF141419.toInt() to 0xFFF8FAFC.toInt()
            }
            blocksOut.add(
                InlineTextBlock(
                    id = blockId,
                    text = blockText,
                    boundingBox = blockBounds,
                    lineCount = groupLines.size,
                    averageLineHeightPx = (heightSum / groupLines.size.coerceAtLeast(1)).coerceAtLeast(12f),
                    detectedLanguageCode = langTag,
                    backgroundColorArgb = bgArgb,
                    foregroundColorArgb = fgArgb,
                    sourceLines = joinedPieces.toList()
                )
            )
        }

        val dominantLang = if (enrichLanguagesAndColors) {
            val fullScreenSample = blocksOut.take(8).joinToString(" ") { cleanTextForLanguageId(it.text) }.trim()
            if (fullScreenSample.isNotBlank()) {
                fastScriptLangHint(fullScreenSample) ?: detectLanguageCode(fullScreenSample)
            } else {
                null
            }
        } else {
            null
        }

        val finalBlocks = if (dominantLang != null) {
            blocksOut.map { b ->
                if (b.detectedLanguageCode == null) b.copy(detectedLanguageCode = dominantLang) else b
            }
        } else {
            blocksOut
        }

        return ScreenOcrAnalysis(
            lines = linesOut,
            words = wordsOut,
            blocks = finalBlocks,
            dominantLanguageCode = dominantLang
        )
    }

    fun cleanTextForLanguageId(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.lineSequence()
            .map { line ->
                line.trim()
                    .replace(Regex("^[>›•\\-*]+\\s*"), "")
                    .replace(Regex("@[\\w.]+"), "")
                    .trim()
            }
            .filter { it.any { ch -> ch.isLetter() } }
            .joinToString(" ")
            .trim()
    }

    fun splitPrefixForTranslation(line: String): Pair<String, String> {
        val trimmed = line.trim()
        val match = Regex("^([>›]+|[•\\-*]|\\d+[.)])\\s*(.*)$").find(trimmed)
        if (match != null) {
            val marker = match.groupValues[1]
            val body = match.groupValues[2].trim()
            if (body.isNotEmpty()) {
                return "$marker " to body
            }
        }
        return "" to trimmed
    }

    fun shouldTranslateLineByLine(lines: List<String>): Boolean {
        if (lines.size <= 1) return false
        val quoteOrBulletCount = lines.count { line ->
            val t = line.trimStart()
            t.startsWith(">") || t.startsWith("›") || t.startsWith("•") ||
                t.startsWith("- ") || t.startsWith("* ")
        }
        if (quoteOrBulletCount >= 2) return true
        // Also keep separate lines when none end cleanly with connectors and every line is a short clause
        return lines.all { it.trim().length <= 44 } && lines.count { it.trim().isNotEmpty() } >= 2
    }

    private suspend fun translatePreservingQuoteLines(
        translator: com.google.mlkit.nl.translate.Translator,
        rawText: String,
        sourceLines: List<String>
    ): String {
        val effectiveLines = (if (sourceLines.isNotEmpty()) sourceLines else rawText.split('\n'))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (effectiveLines.size <= 1) {
            val (prefix, body) = splitPrefixForTranslation(rawText)
            if (body.isBlank()) return rawText.trim()
            val out = translator.translate(body).awaitTask().trim()
            return if (prefix.isNotEmpty()) "$prefix${out.ifBlank { body }}" else out
        }

        if (shouldTranslateLineByLine(effectiveLines)) {
            val outLines = effectiveLines.map { line ->
                val (prefix, body) = splitPrefixForTranslation(line)
                if (body.isBlank()) {
                    line
                } else {
                    val out = translator.translate(body).awaitTask().trim()
                    if (prefix.isNotEmpty()) "$prefix${out.ifBlank { body }}" else out
                }
            }
            return outLines.joinToString("\n")
        }

        val joined = joinLinesIntelligently(effectiveLines)
        val (prefix, body) = splitPrefixForTranslation(joined)
        if (body.isBlank()) return joined
        val out = translator.translate(body).awaitTask().trim()
        return if (prefix.isNotEmpty()) "$prefix${out.ifBlank { body }}" else out
    }

    private fun joinLinesIntelligently(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        if (shouldTranslateLineByLine(lines)) {
            return lines.joinToString("\n") { it.trim() }
        }
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
