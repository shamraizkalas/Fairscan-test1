package org.fairscan.app.camscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.text.Html
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import org.fairscan.app.cloud.CloudAiGateway
import org.fairscan.app.cloud.FreeMlKitEngine
import org.fairscan.app.domain.OcrService
import org.fairscan.app.domain.PdfPageSize
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.io.StringWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import kotlin.math.min
import kotlin.math.roundToInt

enum class ConverterCategory(val label: String) {
    ALL("All"),
    PDF_WORD("PDF & Word"),
    IMAGE_PDF("Images & PDF"),
    DOCS_DATA("Docs & Tables"),
    CODE_WEB("Web & Code"),
}

enum class ConverterTool(
    val title: String,
    val shortBadge: String,
    val subtitle: String,
    val category: ConverterCategory,
    val accentHex: Long,
    val allowMultipleInputs: Boolean,
    val mimeTypes: Array<String>,
) {
    PDF_TO_DOCX(
        title = "PDF to Word",
        shortBadge = "PDF → DOCX",
        subtitle = "Convert PDFs to real editable DOCX / DOC with OCR fallback on scans",
        category = ConverterCategory.PDF_WORD,
        accentHex = 0xFF1E88E5,
        allowMultipleInputs = false,
        mimeTypes = arrayOf("application/pdf"),
    ),
    DOCX_TO_PDF(
        title = "Word to PDF",
        shortBadge = "DOCX → PDF",
        subtitle = "Turn Word (.docx / .doc) documents into paginated crisp PDFs",
        category = ConverterCategory.PDF_WORD,
        accentHex = 0xFFE53935,
        allowMultipleInputs = false,
        mimeTypes = arrayOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "*/*",
        ),
    ),
    IMAGE_TO_PDF(
        title = "Image to PDF",
        shortBadge = "IMG → PDF",
        subtitle = "Combine photos and scans (JPG, PNG, WEBP) into a single PDF",
        category = ConverterCategory.IMAGE_PDF,
        accentHex = 0xFF00897B,
        allowMultipleInputs = true,
        mimeTypes = arrayOf("image/*"),
    ),
    PDF_TO_IMAGES(
        title = "PDF to Images",
        shortBadge = "PDF → IMG",
        subtitle = "Render each PDF page cleanly to PNG, JPG, or WEBP files",
        category = ConverterCategory.IMAGE_PDF,
        accentHex = 0xFF8E24AA,
        allowMultipleInputs = false,
        mimeTypes = arrayOf("application/pdf"),
    ),
    PDF_TO_LONG_IMAGE(
        title = "PDF to Long Image",
        shortBadge = "PDF → LONG",
        subtitle = "Stitch all PDF pages vertically into one scrollable high-res image",
        category = ConverterCategory.IMAGE_PDF,
        accentHex = 0xFF5E35B1,
        allowMultipleInputs = false,
        mimeTypes = arrayOf("application/pdf"),
    ),
    PDF_TO_TXT(
        title = "PDF to Text",
        shortBadge = "PDF → TXT",
        subtitle = "Extract clean UTF-8 text from digital and scanned PDFs with OCR",
        category = ConverterCategory.DOCS_DATA,
        accentHex = 0xFF455A64,
        allowMultipleInputs = false,
        mimeTypes = arrayOf("application/pdf"),
    ),
    PDF_TO_HTML(
        title = "PDF to HTML",
        shortBadge = "PDF → HTML",
        subtitle = "Export PDFs into a clean responsive webpage with OCR support",
        category = ConverterCategory.CODE_WEB,
        accentHex = 0xFFEF6C00,
        allowMultipleInputs = false,
        mimeTypes = arrayOf("application/pdf"),
    ),
    TEXT_DOC_TO_PDF(
        title = "Docs & Tables to PDF",
        shortBadge = "MD/CSV → PDF",
        subtitle = "Convert TXT, Markdown, CSV tables, and HTML files into styled PDFs",
        category = ConverterCategory.DOCS_DATA,
        accentHex = 0xFF2E7D32,
        allowMultipleInputs = false,
        mimeTypes = arrayOf("text/*", "text/csv", "text/html", "text/markdown", "*/*"),
    ),
    CODE_TO_PDF(
        title = "JSON, XML & Code to PDF",
        shortBadge = "CODE → PDF",
        subtitle = "Pretty-print JSON, XML, and code files with line numbers into PDF",
        category = ConverterCategory.CODE_WEB,
        accentHex = 0xFF00838F,
        allowMultipleInputs = false,
        mimeTypes = arrayOf("application/json", "text/*", "*/*"),
    ),
    BATCH_IMAGE_CONVERT(
        title = "Batch Image Format",
        shortBadge = "JPG↔PNG↔WEBP",
        subtitle = "Convert & resize multiple images simultaneously between JPG, PNG & WEBP",
        category = ConverterCategory.IMAGE_PDF,
        accentHex = 0xFFD81B60,
        allowMultipleInputs = true,
        mimeTypes = arrayOf("image/*"),
    ),
}

enum class TargetImageFormat(val extension: String, val mimeType: String, val label: String) {
    JPEG("jpg", "image/jpeg", "JPG"),
    PNG("png", "image/png", "PNG"),
    WEBP("webp", "image/webp", "WEBP"),
}

enum class ImageResizeCap(val maxSidePx: Int, val label: String) {
    ORIGINAL(0, "Original"),
    PX_2048(2048, "2048 px"),
    PX_1280(1280, "1280 px"),
}

data class ConverterOptions(
    val pdfPageSize: PdfPageSize = PdfPageSize.A4,
    val imageFormat: TargetImageFormat = TargetImageFormat.PNG,
    val imageQuality: Int = 90,
    val resizeCap: ImageResizeCap = ImageResizeCap.ORIGINAL,
    val docxOutputMode: DocxOutputMode = DocxOutputMode.EDITABLE_TEXT,
    val useOcrFallback: Boolean = true,
    val includeLineNumbers: Boolean = true,
    val useCloudAiStructure: Boolean = true,
    val aiPolishText: Boolean = false,
)

data class ConverterOutputSummary(
    val tool: ConverterTool,
    val files: List<File>,
    val zipArchive: File? = null,
    val mimeType: String,
    val summaryNote: String = "",
    val textPreview: String? = null,
) {
    val totalBytes: Long get() = files.sumOf { it.length() }
}

/**
 * Pure offline conversion engine supporting PDF <-> DOCX/DOC, Image <-> PDF,
 * TXT/MD/CSV/HTML/JSON/XML/Code -> PDF, PDF -> TXT/HTML/Long Image, and Batch Image conversion.
 */
object UniversalConverterEngine {

    fun copyInputUriToFile(context: Context, uri: Uri, dir: File): Pair<File, String> {
        dir.mkdirs()
        val displayName = PdfToolkitEngine.resolveDisplayName(context, uri)
        val cleanName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "input_file" }
        val outFile = File(dir, "conv_in_${System.currentTimeMillis()}_$cleanName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        } ?: error("Unable to open selected file: $displayName")
        return outFile to displayName
    }

    suspend fun extractPdfPagesText(
        pdfFile: File,
        useOcrFallback: Boolean,
        ocrService: OcrService?,
        tempDir: File,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): List<DocxPageContent> {
        val textByPage = mutableListOf<String>()
        PDDocument.load(pdfFile).use { doc ->
            val count = doc.numberOfPages.coerceAtLeast(1)
            val stripper = PDFTextStripper()
            for (p in 1..count) {
                stripper.startPage = p
                stripper.endPage = p
                val raw = runCatching { stripper.getText(doc) }.getOrDefault("").trim()
                textByPage.add(raw)
            }
        }

        val total = textByPage.size
        val results = ArrayList<DocxPageContent>(total)
        val hasSparsePages = textByPage.any { it.replace(Regex("\\s+"), "").length < 20 }

        var rendererPfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        if (hasSparsePages && pdfFile.exists()) {
            runCatching {
                rendererPfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = rendererPfd?.let { PdfRenderer(it) }
            }
        }

        try {
            for (idx in 0 until total) {
                val pageNum = idx + 1
                var text = textByPage[idx]
                var previewBytes: ByteArray? = null
                var previewW = 0
                var previewH = 0

                val isSparse = text.replace(Regex("\\s+"), "").length < 20
                val pdfPageRenderer = renderer
                if (pdfPageRenderer != null && idx < pdfPageRenderer.pageCount && isSparse) {
                    val bmp = renderSinglePageForOcr(pdfPageRenderer, idx, maxLongEdge = 1600)
                    previewW = bmp.width
                    previewH = bmp.height
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 82, baos)
                    previewBytes = baos.toByteArray()

                    if (useOcrFallback) {
                        onProgress(pageNum, total, "Running on-device OCR on scanned page $pageNum/$total…")
                        val tessText = ocrService?.let {
                            runCatching { it.extractFullText(bmp) }.getOrDefault("").trim()
                        }.orEmpty()
                        val mlKitText = if (tessText.isEmpty()) {
                            runCatching { FreeMlKitEngine.recognizeTextFast(bmp).fullText }.getOrDefault("").trim()
                        } else ""
                        val resolved = tessText.ifEmpty { mlKitText }
                        if (resolved.isNotEmpty()) {
                            text = resolved
                        }
                    }
                    bmp.recycle()
                } else {
                    onProgress(pageNum, total, "Extracting page $pageNum/$total…")
                }

                results.add(
                    DocxPageContent(
                        pageNumber = pageNum,
                        text = text,
                        previewJpegBytes = previewBytes,
                        previewWidthPx = previewW,
                        previewHeightPx = previewH,
                    )
                )
            }
        } finally {
            runCatching { renderer?.close() }
            runCatching { rendererPfd?.close() }
        }
        return results
    }

    suspend fun convertPdfToWord(
        pdfFile: File,
        baseName: String,
        outputDir: File,
        options: ConverterOptions,
        ocrService: OcrService?,
        cloudAiGateway: CloudAiGateway? = null,
        useCloudAi: Boolean = false,
        allowOfflineFallback: Boolean = true,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): ConverterOutputSummary {
        outputDir.mkdirs()
        val cleanBase = sanitizeBaseName(baseName, "converted_word")
        val includePreviews = options.docxOutputMode == DocxOutputMode.HYBRID_WITH_PAGES
        var fallbackNote: String? = null

        if (useCloudAi && cloudAiGateway != null) {
            try {
                val (blocks, previews) = reconstructPdfWithFreeHeuristics(
                    pdfFile = pdfFile,
                    cloudAiGateway = cloudAiGateway,
                    options = options,
                    ocrService = ocrService,
                    tempDir = outputDir,
                    includePreviews = includePreviews,
                    onProgress = onProgress,
                )
                if (blocks.isNotEmpty()) {
                    val plainPreview = DocxOoxmlCodec.renderDocxBlocksToPlainText(blocks).take(360)
                    return if (options.docxOutputMode == DocxOutputMode.LEGACY_DOC_HTML) {
                        val outFile = File(outputDir, "$cleanBase.doc")
                        outFile.writeText(DocxOoxmlCodec.renderDocxBlocksToHtml(baseName, blocks), Charsets.UTF_8)
                        ConverterOutputSummary(
                            tool = ConverterTool.PDF_TO_DOCX,
                            files = listOf(outFile),
                            mimeType = "application/msword",
                            summaryNote = "Smart layout .doc (${blocks.size} structured blocks)",
                            textPreview = plainPreview,
                        )
                    } else {
                        val outFile = File(outputDir, "$cleanBase.docx")
                        DocxOoxmlCodec.createDocxFromBlocks(
                            title = baseName,
                            blocks = blocks,
                            outFile = outFile,
                            previewPages = previews,
                        )
                        ConverterOutputSummary(
                            tool = ConverterTool.PDF_TO_DOCX,
                            files = listOf(outFile),
                            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            summaryNote = "Free Smart DOCX (${blocks.size} headings, lists & tables)",
                            textPreview = plainPreview,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!allowOfflineFallback) throw e
                fallbackNote = e.localizedMessage ?: "Smart layout reconstruction unavailable"
            }
        }

        val pages = extractPdfPagesText(
            pdfFile = pdfFile,
            useOcrFallback = options.useOcrFallback,
            ocrService = ocrService,
            tempDir = outputDir,
            onProgress = onProgress,
        )

        val populatedPages = if (includePreviews) {
            attachMissingPageSnapshots(pdfFile, pages, maxLongEdge = 1200)
        } else {
            pages
        }

        val offlineNoteSuffix = if (fallbackNote != null) " • Offline fallback" else ""
        return if (options.docxOutputMode == DocxOutputMode.LEGACY_DOC_HTML) {
            val outFile = File(outputDir, "$cleanBase.doc")
            DocxOoxmlCodec.createLegacyDocHtml(baseName, populatedPages, outFile)
            ConverterOutputSummary(
                tool = ConverterTool.PDF_TO_DOCX,
                files = listOf(outFile),
                mimeType = "application/msword",
                summaryNote = "${populatedPages.size} pages exported to legacy .doc$offlineNoteSuffix",
                textPreview = populatedPages.firstOrNull()?.text?.take(360),
            )
        } else {
            val outFile = File(outputDir, "$cleanBase.docx")
            DocxOoxmlCodec.createDocxFromPages(
                title = baseName,
                pages = populatedPages,
                outFile = outFile,
                includePagePreviews = includePreviews,
            )
            ConverterOutputSummary(
                tool = ConverterTool.PDF_TO_DOCX,
                files = listOf(outFile),
                mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                summaryNote = "${populatedPages.size} pages converted to DOCX (${options.docxOutputMode.label})$offlineNoteSuffix",
                textPreview = populatedPages.firstOrNull()?.text?.take(360),
            )
        }
    }

    private suspend fun reconstructPdfWithFreeHeuristics(
        pdfFile: File,
        cloudAiGateway: CloudAiGateway,
        options: ConverterOptions,
        ocrService: OcrService?,
        tempDir: File,
        includePreviews: Boolean,
        onProgress: (Int, Int, String) -> Unit,
    ): Pair<List<DocxBlock>, List<DocxPageContent>> {
        val rawPages = extractPdfPagesText(
            pdfFile = pdfFile,
            useOcrFallback = options.useOcrFallback,
            ocrService = ocrService,
            tempDir = tempDir,
            onProgress = onProgress,
        )
        val previewPages = if (includePreviews) {
            attachMissingPageSnapshots(pdfFile, rawPages, maxLongEdge = 1200)
        } else {
            emptyList()
        }
        onProgress(rawPages.size.coerceAtLeast(1), rawPages.size.coerceAtLeast(1), "Reconstructing headings, bullets & tables…")
        val blocks = cloudAiGateway.reconstructPdfBlocksFromFile(pdfFile, rawPages)
        return blocks to previewPages
    }

    private fun attachMissingPageSnapshots(
        pdfFile: File,
        pages: List<DocxPageContent>,
        maxLongEdge: Int,
    ): List<DocxPageContent> {
        if (!pages.any { it.previewJpegBytes == null }) return pages
        return runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    pages.mapIndexed { idx, p ->
                        if (p.previewJpegBytes != null || idx >= renderer.pageCount) {
                            p
                        } else {
                            val bmp = renderSinglePageForOcr(renderer, idx, maxLongEdge)
                            val baos = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                            val w = bmp.width
                            val h = bmp.height
                            bmp.recycle()
                            p.copy(
                                previewJpegBytes = baos.toByteArray(),
                                previewWidthPx = w,
                                previewHeightPx = h,
                            )
                        }
                    }
                }
            }
        }.getOrDefault(pages)
    }

    private fun renderSinglePageForOcr(renderer: PdfRenderer, index: Int, maxLongEdge: Int): Bitmap {
        renderer.openPage(index).use { page ->
            val scale = minOf(
                maxLongEdge.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1),
                2.5f
            ).coerceAtLeast(0.75f)
            val w = (page.width * scale).roundToInt().coerceAtLeast(1)
            val h = (page.height * scale).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    fun convertWordToPdf(
        wordFile: File,
        baseName: String,
        outputDir: File,
        options: ConverterOptions,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): ConverterOutputSummary {
        outputDir.mkdirs()
        onProgress(1, 3, "Parsing Word document structure…")
        val parsed = DocxOoxmlCodec.parseWordFile(wordFile, baseName)
        val outFile = File(outputDir, "${sanitizeBaseName(baseName, "word_export")}.pdf")
        onProgress(2, 3, "Typesetting PDF pages…")
        val pageCount = renderBlocksToPdf(
            blocks = parsed.blocks,
            outPdf = outFile,
            pageSize = options.pdfPageSize,
            monospace = false,
            showLineNumbers = false,
        )
        onProgress(3, 3, "Done")
        val preview = parsed.blocks.filterIsInstance<ParsedDocBlock.Paragraph>()
            .take(4).joinToString("\n") { it.plainText }.take(300)
        return ConverterOutputSummary(
            tool = ConverterTool.DOCX_TO_PDF,
            files = listOf(outFile),
            mimeType = "application/pdf",
            summaryNote = "$pageCount PDF page(s) created from ${wordFile.extension.uppercase().ifBlank { "DOC" }}",
            textPreview = preview.ifBlank { null },
        )
    }

    fun convertImagesToPdf(
        imageFiles: List<File>,
        baseName: String,
        outputDir: File,
        options: ConverterOptions,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): ConverterOutputSummary {
        outputDir.mkdirs()
        val outPdf = File(outputDir, "${sanitizeBaseName(baseName, "images_to_pdf")}.pdf")
        val total = imageFiles.size.coerceAtLeast(1)
        val doc = PDDocument()
        try {
            imageFiles.forEachIndexed { idx, imgFile ->
                onProgress(idx + 1, total, "Adding image ${idx + 1}/$total…")
                val decoded = BitmapFactory.decodeFile(imgFile.absolutePath) ?: return@forEachIndexed
                val scaled = scaleBitmapIfNeeded(decoded, options.resizeCap.maxSidePx)
                val pageRect = resolvePdfPageRect(options.pdfPageSize, scaled.width, scaled.height)
                val page = PDPage(pageRect)
                doc.addPage(page)

                val jpeg = JPEGFactory.createFromImage(doc, scaled, options.imageQuality / 100f)
                val margin = if (options.pdfPageSize == PdfPageSize.AUTO_FIT) 0f else 24f
                val availW = (pageRect.width - 2 * margin).coerceAtLeast(10f)
                val availH = (pageRect.height - 2 * margin).coerceAtLeast(10f)
                val ratio = min(availW / scaled.width, availH / scaled.height)
                val drawW = scaled.width * ratio
                val drawH = scaled.height * ratio
                val x = (pageRect.width - drawW) / 2f
                val y = (pageRect.height - drawH) / 2f

                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(jpeg, x, y, drawW, drawH)
                }
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
            }
            doc.save(outPdf)
        } finally {
            val pagesCreated = doc.numberOfPages
            doc.close()
            if (pagesCreated == 0) {
                error("No readable image files were found.")
            }
        }
        return ConverterOutputSummary(
            tool = ConverterTool.IMAGE_TO_PDF,
            files = listOf(outPdf),
            mimeType = "application/pdf",
            summaryNote = "${imageFiles.size} image(s) merged into ${options.pdfPageSize.label} PDF",
        )
    }

    fun convertPdfToImages(
        pdfFile: File,
        baseName: String,
        outputDir: File,
        options: ConverterOptions,
        longVerticalImage: Boolean = false,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): ConverterOutputSummary {
        outputDir.mkdirs()
        val maxEdge = if (options.resizeCap.maxSidePx > 0) options.resizeCap.maxSidePx else 1800
        val bitmaps = PdfToolkitEngine.renderPdfPagesToBitmaps(
            pdfFile = pdfFile,
            maxLongEdgePx = maxEdge,
            tempCacheDir = outputDir,
            onProgress = { cur, tot -> onProgress(cur, tot, "Rendering page $cur/$tot…") },
        )
        if (bitmaps.isEmpty()) error("PDF contains no renderable pages.")

        val cleanBase = sanitizeBaseName(baseName, "pdf_export")
        val compressFmt = toCompressFormat(options.imageFormat)

        if (longVerticalImage) {
            val stitched = LayoutPrintEngine.stitchLongImage(
                bitmaps = bitmaps,
                addDividers = true,
                targetWidthPx = minOf(maxEdge, 1440),
            )
            bitmaps.forEach { it.recycle() }
            val outFile = File(outputDir, "${cleanBase}_long.${options.imageFormat.extension}")
            FileOutputStream(outFile).use { fos ->
                stitched.compress(compressFmt, options.imageQuality, fos)
            }
            val dims = "${stitched.width}×${stitched.height}"
            stitched.recycle()
            return ConverterOutputSummary(
                tool = ConverterTool.PDF_TO_LONG_IMAGE,
                files = listOf(outFile),
                mimeType = options.imageFormat.mimeType,
                summaryNote = "Stitched ${bitmaps.size} page(s) into $dims ${options.imageFormat.label}",
            )
        }

        val outFiles = ArrayList<File>(bitmaps.size)
        bitmaps.forEachIndexed { idx, bmp ->
            val pageFile = File(outputDir, "${cleanBase}_p${idx + 1}.${options.imageFormat.extension}")
            FileOutputStream(pageFile).use { fos ->
                bmp.compress(compressFmt, options.imageQuality, fos)
            }
            bmp.recycle()
            outFiles.add(pageFile)
        }
        val zipFile = if (outFiles.size > 1) {
            createZipBundle(outFiles, File(outputDir, "${cleanBase}_images.zip"))
        } else null

        return ConverterOutputSummary(
            tool = ConverterTool.PDF_TO_IMAGES,
            files = outFiles,
            zipArchive = zipFile,
            mimeType = options.imageFormat.mimeType,
            summaryNote = "${outFiles.size} ${options.imageFormat.label} image(s) rendered",
        )
    }

    suspend fun convertPdfToTextOrHtml(
        pdfFile: File,
        baseName: String,
        outputDir: File,
        asHtml: Boolean,
        options: ConverterOptions,
        ocrService: OcrService?,
        cloudAiGateway: CloudAiGateway? = null,
        useCloudAi: Boolean = false,
        allowOfflineFallback: Boolean = true,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): ConverterOutputSummary {
        outputDir.mkdirs()
        val cleanBase = sanitizeBaseName(baseName, "pdf_text")
        var fallbackNote: String? = null

        if (useCloudAi && cloudAiGateway != null) {
            try {
                val (blocks, _) = reconstructPdfWithFreeHeuristics(
                    pdfFile = pdfFile,
                    cloudAiGateway = cloudAiGateway,
                    options = options,
                    ocrService = ocrService,
                    tempDir = outputDir,
                    includePreviews = false,
                    onProgress = onProgress,
                )
                if (blocks.isNotEmpty()) {
                    if (asHtml) {
                        val outFile = File(outputDir, "$cleanBase.html")
                        val html = DocxOoxmlCodec.renderDocxBlocksToHtml(baseName, blocks)
                        outFile.writeText(html, Charsets.UTF_8)
                        return ConverterOutputSummary(
                            tool = ConverterTool.PDF_TO_HTML,
                            files = listOf(outFile),
                            mimeType = "text/html",
                            summaryNote = "Free Smart HTML (${blocks.size} semantic blocks)",
                            textPreview = DocxOoxmlCodec.renderDocxBlocksToPlainText(blocks).take(360),
                        )
                    } else {
                        val outFile = File(outputDir, "$cleanBase.txt")
                        val txt = DocxOoxmlCodec.renderDocxBlocksToPlainText(blocks)
                        outFile.writeText(txt, Charsets.UTF_8)
                        return ConverterOutputSummary(
                            tool = ConverterTool.PDF_TO_TXT,
                            files = listOf(outFile),
                            mimeType = "text/plain",
                            summaryNote = "Free Smart structured text (${blocks.size} blocks)",
                            textPreview = txt.take(360),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!allowOfflineFallback) throw e
                fallbackNote = e.localizedMessage
            }
        }

        val pages = extractPdfPagesText(
            pdfFile = pdfFile,
            useOcrFallback = options.useOcrFallback,
            ocrService = ocrService,
            tempDir = outputDir,
            onProgress = onProgress,
        )
        val offlineNoteSuffix = if (fallbackNote != null) " • Offline fallback" else ""
        if (asHtml) {
            val outFile = File(outputDir, "$cleanBase.html")
            val html = buildString {
                append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"/>")
                append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>")
                append("<title>${escapeHtml(baseName)}</title>")
                append("<style>body{font-family:system-ui,-apple-system,sans-serif;max-width:860px;margin:24px auto;padding:0 16px;line-height:1.6;color:#1b1f23;background:#f8fafc;}")
                append(".page{background:#fff;border-radius:12px;padding:20px;margin-bottom:18px;box-shadow:0 2px 8px rgba(0,0,0,.06);}")
                append("h2{font-size:14px;color:#0f62fe;text-transform:uppercase;letter-spacing:.05em;margin-top:0;}p{white-space:pre-wrap;}</style></head><body>")
                append("<h1>${escapeHtml(baseName)}</h1>")
                pages.forEach { p ->
                    append("<section class=\"page\"><h2>Page ${p.pageNumber}</h2>")
                    append("<p>${escapeHtml(p.text.ifBlank { "(No text extracted)" })}</p></section>")
                }
                append("</body></html>")
            }
            outFile.writeText(html, Charsets.UTF_8)
            return ConverterOutputSummary(
                tool = ConverterTool.PDF_TO_HTML,
                files = listOf(outFile),
                mimeType = "text/html",
                summaryNote = "${pages.size} page(s) exported as responsive HTML$offlineNoteSuffix",
                textPreview = pages.firstOrNull()?.text?.take(360),
            )
        } else {
            val outFile = File(outputDir, "$cleanBase.txt")
            val txt = pages.joinToString("\n\n") { p ->
                "=== Page ${p.pageNumber} ===\n${p.text.ifBlank { "(No text extracted)" }}"
            }
            outFile.writeText(txt, Charsets.UTF_8)
            return ConverterOutputSummary(
                tool = ConverterTool.PDF_TO_TXT,
                files = listOf(outFile),
                mimeType = "text/plain",
                summaryNote = "${pages.size} page(s) extracted to UTF-8 .txt$offlineNoteSuffix",
                textPreview = txt.take(360),
            )
        }
    }

    suspend fun convertStructuredTextOrCodeToPdf(
        inputFile: File,
        baseName: String,
        outputDir: File,
        tool: ConverterTool,
        options: ConverterOptions,
        cloudAiGateway: CloudAiGateway? = null,
        polishWithAi: Boolean = false,
        allowOfflineFallback: Boolean = true,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): ConverterOutputSummary {
        outputDir.mkdirs()
        onProgress(1, 2, "Formatting ${inputFile.name}…")
        var raw = inputFile.readText(Charsets.UTF_8)
        val ext = inputFile.extension.lowercase()
        var blocks: List<ParsedDocBlock>? = null
        var monospace = tool == ConverterTool.CODE_TO_PDF
        var aiPolished = false

        if (polishWithAi && cloudAiGateway != null && tool == ConverterTool.TEXT_DOC_TO_PDF &&
            ext !in setOf("csv", "tsv")
        ) {
            try {
                onProgress(1, 2, "Polishing line wraps & structuring document…")
                val cleaned = cloudAiGateway.polishDocumentTextLocally(raw)
                raw = cleaned
                blocks = cloudAiGateway.classifyPlainTextToDocxBlocks(cleaned).map { block ->
                    when (block) {
                        is DocxBlock.Paragraph -> when (block.style) {
                            DocxParagraphStyle.TITLE -> ParsedDocBlock.Heading(1, block.text)
                            DocxParagraphStyle.HEADING_1 -> ParsedDocBlock.Heading(1, block.text)
                            DocxParagraphStyle.HEADING_2 -> ParsedDocBlock.Heading(2, block.text)
                            DocxParagraphStyle.BULLET -> ParsedDocBlock.Paragraph(listOf(DocxRun(block.text)), isBullet = true)
                            DocxParagraphStyle.NORMAL -> ParsedDocBlock.Paragraph(listOf(DocxRun(block.text)))
                        }
                        is DocxBlock.Table -> ParsedDocBlock.Table(block.rows)
                    }
                }
                aiPolished = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!allowOfflineFallback) throw e
            }
        }

        if (blocks == null) {
            blocks = when {
                ext == "json" -> {
                    monospace = true
                    listOf(ParsedDocBlock.Heading(1, baseName)) +
                        formatJsonPretty(raw).lines().map { ParsedDocBlock.Paragraph(listOf(DocxRun(it))) }
                }
                ext == "xml" || ext == "svg" || ext == "xsd" -> {
                    monospace = true
                    listOf(ParsedDocBlock.Heading(1, baseName)) +
                        formatXmlPretty(raw).lines().map { ParsedDocBlock.Paragraph(listOf(DocxRun(it))) }
                }
                ext == "csv" || ext == "tsv" -> {
                    val sep = if (ext == "tsv") '\t' else ','
                    val rows = parseDelimitedTable(raw, sep)
                    if (rows.isNotEmpty()) {
                        listOf(ParsedDocBlock.Heading(1, baseName), ParsedDocBlock.Table(rows))
                    } else {
                        raw.lines().map { ParsedDocBlock.Paragraph(listOf(DocxRun(it))) }
                    }
                }
                ext == "md" || ext == "markdown" -> parseMarkdownToBlocks(raw)
                ext == "html" || ext == "htm" -> {
                    val plain = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
                    listOf(ParsedDocBlock.Heading(1, baseName)) +
                        plain.lines().map { ParsedDocBlock.Paragraph(listOf(DocxRun(it))) }
                }
                tool == ConverterTool.CODE_TO_PDF -> {
                    monospace = true
                    listOf(ParsedDocBlock.Heading(1, inputFile.name)) +
                        raw.lines().map { ParsedDocBlock.Paragraph(listOf(DocxRun(it))) }
                }
                else -> raw.lines().map { ParsedDocBlock.Paragraph(listOf(DocxRun(it))) }
            }
        }

        val outFile = File(outputDir, "${sanitizeBaseName(baseName, "document")}.pdf")
        onProgress(2, 2, "Rendering paginated PDF…")
        val pageCount = renderBlocksToPdf(
            blocks = blocks,
            outPdf = outFile,
            pageSize = options.pdfPageSize,
            monospace = monospace,
            showLineNumbers = monospace && options.includeLineNumbers,
        )
        val polishTag = if (aiPolished) " • Smart Formatted" else ""
        return ConverterOutputSummary(
            tool = tool,
            files = listOf(outFile),
            mimeType = "application/pdf",
            summaryNote = "$pageCount PDF page(s) typeset from ${ext.uppercase().ifBlank { "TEXT" }}$polishTag",
            textPreview = raw.take(300),
        )
    }

    fun convertBatchImagesFormat(
        imageFiles: List<File>,
        outputDir: File,
        options: ConverterOptions,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): ConverterOutputSummary {
        outputDir.mkdirs()
        val total = imageFiles.size.coerceAtLeast(1)
        val outFiles = ArrayList<File>(imageFiles.size)
        val compressFmt = toCompressFormat(options.imageFormat)

        imageFiles.forEachIndexed { idx, srcFile ->
            onProgress(idx + 1, total, "Converting image ${idx + 1}/$total…")
            val decoded = BitmapFactory.decodeFile(srcFile.absolutePath) ?: return@forEachIndexed
            val resized = scaleBitmapIfNeeded(decoded, options.resizeCap.maxSidePx)
            val base = sanitizeBaseName(srcFile.nameWithoutExtension, "image_${idx + 1}")
            val outFile = File(outputDir, "${base}.${options.imageFormat.extension}")
            FileOutputStream(outFile).use { fos ->
                resized.compress(compressFmt, options.imageQuality, fos)
            }
            if (resized !== decoded) resized.recycle()
            decoded.recycle()
            outFiles.add(outFile)
        }

        if (outFiles.isEmpty()) error("No valid images could be converted.")
        val zipFile = if (outFiles.size > 1) {
            createZipBundle(outFiles, File(outputDir, "converted_images_${options.imageFormat.extension}.zip"))
        } else null

        return ConverterOutputSummary(
            tool = ConverterTool.BATCH_IMAGE_CONVERT,
            files = outFiles,
            zipArchive = zipFile,
            mimeType = options.imageFormat.mimeType,
            summaryNote = "${outFiles.size} image(s) converted to ${options.imageFormat.label}",
        )
    }

    internal fun renderBlocksToPdf(
        blocks: List<ParsedDocBlock>,
        outPdf: File,
        pageSize: PdfPageSize,
        monospace: Boolean,
        showLineNumbers: Boolean,
    ): Int {
        val pageRect = when (pageSize) {
            PdfPageSize.LETTER -> PDRectangle.LETTER
            PdfPageSize.LEGAL -> PDRectangle(612f, 1008f)
            else -> PDRectangle.A4
        }
        val margin = 48f
        val contentWidth = pageRect.width - 2 * margin
        val doc = PDDocument()
        try {
            val regularFont = loadBestFont(doc, bold = false, italic = false, monospace = monospace)
            val boldFont = loadBestFont(doc, bold = true, italic = false, monospace = monospace)
            val italicFont = loadBestFont(doc, bold = false, italic = true, monospace = monospace)

            var page = PDPage(pageRect)
            doc.addPage(page)
            var cs = PDPageContentStream(doc, page)
            var cursorY = pageRect.height - margin
            var codeLineNo = 1

            fun nextPage() {
                cs.close()
                page = PDPage(pageRect)
                doc.addPage(page)
                cs = PDPageContentStream(doc, page)
                cursorY = pageRect.height - margin
            }

            fun drawLine(text: String, font: PDFont, fontSize: Float, leading: Float, indent: Float = 0f) {
                if (cursorY - leading < margin) nextPage()
                val safe = sanitizeTextForFont(font, text)
                cs.beginText()
                cs.setFont(font, fontSize)
                cs.newLineAtOffset(margin + indent, cursorY - fontSize)
                cs.showText(safe)
                cs.endText()
                cursorY -= leading
            }

            val effectiveBlocks = if (blocks.isEmpty()) {
                listOf(ParsedDocBlock.Paragraph(listOf(DocxRun(""))))
            } else blocks

            effectiveBlocks.forEach { block ->
                when (block) {
                    is ParsedDocBlock.PageBreak -> nextPage()
                    is ParsedDocBlock.Heading -> {
                        val size = when (block.level) {
                            1 -> 17f
                            2 -> 13.5f
                            else -> 11.5f
                        }
                        val leading = size * 1.45f
                        cursorY -= 4f
                        wrapTextToWidth(block.text, boldFont, size, contentWidth).forEach { line ->
                            drawLine(line, boldFont, size, leading)
                        }
                        cursorY -= 4f
                    }
                    is ParsedDocBlock.Paragraph -> {
                        val fontSize = if (monospace) 9.5f else 11f
                        val leading = if (monospace) 13.5f else 15.5f
                        val firstRun = block.runs.firstOrNull()
                        val chosenFont = when {
                            firstRun?.bold == true -> boldFont
                            firstRun?.italic == true -> italicFont
                            else -> regularFont
                        }
                        val rawText = if (showLineNumbers) {
                            val numStr = codeLineNo.toString().padStart(4, ' ')
                            codeLineNo++
                            "$numStr | ${block.plainText}"
                        } else if (block.isBullet) {
                            "• ${block.plainText}"
                        } else {
                            block.plainText
                        }

                        if (rawText.isBlank()) {
                            cursorY -= leading * 0.55f
                        } else {
                            val indent = if (block.isBullet) 10f else 0f
                            wrapTextToWidth(rawText, chosenFont, fontSize, contentWidth - indent).forEach { wrapped ->
                                drawLine(wrapped, chosenFont, fontSize, leading, indent)
                            }
                        }
                    }
                    is ParsedDocBlock.Table -> {
                        val fontSize = 9.5f
                        val leading = 14f
                        block.rows.forEachIndexed { rIdx, row ->
                            val rowText = row.joinToString("  |  ")
                            val rowFont = if (rIdx == 0) boldFont else regularFont
                            wrapTextToWidth(rowText, rowFont, fontSize, contentWidth).forEach { wrapped ->
                                drawLine(wrapped, rowFont, fontSize, leading)
                            }
                        }
                        cursorY -= 6f
                    }
                    is ParsedDocBlock.EmbeddedImage -> {
                        val bmp = BitmapFactory.decodeByteArray(block.bytes, 0, block.bytes.size)
                        if (bmp != null) {
                            val ratio = min(contentWidth / bmp.width, 280f / bmp.height).coerceAtMost(1f)
                            val drawW = bmp.width * ratio
                            val drawH = bmp.height * ratio
                            if (cursorY - drawH < margin) nextPage()
                            val xImg = JPEGFactory.createFromImage(doc, bmp, 0.85f)
                            cs.drawImage(xImg, margin, cursorY - drawH, drawW, drawH)
                            cursorY -= (drawH + 10f)
                            bmp.recycle()
                        }
                    }
                }
            }

            cs.close()
            doc.save(outPdf)
            return doc.numberOfPages
        } finally {
            doc.close()
        }
    }

    private fun loadBestFont(
        doc: PDDocument,
        bold: Boolean,
        italic: Boolean,
        monospace: Boolean,
    ): PDFont {
        val candidates = when {
            monospace && bold -> listOf("/system/fonts/DroidSansMono.ttf", "/system/fonts/RobotoMono-Bold.ttf")
            monospace -> listOf("/system/fonts/DroidSansMono.ttf", "/system/fonts/RobotoMono-Regular.ttf")
            bold -> listOf("/system/fonts/Roboto-Bold.ttf", "/system/fonts/NotoSans-Bold.ttf")
            italic -> listOf("/system/fonts/Roboto-Italic.ttf", "/system/fonts/NotoSans-Italic.ttf")
            else -> listOf("/system/fonts/Roboto-Regular.ttf", "/system/fonts/NotoSans-Regular.ttf")
        }
        candidates.forEach { path ->
            val f = File(path)
            if (f.exists() && f.canRead()) {
                val loaded = runCatching { PDType0Font.load(doc, f) }.getOrNull()
                if (loaded != null) return loaded
            }
        }
        return when {
            monospace && bold -> PDType1Font.COURIER_BOLD
            monospace -> PDType1Font.COURIER
            bold -> PDType1Font.HELVETICA_BOLD
            italic -> PDType1Font.HELVETICA_OBLIQUE
            else -> PDType1Font.HELVETICA
        }
    }

    private fun sanitizeTextForFont(font: PDFont, text: String): String {
        val normalized = text
            .replace("\t", "    ")
            .replace('\u00A0', ' ')
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace('\u201C', '"')
            .replace('\u201D', '"')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace("\u2026", "...")
            .replace('\u2022', '*')
        if (font !is PDType1Font) return normalized
        val sb = StringBuilder(normalized.length)
        for (ch in normalized) {
            val code = ch.code
            if (code in 32..126 || code in 160..255) {
                sb.append(ch)
            } else if (code >= 127) {
                sb.append('?')
            }
        }
        return sb.toString()
    }

    private fun wrapTextToWidth(text: String, font: PDFont, fontSize: Float, maxWidthPt: Float): List<String> {
        val safe = sanitizeTextForFont(font, text)
        if (safe.isEmpty()) return listOf("")
        val approxCharWidth = (fontSize * 0.54f).coerceAtLeast(3f)
        val maxChars = (maxWidthPt / approxCharWidth).toInt().coerceAtLeast(16)
        val lines = mutableListOf<String>()
        var remaining = safe
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxChars) {
                lines.add(remaining)
                break
            }
            var cut = remaining.lastIndexOf(' ', maxChars)
            if (cut <= maxChars / 3) cut = maxChars
            lines.add(remaining.substring(0, cut).trimEnd())
            remaining = remaining.substring(cut).trimStart()
        }
        return lines
    }

    internal fun parseMarkdownToBlocks(md: String): List<ParsedDocBlock> {
        val blocks = mutableListOf<ParsedDocBlock>()
        md.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("### ") -> blocks.add(ParsedDocBlock.Heading(3, trimmed.removePrefix("### ").trim()))
                trimmed.startsWith("## ") -> blocks.add(ParsedDocBlock.Heading(2, trimmed.removePrefix("## ").trim()))
                trimmed.startsWith("# ") -> blocks.add(ParsedDocBlock.Heading(1, trimmed.removePrefix("# ").trim()))
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    blocks.add(ParsedDocBlock.Paragraph(listOf(DocxRun(stripMarkdownInline(trimmed.drop(2)))), isBullet = true))
                }
                else -> {
                    val isBoldLine = trimmed.startsWith("**") && trimmed.endsWith("**") && trimmed.length > 4
                    blocks.add(
                        ParsedDocBlock.Paragraph(
                            listOf(DocxRun(stripMarkdownInline(line), bold = isBoldLine))
                        )
                    )
                }
            }
        }
        return blocks
    }

    private fun stripMarkdownInline(s: String): String = s
        .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
        .replace(Regex("""__(.+?)__"""), "$1")
        .replace(Regex("""`(.+?)`"""), "$1")

    internal fun parseDelimitedTable(csvText: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        csvText.lines().forEach { line ->
            if (line.isBlank()) return@forEach
            val row = mutableListOf<String>()
            val cell = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '"') {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                } else if (c == delimiter && !inQuotes) {
                    row.add(cell.toString().trim())
                    cell.clear()
                } else {
                    cell.append(c)
                }
                i++
            }
            row.add(cell.toString().trim())
            rows.add(row)
        }
        return rows
    }

    internal fun formatJsonPretty(raw: String): String {
        val trimmed = raw.trim()
        return runCatching {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                else -> raw
            }
        }.getOrDefault(raw)
    }

    internal fun formatXmlPretty(raw: String): String {
        return runCatching {
            val transformer = TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.INDENT, "yes")
                setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
            }
            val writer = StringWriter()
            transformer.transform(StreamSource(StringReader(raw)), StreamResult(writer))
            writer.toString()
        }.getOrDefault(raw)
    }

    private fun resolvePdfPageRect(size: PdfPageSize, imgW: Int, imgH: Int): PDRectangle {
        return when (size) {
            PdfPageSize.A4 -> if (imgW > imgH) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
            PdfPageSize.LETTER -> if (imgW > imgH) PDRectangle(PDRectangle.LETTER.height, PDRectangle.LETTER.width) else PDRectangle.LETTER
            PdfPageSize.LEGAL -> if (imgW > imgH) PDRectangle(1008f, 612f) else PDRectangle(612f, 1008f)
            PdfPageSize.AUTO_FIT -> PDRectangle(imgW.toFloat().coerceAtLeast(100f), imgH.toFloat().coerceAtLeast(100f))
        }
    }

    private fun scaleBitmapIfNeeded(src: Bitmap, maxSide: Int): Bitmap {
        if (maxSide <= 0) return src
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val ratio = maxSide.toFloat() / longest
        val w = (src.width * ratio).roundToInt().coerceAtLeast(1)
        val h = (src.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun toCompressFormat(fmt: TargetImageFormat): Bitmap.CompressFormat {
        return when (fmt) {
            TargetImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
            TargetImageFormat.PNG -> Bitmap.CompressFormat.PNG
            TargetImageFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        }
    }

    private fun createZipBundle(files: List<File>, outZip: File): File {
        ZipOutputStream(FileOutputStream(outZip)).use { zos ->
            files.forEach { file ->
                if (file.exists()) {
                    zos.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        return outZip
    }

    private fun sanitizeBaseName(raw: String, fallback: String): String {
        val noExt = raw.substringBeforeLast('.')
        return noExt.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_').ifBlank { fallback }
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
