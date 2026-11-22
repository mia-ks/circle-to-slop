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

    fun prewarm(context: Context) {
        val appCtx = context.applicationContext
        // Touch recognizers off UI thread & inspect local/Play Services OCR module availability
        latinRecognizer
        refreshAllOcrPacksAsync(appCtx)
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

    suspend fun recognizeWithMoE(
        context: Context,
        bitmap: Bitmap,
        scriptMode: OcrScriptMode
    ): List<CandidateOcrLine> = withContext(Dispatchers.Default) {
        val appCtx = context.applicationContext
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        // Refresh local Tesseract availability quickly
        val tessDir = File(appCtx.filesDir, "tessdata")
        val downloadedTessLangs = SupportedTesseractOcrPacks
            .map { it.code }
            .filter { File(tessDir, "$it.traineddata").let { f -> f.exists() && f.length() > 10_000L } }

        // Determine active ML Kit script experts
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

        // If user explicitly picked a non-Latin single mode whose Play Services module isn't downloaded yet,
        // trigger ModuleInstall check cleanly
        if (scriptMode != OcrScriptMode.AUTO_MOE && scriptMode != OcrScriptMode.LATIN) {
            val primaryKey = mlKitKeys.lastOrNull()
            if (primaryKey != null && _mlKitOcrStatusFlow.value[primaryKey] != ModelPackStatus.DOWNLOADED) {
                routerScope.launch { ensureMlKitScriptInstalled(appCtx, primaryKey) }
            }
        }

        val allCandidateLists = coroutineScope {
            val mlKitJobs = mlKitKeys.map { key ->
                async(Dispatchers.Default) {
                    runMlKitExpert(key, inputImage)
                }
            }
            val tessJob = if (downloadedTessLangs.isNotEmpty()) {
                async(Dispatchers.IO) {
                    runTesseractExpert(appCtx, bitmap, downloadedTessLangs)
                }
            } else {
                null
            }

            val results = mlKitJobs.awaitAll().toMutableList()
            if (tessJob != null) {
                results.add(tessJob.await())
            }
            results
        }

        val flattened = allCandidateLists.flatten()
        fuseMoECandidates(flattened)
    }

    private suspend fun runMlKitExpert(key: String, inputImage: InputImage): List<CandidateOcrLine> {
        val recognizer = recognizerForKey(key) ?: return emptyList()
        val expertKind = expertForMlKitKey(key)
        return try {
            val visionText: Text = recognizer.process(inputImage).awaitGmsTask()
            _mlKitOcrStatusFlow.update { it + (key to ModelPackStatus.DOWNLOADED) }
            val out = mutableListOf<CandidateOcrLine>()
            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val box = line.boundingBox ?: continue
                    if (line.text.isBlank()) continue
                    val words = line.elements.mapNotNull { el ->
                        val wBox = el.boundingBox ?: return@mapNotNull null
                        if (el.text.isBlank()) null else CandidateOcrWord(el.text, wBox)
                    }
                    out.add(
                        CandidateOcrLine(
                            expert = expertKind,
                            text = line.text.trim(),
                            boundingBox = box,
                            words = words.ifEmpty { listOf(CandidateOcrWord(line.text.trim(), box)) },
                            confidence = (line.confidence ?: 0.90f) * 100f
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
        langCodes: List<String>
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
                    if (wText.isNotEmpty() && wBox != null && wBox.size >= 4 && wConf >= 35f) {
                        val rect = Rect(wBox[0], wBox[1], wBox[2], wBox[3])
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
                    if (lText.isNotEmpty() && lBox != null && lBox.size >= 4 && lConf >= 40f) {
                        val rect = Rect(lBox[0], lBox[1], lBox[2], lBox[3])
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
     * Fuses detections from multiple OCR script experts using script-affinity scoring + NMS,
     * keeping the highest-fidelity script expert per region without overlapping duplicates.
     */
    fun fuseMoECandidates(candidates: List<CandidateOcrLine>): List<CandidateOcrLine> {
        if (candidates.isEmpty()) return emptyList()
        val scored = candidates
            .filter { it.text.isNotBlank() }
            .map { it to scoreCandidateLine(it) }
            .sortedByDescending { it.second }

        val kept = mutableListOf<CandidateOcrLine>()
        for ((cand, _) in scored) {
            val overlapsExisting = kept.any { existing ->
                val iou = intersectionOverUnion(existing.boundingBox, cand.boundingBox)
                val minOverlap = maxOf(
                    boxOverlapRatio(existing.boundingBox, cand.boundingBox),
                    boxOverlapRatio(cand.boundingBox, existing.boundingBox)
                )
                iou >= 0.28f || minOverlap >= 0.45f
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

    fun scoreCandidateLine(candidate: CandidateOcrLine): Float {
        val text = candidate.text
        var arabicCount = 0
        var cyrillicCount = 0
        var kanaCount = 0
        var hangulCount = 0
        var devanagariCount = 0
        var hanCount = 0
        var latinOrDigitCount = 0
        var symbolGarbageCount = 0

        for (ch in text) {
            val c = ch.code
            when {
                c in 0x0600..0x06FF || c in 0x0750..0x077F -> arabicCount++
                c in 0x0400..0x04FF -> cyrillicCount++
                c in 0x3040..0x30FF -> kanaCount++
                c in 0xAC00..0xD7AF -> hangulCount++
                c in 0x0900..0x097F -> devanagariCount++
                c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF -> hanCount++
                ch.isLetterOrDigit() -> latinOrDigitCount++
                !ch.isWhitespace() && ch !in ".,:;!?-_/()€¥$£%·" -> symbolGarbageCount++
            }
        }

        val scriptAffinity = when (candidate.expert) {
            OcrExpertKind.TESSERACT -> {
                when {
                    arabicCount > 0 || cyrillicCount > 0 -> 72f + (arabicCount + cyrillicCount) * 2f
                    symbolGarbageCount > latinOrDigitCount -> 5f
                    else -> 24f // Defer Latin/CJK to specialized ML Kit models when present
                }
            }
            OcrExpertKind.MLKIT_JAPANESE -> when {
                kanaCount > 0 -> 78f + kanaCount * 2f
                hanCount > 0 -> 60f + hanCount
                else -> 40f
            }
            OcrExpertKind.MLKIT_CHINESE -> when {
                hanCount > 0 && kanaCount == 0 -> 68f + hanCount
                else -> 38f
            }
            OcrExpertKind.MLKIT_KOREAN -> when {
                hangulCount > 0 -> 78f + hangulCount * 2f
                else -> 38f
            }
            OcrExpertKind.MLKIT_DEVANAGARI -> when {
                devanagariCount > 0 -> 78f + devanagariCount * 2f
                else -> 38f
            }
            OcrExpertKind.MLKIT_LATIN -> when {
                latinOrDigitCount > 0 && symbolGarbageCount <= latinOrDigitCount -> 56f
                else -> 12f
            }
        }

        val penalty = symbolGarbageCount * 5f
        return scriptAffinity + (candidate.confidence.coerceIn(0f, 100f) * 0.2f) - penalty
    }

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
