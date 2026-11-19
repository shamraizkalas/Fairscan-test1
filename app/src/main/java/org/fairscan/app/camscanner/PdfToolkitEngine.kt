package org.fairscan.app.camscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.fairscan.app.domain.PdfCompression
import org.fairscan.app.domain.recompressForPdf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import kotlin.math.roundToInt

sealed interface PdfInspectResult {
    data class Ready(
        val pageCount: Int,
        val isEncrypted: Boolean,
        val fileSizeBytes: Long,
    ) : PdfInspectResult

    data class PasswordRequired(val wrongPassword: Boolean) : PdfInspectResult
    data class Invalid(val message: String) : PdfInspectResult
}

sealed interface MergeSourceSpec {
    val displayName: String

    data class ExternalPdf(
        val file: File,
        val password: String? = null,
        override val displayName: String,
    ) : MergeSourceSpec

    data class LibraryImages(
        val imageFiles: List<Pair<File, Int>>,
        override val displayName: String,
    ) : MergeSourceSpec
}

data class PageTransformEntry(
    val zeroBasedIndex: Int,
    val extraRotationDegrees: Int = 0,
)

data class PdfOperationResult(
    val file: File,
    val pageCount: Int,
    val sizeBytes: Long,
)

data class PdfCompressionResult(
    val file: File,
    val pageCount: Int,
    val originalBytes: Long,
    val compressedBytes: Long,
)

sealed interface PdfUnlockResult {
    data class Success(val file: File, val pageCount: Int, val sizeBytes: Long) : PdfUnlockResult
    data object WrongPassword : PdfUnlockResult
    data object NotEncrypted : PdfUnlockResult
    data class Failed(val message: String) : PdfUnlockResult
}

object PdfToolkitEngine {

    fun copyUriToTempFile(context: Context, uri: Uri, targetDir: File): Pair<File, String> {
        targetDir.mkdirs()
        val displayName = resolveDisplayName(context, uri)
        val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outFile = File(targetDir, "import_${System.currentTimeMillis()}_$safeName").let {
            if (it.extension.equals("pdf", true)) it else File(targetDir, "${it.nameWithoutExtension}.pdf")
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to open selected PDF")
        return outFile to displayName.removeSuffix(".pdf").removeSuffix(".PDF")
    }

    fun resolveDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(idx)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "Document.pdf" } ?: "Document.pdf"
    }

    fun inspectPdf(pdfFile: File, password: String? = null): PdfInspectResult {
        if (!pdfFile.exists() || pdfFile.length() == 0L) {
            return PdfInspectResult.Invalid("PDF file is empty or missing")
        }
        return try {
            val doc = if (password.isNullOrEmpty()) {
                PDDocument.load(pdfFile)
            } else {
                PDDocument.load(pdfFile, password)
            }
            doc.use {
                PdfInspectResult.Ready(
                    pageCount = it.numberOfPages,
                    isEncrypted = it.isEncrypted,
                    fileSizeBytes = pdfFile.length(),
                )
            }
        } catch (_: InvalidPasswordException) {
            PdfInspectResult.PasswordRequired(wrongPassword = !password.isNullOrEmpty())
        } catch (e: Exception) {
            PdfInspectResult.Invalid(e.message ?: "Unable to read PDF")
        }
    }

    fun renderPdfPagesToBitmaps(
        pdfFile: File,
        password: String? = null,
        maxLongEdgePx: Int = 2000,
        tempCacheDir: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<Bitmap> {
        val renderTarget = prepareRenderablePdf(pdfFile, password, tempCacheDir)
        val deleteTemp = renderTarget.absolutePath != pdfFile.absolutePath
        try {
            ParcelFileDescriptor.open(renderTarget, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val count = renderer.pageCount
                    val results = ArrayList<Bitmap>(count)
                    for (i in 0 until count) {
                        renderer.openPage(i).use { page ->
                            val scale = minOf(
                                maxLongEdgePx.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1),
                                3.0f
                            ).coerceAtLeast(0.5f)
                            val w = (page.width * scale).roundToInt().coerceAtLeast(1)
                            val h = (page.height * scale).roundToInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            Canvas(bmp).drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            results.add(bmp)
                            onProgress(i + 1, count)
                        }
                    }
                    return results
                }
            }
        } finally {
            if (deleteTemp) renderTarget.delete()
        }
    }

    fun renderThumbnails(
        pdfFile: File,
        password: String? = null,
        maxThumbEdgePx: Int = 260,
        tempCacheDir: File,
    ): List<Bitmap> = renderPdfPagesToBitmaps(
        pdfFile = pdfFile,
        password = password,
        maxLongEdgePx = maxThumbEdgePx,
        tempCacheDir = tempCacheDir,
    )

    private fun prepareRenderablePdf(pdfFile: File, password: String?, tempCacheDir: File): File {
        if (password.isNullOrEmpty()) {
            // Check if PDF is unencrypted or encrypted with empty owner password
            PDDocument.load(pdfFile).use { doc ->
                if (!doc.isEncrypted) return pdfFile
                tempCacheDir.mkdirs()
                val unlocked = File(tempCacheDir, "dec_${System.currentTimeMillis()}.pdf")
                doc.isAllSecurityToBeRemoved = true
                doc.save(unlocked)
                return unlocked
            }
        }
        tempCacheDir.mkdirs()
        val unlocked = File(tempCacheDir, "dec_${System.currentTimeMillis()}.pdf")
        PDDocument.load(pdfFile, password).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(unlocked)
        }
        return unlocked
    }

    fun buildPdfFromImageFiles(
        imageFiles: List<Pair<File, Int>>,
        outputPdf: File,
        compression: PdfCompression = PdfCompression.BALANCED,
    ): PdfOperationResult {
        outputPdf.parentFile?.mkdirs()
        val doc = PDDocument()
        doc.documentInformation.creationDate = Calendar.getInstance()
        doc.documentInformation.creator = "ProScan Studio"
        var writtenPages = 0
        doc.use { document ->
            imageFiles.forEach { (file, rotationDeg) ->
                val raw = BitmapFactory.decodeFile(file.absolutePath) ?: return@forEach
                val rotated = if (rotationDeg % 360 != 0) {
                    val m = Matrix().apply { postRotate((rotationDeg % 360).toFloat()) }
                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
                        if (it !== raw) raw.recycle()
                    }
                } else raw

                val jpegBytes = bitmapToCompressedJpeg(rotated, compression)
                val pdImg = JPEGFactory.createFromByteArray(document, jpegBytes)
                val pointsPerMm = 72f / 25.4f
                val maxMm = 297f
                val scale = maxMm / maxOf(rotated.width, rotated.height).coerceAtLeast(1)
                val wPt = (rotated.width * scale * pointsPerMm).coerceAtLeast(72f)
                val hPt = (rotated.height * scale * pointsPerMm).coerceAtLeast(72f)
                rotated.recycle()

                val page = PDPage(PDRectangle(wPt, hPt))
                document.addPage(page)
                PDPageContentStream(document, page).use { cs ->
                    cs.drawImage(pdImg, 0f, 0f, wPt, hPt)
                }
                writtenPages++
            }
            document.save(outputPdf)
        }
        return PdfOperationResult(outputPdf, writtenPages, outputPdf.length())
    }

    fun mergePdfSources(
        sources: List<MergeSourceSpec>,
        outputPdf: File,
        tempCacheDir: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): PdfOperationResult {
        require(sources.isNotEmpty()) { "Select at least one document to merge" }
        outputPdf.parentFile?.mkdirs()
        tempCacheDir.mkdirs()

        val openedDocs = mutableListOf<PDDocument>()
        val tempFiles = mutableListOf<File>()
        try {
            val outDoc = PDDocument()
            outDoc.documentInformation.creationDate = Calendar.getInstance()
            outDoc.documentInformation.creator = "ProScan Studio"
            outDoc.use { merged ->
                sources.forEachIndexed { idx, source ->
                    when (source) {
                        is MergeSourceSpec.ExternalPdf -> {
                            val srcDoc = if (source.password.isNullOrEmpty()) {
                                PDDocument.load(source.file)
                            } else {
                                PDDocument.load(source.file, source.password)
                            }
                            if (srcDoc.isEncrypted) {
                                srcDoc.isAllSecurityToBeRemoved = true
                            }
                            openedDocs.add(srcDoc)
                            for (p in 0 until srcDoc.numberOfPages) {
                                merged.importPage(srcDoc.getPage(p))
                            }
                        }
                        is MergeSourceSpec.LibraryImages -> {
                            val tmpPdf = File(tempCacheDir, "lib_merge_${idx}_${System.currentTimeMillis()}.pdf")
                            tempFiles.add(tmpPdf)
                            buildPdfFromImageFiles(source.imageFiles, tmpPdf, PdfCompression.HIGH)
                            val srcDoc = PDDocument.load(tmpPdf)
                            openedDocs.add(srcDoc)
                            for (p in 0 until srcDoc.numberOfPages) {
                                merged.importPage(srcDoc.getPage(p))
                            }
                        }
                    }
                    onProgress(idx + 1, sources.size)
                }
                merged.save(outputPdf)
                return PdfOperationResult(outputPdf, merged.numberOfPages, outputPdf.length())
            }
        } finally {
            openedDocs.forEach { runCatching { it.close() } }
            tempFiles.forEach { it.delete() }
        }
    }

    fun parsePageRangeSpec(spec: String, totalPages: Int): List<Int> {
        if (totalPages <= 0) return emptyList()
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) return emptyList()
        val selected = LinkedHashSet<Int>()
        trimmed.split(',').forEach { token ->
            val part = token.trim()
            if (part.isEmpty()) return@forEach
            if ('-' in part) {
                val bounds = part.split('-', limit = 2)
                val start = bounds.getOrNull(0)?.trim()?.toIntOrNull()
                val end = bounds.getOrNull(1)?.trim()?.toIntOrNull()
                if (start != null && end != null) {
                    val s = start.coerceIn(1, totalPages)
                    val e = end.coerceIn(1, totalPages)
                    val range = if (s <= e) s..e else s downTo e
                    for (p in range) selected.add(p - 1)
                }
            } else {
                val pageNum = part.toIntOrNull()
                if (pageNum != null && pageNum in 1..totalPages) {
                    selected.add(pageNum - 1)
                }
            }
        }
        return selected.toList()
    }

    fun extractOrOrganizePdf(
        sourceFile: File,
        password: String?,
        pageEntries: List<PageTransformEntry>,
        outputPdf: File,
    ): PdfOperationResult {
        require(pageEntries.isNotEmpty()) { "Select at least one page" }
        outputPdf.parentFile?.mkdirs()
        val srcDoc = if (password.isNullOrEmpty()) {
            PDDocument.load(sourceFile)
        } else {
            PDDocument.load(sourceFile, password)
        }
        srcDoc.use { input ->
            if (input.isEncrypted) input.isAllSecurityToBeRemoved = true
            val outDoc = PDDocument()
            outDoc.documentInformation.creationDate = Calendar.getInstance()
            outDoc.documentInformation.creator = "ProScan Studio"
            outDoc.use { output ->
                pageEntries.forEach { entry ->
                    if (entry.zeroBasedIndex in 0 until input.numberOfPages) {
                        val imported = output.importPage(input.getPage(entry.zeroBasedIndex))
                        if (entry.extraRotationDegrees % 360 != 0) {
                            val currentRot = imported.rotation
                            imported.rotation = ((currentRot + entry.extraRotationDegrees) % 360 + 360) % 360
                        }
                    }
                }
                output.save(outputPdf)
                return PdfOperationResult(outputPdf, output.numberOfPages, outputPdf.length())
            }
        }
    }

    fun splitPdfToSinglePages(
        sourceFile: File,
        password: String?,
        selectedZeroBasedIndices: List<Int>,
        outputDir: File,
        baseName: String,
    ): List<PdfOperationResult> {
        require(selectedZeroBasedIndices.isNotEmpty()) { "Select at least one page to split" }
        outputDir.mkdirs()
        val srcDoc = if (password.isNullOrEmpty()) {
            PDDocument.load(sourceFile)
        } else {
            PDDocument.load(sourceFile, password)
        }
        val cleanBase = baseName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "Page" }
        val results = mutableListOf<PdfOperationResult>()
        srcDoc.use { input ->
            if (input.isEncrypted) input.isAllSecurityToBeRemoved = true
            selectedZeroBasedIndices.forEach { index ->
                if (index in 0 until input.numberOfPages) {
                    val outFile = File(outputDir, "${cleanBase}_page_${index + 1}.pdf")
                    PDDocument().use { single ->
                        single.importPage(input.getPage(index))
                        single.save(outFile)
                    }
                    results.add(PdfOperationResult(outFile, 1, outFile.length()))
                }
            }
        }
        return results
    }

    fun compressPdf(
        sourceFile: File,
        password: String?,
        compression: PdfCompression,
        tempCacheDir: File,
        outputPdf: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): PdfCompressionResult {
        outputPdf.parentFile?.mkdirs()
        val originalBytes = sourceFile.length()
        val bitmaps = renderPdfPagesToBitmaps(
            pdfFile = sourceFile,
            password = password,
            maxLongEdgePx = compression.maxLongEdgePx,
            tempCacheDir = tempCacheDir,
            onProgress = onProgress,
        )
        val outDoc = PDDocument()
        outDoc.documentInformation.creationDate = Calendar.getInstance()
        outDoc.documentInformation.creator = "ProScan Studio"
        var pageCount = 0
        outDoc.use { doc ->
            bitmaps.forEach { bmp ->
                val jpegBytes = bitmapToCompressedJpeg(bmp, compression)
                val pdImg = JPEGFactory.createFromByteArray(doc, jpegBytes)
                val pointsPerMm = 72f / 25.4f
                val maxMm = 297f
                val scale = maxMm / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
                val wPt = (bmp.width * scale * pointsPerMm).coerceAtLeast(72f)
                val hPt = (bmp.height * scale * pointsPerMm).coerceAtLeast(72f)
                bmp.recycle()

                val page = PDPage(PDRectangle(wPt, hPt))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(pdImg, 0f, 0f, wPt, hPt)
                }
                pageCount++
            }
            doc.save(outputPdf)
        }
        return PdfCompressionResult(
            file = outputPdf,
            pageCount = pageCount,
            originalBytes = originalBytes,
            compressedBytes = outputPdf.length(),
        )
    }

    fun unlockPdf(
        sourceFile: File,
        password: String,
        outputPdf: File,
    ): PdfUnlockResult {
        if (!sourceFile.exists()) return PdfUnlockResult.Failed("Source PDF not found")
        outputPdf.parentFile?.mkdirs()
        return try {
            PDDocument.load(sourceFile, password).use { doc ->
                if (!doc.isEncrypted) {
                    return PdfUnlockResult.NotEncrypted
                }
                doc.isAllSecurityToBeRemoved = true
                doc.save(outputPdf)
            }
            val count = PDDocument.load(outputPdf).use { it.numberOfPages }
            PdfUnlockResult.Success(outputPdf, count, outputPdf.length())
        } catch (_: InvalidPasswordException) {
            PdfUnlockResult.WrongPassword
        } catch (e: Exception) {
            PdfUnlockResult.Failed(e.message ?: "Failed to unlock PDF")
        }
    }

    private fun bitmapToCompressedJpeg(bitmap: Bitmap, compression: PdfCompression): ByteArray {
        val rawOut = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, compression.jpegQuality, rawOut)
        val bytes = rawOut.toByteArray()
        return recompressForPdf(bytes, compression)
    }
}
