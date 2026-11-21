package com.example.engine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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

data class RecognizedTextLine(
    val text: String,
    val boundingBox: Rect
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

    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val japaneseRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

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
            // Touch lazy instances to start native ML Kit initialization off the critical draw path
            latinRecognizer
            japaneseRecognizer
            languageIdentifier
            refreshDownloadedPacksAsync()
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
        bitmap: Bitmap,
        scriptMode: OcrScriptMode
    ): List<RecognizedTextLine> = withContext(Dispatchers.Default) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = when (scriptMode) {
                OcrScriptMode.LATIN -> latinRecognizer
                OcrScriptMode.JAPANESE_AND_LATIN -> japaneseRecognizer
            }
            val result = recognizer.process(image).awaitTask()
            extractLinesFromMlKitResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "Full-screen OCR failed", e)
            emptyList()
        }
    }

    suspend fun extractRegionOcr(
        cropBitmap: Bitmap,
        cropRectInScreen: Rect,
        fullScreenLines: List<RecognizedTextLine>,
        scriptMode: OcrScriptMode
    ): OcrRegionResult = withContext(Dispatchers.Default) {
        // 1. Run high-precision OCR directly on the cropped bitmap (upscale small crops for crispness)
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
            val recognizer = when (scriptMode) {
                OcrScriptMode.LATIN -> latinRecognizer
                OcrScriptMode.JAPANESE_AND_LATIN -> japaneseRecognizer
            }
            val visionText = recognizer.process(InputImage.fromBitmap(workingBitmap, 0)).awaitTask()
            extractLinesFromMlKitResult(visionText).map { it.text }
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
