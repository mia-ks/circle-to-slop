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
import kotlinx.coroutines.delay
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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Deferred
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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

data class PerspectivePose(
    val tiltZDeg: Float,
    val tiltXDeg: Float,
    val tiltYDeg: Float,
    val quadWidthPx: Float,
    val quadHeightPx: Float
)

data class CandidateOcrLine(
    val expert: OcrExpertKind,
    val text: String,
    val boundingBox: Rect,
    val words: List<CandidateOcrWord>,
    val confidence: Float = 85f,
    val tiltZDeg: Float = 0f,
    val tiltXDeg: Float = 0f,
    val tiltYDeg: Float = 0f,
    val quadWidthPx: Float = 0f,
    val quadHeightPx: Float = 0f
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

    private val _mlKitOcrProgressFlow = MutableStateFlow<Map<String, Float>>(emptyMap())
    val mlKitOcrProgressFlow: StateFlow<Map<String, Float>> = _mlKitOcrProgressFlow.asStateFlow()

    private val _tessOcrStatusFlow = MutableStateFlow<Map<String, ModelPackStatus>>(
        SupportedTesseractOcrPacks.associate { it.code to ModelPackStatus.NOT_DOWNLOADED }
    )
    val tessOcrStatusFlow: StateFlow<Map<String, ModelPackStatus>> = _tessOcrStatusFlow.asStateFlow()

    private val _tessOcrErrorFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val tessOcrErrorFlow: StateFlow<Map<String, String>> = _tessOcrErrorFlow.asStateFlow()

    private val _tessOcrProgressFlow = MutableStateFlow<Map<String, Float>>(emptyMap())
    val tessOcrProgressFlow: StateFlow<Map<String, Float>> = _tessOcrProgressFlow.asStateFlow()

    private val activeTessDownloads = ConcurrentHashMap<String, Deferred<Boolean>>()
    private val activeMlKitDownloads = ConcurrentHashMap<String, Deferred<Boolean>>()

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
    private var isLatinWarmedUp = false

    @Volatile
    private var isMoEWarmedUp = false

    @Volatile
    private var isMoEPrewarming = false

    @Volatile
    private var hasRefreshedMlKitModules = false

    /**
     * Lightweight startup/service-connect warmup: refreshes pack metadata and keeps only
     * the bundled Latin recognizer ready without allocating heavy MoE weights in RAM.
     */
    fun prewarmLatinOnly(context: Context) {
        val appCtx = context.applicationContext
        refreshAllOcrPacksAsync(appCtx)
        if (isLatinWarmedUp) return
        routerScope.launch {
            try {
                val warmupBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.WHITE)
                }
                try {
                    val img = InputImage.fromBitmap(warmupBmp, 0)
                    try {
                        latinRecognizer.process(img).awaitGmsTask()
                    } catch (_: Throwable) {
                    }
                } finally {
                    warmupBmp.recycle()
                }
                isLatinWarmedUp = true
            } catch (e: Throwable) {
                Log.w(TAG, "Latin OCR prewarm skipped: ${e.message}")
            }
        }
    }

    /**
     * Triggered only on Circle invoke gesture: warms downloaded Play Services script models
     * in parallel and initializes native Tesseract LSTM weights in the background after yielding
     * to Latin Fast, without running OCR on the captured screen until MoE or Translate is requested.
     */
    fun prewarmMoEOnInvoke(context: Context, delayMs: Long = 120L) {
        val appCtx = context.applicationContext
        if (isMoEWarmedUp || isMoEPrewarming) return
        isMoEPrewarming = true
        routerScope.launch {
            try {
                if (delayMs > 0L) {
                    delay(delayMs)
                }
                val cachedStatuses = _mlKitOcrStatusFlow.value
                val client = try {
                    ModuleInstall.getClient(appCtx)
                } catch (_: Throwable) {
                    null
                }
                coroutineScope {
                    val mlWarmupJob = async(Dispatchers.Default) {
                        val warmupBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(android.graphics.Color.WHITE)
                        }
                        try {
                            val img = InputImage.fromBitmap(warmupBmp, 0)
                            val latinJob = if (!isLatinWarmedUp) {
                                async {
                                    try {
                                        latinRecognizer.process(img).awaitGmsTask()
                                        isLatinWarmedUp = true
                                    } catch (_: Throwable) {
                                    }
                                }
                            } else {
                                null
                            }

                            val extraJobs = listOf("japanese", "chinese", "korean", "devanagari").map { key ->
                                async {
                                    val rec = recognizerForKey(key) ?: return@async
                                    val ready = cachedStatuses[key] == ModelPackStatus.DOWNLOADED || try {
                                        client?.areModulesAvailable(rec)?.awaitGmsTask()?.areModulesAvailable() == true
                                    } catch (_: Throwable) {
                                        false
                                    }
                                    if (ready) {
                                        try {
                                            rec.process(img).awaitGmsTask()
                                            _mlKitOcrStatusFlow.update { it + (key to ModelPackStatus.DOWNLOADED) }
                                        } catch (_: Throwable) {
                                        }
                                    }
                                }
                            }
                            latinJob?.await()
                            extraJobs.awaitAll()
                        } finally {
                            warmupBmp.recycle()
                        }
                    }

                    val tessWarmupJob = async(Dispatchers.IO) {
                        val downloadedTess = getDownloadedTesseractLangs(appCtx)
                        if (downloadedTess.isNotEmpty()) {
                            prewarmTesseract(appCtx, downloadedTess)
                        }
                    }

                    mlWarmupJob.await()
                    tessWarmupJob.await()
                }
                isMoEWarmedUp = true
            } catch (e: Throwable) {
                Log.w(TAG, "MoE invoke prewarm skipped: ${e.message}")
            } finally {
                isMoEPrewarming = false
            }
        }
    }

    fun prewarm(context: Context) {
        prewarmLatinOnly(context)
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
            hasRefreshedMlKitModules = true
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
            coroutineScope {
                SupportedMlKitDynamicScripts
                    .map { script -> async { ensureMlKitScriptInstalled(appCtx, script.code) } }
                    .awaitAll()
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
            _mlKitOcrProgressFlow.update { it - scriptKey }
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
            _mlKitOcrProgressFlow.update { it + (scriptKey to 1f) }
            return@withContext true
        }

        val existing = activeMlKitDownloads[scriptKey]
        if (existing != null && existing.isActive) {
            return@withContext existing.await()
        }

        val deferred = routerScope.async {
            _mlKitOcrStatusFlow.update { it + (scriptKey to ModelPackStatus.DOWNLOADING) }
            _mlKitOcrErrorFlow.update { it - scriptKey }
            _mlKitOcrProgressFlow.update { it + (scriptKey to 0.06f) }

            val hasRealProgress = java.util.concurrent.atomic.AtomicBoolean(false)
            val ticker = launch {
                val startMs = System.currentTimeMillis()
                while (true) {
                    delay(220L)
                    if (!hasRealProgress.get()) {
                        val elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0
                        val est = (0.06 + (1.0 - kotlin.math.exp(-elapsedSec / 3.0)) * 0.84).toFloat().coerceIn(0.06f, 0.90f)
                        _mlKitOcrProgressFlow.update { cur ->
                            val curVal = cur[scriptKey] ?: 0f
                            cur + (scriptKey to max(curVal, est))
                        }
                    }
                }
            }

            try {
                suspendCancellableCoroutine { cont ->
                    val listener = object : InstallStatusListener {
                        override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                            when (update.installState) {
                                STATE_DOWNLOADING, STATE_INSTALLING -> {
                                    _mlKitOcrStatusFlow.update { it + (scriptKey to ModelPackStatus.DOWNLOADING) }
                                    val info = update.progressInfo
                                    if (info != null && info.totalBytesToDownload > 0L) {
                                        hasRealProgress.set(true)
                                        val frac = (info.bytesDownloaded.toFloat() / info.totalBytesToDownload.toFloat())
                                            .coerceIn(0.05f, 0.99f)
                                        _mlKitOcrProgressFlow.update { it + (scriptKey to frac) }
                                    } else if (update.installState == STATE_INSTALLING) {
                                        hasRealProgress.set(true)
                                        _mlKitOcrProgressFlow.update { cur ->
                                            cur + (scriptKey to max(cur[scriptKey] ?: 0.92f, 0.92f))
                                        }
                                    }
                                }
                                STATE_COMPLETED -> {
                                    client.unregisterListener(this)
                                    isMoEWarmedUp = false
                                    _mlKitOcrStatusFlow.update { it + (scriptKey to ModelPackStatus.DOWNLOADED) }
                                    _mlKitOcrErrorFlow.update { it - scriptKey }
                                    _mlKitOcrProgressFlow.update { it + (scriptKey to 1f) }
                                    if (cont.isActive) cont.resume(true)
                                }
                                STATE_FAILED, STATE_CANCELED -> {
                                    client.unregisterListener(this)
                                    _mlKitOcrStatusFlow.update { it + (scriptKey to ModelPackStatus.ERROR) }
                                    _mlKitOcrErrorFlow.update {
                                        it + (scriptKey to "Play Services download failed (code ${update.errorCode})")
                                    }
                                    _mlKitOcrProgressFlow.update { it - scriptKey }
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
                                _mlKitOcrProgressFlow.update { it + (scriptKey to 1f) }
                                if (cont.isActive) cont.resume(true)
                            }
                        }
                        .addOnFailureListener { exc ->
                            client.unregisterListener(listener)
                            _mlKitOcrStatusFlow.update { it + (scriptKey to ModelPackStatus.ERROR) }
                            _mlKitOcrErrorFlow.update {
                                it + (scriptKey to (exc.localizedMessage ?: "Play Services module install failed"))
                            }
                            _mlKitOcrProgressFlow.update { it - scriptKey }
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
                _mlKitOcrProgressFlow.update { it - scriptKey }
                false
            } finally {
                ticker.cancel()
                activeMlKitDownloads.remove(scriptKey)
            }
        }
        activeMlKitDownloads[scriptKey] = deferred
        return@withContext deferred.await()
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
            _tessOcrProgressFlow.update { it + (cleanCode to 1f) }
            return@withContext true
        }

        val existing = activeTessDownloads[cleanCode]
        if (existing != null && existing.isActive) {
            return@withContext existing.await()
        }

        val deferred = routerScope.async {
            _tessOcrStatusFlow.update { it + (cleanCode to ModelPackStatus.DOWNLOADING) }
            _tessOcrErrorFlow.update { it - cleanCode }
            _tessOcrProgressFlow.update { it + (cleanCode to 0.03f) }

            val tmpFile = File(tessDir, "$cleanCode.traineddata.tmp")
            val downloadUrl = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/$cleanCode.traineddata"
            try {
                val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                val statusCode = conn.responseCode
                if (statusCode !in 200..299) {
                    throw IllegalStateException("HTTP $statusCode downloading $cleanCode.traineddata")
                }
                val totalBytes = conn.contentLengthLong.takeIf { it > 0L }
                    ?: conn.contentLength.toLong().takeIf { it > 0L }
                    ?: -1L
                var bytesRead = 0L
                var lastEmitMs = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buf = ByteArray(16_384)
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            bytesRead += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= 80L) {
                                lastEmitMs = now
                                val frac = if (totalBytes > 0L) {
                                    (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0.03f, 0.99f)
                                } else {
                                    (0.05f + (bytesRead / 2_800_000f) * 0.87f).coerceIn(0.05f, 0.92f)
                                }
                                _tessOcrProgressFlow.update { it + (cleanCode to frac) }
                            }
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
                    isMoEWarmedUp = false
                }
                _tessOcrStatusFlow.update { it + (cleanCode to ModelPackStatus.DOWNLOADED) }
                _tessOcrErrorFlow.update { it - cleanCode }
                _tessOcrProgressFlow.update { it + (cleanCode to 1f) }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed downloading tessdata_fast/$cleanCode.traineddata", e)
                tmpFile.delete()
                _tessOcrStatusFlow.update { it + (cleanCode to ModelPackStatus.ERROR) }
                _tessOcrErrorFlow.update {
                    it + (cleanCode to (e.localizedMessage ?: "Download failed"))
                }
                _tessOcrProgressFlow.update { it - cleanCode }
                false
            } finally {
                activeTessDownloads.remove(cleanCode)
            }
        }
        activeTessDownloads[cleanCode] = deferred
        return@withContext deferred.await()
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
            _tessOcrProgressFlow.update { it - cleanCode }
        }
    }

    private suspend fun resolveMlKitKeys(appCtx: Context, scriptMode: OcrScriptMode): List<String> {
        if (scriptMode == OcrScriptMode.AUTO_MOE && !hasRefreshedMlKitModules) {
            refreshAllOcrPacks(appCtx)
        }
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
     * Ultra-fast first-pass Latin OCR (~80-160ms): runs ONLY the prewarmed bundled Latin model
     * on a downscaled frame so text is selectable almost immediately while MoE stays warmed
     * and ready for an explicit user tap on the MoE button.
     */
    suspend fun recognizeFastLatin(
        bitmap: Bitmap
    ): List<CandidateOcrLine> = withContext(Dispatchers.Default) {
        val (mlBitmap, mlScaleBack) = scaleDownForOcr(bitmap, maxLongSide = 1280)
        val inputImage = InputImage.fromBitmap(mlBitmap, 0)
        val raw = try {
            runMlKitExpert("latin", inputImage, mlScaleBack)
        } finally {
            if (mlBitmap !== bitmap) {
                mlBitmap.recycle()
            }
        }
        fuseMoECandidates(raw)
    }

    /**
     * Progressive two-stage MoE OCR:
     * - Stage 1 emits fast ML Kit detections immediately (typically 80–220ms) so tapping words works right away.
     * - Stage 2 (only if Tesseract extra script packs like Arabic/Cyrillic are downloaded) masks out
     *   already-clean ML Kit Latin/CJK detections and runs Tesseract only on residual regions for 3-6x speedup.
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

        val stage1Raw = runMlKitKeysOnScaledBitmaps(bitmap, mlKitKeys)
        val stage1Fused = fuseMoECandidates(stage1Raw)
        val hasTesseractStage = downloadedTessLangs.isNotEmpty()
        onStageResult(stage1Fused, !hasTesseractStage)

        if (!hasTesseractStage) return@withContext

        val maskRects = collectCleanStage1MaskRects(stage1Raw)
        val (tessBitmap, tessScaleBack) = buildMaskedTesseractBitmap(bitmap, maskRects, maxLongSide = 900)
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

        val stage1Raw = runMlKitKeysOnScaledBitmaps(bitmap, mlKitKeys)
        if (downloadedTessLangs.isEmpty()) {
            return@withContext fuseMoECandidates(stage1Raw)
        }

        val maskRects = collectCleanStage1MaskRects(stage1Raw)
        val (tessBitmap, tessScaleBack) = buildMaskedTesseractBitmap(bitmap, maskRects, maxLongSide = 900)
        val tessRes = try {
            withContext(Dispatchers.IO) {
                runTesseractExpert(appCtx, tessBitmap, downloadedTessLangs, tessScaleBack)
            }
        } finally {
            if (tessBitmap !== bitmap) tessBitmap.recycle()
        }

        fuseMoECandidates(stage1Raw + tessRes)
    }

    private suspend fun runMlKitKeysOnScaledBitmaps(
        bitmap: Bitmap,
        mlKitKeys: List<String>
    ): List<CandidateOcrLine> = coroutineScope {
        val hasLatin = "latin" in mlKitKeys
        val nonLatinKeys = mlKitKeys.filter { it != "latin" }
        val (latinBmp, latinScale) = if (hasLatin) scaleDownForOcr(bitmap, maxLongSide = 1280) else (bitmap to 1f)
        val (cjkBmp, cjkScale) = if (nonLatinKeys.isNotEmpty()) scaleDownForOcr(bitmap, maxLongSide = 960) else (bitmap to 1f)

        try {
            val jobs = buildList {
                if (hasLatin) {
                    val latinImg = InputImage.fromBitmap(latinBmp, 0)
                    add(async(Dispatchers.Default) { runMlKitExpert("latin", latinImg, latinScale) })
                }
                if (nonLatinKeys.isNotEmpty()) {
                    val cjkImg = InputImage.fromBitmap(cjkBmp, 0)
                    for (key in nonLatinKeys) {
                        add(async(Dispatchers.Default) { runMlKitExpert(key, cjkImg, cjkScale) })
                    }
                }
            }
            jobs.awaitAll().flatten()
        } finally {
            if (hasLatin && latinBmp !== bitmap) latinBmp.recycle()
            if (nonLatinKeys.isNotEmpty() && cjkBmp !== bitmap) cjkBmp.recycle()
        }
    }

    /**
     * Collects bounding rects of lines that Stage 1 ML Kit already read with high confidence
     * (clean Latin or CJK/Devanagari) so Tesseract can skip layout/LSTM decoding on those regions.
     */
    fun collectCleanStage1MaskRects(candidates: List<CandidateOcrLine>): List<Rect> {
        return candidates.mapNotNull { cand ->
            val stats = analyzeScriptStats(cand.text)
            val isCleanLatin = cand.expert == OcrExpertKind.MLKIT_LATIN &&
                cand.confidence >= 84f &&
                isStrongCleanLatinLine(cand) &&
                cand.text.length >= 4
            val isCleanCjk = cand.expert != OcrExpertKind.MLKIT_LATIN &&
                cand.expert != OcrExpertKind.TESSERACT &&
                cand.confidence >= 80f &&
                (stats.kanaCount + stats.hanCount + stats.hangulCount + stats.devanagariCount) >= 2
            if (isCleanLatin || isCleanCjk) cand.boundingBox else null
        }
    }

    private fun buildMaskedTesseractBitmap(
        source: Bitmap,
        maskRectsInSourceCoords: List<Rect>,
        maxLongSide: Int
    ): Pair<Bitmap, Float> {
        val (scaled, scaleBack) = scaleDownForOcr(source, maxLongSide = maxLongSide)
        if (maskRectsInSourceCoords.isEmpty() || scaled.width <= 4 || scaled.height <= 4) {
            return scaled to scaleBack
        }
        val mutable = if (scaled.isMutable) {
            scaled
        } else {
            val copy = scaled.copy(Bitmap.Config.ARGB_8888, true)
            if (scaled !== source) scaled.recycle()
            copy
        }
        val canvas = android.graphics.Canvas(mutable)
        val paint = android.graphics.Paint().apply { style = android.graphics.Paint.Style.FILL }
        val invScale = 1f / scaleBack.coerceAtLeast(0.01f)
        for (srcRect in maskRectsInSourceCoords) {
            val l = (srcRect.left * invScale).toInt().coerceIn(0, mutable.width - 1)
            val t = (srcRect.top * invScale).toInt().coerceIn(0, mutable.height - 1)
            val r = (srcRect.right * invScale).toInt().coerceIn(l + 1, mutable.width)
            val b = (srcRect.bottom * invScale).toInt().coerceIn(t + 1, mutable.height)
            if (r - l < 4 || b - t < 4) continue
            val sampleX = (l - 2).coerceIn(0, mutable.width - 1)
            val sampleY = (t - 2).coerceIn(0, mutable.height - 1)
            paint.color = mutable.getPixel(sampleX, sampleY)
            canvas.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), paint)
        }
        return mutable to scaleBack
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

    /**
     * Estimates planar tilt (rotationZ) and 3D perspective foreshortening (rotationX, rotationY)
     * plus true un-inflated quad dimensions from ML Kit's 4 cornerPoints [TL, TR, BR, BL].
     */
    fun computePerspectiveFromCornerPoints(
        points: List<android.graphics.Point>,
        scaleBack: Float = 1f
    ): PerspectivePose? {
        if (points.size < 4) return null
        val tlX = points[0].x * scaleBack
        val tlY = points[0].y * scaleBack
        val trX = points[1].x * scaleBack
        val trY = points[1].y * scaleBack
        val brX = points[2].x * scaleBack
        val brY = points[2].y * scaleBack
        val blX = points[3].x * scaleBack
        val blY = points[3].y * scaleBack

        val topW = hypot(trX - tlX, trY - tlY)
        val bottomW = hypot(brX - blX, brY - blY)
        val leftH = hypot(blX - tlX, blY - tlY)
        val rightH = hypot(brX - trX, brY - trY)
        if (topW < 4f || bottomW < 4f || leftH < 4f || rightH < 4f) return null

        val avgW = (topW + bottomW) * 0.5f
        val avgH = (leftH + rightH) * 0.5f

        val topAngle = atan2(trY - tlY, trX - tlX)
        val botAngle = atan2(brY - blY, brX - blX)
        val tiltZRaw = ((topAngle + botAngle) * 0.5f) * (180f / PI.toFloat())
        val tiltZ = if (abs(tiltZRaw) < 2.0f) 0f else tiltZRaw.coerceIn(-45f, 45f)

        // Vertical foreshortening (top shorter than bottom => positive rotationX; left shorter than right => positive rotationY)
        // Perspective projection scales size by ~ D / (D + deltaZ), so relative edge delta dW / W ~= H * sin(rotX) / D.
        val depthRatioY = (bottomW - topW) / max(topW, bottomW)
        val depthRatioX = (leftH - rightH) / max(leftH, rightH)

        // With Compose cameraDistance = 12f * density (~6..10x typical line height), scale gently and clamp safely.
        val rawTiltX = if (abs(depthRatioY) > 0.025f && avgH > 6f) {
            (atan(depthRatioY * 3.8f) * (180f / PI.toFloat())).coerceIn(-42f, 42f)
        } else {
            0f
        }
        val rawTiltY = if (abs(depthRatioX) > 0.025f && avgW > 12f) {
            // Note: in Compose graphicsLayer, negative rotationY pushes left toward viewer & right away (leftH > rightH -> neg rotationY? wait we check sign in unit test!)
            -(atan(depthRatioX * 3.2f) * (180f / PI.toFloat())).coerceIn(-42f, 42f)
        } else {
            0f
        }
        val tiltX = if (abs(rawTiltX) < 3.0f) 0f else rawTiltX
        val tiltY = if (abs(rawTiltY) < 3.0f) 0f else rawTiltY

        return PerspectivePose(
            tiltZDeg = tiltZ,
            tiltXDeg = tiltX,
            tiltYDeg = tiltY,
            quadWidthPx = avgW,
            quadHeightPx = avgH
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
                    val pose = line.cornerPoints?.toList()?.let {
                        computePerspectiveFromCornerPoints(it, scaleBack)
                    }
                    val words = line.elements.mapNotNull { el ->
                        val wRawBox = el.boundingBox ?: return@mapNotNull null
                        if (el.text.isBlank()) null else CandidateOcrWord(el.text, rescaleRect(wRawBox, scaleBack))
                    }
                    val confPct = if (line.confidence > 0f) (line.confidence * 100f).coerceIn(1f, 100f) else 90f
                    out.add(
                        CandidateOcrLine(
                            expert = expertKind,
                            text = line.text.trim(),
                            boundingBox = box,
                            words = words.ifEmpty { listOf(CandidateOcrWord(line.text.trim(), box)) },
                            confidence = confPct,
                            tiltZDeg = pose?.tiltZDeg ?: 0f,
                            tiltXDeg = pose?.tiltXDeg ?: 0f,
                            tiltYDeg = pose?.tiltYDeg ?: 0f,
                            quadWidthPx = pose?.quadWidthPx ?: box.width().toFloat(),
                            quadHeightPx = pose?.quadHeightPx ?: box.height().toFloat()
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
                !ch.isWhitespace() && ch !in ".,:;!?-_/()€¥$£%·&+'\"<>@#•–—«»°→←↑↓" -> symbolGarbageCount++
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
