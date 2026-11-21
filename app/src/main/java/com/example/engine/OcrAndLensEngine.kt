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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
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

sealed interface TranslationState {
    data object Idle : TranslationState
    data class DownloadingModel(val languagePairLabel: String) : TranslationState
    data object Translating : TranslationState
    data class Success(val translatedText: String, val sourceLangName: String, val targetLangName: String) : TranslationState
    data class SameLanguage(val languageName: String) : TranslationState
    data class Error(val message: String) : TranslationState
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

    fun prewarm(context: Context) {
        try {
            com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(context.applicationContext)
            // Touch lazy instances to start native ML Kit initialization off the critical draw path
            latinRecognizer
            japaneseRecognizer
            languageIdentifier
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit prewarm skipped: ${e.message}")
        }
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
        val mlSource = TranslateLanguage.fromLanguageTag(resolvedSourceTag)
            ?: TranslateLanguage.fromLanguageTag(resolvedSourceTag.substringBefore("-"))
        val mlTarget = TranslateLanguage.fromLanguageTag(targetLangCode)

        if (mlSource == null || mlTarget == null) {
            onState(TranslationState.Error("Unsupported language pair ($resolvedSourceTag -> $targetLangCode)."))
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

            if (!sourceDownloaded || !targetDownloaded) {
                onState(TranslationState.DownloadingModel("$sourceLabel ↔ $targetLabel"))
                val conditions = DownloadConditions.Builder().build()
                translator.downloadModelIfNeeded(conditions).awaitTask()
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
                    e.localizedMessage ?: "Offline translation failed. Connect once to download the language model."
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

    fun launchWebTranslate(context: Context, text: String, targetLangCode: String): Boolean {
        if (text.isBlank()) return false
        return try {
            val url = "https://translate.google.com/?sl=auto&tl=${Uri.encode(targetLangCode)}&text=${Uri.encode(text.trim())}&op=translate"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Google Translate in browser", e)
            false
        }
    }

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
