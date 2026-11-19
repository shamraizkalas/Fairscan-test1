package org.fairscan.app.cloud

import android.app.Activity
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.fairscan.app.camscanner.DocumentDataExtractor
import org.fairscan.imageprocessing.ImageRect
import org.fairscan.imageprocessing.OcrTextBox
import java.nio.FloatBuffer
import kotlin.math.roundToInt

data class FreeLanguagePackOption(
    val displayName: String,
    val mlKitCode: String,
)

data class FreeTranslationResult(
    val sourceLanguageCode: String,
    val targetLanguageName: String,
    val targetLanguageCode: String,
    val summary: String,
    val translatedText: String,
)

/**
 * 100% Free & Unlimited Google ML Kit engine:
 * 1. ML Kit Text Recognition OCR (instant on-device Latin + bounding boxes for Receipts/Tables)
 * 2. ML Kit On-Device Translation across 59+ languages including Urdu, Arabic, Hindi, English
 * 3. ML Kit Subject Segmentation for cleaner foreground cutout, bokeh blur, and studio backgrounds
 * 4. ML Kit Document Scanner Play Services flow with shadow/finger cleanup
 * 5. Local extractive summary & QR payload safety inspection
 */
object FreeMlKitEngine {

    private const val TAG = "FreeMlKitEngine"

    val SUPPORTED_TRANSLATION_LANGUAGES = listOf(
        FreeLanguagePackOption("Urdu", TranslateLanguage.URDU),
        FreeLanguagePackOption("English", TranslateLanguage.ENGLISH),
        FreeLanguagePackOption("Arabic", TranslateLanguage.ARABIC),
        FreeLanguagePackOption("Hindi", TranslateLanguage.HINDI),
        FreeLanguagePackOption("Spanish", TranslateLanguage.SPANISH),
        FreeLanguagePackOption("French", TranslateLanguage.FRENCH),
        FreeLanguagePackOption("German", TranslateLanguage.GERMAN),
        FreeLanguagePackOption("Turkish", TranslateLanguage.TURKISH),
        FreeLanguagePackOption("Chinese", TranslateLanguage.CHINESE),
    )

    fun mapDisplayLanguageToCode(name: String): String {
        val clean = name.trim().lowercase()
        return when {
            clean.startsWith("urdu") || clean == "ur" -> TranslateLanguage.URDU
            clean.startsWith("arab") || clean == "ar" -> TranslateLanguage.ARABIC
            clean.startsWith("hind") || clean == "hi" -> TranslateLanguage.HINDI
            clean.startsWith("span") || clean == "es" -> TranslateLanguage.SPANISH
            clean.startsWith("fren") || clean == "fr" -> TranslateLanguage.FRENCH
            clean.startsWith("germ") || clean == "de" -> TranslateLanguage.GERMAN
            clean.startsWith("turk") || clean == "tr" -> TranslateLanguage.TURKISH
            clean.startsWith("chin") || clean == "zh" -> TranslateLanguage.CHINESE
            else -> TranslateLanguage.ENGLISH
        }
    }

    fun mapCodeToDisplayLanguage(code: String): String {
        return SUPPORTED_TRANSLATION_LANGUAGES.firstOrNull { it.mlKitCode == code }?.displayName
            ?: code.uppercase()
    }

    suspend fun recognizeTextFast(bitmap: Bitmap): CloudOcrAnalysisResult = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(inputImage).await()
            val fullText = visionText.text.trim()

            val boxes = mutableListOf<OcrTextBox>()
            visionText.textBlocks.forEach { block ->
                block.lines.forEach { line ->
                    val rect = line.boundingBox
                    val lineText = line.text.trim()
                    if (rect != null && lineText.isNotEmpty()) {
                        boxes.add(
                            OcrTextBox(
                                text = lineText,
                                box = ImageRect(
                                    left = rect.left.coerceAtLeast(0),
                                    top = rect.top.coerceAtLeast(0),
                                    right = rect.right.coerceAtLeast(rect.left + 1),
                                    bottom = rect.bottom.coerceAtLeast(rect.top + 1),
                                ),
                                lineHeight = rect.height().coerceAtLeast(1),
                                lineBottom = rect.bottom.coerceAtLeast(1),
                            )
                        )
                    }
                }
            }

            val receiptResult = DocumentDataExtractor.extractReceiptData(fullText)
            val tableCsv = DocumentDataExtractor.extractTableToCsv(boxes).csv
            val summary = summarizeTextLocally(fullText)
            val detectedLang = detectLanguageLabelLocally(fullText)

            CloudOcrAnalysisResult(
                detectedLanguage = detectedLang,
                fullText = fullText,
                summary = summary,
                receiptResult = receiptResult,
                tableCsv = tableCsv,
                ocrBoxes = boxes,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit text recognition failed", e)
            throw IllegalStateException(e.localizedMessage ?: "ML Kit OCR failed.", e)
        } finally {
            runCatching { recognizer.close() }
        }
    }

    suspend fun segmentForegroundMask(bitmap: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = segmenter.process(inputImage).await()
            val maskBuffer = result.foregroundConfidenceMask ?: return@withContext null
            confidenceBufferToAlphaMask(maskBuffer, bitmap.width, bitmap.height)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit Subject Segmentation unavailable, will use OpenCV GrabCut: ${e.message}")
            null
        } finally {
            runCatching { segmenter.close() }
        }
    }

    internal fun confidenceBufferToAlphaMask(
        buffer: FloatBuffer,
        width: Int,
        height: Int,
    ): Bitmap? {
        if (width <= 0 || height <= 0) return null
        buffer.rewind()
        val expectedCount = width * height
        if (buffer.remaining() < expectedCount) return null
        val pixels = IntArray(expectedCount)
        for (i in 0 until expectedCount) {
            val conf = buffer.get().coerceIn(0f, 1f)
            // Soft threshold smoothing keeps hair/edges natural while clearing background noise
            val normalized = when {
                conf <= 0.18f -> 0f
                conf >= 0.72f -> 1f
                else -> (conf - 0.18f) / (0.72f - 0.18f)
            }
            val alpha = (normalized * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(alpha, alpha, alpha, alpha)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    suspend fun detectSourceLanguageCode(sampleText: String): String = withContext(Dispatchers.Default) {
        val clean = sampleText.trim().take(800)
        if (clean.isEmpty()) return@withContext TranslateLanguage.ENGLISH

        val heuristic = detectArabicUrduScriptCode(clean)
        val identifier = LanguageIdentification.getClient()
        try {
            val detected = identifier.identifyLanguage(clean).await()
            if (detected == "und" || detected.isBlank()) {
                return@withContext heuristic ?: TranslateLanguage.ENGLISH
            }
            TranslateLanguage.fromLanguageTag(detected) ?: heuristic ?: TranslateLanguage.ENGLISH
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            heuristic ?: TranslateLanguage.ENGLISH
        } finally {
            runCatching { identifier.close() }
        }
    }

    suspend fun translateText(
        sourceText: String,
        targetLanguageName: String,
        allowCellularDownload: Boolean = true,
        onStatusUpdate: ((String) -> Unit)? = null,
    ): FreeTranslationResult = withContext(Dispatchers.IO) {
        val clean = sourceText.trim()
        require(clean.isNotEmpty()) { "No text available to translate." }

        val targetCode = mapDisplayLanguageToCode(targetLanguageName)
        val sourceCode = detectSourceLanguageCode(clean)
        if (sourceCode == targetCode) {
            // If user picked same language as auto-detected, still return clean summary + original text
            val summary = summarizeTextLocally(clean)
            return@withContext FreeTranslationResult(
                sourceLanguageCode = sourceCode,
                targetLanguageName = targetLanguageName,
                targetLanguageCode = targetCode,
                summary = summary,
                translatedText = clean,
            )
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceCode)
            .setTargetLanguage(targetCode)
            .build()
        val translator = Translation.getClient(options)
        try {
            val conditionsBuilder = DownloadConditions.Builder()
            if (!allowCellularDownload) {
                conditionsBuilder.requireWifi()
            }
            onStatusUpdate?.invoke("Checking offline $targetLanguageName translation pack (~30 MB first time)…")
            translator.downloadModelIfNeeded(conditionsBuilder.build()).await()

            onStatusUpdate?.invoke("Translating offline with ML Kit…")
            val paragraphs = clean.take(18_000)
                .split(Regex("\\n{2,}"))
                .filter { it.isNotBlank() }

            val translatedParagraphs = if (paragraphs.size <= 1) {
                listOf(translator.translate(clean.take(12_000)).await())
            } else {
                paragraphs.map { para ->
                    if (para.length <= 2500) {
                        translator.translate(para).await()
                    } else {
                        para.chunked(2000).map { chunk ->
                            translator.translate(chunk).await()
                        }.joinToString(" ")
                    }
                }
            }
            val translatedFull = translatedParagraphs.joinToString("\n\n").trim()
            val summarySource = summarizeTextLocally(clean)
            val translatedSummary = if (summarySource.isNotBlank()) {
                runCatching { translator.translate(summarySource).await() }.getOrDefault(summarySource)
            } else ""

            FreeTranslationResult(
                sourceLanguageCode = sourceCode,
                targetLanguageName = targetLanguageName,
                targetLanguageCode = targetCode,
                summary = translatedSummary,
                translatedText = translatedFull,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit translation failed", e)
            throw IllegalStateException(
                "Offline translation pack download/translation failed: ${e.localizedMessage ?: "Connect to internet once to download the $targetLanguageName pack (~30 MB)."}",
                e
            )
        } finally {
            runCatching { translator.close() }
        }
    }

    suspend fun getDownloadedLanguageCodes(): Set<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val manager = RemoteModelManager.getInstance()
            val models = manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            models.map { it.language }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptySet()
        }
    }

    suspend fun deleteLanguagePack(langCode: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val model = TranslateRemoteModel.Builder(langCode).build()
            RemoteModelManager.getInstance().deleteDownloadedModel(model).await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    fun getDocumentScannerStartIntent(
        activity: Activity,
        pageLimit: Int = 20,
    ): com.google.android.gms.tasks.Task<IntentSender> {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(pageLimit.coerceIn(1, 50))
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        val scanner = GmsDocumentScanning.getClient(options)
        return scanner.getStartScanIntent(activity)
    }

    fun summarizeTextLocally(rawText: String, maxPoints: Int = 3): String {
        val clean = rawText.trim()
        if (clean.isEmpty()) return ""
        val candidates = clean
            .split(Regex("(?<=[.!?۔])\\s+|\\n+"))
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 16 }
            .distinct()

        if (candidates.isEmpty()) return clean.take(240)
        if (candidates.size <= maxPoints) return candidates.joinToString("\n• ", prefix = "• ")

        val importantKeywords = listOf(
            "total", "amount", "date", "invoice", "bill", "due", "paid", "name",
            "agreement", "contract", "summary", "important", "account", "number",
            "id", "address", "tax", "balance", "approved", "verified",
        )
        val scored = candidates.mapIndexed { idx, line ->
            val lower = line.lowercase()
            var score = 0
            if (idx == 0) score += 4
            if (idx == candidates.lastIndex) score += 1
            if (line.any { it.isDigit() }) score += 2
            importantKeywords.forEach { kw ->
                if (lower.contains(kw)) score += 3
            }
            if (line.length in 30..160) score += 2
            Triple(idx, score, line)
        }

        val topOrdered = scored
            .sortedByDescending { it.second }
            .take(maxPoints)
            .sortedBy { it.first }
            .map { it.third }

        return topOrdered.joinToString("\n• ", prefix = "• ")
    }

    fun analyzeQrPayloadLocally(payload: String): String {
        val raw = payload.trim()
        if (raw.isEmpty()) return "Empty QR payload."
        val lower = raw.lowercase()

        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> {
                val parsed = runCatching { Uri.parse(raw) }.getOrNull()
                val host = parsed?.host.orEmpty().ifBlank { "unknown host" }
                val isHttps = lower.startsWith("https://")
                val hasAt = raw.substringBefore("/", missingDelimiterValue = raw).contains("@")
                val isIpHost = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(host)
                val shorteners = setOf("bit.ly", "tinyurl.com", "t.co", "rb.gy", "is.gd", "shorturl.at")
                val isShortener = shorteners.any { host.equals(it, true) || host.endsWith(".$it", true) }

                buildString {
                    appendLine("• Type: Website Link (${if (isHttps) "Encrypted HTTPS" else "Unencrypted HTTP"})")
                    appendLine("• Domain: $host")
                    when {
                        hasAt -> appendLine("• Caution: Link contains '@' symbol — verify the actual destination domain before opening.")
                        isIpHost -> appendLine("• Caution: Direct IP address link instead of a named domain.")
                        isShortener -> appendLine("• Note: Shortened URL — destination is hidden until opened.")
                        !isHttps -> appendLine("• Caution: Uses plain HTTP (not encrypted). Avoid entering passwords.")
                        else -> appendLine("• Safety Check: Standard HTTPS URL with no structural red flags detected.")
                    }
                }.trim()
            }
            lower.startsWith("wifi:") -> {
                val ssid = Regex("S:([^;]+)").find(raw)?.groupValues?.getOrNull(1)?.replace("\\;", ";") ?: "Hidden"
                val sec = Regex("T:([^;]+)").find(raw)?.groupValues?.getOrNull(1) ?: "Open"
                "• Type: Wi-Fi Network Credentials\n• Network Name (SSID): $ssid\n• Security: $sec"
            }
            lower.startsWith("begin:vcard") -> {
                val fn = Regex("FN:([^\\r\\n]+)").find(raw)?.groupValues?.getOrNull(1) ?: "Contact"
                val tel = Regex("TEL[^:]*:([^\\r\\n]+)").find(raw)?.groupValues?.getOrNull(1)
                buildString {
                    appendLine("• Type: vCard Contact")
                    appendLine("• Name: $fn")
                    if (!tel.isNullOrBlank()) appendLine("• Phone: $tel")
                }.trim()
            }
            lower.startsWith("upi://pay") -> {
                val uri = runCatching { Uri.parse(raw) }.getOrNull()
                val pa = uri?.getQueryParameter("pa").orEmpty()
                val pn = uri?.getQueryParameter("pn").orEmpty()
                val am = uri?.getQueryParameter("am").orEmpty()
                buildString {
                    appendLine("• Type: UPI Payment Request")
                    if (pn.isNotBlank()) appendLine("• Payee: $pn")
                    if (pa.isNotBlank()) appendLine("• UPI ID: $pa")
                    if (am.isNotBlank()) appendLine("• Amount: $am")
                    append("• Caution: Always verify payee name & amount in your banking app before confirming.")
                }.trim()
            }
            lower.startsWith("mailto:") -> "• Type: Email Address\n• Target: ${raw.removePrefix("mailto:")}"
            lower.startsWith("tel:") -> "• Type: Phone Number\n• Number: ${raw.removePrefix("tel:")}"
            else -> {
                val wordCount = raw.split(Regex("\\s+")).count { it.isNotBlank() }
                "• Type: Plain Text / Note ($wordCount words, ${raw.length} chars)\n• Summary: ${raw.take(180)}"
            }
        }
    }

    private fun detectArabicUrduScriptCode(text: String): String? {
        var arabicScriptCount = 0
        var urduSpecificCount = 0
        var devanagariCount = 0
        text.forEach { ch ->
            when (ch.code) {
                in 0x0600..0x06FF -> {
                    arabicScriptCount++
                    if (ch in listOf('پ', 'چ', 'ژ', 'گ', 'ٹ', 'ڈ', 'ڑ', 'ں', 'ے', 'ہ', 'ھ')) {
                        urduSpecificCount++
                    }
                }
                in 0x0900..0x097F -> devanagariCount++
            }
        }
        return when {
            urduSpecificCount > 0 -> TranslateLanguage.URDU
            arabicScriptCount > 4 -> TranslateLanguage.ARABIC
            devanagariCount > 4 -> TranslateLanguage.HINDI
            else -> null
        }
    }

    private fun detectLanguageLabelLocally(text: String): String {
        val code = detectArabicUrduScriptCode(text)
        return when (code) {
            TranslateLanguage.URDU -> "Urdu"
            TranslateLanguage.ARABIC -> "Arabic"
            TranslateLanguage.HINDI -> "Hindi"
            else -> if (text.isBlank()) "None" else "ML Kit Fast"
        }
    }
}
