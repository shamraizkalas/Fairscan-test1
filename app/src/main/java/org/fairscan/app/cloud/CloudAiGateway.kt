package org.fairscan.app.cloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.Base64
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.fairscan.app.camscanner.DocxBlock
import org.fairscan.app.camscanner.DocxPageContent
import org.fairscan.app.camscanner.DocxParagraphStyle
import org.fairscan.app.camscanner.EraserStroke
import org.fairscan.app.camscanner.ReceiptExtractResult
import org.fairscan.imageprocessing.OcrTextBox
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

data class CloudOcrAnalysisResult(
    val detectedLanguage: String,
    val fullText: String,
    val summary: String,
    val receiptResult: ReceiptExtractResult,
    val tableCsv: String,
    val ocrBoxes: List<OcrTextBox> = emptyList(),
)

/**
 * 100% Free & Unlimited AI Gateway powered by Google ML Kit + On-Device PDFBox/OpenCV heuristics,
 * plus an optional user-supplied BYOK (Bring Your Own Key) REST client for generative photo edits.
 * Contains zero Firebase AI Logic billing or shared-project server quotas.
 */
class CloudAiGateway {

    private companion object {
        const val TAG = "FreeAiGateway"
        const val MAX_UPLOAD_EDGE_PX = 1400
        const val BYOK_IMAGE_MODEL = "gemini-2.5-flash-image"
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Free unlimited ML Kit Fast OCR + Receipt/Table Extraction + Local Extractive Summary. */
    suspend fun analyzeDocumentPage(bitmap: Bitmap): CloudOcrAnalysisResult {
        return FreeMlKitEngine.recognizeTextFast(bitmap)
    }

    /** Free unlimited ML Kit Offline Translation (59+ languages including Urdu) + Local Summary. */
    suspend fun translateOrSummarizeText(
        sourceText: String,
        targetLanguage: String,
        includeSummary: Boolean = true,
        allowCellularDownload: Boolean = true,
        onStatusUpdate: ((String) -> Unit)? = null,
    ): String {
        val result = FreeMlKitEngine.translateText(
            sourceText = sourceText,
            targetLanguageName = targetLanguage,
            allowCellularDownload = allowCellularDownload,
            onStatusUpdate = onStatusUpdate,
        )
        return buildString {
            if (includeSummary && result.summary.isNotBlank()) {
                appendLine("Key Summary (${result.targetLanguageName}):")
                appendLine(result.summary)
                appendLine()
            }
            appendLine("Translation (${FreeMlKitEngine.mapCodeToDisplayLanguage(result.sourceLanguageCode)} → ${result.targetLanguageName}):")
            append(result.translatedText)
        }.trim()
    }

    suspend fun getDownloadedTranslationLanguages(): Set<String> {
        return FreeMlKitEngine.getDownloadedLanguageCodes()
    }

    suspend fun deleteTranslationLanguagePack(langCode: String): Boolean {
        return FreeMlKitEngine.deleteLanguagePack(langCode)
    }

    /** Free unlimited ML Kit Subject Segmentation foreground mask (null if unavailable on device). */
    suspend fun segmentSubjectMask(bitmap: Bitmap): Bitmap? {
        return FreeMlKitEngine.segmentForegroundMask(bitmap)
    }

    /** Free unlimited instant local QR safety & intent analyzer. */
    fun analyzeQrPayload(payload: String): String {
        return FreeMlKitEngine.analyzeQrPayloadLocally(payload)
    }

    /**
     * Free offline structural PDF layout reconstruction using PDFBox font sizes & positional clustering
     * with OCR text fallback for scanned PDFs. Produces real Headings, Bullets, and native Word tables.
     */
    fun reconstructPdfBlocksFromFile(
        pdfFile: File,
        fallbackPages: List<DocxPageContent>,
    ): List<DocxBlock> {
        val blocks = mutableListOf<DocxBlock>()
        runCatching {
            PDDocument.load(pdfFile).use { doc ->
                val collector = PositionalLineStripper()
                for (p in 1..doc.numberOfPages) {
                    collector.reset()
                    collector.startPage = p
                    collector.endPage = p
                    collector.getText(doc)
                    val lines = collector.collectedLines
                    if (lines.isNotEmpty()) {
                        blocks.addAll(classifyLinesToDocxBlocks(lines))
                    } else {
                        val ocrPage = fallbackPages.getOrNull(p - 1)
                        if (ocrPage != null && ocrPage.text.isNotBlank()) {
                            blocks.addAll(classifyPlainTextToDocxBlocks(ocrPage.text))
                        }
                    }
                }
            }
        }.onFailure {
            fallbackPages.forEach { page ->
                if (page.text.isNotBlank()) {
                    blocks.addAll(classifyPlainTextToDocxBlocks(page.text))
                }
            }
        }
        return blocks
    }

    /** Cleans broken OCR line-wraps, stray hyphens, and extra whitespace offline for free. */
    fun polishDocumentTextLocally(rawText: String): String {
        val normalized = rawText.replace("\r\n", "\n").replace("\r", "\n").trim()
        if (normalized.isEmpty()) return ""
        val paragraphs = normalized.split(Regex("\\n{2,}"))
        return paragraphs.joinToString("\n\n") { block ->
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return@joinToString ""
            val isListOrHeadingOrTable = lines.all { line ->
                line.startsWith("#") || line.startsWith("•") || line.startsWith("- ") ||
                    line.startsWith("* ") || Regex("^\\d+[.)]\\s+").containsMatchIn(line)
            } || lines.any { line ->
                Regex("\\s{3,}|\\t+|\\|").containsMatchIn(line)
            }
            if (isListOrHeadingOrTable) {
                lines.joinToString("\n") { line ->
                    if (line.startsWith("- ") || line.startsWith("* ")) "• ${line.drop(2).trim()}" else line
                }
            } else {
                val joined = StringBuilder()
                lines.forEach { line ->
                    if (joined.isEmpty()) {
                        joined.append(line)
                    } else if (joined.endsWith("-")) {
                        joined.deleteCharAt(joined.length - 1)
                        joined.append(line)
                    } else {
                        joined.append(' ').append(line)
                    }
                }
                joined.toString()
            }
        }
    }

    /**
     * Optional user-provided BYOK Gemini REST image edit.
     * Uses the user's own API key from Settings (zero cost to the app publisher).
     */
    suspend fun editPhotoWithUserByokKey(
        sourceBitmap: Bitmap,
        instruction: String,
        apiKey: String,
    ): Bitmap {
        require(apiKey.isNotBlank()) { "Please add your personal Gemini API key first." }
        require(instruction.isNotBlank()) { "Edit prompt cannot be blank." }
        return callByokImageGenerate(
            sourceBitmap = sourceBitmap,
            prompt = "Apply this photo edit while preserving identity, faces, text, and unmentioned details cleanly: $instruction",
            apiKey = apiKey,
        )
    }

    suspend fun removeMarkedObjectsWithUserByokKey(
        sourceBitmap: Bitmap,
        strokes: List<EraserStroke>,
        apiKey: String,
    ): Bitmap {
        require(apiKey.isNotBlank()) { "Please add your personal Gemini API key first." }
        if (strokes.isEmpty()) return sourceBitmap

        val scaled = scaleBitmap(sourceBitmap, MAX_UPLOAD_EDGE_PX)
        val markedCopy = scaled.copy(Bitmap.Config.ARGB_8888, true)
        try {
            val canvas = Canvas(markedCopy)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = android.graphics.Color.argb(235, 255, 0, 128)
            }
            val minSide = minOf(markedCopy.width, markedCopy.height).toFloat()
            strokes.forEach { stroke ->
                if (stroke.pointsNorm.isEmpty()) return@forEach
                paint.strokeWidth = (stroke.brushRadiusNorm * minSide * 2f).coerceAtLeast(10f)
                val path = Path()
                val first = stroke.pointsNorm.first()
                path.moveTo(first.first * markedCopy.width, first.second * markedCopy.height)
                for (i in 1 until stroke.pointsNorm.size) {
                    val p = stroke.pointsNorm[i]
                    path.lineTo(p.first * markedCopy.width, p.second * markedCopy.height)
                }
                canvas.drawPath(path, paint)
            }
            return callByokImageGenerate(
                sourceBitmap = markedCopy,
                prompt = "Remove the objects or marks covered by the bright pink brush strokes and reconstruct the background cleanly.",
                apiKey = apiKey,
            )
        } finally {
            markedCopy.recycle()
            if (scaled !== sourceBitmap) scaled.recycle()
        }
    }

    private suspend fun callByokImageGenerate(
        sourceBitmap: Bitmap,
        prompt: String,
        apiKey: String,
    ): Bitmap = withContext(Dispatchers.IO) {
        val scaled = scaleBitmap(sourceBitmap, MAX_UPLOAD_EDGE_PX)
        try {
            val jpegStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, jpegStream)
            val b64Image = Base64.encodeToString(jpegStream.toByteArray(), Base64.NO_WRAP)

            val payload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put(
                                "parts",
                                JSONArray()
                                    .put(
                                        JSONObject().apply {
                                            put(
                                                "inline_data",
                                                JSONObject().apply {
                                                    put("mime_type", "image/jpeg")
                                                    put("data", b64Image)
                                                }
                                            )
                                        }
                                    )
                                    .put(JSONObject().apply { put("text", prompt) })
                            )
                        }
                    )
                )
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("responseModalities", JSONArray().put("IMAGE").put("TEXT"))
                    }
                )
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$BYOK_IMAGE_MODEL:generateContent"
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey.trim())
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = runCatching {
                        JSONObject(rawBody).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty().ifBlank { "HTTP ${response.code}" }
                    throw IllegalStateException("Personal BYOK key error: $msg")
                }
                val root = JSONObject(rawBody)
                val candidates = root.optJSONArray("candidates") ?: JSONArray()
                for (c in 0 until candidates.length()) {
                    val parts = candidates.optJSONObject(c)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts") ?: continue
                    for (p in 0 until parts.length()) {
                        val part = parts.optJSONObject(p) ?: continue
                        val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                        val data = inline?.optString("data").orEmpty()
                        if (data.isNotEmpty()) {
                            val bytes = Base64.decode(data, Base64.DEFAULT)
                            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (decoded != null) return@withContext decoded
                        }
                    }
                }
                throw IllegalStateException("Personal Gemini key returned text only (no edited image).")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "BYOK image request failed: ${e.message}")
            throw e
        } finally {
            if (scaled !== sourceBitmap) scaled.recycle()
        }
    }

    internal data class PositionalLine(
        val text: String,
        val avgFontSizePt: Float,
        val isAllCapsOrShortTitle: Boolean,
        val columnSegments: List<String>,
    )

    private class PositionalLineStripper : PDFTextStripper() {
        val collectedLines = mutableListOf<PositionalLine>()

        fun reset() {
            collectedLines.clear()
        }

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            val clean = text?.trim().orEmpty()
            if (clean.isEmpty()) return
            val positions = textPositions.orEmpty()
            val avgFont = if (positions.isNotEmpty()) {
                positions.map { it.fontSizeInPt }.average().toFloat()
            } else 11f

            // Detect column gaps using horizontal jumps between consecutive glyphs
            val segments = mutableListOf<String>()
            if (positions.size >= 2) {
                val buf = StringBuilder()
                var prevEndX = positions.first().xDirAdj
                positions.forEachIndexed { index, tp ->
                    if (index > 0) {
                        val gap = tp.xDirAdj - prevEndX
                        val spaceThreshold = max(tp.widthOfSpace * 3.2f, 14f)
                        if (gap > spaceThreshold && buf.isNotEmpty()) {
                            segments.add(buf.toString().trim())
                            buf.setLength(0)
                        } else if (gap > max(tp.widthOfSpace * 0.45f, 2f) && buf.isNotEmpty() && !buf.endsWith(" ")) {
                            buf.append(' ')
                        }
                    }
                    buf.append(tp.unicode)
                    prevEndX = tp.xDirAdj + tp.widthDirAdj
                }
                if (buf.isNotEmpty()) segments.add(buf.toString().trim())
            } else {
                segments.addAll(clean.split(Regex("\\s{3,}|\\t+")).map { it.trim() }.filter { it.isNotEmpty() })
            }

            val finalSegments = segments.filter { it.isNotEmpty() }
            val isShortTitle = clean.length in 3..70 && !clean.endsWith(".") &&
                clean.count { it.isLetter() } >= 3 &&
                (clean == clean.uppercase() || abs(avgFont) >= 13.5f)

            collectedLines.add(
                PositionalLine(
                    text = clean,
                    avgFontSizePt = avgFont,
                    isAllCapsOrShortTitle = isShortTitle,
                    columnSegments = finalSegments,
                )
            )
        }
    }

    internal fun classifyLinesToDocxBlocks(lines: List<PositionalLine>): List<DocxBlock> {
        if (lines.isEmpty()) return emptyList()
        val sortedSizes = lines.map { it.avgFontSizePt }.sorted()
        val medianBodySize = sortedSizes[sortedSizes.size / 2].coerceAtLeast(9f)

        val blocks = mutableListOf<DocxBlock>()
        val pendingTable = mutableListOf<List<String>>()
        fun flushTable() {
            if (pendingTable.size >= 2) {
                blocks.add(DocxBlock.Table(pendingTable.toList()))
            } else if (pendingTable.size == 1) {
                blocks.add(DocxBlock.Paragraph(pendingTable.first().joinToString("   "), DocxParagraphStyle.NORMAL))
            }
            pendingTable.clear()
        }

        lines.forEach { line ->
            val trimmed = line.text.trim()
            if (trimmed.isEmpty()) return@forEach

            if (line.columnSegments.size >= 2 && trimmed.length < 180) {
                pendingTable.add(line.columnSegments)
                return@forEach
            }
            flushTable()

            when {
                line.avgFontSizePt >= medianBodySize + 4.0f ||
                    (trimmed.startsWith("# ") && trimmed.length < 90) -> {
                    blocks.add(DocxBlock.Paragraph(trimmed.removePrefix("# ").trim(), DocxParagraphStyle.HEADING_1))
                }
                line.avgFontSizePt >= medianBodySize + 1.8f ||
                    (line.isAllCapsOrShortTitle && trimmed.length in 4..60) ||
                    trimmed.startsWith("## ") -> {
                    blocks.add(DocxBlock.Paragraph(trimmed.removePrefix("## ").trim(), DocxParagraphStyle.HEADING_2))
                }
                trimmed.startsWith("•") || trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    val bulletText = trimmed.removePrefix("•").removePrefix("-").removePrefix("*").trim()
                    if (bulletText.isNotEmpty()) {
                        blocks.add(DocxBlock.Paragraph(bulletText, DocxParagraphStyle.BULLET))
                    }
                }
                else -> {
                    blocks.add(DocxBlock.Paragraph(trimmed, DocxParagraphStyle.NORMAL))
                }
            }
        }
        flushTable()
        return blocks
    }

    internal fun classifyPlainTextToDocxBlocks(text: String): List<DocxBlock> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val synthetic = lines.map { line ->
            val cols = line.split(Regex("\\s{3,}|\\t+|\\|")).map { it.trim() }.filter { it.isNotEmpty() }
            PositionalLine(
                text = line,
                avgFontSizePt = 11f,
                isAllCapsOrShortTitle = line.length in 4..55 && line == line.uppercase() && line.any { it.isLetter() },
                columnSegments = cols,
            )
        }
        return classifyLinesToDocxBlocks(synthetic)
    }

    private fun scaleBitmap(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest.toFloat()
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
