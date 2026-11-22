package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.data.OcrScriptMode
import com.example.data.SupportedMlKitDynamicScripts
import com.example.data.SupportedTesseractOcrPacks
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_CANCELED
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_DOWNLOADING
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_FAILED
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_INSTALLING
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class OcrExpertKind {
    MLKIT_LATIN,
    MLKIT_JAPANESE,
    MLKIT_CHINESE,
    MLKIT_KOREAN,
    MLKIT_DEVANAGARI,
    TESSERACT
}

data class CandidateOcrWord(
    val text: String,
    val boundingBox: Rect
)

data class CandidateOcrLine(
    val expert: OcrExpertKind,
    val text: String,
    val boundingBox: Rect,
    val words: List<CandidateOcrWord>,
    val confidence: Float = 85f
)

object MoEOcrRouter {
    private const val TAG = "MoEOcrRouter"

    private val routerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tessMutex = Mutex()
    private var cachedTessApi: TessBaseAPI? = null
    private var cachedTessLangCombo: String? = null

    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val japaneseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val chineseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val koreanRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
    private val devanagariRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    private val _mlKitOcrStatusFlow = MutableStateFlow<Map<String, ModelPackStatus>>(
        SupportedMlKitDynamicScripts.associate { it.code to ModelPackStatus.NOT_DOWNLOADED }
    )
    val mlKitOcrStatusFlow: StateFlow<Map<String, ModelPackStatus>> = _mlKitOcrStatusFlow.asStateFlow()

    private val _mlKitOcrErrorFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val mlKitOcrErrorFlow: StateFlow<Map<String, String>> = _mlKitOcrErrorFlow.asStateFlow()

    private val _tessOcrStatusFlow = MutableStateFlow<Map<String, ModelPackStatus>>(
        SupportedTesseractOcrPacks.associate { it.code to ModelPackStatus.NOT_DOWNLOADED }
    )
    val tessOcrStatusFlow: StateFlow<Map<String, ModelPackStatus>> = _tessOcrStatusFlow.asStateFlow()

    private val _tessOcrErrorFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val tessOcrErrorFlow: StateFlow<Map<String, String>> = _tessOcrErrorFlow.asStateFlow()

    private fun recognizerForKey(key: String): TextRecognizer? = when (key) {
        "latin" -> latinRecognizer
        "japanese" -> japaneseRecognizer
        "chinese" -> chineseRecognizer
        "korean" -> koreanRecognizer
        "devanagari" -> devanagariRecognizer
        else -> null
    }

    private fun expertForMlKitKey(key: String): OcrExpertKind = when (key) {
        "japanese" -> OcrExpertKind.MLKIT_JAPANESE
        "chinese" -> OcrExpertKind.MLKIT_CHINESE
        "korean" -> OcrExpertKind.MLKIT_KOREAN
        "devanagari" -> OcrExpertKind.MLKIT_DEVANAGARI
        else -> OcrExpertKind.MLKIT_LATIN
    }

    @Volatile
    private var isWarmedUp = false

    fun prewarm(context: Context) {
        val appCtx = context.applicationContext
        refreshAllOcrPacksAsync(appCtx)
        if (isWarmedUp) return
        routerScope.launch {
            try {
                // Force Play Services ML Kit OCR TFLite runtime + binder hot in memory
                val warmupBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.WHITE)
                }
                try {
                    val img = InputImage.fromBitmap(warmupBmp, 0)
                    try {
                        latinRecognizer.process(img).awaitGmsTask()
                    } catch (_: Throwable) {
                    }
                    // Warm any already-installed dynamic Play Services OCR scripts too
                    val client = try {
                        ModuleInstall.getClient(appCtx)
                    } catch (_: Throwable) {
                        null
                    }
                    for (key in listOf("japanese", "chinese", "korean", "devanagari")) {
                        val rec = recognizerForKey(key) ?: continue
                        val ready = try {
                            client?.areModulesAvailable(rec)?.awaitGmsTask()?.areModulesAvailable() == true
                        } catch (_: Throwable) {
                            false
                        }
                        if (ready) {
                            try {
                                rec.process(img).awaitGmsTask()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                } finally {
                    warmupBmp.recycle()
                }

                // Pre-initialize Tesseract native engine + LSTM weights if any packs are downloaded
                val downloadedTess = getDownloadedTesseractLangs(appCtx)
                if (downloadedTess.isNotEmpty()) {
                    prewarmTesseract(appCtx, downloadedTess)
                }
                isWarmedUp = true
            } catch (e: Throwable) {
                Log.w(TAG, "OCR prewarm skipped: ${e.message}")
            }
        }
    }

    private fun getDownloadedTesseractLangs(appCtx: Context): List<String> {
        val tessDir = File(appCtx.filesDir, "tessdata")
        return SupportedTesseractOcrPacks
            .map { it.code }
            .filter { File(tessDir, "$it.traineddata").let { f -> f.exists() && f.length() > 10_000L } }
    }

    private suspend fun prewarmTesseract(appCtx: Context, langCodes: List<String>) = tessMutex.withLock {
        if (langCodes.isEmpty()) return@withLock
        val combo = langCodes.sorted().joinToString("+")
        if (cachedTessApi != null && cachedTessLangCombo == combo) return@withLock
        try {
            cachedTessApi?.recycle()
            val created = TessBaseAPI()
            if (created.init(appCtx.filesDir.absolutePath, combo)) {
                created.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
                cachedTessApi = created
                cachedTessLangCombo = combo
            } else {
                created.recycle()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Tesseract prewarm skipped: ${t.message}")
        }
    }

    fun refreshAllOcrPacksAsync(context: Context) {
        val appCtx = context.applicationContext
        routerScope.launch {
            refreshAllOcrPacks(appCtx)
        }
    }

    suspend fun refreshAllOcrPacks(context: Context) = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        // 1. Check Tesseract .traineddata packs on disk
        val tessDir = File(appCtx.filesDir, "tessdata")
        _tessOcrStatusFlow.update { current ->
            SupportedTesseractOcrPacks.associate { pack ->
                val f = File(tessDir, "${pack.code}.traineddata")
                val existing = current[pack.code]
                val status = when {
                    f.exists() && f.length() > 10_000L -> ModelPackStatus.DOWNLOADED
                    existing == ModelPackStatus.DOWNLOADING -> ModelPackStatus.DOWNLOADING
                    existing == ModelPackStatus.ERROR -> ModelPackStatus.ERROR
                    else -> ModelPackStatus.NOT_DOWNLOADED
                }
                pack.code to status
            }
        }

        // 2. Check Play Services ML Kit OCR module availability
        try {
            val client = ModuleInstall.getClient(appCtx)
            for (script in SupportedMlKitDynamicScripts) {
                val rec = recognizerForKey(script.code) ?: continue
                val available = try {
                    client.areModulesAvailable(rec).awaitGmsTask().areModulesAvailable()
                } catch (_: Exception) {
                    false
                }
                _mlKitOcrStatusFlow.update { current ->
                    val existing = current[script.code]
                    val next = when {
                        available -> ModelPackStatus.DOWNLOADED
                        existing == ModelPackStatus.DOWNLOADING -> ModelPackStatus.DOWNLOADING
                        existing == ModelPackStatus.ERROR -> ModelPackStatus.ERROR
                        else -> ModelPackStatus.NOT_DOWNLOADED
                    }
                    current + (script.code to next)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ModuleInstall check unavailable: ${e.message}")
        }
    }

    fun startMlKitScriptDownload(context: Context, scriptKey: String) {
        val appCtx = context.applicationContext
        routerScope.launch {
            ensureMlKitScriptInstalled(appCtx, scriptKey)
        }
    }

    fun preloadAllMlKitScripts(context: Context) {
        val appCtx = context.applicationContext
        routerScope.launch {
            for (script in SupportedMlKitDynamicScripts) {
                ensureMlKitScriptInstalled(appCtx, script.code)
            }
        }
    }

    suspend fun ensureMlKitScriptInstalled(context: Context, scriptKey: String): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val recognizer = recognizerForKey(scriptKey) ?: return@withContext false
        val client = try {
            ModuleInstall.getClient(appCtx)
        } catch (e: Exception) {
            _mlKitOcrStatusFlow.update { it + (scriptKey to ModelPackStatus.ERROR) }
            _mlKitOcrErrorFlow.update { it + (scriptKey to (e.localizedMessage ?: "Play Services unavailable")) }
            return@withContext false
        }

        val alreadyAvailable = try {
            client.areModulesAvailable(recognizer).awaitGmsTask().areModulesAvailable()
        } catch (_: Exception) {
            false
        }
        if (alreadyAvailable) {
            _mlKitOcrStatusFlow.update { it + (scriptKey to ModelPackStatus.DOWNLOADED) }
            _mlKitOcrErrorFlow.update { it - scriptKey }
            return@withContext true
        }

        _mlKitOcrStatusFlow.update { it + (scriptKey to ModelPackStatus.DOWNLOADING) }
        _mlKitOcrErrorFlow.update { it - scriptKey }

        return@withContext try {
            suspendCancellableCoroutine { cont ->
                val listener = object : InstallStatusListener {
                    override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                        when (update.installState) {
                            STATE_DOWNLOADING, STATE_INSTALLING -> {
                                _mlKitOcrStatusFlow.update { it + (scriptKey to ModelPackStatus.DOWNLOADING) }
                            }
                            STATE_COMPLETED -> {
                                client.unregisterListener(this)
                                _mlKitOcrStatusFlow.update { it + (scriptKey to ModelPackStatus.DOWNLOADED) }
                                _mlKitOcrErrorFlow.update { it - scriptKey }
                                if (cont.isActive) cont.resume(true)
                            }
                            STATE_FAILED, STATE_CANCELED -> {
                                client.unregisterListener(this)
                                _mlKitOcrStatusFlow.update { it + (scriptKey to ModelPackStatus.ERROR) }
                                _mlKitOcrErrorFlow.update {
                                    it + (scriptKey to "Play Services download failed (code ${update.errorCode})")
                                }
                                if (cont.isActive) cont.resume(false)
                            }
                            else -> Unit
                        }
                    }
                }

                val request = ModuleInstallRequest.newBuilder()
                    .addApi(recognizer)
                    .setListener(listener)
                    .build()

                client.installModules(request)
                    .addOnSuccessListener { response ->
                        if (response.areModulesAlreadyInstalled()) {
                            client.unregisterListener(listener)
                            _mlKitOcrStatusFlow.update { it + (scriptKey to ModelPackStatus.DOWNLOADED) }
                            _mlKitOcrErrorFlow.update { it - scriptKey }
                            if (cont.isActive) cont.resume(true)
                        }
                    }
                    .addOnFailureListener { exc ->
                        client.unregisterListener(listener)
                        _mlKitOcrStatusFlow.update { it + (scriptKey to ModelPackStatus.ERROR) }
                        _mlKitOcrErrorFlow.update {
                            it + (scriptKey to (exc.localizedMessage ?: "Play Services module install failed"))
                        }
                        if (cont.isActive) cont.resume(false)
                    }

                cont.invokeOnCancellation {
                    try {
                        client.unregisterListener(listener)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit module install failed for $scriptKey", e)
            _mlKitOcrStatusFlow.update { it + (scriptKey to ModelPackStatus.ERROR) }
            _mlKitOcrErrorFlow.update { it + (scriptKey to (e.localizedMessage ?: "Install error")) }
            false
        }
    }

    fun startTesseractPackDownload(context: Context, langCode: String) {
        val appCtx = context.applicationContext
        routerScope.launch {
            downloadTesseractPack(appCtx, langCode)
        }
    }

    suspend fun downloadTesseractPack(context: Context, langCode: String): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val cleanCode = langCode.trim()
        val tessDir = File(appCtx.filesDir, "tessdata").apply { mkdirs() }
        val finalFile = File(tessDir, "$cleanCode.traineddata")
        if (finalFile.exists() && finalFile.length() > 10_000L) {
            _tessOcrStatusFlow.update { it + (cleanCode to ModelPackStatus.DOWNLOADED) }
            _tessOcrErrorFlow.update { it - cleanCode }
            return@withContext true
        }

        _tessOcrStatusFlow.update { it + (cleanCode to ModelPackStatus.DOWNLOADING) }
        _tessOcrErrorFlow.update { it - cleanCode }

        val tmpFile = File(tessDir, "$cleanCode.traineddata.tmp")
        val downloadUrl = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/$cleanCode.traineddata"
        return@withContext try {
            val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            val statusCode = conn.responseCode
            if (statusCode !in 200..299) {
                throw IllegalStateException("HTTP $statusCode downloading $cleanCode.traineddata")
            }
            conn.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buf = ByteArray(16_384)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                    }
                    output.flush()
                }
            }
            conn.disconnect()
            if (finalFile.exists()) finalFile.delete()
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }
            // Invalidate cached Tesseract session so the new language joins immediately
            tessMutex.withLock {
                cachedTessApi?.recycle()
                cachedTessApi = null
                cachedTessLangCombo = null
            }
            _tessOcrStatusFlow.update { it + (cleanCode to ModelPackStatus.DOWNLOADED) }
            _tessOcrErrorFlow.update { it - cleanCode }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed downloading tessdata_fast/$cleanCode.traineddata", e)
            tmpFile.delete()
            _tessOcrStatusFlow.update { it + (cleanCode to ModelPackStatus.ERROR) }
            _tessOcrErrorFlow.update {
                it + (cleanCode to (e.localizedMessage ?: "Download failed"))
            }
            false
        }
    }

    fun deleteTesseractPack(context: Context, langCode: String) {
        val appCtx = context.applicationContext
        val cleanCode = langCode.trim()
        routerScope.launch {
            tessMutex.withLock {
                cachedTessApi?.recycle()
                cachedTessApi = null
                cachedTessLangCombo = null
            }
            val file = File(File(appCtx.filesDir, "tessdata"), "$cleanCode.traineddata")
            if (file.exists()) {
                file.delete()
            }
            _tessOcrStatusFlow.update { it + (cleanCode to ModelPackStatus.NOT_DOWNLOADED) }
            _tessOcrErrorFlow.update { it - cleanCode }
        }
    }

    private fun resolveMlKitKeys(appCtx: Context, scriptMode: OcrScriptMode): List<String> {
        val mlKitKeys = when (scriptMode) {
            OcrScriptMode.LATIN -> listOf("latin")
            OcrScriptMode.JAPANESE_AND_LATIN -> listOf("latin", "japanese")
            OcrScriptMode.CHINESE_AND_LATIN -> listOf("latin", "chinese")
            OcrScriptMode.KOREAN_AND_LATIN -> listOf("latin", "korean")
            OcrScriptMode.DEVANAGARI_AND_LATIN -> listOf("latin", "devanagari")
            OcrScriptMode.AUTO_MOE -> {
                val statuses = _mlKitOcrStatusFlow.value
                val readyNonLatin = listOf("japanese", "chinese", "korean", "devanagari")
                    .filter { statuses[it] == ModelPackStatus.DOWNLOADED }
                listOf("latin") + readyNonLatin
            }
        }.distinct()

        if (scriptMode != OcrScriptMode.AUTO_MOE && scriptMode != OcrScriptMode.LATIN) {
            val primaryKey = mlKitKeys.lastOrNull()
            if (primaryKey != null && _mlKitOcrStatusFlow.value[primaryKey] != ModelPackStatus.DOWNLOADED) {
                routerScope.launch { ensureMlKitScriptInstalled(appCtx, primaryKey) }
            }
        }
        return mlKitKeys
    }

    /**
     * Progressive two-stage MoE OCR:
     * - Stage 1 emits fast ML Kit detections immediately (typically 80–220ms) so tapping words works right away.
     * - Stage 2 (only if Tesseract extra script packs like Arabic/Cyrillic are downloaded) runs asynchronously
     *   on a downscaled bitmap and merges non-Latin lines cleanly without blocking the UI.
     */
    suspend fun recognizeWithMoEProgressive(
        context: Context,
        bitmap: Bitmap,
        scriptMode: OcrScriptMode,
        onStageResult: suspend (candidates: List<CandidateOcrLine>, isFinal: Boolean) -> Unit
    ) = withContext(Dispatchers.Default) {
        val appCtx = context.applicationContext
        val downloadedTessLangs = getDownloadedTesseractLangs(appCtx)
        val mlKitKeys = resolveMlKitKeys(appCtx, scriptMode)

        // Downscale full-screen captures so ML Kit & Tesseract process ~3-4x fewer pixels without losing legibility
        val (mlBitmap, mlScaleBack) = scaleDownForOcr(bitmap, maxLongSide = 1280)
        val inputImage = InputImage.fromBitmap(mlBitmap, 0)

        val stage1Raw = try {
            coroutineScope {
                mlKitKeys.map { key ->
                    async(Dispatchers.Default) {
                        runMlKitExpert(key, inputImage, mlScaleBack)
                    }
                }.awaitAll().flatten()
            }
        } finally {
            if (mlBitmap !== bitmap) {
                mlBitmap.recycle()
            }
        }

        val stage1Fused = fuseMoECandidates(stage1Raw)
        val hasTesseractStage = downloadedTessLangs.isNotEmpty()
        onStageResult(stage1Fused, !hasTesseractStage)

        if (!hasTesseractStage) return@withContext

        val (tessBitmap, tessScaleBack) = scaleDownForOcr(bitmap, maxLongSide = 1024)
        val tessCandidates = try {
            withContext(Dispatchers.IO) {
                runTesseractExpert(appCtx, tessBitmap, downloadedTessLangs, tessScaleBack)
            }
        } finally {
            if (tessBitmap !== bitmap) {
                tessBitmap.recycle()
            }
        }

        if (tessCandidates.isEmpty()) {
            onStageResult(stage1Fused, true)
            return@withContext
        }

        val combinedFused = fuseMoECandidates(stage1Raw + tessCandidates)
        onStageResult(combinedFused, true)
    }

    suspend fun recognizeWithMoE(
        context: Context,
        bitmap: Bitmap,
        scriptMode: OcrScriptMode
    ): List<CandidateOcrLine> = withContext(Dispatchers.Default) {
        val appCtx = context.applicationContext
        val downloadedTessLangs = getDownloadedTesseractLangs(appCtx)
        val mlKitKeys = resolveMlKitKeys(appCtx, scriptMode)

        val (mlBitmap, mlScaleBack) = scaleDownForOcr(bitmap, maxLongSide = 1280)
        val inputImage = InputImage.fromBitmap(mlBitmap, 0)

        val allCandidates = coroutineScope {
            val mlKitJobs = mlKitKeys.map { key ->
                async(Dispatchers.Default) {
                    runMlKitExpert(key, inputImage, mlScaleBack)
                }
            }
            val tessJob = if (downloadedTessLangs.isNotEmpty()) {
                val (tessBitmap, tessScaleBack) = scaleDownForOcr(bitmap, maxLongSide = 1024)
                async(Dispatchers.IO) {
                    try {
                        runTesseractExpert(appCtx, tessBitmap, downloadedTessLangs, tessScaleBack)
                    } finally {
                        if (tessBitmap !== bitmap) tessBitmap.recycle()
                    }
                }
            } else {
                null
            }

            val mlRes = try {
                mlKitJobs.awaitAll().flatten()
            } finally {
                if (mlBitmap !== bitmap) mlBitmap.recycle()
            }
            val tessRes = tessJob?.await().orEmpty()
            mlRes + tessRes
        }

        fuseMoECandidates(allCandidates)
    }

    private fun scaleDownForOcr(bitmap: Bitmap, maxLongSide: Int): Pair<Bitmap, Float> {
        val longSide = maxOf(bitmap.width, bitmap.height)
        if (longSide <= maxLongSide || bitmap.width <= 0 || bitmap.height <= 0) {
            return bitmap to 1f
        }
        val ratio = maxLongSide.toFloat() / longSide.toFloat()
        val dstW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val dstH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, dstW, dstH, true)
        val scaleBack = bitmap.width.toFloat() / dstW.toFloat()
        return scaled to scaleBack
    }

    private fun rescaleRect(r: Rect, scaleBack: Float): Rect {
        if (scaleBack == 1f) return r
        return Rect(
            (r.left * scaleBack).toInt(),
            (r.top * scaleBack).toInt(),
            (r.right * scaleBack).toInt(),
            (r.bottom * scaleBack).toInt()
        )
    }

    private suspend fun runMlKitExpert(
        key: String,
        inputImage: InputImage,
        scaleBack: Float = 1f
    ): List<CandidateOcrLine> {
        val recognizer = recognizerForKey(key) ?: return emptyList()
        val expertKind = expertForMlKitKey(key)
        return try {
            val visionText: Text = recognizer.process(inputImage).awaitGmsTask()
            _mlKitOcrStatusFlow.update { it + (key to ModelPackStatus.DOWNLOADED) }
            val out = mutableListOf<CandidateOcrLine>()
            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val rawBox = line.boundingBox ?: continue
                    if (line.text.isBlank()) continue
                    val box = rescaleRect(rawBox, scaleBack)
                    val words = line.elements.mapNotNull { el ->
                        val wRawBox = el.boundingBox ?: return@mapNotNull null
                        if (el.text.isBlank()) null else CandidateOcrWord(el.text, rescaleRect(wRawBox, scaleBack))
                    }
                    out.add(
                        CandidateOcrLine(
                            expert = expertKind,
                            text = line.text.trim(),
                            boundingBox = box,
                            words = words.ifEmpty { listOf(CandidateOcrWord(line.text.trim(), box)) },
                            confidence = line.confidence * 100f
                        )
                    )
                }
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit expert '$key' skipped/pending: ${e.message}")
            emptyList()
        }
    }

    private suspend fun runTesseractExpert(
        appCtx: Context,
        bitmap: Bitmap,
        langCodes: List<String>,
        scaleBack: Float = 1f
    ): List<CandidateOcrLine> = tessMutex.withLock {
        if (langCodes.isEmpty()) return@withLock emptyList()
        val combo = langCodes.sorted().joinToString("+")
        val dataPath = appCtx.filesDir.absolutePath

        return@withLock try {
            val tess = if (cachedTessApi != null && cachedTessLangCombo == combo) {
                cachedTessApi!!
            } else {
                cachedTessApi?.recycle()
                val created = TessBaseAPI()
                val ok = created.init(dataPath, combo)
                if (!ok) {
                    created.recycle()
                    cachedTessApi = null
                    cachedTessLangCombo = null
                    return@withLock emptyList()
                }
                created.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
                cachedTessApi = created
                cachedTessLangCombo = combo
                created
            }

            tess.setImage(bitmap)
            // Trigger recognition layout pass
            tess.utF8Text
            val iter = tess.resultIterator ?: return@withLock emptyList()

            val words = mutableListOf<Pair<CandidateOcrWord, Float>>()
            try {
                iter.begin()
                do {
                    val wText = iter.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                    val wBox = iter.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    val wConf = iter.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    if (wText.isNotEmpty() && wBox != null && wBox.size >= 4 && wConf >= 40f) {
                        val rect = rescaleRect(Rect(wBox[0], wBox[1], wBox[2], wBox[3]), scaleBack)
                        if (rect.width() > 2 && rect.height() > 4) {
                            words.add(CandidateOcrWord(wText, rect) to wConf)
                        }
                    }
                } while (iter.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))

                val lines = mutableListOf<CandidateOcrLine>()
                iter.begin()
                do {
                    val lText = iter.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trim().orEmpty()
                    val lBox = iter.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    val lConf = iter.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    if (lText.isNotEmpty() && lBox != null && lBox.size >= 4 && lConf >= 48f) {
                        val rect = rescaleRect(Rect(lBox[0], lBox[1], lBox[2], lBox[3]), scaleBack)
                        if (rect.width() > 4 && rect.height() > 6 && lText.any { it.isLetterOrDigit() }) {
                            val lineWords = words
                                .filter { (w, _) ->
                                    rect.contains(w.boundingBox.centerX(), w.boundingBox.centerY()) ||
                                        boxOverlapRatio(w.boundingBox, rect) >= 0.45f
                                }
                                .map { it.first }
                            lines.add(
                                CandidateOcrLine(
                                    expert = OcrExpertKind.TESSERACT,
                                    text = lText,
                                    boundingBox = rect,
                                    words = lineWords.ifEmpty { listOf(CandidateOcrWord(lText, rect)) },
                                    confidence = lConf
                                )
                            )
                        }
                    }
                } while (iter.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
                lines
            } finally {
                iter.delete()
                tess.clear()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tesseract MoE expert failed ($combo)", e)
            emptyList()
        }
    }

    /**
     * Fuses detections from multiple OCR script experts using script-affinity scoring,
     * anti-homoglyph arbitration against clean ML Kit Latin reads, and NMS.
     */
    fun fuseMoECandidates(candidates: List<CandidateOcrLine>): List<CandidateOcrLine> {
        if (candidates.isEmpty()) return emptyList()
        val nonBlank = candidates.filter { it.text.isNotBlank() }
        val cleanLatinLines = nonBlank.filter {
            it.expert == OcrExpertKind.MLKIT_LATIN && isStrongCleanLatinLine(it)
        }

        val filtered = nonBlank.filter { cand ->
            if (cand.expert != OcrExpertKind.TESSERACT) return@filter true
            val stats = analyzeScriptStats(cand.text)
            // Discard Tesseract tokens mixing Latin & Cyrillic letters inside the same word (homoglyph noise)
            if (stats.hasMixedLatinCyrillicWord) return@filter false

            val isCyrillicDominant = stats.cyrillicCount > stats.arabicCount && stats.cyrillicCount > 0
            if (!isCyrillicDominant) return@filter true

            val cyrillicPurity = if (stats.totalLetters > 0) {
                stats.cyrillicCount.toFloat() / stats.totalLetters
            } else {
                0f
            }
            if (cyrillicPurity < 0.72f) return@filter false

            // If a clean, high-confidence ML Kit Latin read overlaps the same region, reject
            // low-confidence or homoglyph-ambiguous Cyrillic hallucinations (e.g. "rus" misreading English UI).
            val overlappingCleanLatin = cleanLatinLines.firstOrNull { latinLine ->
                linesOverlapSignificantly(latinLine.boundingBox, cand.boundingBox)
            }
            if (overlappingCleanLatin != null) {
                val keepCyrillicOverCleanLatin =
                    cand.confidence >= 84f &&
                        cyrillicPurity >= 0.88f &&
                        stats.distinctiveCyrillicCount >= 2 &&
                        overlappingCleanLatin.confidence < 88f
                if (!keepCyrillicOverCleanLatin) {
                    return@filter false
                }
            }
            true
        }

        val scored = filtered
            .map { it to scoreCandidateLine(it) }
            .sortedByDescending { it.second }

        val kept = mutableListOf<CandidateOcrLine>()
        for ((cand, _) in scored) {
            val overlapsExisting = kept.any { existing ->
                linesOverlapSignificantly(existing.boundingBox, cand.boundingBox)
            }
            if (!overlapsExisting) {
                kept.add(cand)
            }
        }

        return kept.sortedWith(
            compareBy<CandidateOcrLine> { it.boundingBox.top / 24 }
                .thenBy { it.boundingBox.left }
        )
    }

    private fun linesOverlapSignificantly(a: Rect, b: Rect): Boolean {
        val iou = intersectionOverUnion(a, b)
        val minOverlap = maxOf(boxOverlapRatio(a, b), boxOverlapRatio(b, a))
        return iou >= 0.28f || minOverlap >= 0.45f
    }

    private data class ScriptStats(
        val arabicCount: Int = 0,
        val cyrillicCount: Int = 0,
        val distinctiveCyrillicCount: Int = 0,
        val kanaCount: Int = 0,
        val hangulCount: Int = 0,
        val devanagariCount: Int = 0,
        val hanCount: Int = 0,
        val latinLetterCount: Int = 0,
        val digitCount: Int = 0,
        val symbolGarbageCount: Int = 0,
        val hasMixedLatinCyrillicWord: Boolean = false
    ) {
        val totalLetters: Int
            get() = arabicCount + cyrillicCount + kanaCount + hangulCount + devanagariCount + hanCount + latinLetterCount
    }

    private fun analyzeScriptStats(text: String): ScriptStats {
        var arabicCount = 0
        var cyrillicCount = 0
        var distinctiveCyrillicCount = 0
        var kanaCount = 0
        var hangulCount = 0
        var devanagariCount = 0
        var hanCount = 0
        var latinLetterCount = 0
        var digitCount = 0
        var symbolGarbageCount = 0

        for (ch in text) {
            val c = ch.code
            when {
                c in 0x0600..0x06FF || c in 0x0750..0x077F -> arabicCount++
                c in 0x0400..0x04FF -> {
                    cyrillicCount++
                    if (ch !in CYRILLIC_LATIN_HOMOGLYPHS) {
                        distinctiveCyrillicCount++
                    }
                }
                c in 0x3040..0x30FF -> kanaCount++
                c in 0xAC00..0xD7AF -> hangulCount++
                c in 0x0900..0x097F -> devanagariCount++
                c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF -> hanCount++
                ch.isLetter() -> latinLetterCount++
                ch.isDigit() -> digitCount++
                !ch.isWhitespace() && ch !in ".,:;!?-_/()€¥$£%·&+'\"" -> symbolGarbageCount++
            }
        }

        val mixedWord = text.split(Regex("\\s+")).any { token ->
            var hasLatin = false
            var hasCyrillic = false
            for (ch in token) {
                if (ch.code in 0x0400..0x04FF) hasCyrillic = true
                else if (ch.isLetter() && ch.code < 0x0250) hasLatin = true
                if (hasLatin && hasCyrillic) break
            }
            hasLatin && hasCyrillic
        }

        return ScriptStats(
            arabicCount = arabicCount,
            cyrillicCount = cyrillicCount,
            distinctiveCyrillicCount = distinctiveCyrillicCount,
            kanaCount = kanaCount,
            hangulCount = hangulCount,
            devanagariCount = devanagariCount,
            hanCount = hanCount,
            latinLetterCount = latinLetterCount,
            digitCount = digitCount,
            symbolGarbageCount = symbolGarbageCount,
            hasMixedLatinCyrillicWord = mixedWord
        )
    }

    /**
     * Checks whether an MLKIT_LATIN detection looks like genuine clean English/Latin UI text
     * rather than a mangled transliteration of Cyrillic or Arabic glyphs (e.g., "MockoBCKNN BoK3aJI").
     */
    fun isStrongCleanLatinLine(candidate: CandidateOcrLine): Boolean {
        if (candidate.expert != OcrExpertKind.MLKIT_LATIN) return false
        val text = candidate.text.trim()
        if (text.isEmpty() || candidate.confidence < 72f) return false

        val stats = analyzeScriptStats(text)
        val totalAlphaNum = stats.latinLetterCount + stats.digitCount
        if (totalAlphaNum == 0 || stats.symbolGarbageCount > 1) return false
        if (stats.symbolGarbageCount * 4 > totalAlphaNum) return false

        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false

        var suspiciousTokens = 0
        for (token in tokens) {
            // Check digits embedded directly inside alphabetic word (e.g. "BoK3aJI", "npuBeT", "3aJI")
            var hasLetterDigitLetter = false
            var upperAfterLowerCount = 0
            var prevIsLetter = false
            var prevIsDigit = false
            var prevIsLower = false

            for (ch in token) {
                val isLetter = ch.isLetter()
                val isDigit = ch.isDigit()
                if (prevIsDigit && isLetter && token.any { it.isLetter() } && token.indexOf(ch) > 0) {
                    // e.g. "3aJI" or "BoK3aJI" — exclude common short ordinals like "1st", "2nd", "3rd", "4th", "4G", "24h"
                    val lower = token.lowercase()
                    val benignOrdinalOrUnit = lower.endsWith("st") || lower.endsWith("nd") ||
                        lower.endsWith("rd") || lower.endsWith("th") ||
                        lower.endsWith("px") || lower.endsWith("dp") ||
                        lower.endsWith("mb") || lower.endsWith("gb") ||
                        lower.endsWith("ms") || lower.endsWith("km") ||
                        lower.endsWith("am") || lower.endsWith("pm") ||
                        lower.endsWith("h") || lower.endsWith("m") ||
                        lower.endsWith("g")
                    if (!benignOrdinalOrUnit && prevIsLetter) {
                        hasLetterDigitLetter = true
                    }
                }
                if (isDigit && prevIsLetter) {
                    // Track transition into digit so next char can catch letter-digit-letter
                    prevIsDigit = true
                } else {
                    if (isLetter && prevIsLower && ch.isUpperCase()) {
                        upperAfterLowerCount++
                    }
                    prevIsDigit = isDigit
                }
                if (isLetter) prevIsLetter = true
                prevIsLower = isLetter && ch.isLowerCase()
            }

            // Classic Cyrillic->Latin OCR garble has >=2 mid-word case jumps ("MockoBCKNN") or letter-digit-letter ("BoK3aJI")
            if (hasLetterDigitLetter || upperAfterLowerCount >= 2) {
                suspiciousTokens++
            }
        }

        // Reject if any multi-token line is heavily mangled
        return suspiciousTokens == 0 || (tokens.size >= 3 && suspiciousTokens * 3 < tokens.size)
    }

    fun scoreCandidateLine(candidate: CandidateOcrLine): Float {
        val stats = analyzeScriptStats(candidate.text)
        val totalLetters = stats.totalLetters
        val latinOrDigitCount = stats.latinLetterCount + stats.digitCount

        val scriptAffinity = when (candidate.expert) {
            OcrExpertKind.TESSERACT -> {
                val arabicRatio = if (totalLetters > 0) stats.arabicCount.toFloat() / totalLetters else 0f
                val cyrillicRatio = if (totalLetters > 0) stats.cyrillicCount.toFloat() / totalLetters else 0f
                when {
                    stats.hasMixedLatinCyrillicWord -> 10f
                    stats.arabicCount > 0 && arabicRatio >= 0.60f -> 76f + stats.arabicCount * 2f
                    stats.cyrillicCount > 0 && cyrillicRatio >= 0.78f && candidate.confidence >= 68f ->
                        68f + stats.distinctiveCyrillicCount * 2.2f
                    stats.cyrillicCount > 0 -> 34f
                    stats.symbolGarbageCount > latinOrDigitCount -> 5f
                    else -> 22f // Defer Latin/CJK to specialized ML Kit models
                }
            }
            OcrExpertKind.MLKIT_JAPANESE -> when {
                stats.kanaCount > 0 -> 80f + stats.kanaCount * 2f
                stats.hanCount > 0 -> 62f + stats.hanCount
                else -> 40f
            }
            OcrExpertKind.MLKIT_CHINESE -> when {
                stats.hanCount > 0 && stats.kanaCount == 0 -> 70f + stats.hanCount
                else -> 38f
            }
            OcrExpertKind.MLKIT_KOREAN -> when {
                stats.hangulCount > 0 -> 80f + stats.hangulCount * 2f
                else -> 38f
            }
            OcrExpertKind.MLKIT_DEVANAGARI -> when {
                stats.devanagariCount > 0 -> 80f + stats.devanagariCount * 2f
                else -> 38f
            }
            OcrExpertKind.MLKIT_LATIN -> when {
                isStrongCleanLatinLine(candidate) -> 68f
                latinOrDigitCount > 0 && stats.symbolGarbageCount <= latinOrDigitCount -> 46f
                else -> 12f
            }
        }

        val penalty = stats.symbolGarbageCount * 5f
        return scriptAffinity + (candidate.confidence.coerceIn(0f, 100f) * 0.22f) - penalty
    }

    private const val CYRILLIC_LATIN_HOMOGLYPHS = "АВЕКМНОРСТХаеорсухіІЈЅ"

    private fun boxOverlapRatio(a: Rect, b: Rect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top).toFloat()
        val aArea = (a.width() * a.height()).coerceAtLeast(1).toFloat()
        return intersection / aArea
    }

    private fun intersectionOverUnion(a: Rect, b: Rect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top).toFloat()
        val aArea = (a.width() * a.height()).coerceAtLeast(1).toFloat()
        val bArea = (b.width() * b.height()).coerceAtLeast(1).toFloat()
        return intersection / (aArea + bArea - intersection).coerceAtLeast(1f)
    }

    private suspend fun <T> Task<T>.awaitGmsTask(): T = suspendCancellableCoroutine { cont ->
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
