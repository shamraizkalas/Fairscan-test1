/*
 * Copyright 2025-2026 The FairScan authors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.fairscan.app.ui.screens.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fairscan.app.AppContainer
import org.fairscan.app.R
import org.fairscan.app.camscanner.PdfProjectCodec
import org.fairscan.app.data.FileManager
import org.fairscan.app.data.ImageRepository
import org.fairscan.app.domain.ExportQuality
import org.fairscan.app.domain.PageViewKey
import org.fairscan.app.domain.PdfCompression
import org.fairscan.app.domain.PdfExportOptions
import org.fairscan.app.domain.PdfPageNumberStyle
import org.fairscan.app.domain.PdfPageSize
import org.fairscan.app.domain.StudioExportFormat
import org.fairscan.app.domain.jpegsToExport
import org.fairscan.app.domain.pagesToExport
import org.fairscan.app.ui.screens.settings.ExportFormat
import org.fairscan.imageprocessing.ColorMode
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

sealed interface ExportEvent {
    data object RequestSave : ExportEvent
    data class Share(val result: ExportResult) : ExportEvent
}

class ExportViewModel(container: AppContainer, val imageRepository: ImageRepository) : ViewModel() {

    private val preparationDir = container.preparationDir
    private val fileManager = container.fileManager
    private val settingsRepository = container.settingsRepository
    private val ocrService = container.ocrService
    private val logger = container.logger

    private val _events = MutableSharedFlow<ExportEvent>()
    val events = _events.asSharedFlow()

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    private var lastPreparationKey: ExportPreparationKey? = null
    private var preparationJob: Job? = null
    private var hasInitializedFormatFromSettings = false

    private suspend fun generatePdf(
        exportQuality: ExportQuality,
        disableOcr: Boolean,
        options: PdfExportOptions,
        onPageCompleted: (Int) -> Unit,
    ): ExportResult.Pdf = withContext(Dispatchers.IO) {
        val pageToExports = pagesToExport(imageRepository, exportQuality)
        val pdf = fileManager.generatePdf(pageToExports, disableOcr, options, onPageCompleted)
        preparationDir.mkdirs()
        val tempZip = File(preparationDir, "proj_${System.currentTimeMillis()}.zip")
        try {
            val titleHint = _uiState.value.filename.ifBlank { defaultFilename() }
            if (imageRepository.exportProjectArchive(tempZip, titleHint)) {
                PdfProjectCodec.embedProjectArchive(
                    pdfFile = pdf.file,
                    projectZipFile = tempZip,
                    password = options.password.ifBlank { null },
                )
            }
        } catch (e: Exception) {
            logger.e("ExportViewModel", "Failed to embed editable layer archive in PDF", e)
        } finally {
            tempZip.delete()
        }
        return@withContext ExportResult.Pdf(pdf.file, pdf.file.length(), pdf.pageCount)
    }

    suspend fun generatePdfForExternalCall(): ExportResult.Pdf {
        val pdf = generatePdf(ExportQuality.BALANCED, true, PdfExportOptions()) {}
        val sourceFile = pdf.file
        val targetFile = File(sourceFile.parentFile, defaultFilename() + ".pdf")
        if (sourceFile.absolutePath == targetFile.absolutePath) return pdf
        if (targetFile.exists() || !sourceFile.renameTo(targetFile)) return pdf
        return pdf.copy(file = targetFile)
    }

    fun setFilename(name: String) {
        _uiState.update {
            it.copy(filename = name, isUserDefinedFilename = true)
        }
    }

    fun resetFilename() {
        hasInitializedFormatFromSettings = false
        lastPreparationKey = null
        _uiState.update {
            ExportUiState()
        }
    }

    fun setStudioFormat(format: StudioExportFormat) {
        hasInitializedFormatFromSettings = true
        if (_uiState.value.format == format) return
        _uiState.update { it.copy(format = format, savedBundle = null, error = null) }
        prepareExportIfNeeded()
    }

    fun setPageSize(pageSize: PdfPageSize) {
        if (_uiState.value.pageSize == pageSize) return
        _uiState.update { it.copy(pageSize = pageSize, savedBundle = null, error = null) }
        prepareExportIfNeeded()
    }

    fun setCompression(compression: PdfCompression) {
        if (_uiState.value.compression == compression) return
        _uiState.update { it.copy(compression = compression, savedBundle = null, error = null) }
        prepareExportIfNeeded()
    }

    fun setPasswordInput(password: String) {
        _uiState.update { it.copy(passwordInput = password) }
    }

    fun applyPassword() {
        val pw = _uiState.value.passwordInput.trim()
        _uiState.update { it.copy(appliedPassword = pw, savedBundle = null, error = null) }
        prepareExportIfNeeded()
    }

    fun setPageNumberStyle(style: PdfPageNumberStyle) {
        if (_uiState.value.pageNumberStyle == style) return
        _uiState.update { it.copy(pageNumberStyle = style, savedBundle = null, error = null) }
        prepareExportIfNeeded()
    }

    fun setHeaderText(text: String) {
        _uiState.update { it.copy(headerText = text.take(80), savedBundle = null, error = null) }
    }

    fun setFooterText(text: String) {
        _uiState.update { it.copy(footerText = text.take(80), savedBundle = null, error = null) }
    }

    fun applyHeaderFooter() {
        prepareExportIfNeeded()
    }

    private fun defaultFilename(): String {
        return settingsRepository.defaultFilenameStyle.value.filename()
    }

    private fun ensureValidFilename() {
        _uiState.update {
            val normalized = it.filename.trim()
            if (normalized.isEmpty() || !it.isUserDefinedFilename) {
                it.copy(filename = defaultFilename(), isUserDefinedFilename = false)
            } else if (normalized != it.filename) {
                it.copy(filename = normalized)
            } else it
        }
    }

    private suspend fun currentPageKeys(): ImmutableList<PageViewKey> =
        imageRepository.pages().map { it.key() }.toImmutableList()

    fun cancelPreparationJob() {
        preparationJob?.let {
            if (it.isActive) {
                _uiState.update { s -> s.copy(isGenerating = false) }
            }
            it.cancel()
        }
    }

    fun prepareExportIfNeeded() {
        ensureValidFilename()

        viewModelScope.launch {
            if (!hasInitializedFormatFromSettings) {
                hasInitializedFormatFromSettings = true
                val defaultSettingsFormat = settingsRepository.exportFormat.first()
                val mapped = when (defaultSettingsFormat) {
                    ExportFormat.PDF -> StudioExportFormat.PDF
                    ExportFormat.JPEG -> StudioExportFormat.JPEG
                }
                _uiState.update { it.copy(format = mapped) }
            }

            val settingsQuality = settingsRepository.exportQuality.first()
            val ocrLanguageString = ocrService.languageString()
            val currentState = _uiState.value
            val studioFormat = currentState.format
            val effectiveQuality = when (currentState.compression) {
                PdfCompression.HIGH -> ExportQuality.HIGH
                PdfCompression.BALANCED -> settingsQuality
                PdfCompression.COMPACT, PdfCompression.SMALLEST -> ExportQuality.LOW
            }

            val currentPageKeys = currentPageKeys()
            val key = ExportPreparationKey(
                pages = currentPageKeys,
                format = studioFormat,
                quality = effectiveQuality,
                pageSize = currentState.pageSize,
                compression = currentState.compression,
                password = currentState.appliedPassword,
                pageNumberStyle = currentState.pageNumberStyle,
                headerText = currentState.headerText.trim(),
                footerText = currentState.footerText.trim(),
                ocrLanguageString = ocrLanguageString,
                layersHash = imageRepository.pageEditStates.value.hashCode(),
            )
            if (key == lastPreparationKey && _uiState.value.result != null) {
                return@launch
            }
            val pageCount = currentPageKeys.size
            if (pageCount == 0) return@launch

            lastPreparationKey = key
            preparationJob?.cancel()
            preparationJob?.join()

            preparationJob = launch {
                val ocrActivation = if (studioFormat == StudioExportFormat.PDF ||
                    studioFormat == StudioExportFormat.TXT ||
                    studioFormat == StudioExportFormat.DOC
                ) ocrLanguageString.isNotEmpty() else null

                val blackAndWhiteAsJpeg = studioFormat == StudioExportFormat.JPEG &&
                    currentPageKeys.any { it.colorMode == ColorMode.BLACK_AND_WHITE }

                _uiState.update {
                    it.copy(
                        isGenerating = true,
                        progress = ExportProgress(0, pageCount),
                        ocrActivation = ocrActivation,
                        ocrLanguagesSummary = ocrLanguageString,
                        blackAndWhiteAsJpeg = blackAndWhiteAsJpeg,
                        error = null,
                    )
                }

                val onPageCompleted: (Int) -> Unit = { completedPages ->
                    ensureActive()
                    _uiState.update {
                        it.copy(progress = ExportProgress(completedPages, pageCount))
                    }
                }

                try {
                    val t1 = System.currentTimeMillis()
                    val result: ExportResult = when (studioFormat) {
                        StudioExportFormat.JPEG -> generateJpegs(effectiveQuality, onPageCompleted)
                        StudioExportFormat.PDF -> generatePdf(
                            effectiveQuality,
                            disableOcr = false,
                            options = PdfExportOptions(
                                pageSize = currentState.pageSize,
                                compression = currentState.compression,
                                password = currentState.appliedPassword,
                                pageNumberStyle = currentState.pageNumberStyle,
                                headerText = currentState.headerText.trim(),
                                footerText = currentState.footerText.trim(),
                            ),
                            onPageCompleted = onPageCompleted
                        )
                        StudioExportFormat.TXT -> generateTextDocument(asWordDoc = false, onPageCompleted)
                        StudioExportFormat.DOC -> generateTextDocument(asWordDoc = true, onPageCompleted)
                    }
                    _uiState.update { it.copy(result = result) }
                    val t2 = System.currentTimeMillis()
                    Log.i("Export", "Generation ($studioFormat): $pageCount pages, ${t2 - t1} ms")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = "Failed to prepare $studioFormat export"
                    logger.e("FairScan", message, e)
                    _uiState.update {
                        it.copy(error = ExportError.OnPrepareOrShare(message, e))
                    }
                } finally {
                    _uiState.update { it.copy(isGenerating = false) }
                }
            }
        }
    }

    private suspend fun generateJpegs(
        exportQuality: ExportQuality,
        onPageCompleted: (Int) -> Unit,
    ): ExportResult.Jpeg = withContext(Dispatchers.IO) {
        val jpegPages = jpegsToExport(imageRepository, exportQuality)
        val timestamp = System.currentTimeMillis()
        preparationDir.mkdirs()
        val files = jpegPages.mapIndexed { index, page ->
            val file = File(preparationDir, "$timestamp-${index + 1}.jpg")
            file.writeBytes(page.image.get().bytes)
            onPageCompleted(index + 1)
            file
        }.toList()
        val sizeInBytes = files.sumOf { it.length() }
        ExportResult.Jpeg(files, sizeInBytes)
    }

    private suspend fun generateTextDocument(
        asWordDoc: Boolean,
        onPageCompleted: (Int) -> Unit,
    ): ExportResult.TextDoc = withContext(Dispatchers.IO) {
        val pages = imageRepository.pages()
        preparationDir.mkdirs()
        val ext = if (asWordDoc) "doc" else "txt"
        val format = if (asWordDoc) StudioExportFormat.DOC else StudioExportFormat.TXT
        val file = File(preparationDir, "ocr_${System.currentTimeMillis()}.$ext")

        val extractedPages = pages.mapIndexed { index, scanPage ->
            val bmp = imageRepository.image(scanPage.key())?.toBitmap()
            val text = if (bmp != null) {
                try {
                    ocrService.extractFullText(bmp)
                } finally {
                    bmp.recycle()
                }
            } else ""
            onPageCompleted(index + 1)
            text
        }

        val content = if (asWordDoc) {
            buildString {
                append("<html xmlns:w=\"urn:schemas-microsoft-com:office:word\">")
                append("<head><meta charset=\"utf-8\"/><title>Scanned Document</title></head>")
                append("<body style=\"font-family: sans-serif; line-height: 1.5;\">")
                extractedPages.forEachIndexed { idx, pageText ->
                    append("<h3>Page ${idx + 1}</h3>")
                    val safe = pageText
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\n", "<br/>")
                    append("<p>${safe.ifBlank { "(No OCR text detected on this page)" }}</p>")
                    if (idx < extractedPages.lastIndex) append("<hr/>")
                }
                append("</body></html>")
            }
        } else {
            extractedPages.mapIndexed { idx, pageText ->
                "--- Page ${idx + 1} ---\n${pageText.ifBlank { "(No OCR text detected)" }}"
            }.joinToString("\n\n")
        }

        file.writeText(content, Charsets.UTF_8)
        ExportResult.TextDoc(file, file.length(), pages.size, format)
    }

    private fun renameFile(source: File, target: File) {
        if (source.absolutePath == target.absolutePath) return
        if (target.exists() && !target.delete()) {
            throw IOException("Cannot delete existing file ${target.absolutePath}")
        }
        if (!source.renameTo(target)) {
            throw IOException("Failed to rename ${source.name} to ${target.name}")
        }
    }

    private fun applyRenaming(): ExportResult {
        val result = _uiState.value.result
            ?: throw IllegalStateException("Export result missing")
        ensureValidFilename()
        val filename = _uiState.value.filename
        val updated = when (result) {
            is ExportResult.Pdf -> {
                val fileName = FileManager.addPdfExtensionIfMissing(filename)
                val newFile = File(result.file.parentFile, fileName)
                renameFile(result.file, newFile)
                ExportResult.Pdf(newFile, result.sizeInBytes, result.pageCount)
            }
            is ExportResult.Jpeg -> {
                val base = filename.removeSuffix(".jpg")
                val files = result.files
                val renamedFiles = files.mapIndexed { index, file ->
                    val indexSuffix = if (files.size == 1) "" else "_${index + 1}"
                    val newFile = File(file.parentFile, "${base}${indexSuffix}.jpg")
                    renameFile(file, newFile)
                    newFile
                }
                result.copy(jpegFiles = renamedFiles)
            }
            is ExportResult.TextDoc -> {
                val ext = result.format.extension
                val base = filename.removeSuffix(".$ext")
                val newFile = File(result.file.parentFile, "$base.$ext")
                renameFile(result.file, newFile)
                result.copy(file = newFile)
            }
        }
        _uiState.update { it.copy(result = updated) }
        return updated
    }

    fun onShareClicked() {
        viewModelScope.launch {
            try {
                val result = applyRenaming()
                _events.emit(ExportEvent.Share(result))
                _uiState.update { it.copy(hasShared = true) }
            } catch (e: Exception) {
                val message = "Failed to prepare share"
                logger.e("FairScan", message, e)
                _uiState.update { it.copy(error = ExportError.OnPrepareOrShare(message, e)) }
            }
        }
    }

    fun onSaveClicked() {
        viewModelScope.launch {
            _events.emit(ExportEvent.RequestSave)
        }
    }

    fun onRequestSave(context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null, savedBundle = null) }
            val exportFormat = uiState.value.format
            val saveDir = saveDir(context)
            try {
                withContext(Dispatchers.IO) {
                    save(context, saveDir, exportFormat)
                }
            } catch (e: MissingExportDirPermissionException) {
                logger.e("FairScan", "Missing export dir permission", e)
                _uiState.update {
                    it.copy(error = ExportError.OnSave(R.string.error_export_dir_permission_lost, saveDir))
                }
            } catch (e: Exception) {
                logger.e("FairScan", "Failed to save export", e)
                _uiState.update {
                    it.copy(error = ExportError.OnSave(R.string.error_save, saveDir, e))
                }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private suspend fun saveDir(context: Context): SaveDir? {
        val uri = settingsRepository.exportDirUri.first()?.toUri() ?: return null
        val name = resolveExportDirName(context, uri)
        return SaveDir(uri, name)
    }

    private suspend fun save(context: Context, saveDir: SaveDir?, exportFormat: StudioExportFormat) {
        val result = applyRenaming()
        val savedItems = mutableListOf<SavedItem>()
        val filesForMediaScan = mutableListOf<File>()

        for (file in result.files) {
            val saved = if (saveDir == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val uri = saveViaMediaStore(context, file, exportFormat)
                    SavedItem(uri, file.name, exportFormat)
                } else {
                    val out = fileManager.copyToExternalDir(file)
                    filesForMediaScan.add(out)
                    SavedItem(out.toUri(), out.name, exportFormat)
                }
            } else {
                if (!context.contentResolver.persistedUriPermissions.any { perm ->
                        perm.uri == saveDir.uri && perm.isWritePermission
                    }) {
                    throw MissingExportDirPermissionException(saveDir.uri)
                }
                val safFile = saveViaSaf(context, file, saveDir.uri, exportFormat)
                SavedItem(safFile.uri, safFile.name ?: file.name, exportFormat)
            }
            savedItems += saved
        }

        val bundle = SavedBundle(savedItems, saveDir)
        _uiState.update { it.copy(savedBundle = bundle) }

        filesForMediaScan.forEach { f -> mediaScan(context, f, exportFormat.mimeType) }
    }

    private suspend fun mediaScan(context: Context, file: File, mimeType: String): Uri? =
        suspendCoroutine { cont ->
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(mimeType)
            ) { _, uri -> cont.resume(uri) }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        context: Context,
        source: File,
        format: StudioExportFormat,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Failed to create MediaStore entry")

        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        } ?: throw IOException("Failed to open output stream")

        return uri
    }

    private fun saveViaSaf(
        context: Context,
        source: File,
        exportDirUri: Uri,
        exportFormat: StudioExportFormat,
    ): DocumentFile {
        val resolver = context.contentResolver
        val tree = DocumentFile.fromTreeUri(context, exportDirUri)
            ?: throw IllegalStateException("Invalid SAF directory")
        val target = tree.createFile(exportFormat.mimeType, source.name)
            ?: throw IllegalStateException("Unable to create SAF file")

        resolver.openOutputStream(target.uri)?.use { output ->
            FileInputStream(source).use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("Failed to open SAF output stream")

        return target
    }

    fun cleanUpOldPreparedFiles(thresholdInMillis: Int) {
        fileManager.cleanUpOldFiles(thresholdInMillis)
    }

    private fun resolveExportDirName(context: Context, exportDirUri: Uri?): String? {
        return if (exportDirUri == null) null else DocumentFile.fromTreeUri(context, exportDirUri)?.name
    }
}

data class ExportPreparationKey(
    val pages: ImmutableList<PageViewKey>,
    val format: StudioExportFormat,
    val quality: ExportQuality,
    val pageSize: PdfPageSize,
    val compression: PdfCompression,
    val password: String,
    val pageNumberStyle: PdfPageNumberStyle,
    val headerText: String,
    val footerText: String,
    val ocrLanguageString: String,
    val layersHash: Int = 0,
)

sealed class ExportResult {
    abstract val files: List<File>
    abstract val sizeInBytes: Long
    abstract val pageCount: Int
    abstract val format: StudioExportFormat

    data class Pdf(
        val file: File,
        override val sizeInBytes: Long,
        override val pageCount: Int,
    ) : ExportResult() {
        override val files get() = listOf(file)
        override val format: StudioExportFormat = StudioExportFormat.PDF
    }

    data class Jpeg(
        val jpegFiles: List<File>,
        override val sizeInBytes: Long,
    ) : ExportResult() {
        override val files get() = jpegFiles
        override val pageCount get() = jpegFiles.size
        override val format: StudioExportFormat = StudioExportFormat.JPEG
    }

    data class TextDoc(
        val file: File,
        override val sizeInBytes: Long,
        override val pageCount: Int,
        override val format: StudioExportFormat,
    ) : ExportResult() {
        override val files get() = listOf(file)
    }
}

data class ExportActions(
    val prepareExportIfNeeded: () -> Unit,
    val setFilename: (String) -> Unit,
    val setFormat: (StudioExportFormat) -> Unit = {},
    val setPageSize: (PdfPageSize) -> Unit = {},
    val setCompression: (PdfCompression) -> Unit = {},
    val setPageNumberStyle: (PdfPageNumberStyle) -> Unit = {},
    val setHeaderText: (String) -> Unit = {},
    val setFooterText: (String) -> Unit = {},
    val applyHeaderFooter: () -> Unit = {},
    val setPasswordInput: (String) -> Unit = {},
    val applyPassword: () -> Unit = {},
    val share: () -> Unit,
    val save: () -> Unit,
    val open: (SavedItem) -> Unit,
    val cancelPreparationJob: () -> Unit,
)

class MissingExportDirPermissionException(
    val uri: Uri,
) : IllegalStateException(
    "Missing persisted write permission for export dir: $uri"
)
