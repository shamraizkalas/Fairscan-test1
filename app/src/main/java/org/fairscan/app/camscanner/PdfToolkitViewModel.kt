package org.fairscan.app.camscanner

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fairscan.app.domain.PdfCompression
import org.fairscan.app.library.LibraryRepository
import org.fairscan.app.library.SavedDocumentEntity
import java.io.File
import java.util.UUID

enum class PdfToolkitTool(val title: String) {
    HUB("PDF Tools"),
    IMPORT("Import & Edit PDF"),
    MERGE("Merge PDFs"),
    SPLIT("Split & Extract"),
    ORGANIZE("Organize Pages"),
    COMPRESS("Compress PDF"),
    UNLOCK("Unlock PDF"),
}

data class ToolkitSourceItem(
    val id: String,
    val title: String,
    val pdfFile: File,
    val password: String? = null,
    val pageCount: Int,
    val sizeBytes: Long,
    val isFromLibrary: Boolean = false,
)

data class ToolkitPageItem(
    val zeroBasedIndex: Int,
    val thumbnail: Bitmap?,
    val isSelected: Boolean = true,
    val rotationDegrees: Int = 0,
)

data class PendingEncryptedPdf(
    val file: File,
    val title: String,
    val targetTool: PdfToolkitTool,
    val wrongPassword: Boolean = false,
)

data class PdfToolkitResult(
    val files: List<File>,
    val title: String,
    val pageCount: Int,
    val originalBytes: Long? = null,
    val outputBytes: Long,
    val savedToLibraryTitle: String? = null,
    val isUnlockResult: Boolean = false,
)

data class PdfToolkitUiState(
    val isOpen: Boolean = false,
    val activeTool: PdfToolkitTool = PdfToolkitTool.HUB,
    val isBusy: Boolean = false,
    val progressText: String = "",
    val progressFraction: Float? = null,
    val errorMessage: String? = null,
    val mergeSources: List<ToolkitSourceItem> = emptyList(),
    val saveMergedToLibrary: Boolean = true,
    val workingSource: ToolkitSourceItem? = null,
    val pages: List<ToolkitPageItem> = emptyList(),
    val rangeInput: String = "",
    val splitSeparateFiles: Boolean = false,
    val compressionPreset: PdfCompression = PdfCompression.COMPACT,
    val pendingPassword: PendingEncryptedPdf? = null,
    val result: PdfToolkitResult? = null,
)

class PdfToolkitViewModel(
    context: Context,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val appContext = context.applicationContext
    private val scratchDir = File(appContext.cacheDir, "pdf_toolkit_work").apply { mkdirs() }
    private val outputDir = File(appContext.cacheDir, "pdf_toolkit_out").apply { mkdirs() }

    private val _uiState = MutableStateFlow(PdfToolkitUiState())
    val uiState: StateFlow<PdfToolkitUiState> = _uiState.asStateFlow()

    private var onImportedReadyCallback: ((String, List<Bitmap>) -> Unit)? = null
    private var onEditableProjectReadyCallback: ((String, File) -> Unit)? = null

    fun registerImportHandler(handler: (String, List<Bitmap>) -> Unit) {
        onImportedReadyCallback = handler
    }

    fun registerEditableProjectHandler(handler: (String, File) -> Unit) {
        onEditableProjectReadyCallback = handler
    }

    fun openHub() {
        _uiState.update {
            PdfToolkitUiState(isOpen = true, activeTool = PdfToolkitTool.HUB)
        }
    }

    fun closeToolkit() {
        recycleThumbnails()
        _uiState.value = PdfToolkitUiState(isOpen = false)
        viewModelScope.launch(Dispatchers.IO) {
            scratchDir.listFiles()?.forEach { it.delete() }
        }
    }

    fun selectTool(tool: PdfToolkitTool) {
        recycleThumbnails()
        _uiState.update {
            it.copy(
                activeTool = tool,
                errorMessage = null,
                workingSource = null,
                pages = emptyList(),
                rangeInput = "",
                result = null,
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissPasswordPrompt() {
        _uiState.update { it.copy(pendingPassword = null, isBusy = false) }
    }

    fun handlePickedPdfUris(uris: List<Uri>, targetTool: PdfToolkitTool) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    progressText = "Reading PDF…",
                    progressFraction = null,
                    errorMessage = null,
                    result = null,
                )
            }
            try {
                if (targetTool == PdfToolkitTool.MERGE) {
                    val added = mutableListOf<ToolkitSourceItem>()
                    for (uri in uris) {
                        val (file, title) = withContext(Dispatchers.IO) {
                            PdfToolkitEngine.copyUriToTempFile(appContext, uri, scratchDir)
                        }
                        when (val inspect = withContext(Dispatchers.IO) { PdfToolkitEngine.inspectPdf(file) }) {
                            is PdfInspectResult.Ready -> {
                                added.add(
                                    ToolkitSourceItem(
                                        id = UUID.randomUUID().toString(),
                                        title = title,
                                        pdfFile = file,
                                        pageCount = inspect.pageCount,
                                        sizeBytes = inspect.fileSizeBytes,
                                    )
                                )
                            }
                            is PdfInspectResult.PasswordRequired -> {
                                _uiState.update { state ->
                                    state.copy(
                                        isBusy = false,
                                        mergeSources = state.mergeSources + added,
                                        pendingPassword = PendingEncryptedPdf(file, title, PdfToolkitTool.MERGE),
                                    )
                                }
                                return@launch
                            }
                            is PdfInspectResult.Invalid -> {
                                _uiState.update { state ->
                                    state.copy(isBusy = false, errorMessage = inspect.message)
                                }
                                return@launch
                            }
                        }
                    }
                    _uiState.update { state ->
                        state.copy(
                            isBusy = false,
                            activeTool = PdfToolkitTool.MERGE,
                            mergeSources = state.mergeSources + added,
                        )
                    }
                } else {
                    val uri = uris.first()
                    val (file, title) = withContext(Dispatchers.IO) {
                        PdfToolkitEngine.copyUriToTempFile(appContext, uri, scratchDir)
                    }
                    processSingleSourceFile(file, title, password = null, targetTool = targetTool)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, errorMessage = e.message ?: "Failed to read selected PDF")
                }
            }
        }
    }

    fun submitPdfPassword(password: String) {
        val pending = _uiState.value.pendingPassword ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            when (val inspect = withContext(Dispatchers.IO) { PdfToolkitEngine.inspectPdf(pending.file, password) }) {
                is PdfInspectResult.Ready -> {
                    _uiState.update { it.copy(pendingPassword = null) }
                    if (pending.targetTool == PdfToolkitTool.UNLOCK) {
                        runUnlockPdf(pending.file, pending.title, password)
                    } else if (pending.targetTool == PdfToolkitTool.MERGE) {
                        val item = ToolkitSourceItem(
                            id = UUID.randomUUID().toString(),
                            title = pending.title,
                            pdfFile = pending.file,
                            password = password,
                            pageCount = inspect.pageCount,
                            sizeBytes = inspect.fileSizeBytes,
                        )
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                activeTool = PdfToolkitTool.MERGE,
                                mergeSources = it.mergeSources + item,
                            )
                        }
                    } else {
                        processSingleSourceFile(pending.file, pending.title, password, pending.targetTool)
                    }
                }
                is PdfInspectResult.PasswordRequired -> {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            pendingPassword = pending.copy(wrongPassword = true),
                        )
                    }
                }
                is PdfInspectResult.Invalid -> {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            pendingPassword = null,
                            errorMessage = inspect.message,
                        )
                    }
                }
            }
        }
    }

    private suspend fun processSingleSourceFile(
        file: File,
        title: String,
        password: String?,
        targetTool: PdfToolkitTool,
    ) {
        when (val inspect = withContext(Dispatchers.IO) { PdfToolkitEngine.inspectPdf(file, password) }) {
            is PdfInspectResult.Ready -> {
                val source = ToolkitSourceItem(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    pdfFile = file,
                    password = password,
                    pageCount = inspect.pageCount,
                    sizeBytes = inspect.fileSizeBytes,
                )
                when (targetTool) {
                    PdfToolkitTool.IMPORT -> {
                        val projectZip = File(scratchDir, "reimport_${System.currentTimeMillis()}.zip")
                        val hasEmbeddedProject = withContext(Dispatchers.IO) {
                            PdfProjectCodec.extractProjectArchive(file, password, projectZip)
                        }
                        if (hasEmbeddedProject && onEditableProjectReadyCallback != null) {
                            _uiState.update { it.copy(isBusy = false, isOpen = false) }
                            onEditableProjectReadyCallback?.invoke(title, projectZip)
                            return
                        } else {
                            projectZip.delete()
                        }
                        val rendered = withContext(Dispatchers.IO) {
                            PdfToolkitEngine.renderPdfPagesToBitmaps(
                                pdfFile = file,
                                password = password,
                                maxLongEdgePx = 2000,
                                tempCacheDir = scratchDir,
                            ) { done, total ->
                                _uiState.update {
                                    it.copy(
                                        progressText = "Importing page $done of $total…",
                                        progressFraction = done.toFloat() / total.coerceAtLeast(1),
                                    )
                                }
                            }
                        }
                        _uiState.update { it.copy(isBusy = false, isOpen = false) }
                        onImportedReadyCallback?.invoke(title, rendered)
                    }
                    PdfToolkitTool.SPLIT, PdfToolkitTool.ORGANIZE -> {
                        _uiState.update {
                            it.copy(
                                isBusy = true,
                                progressText = "Generating page previews (${inspect.pageCount} pages)…",
                                progressFraction = null,
                            )
                        }
                        val thumbs = withContext(Dispatchers.IO) {
                            PdfToolkitEngine.renderThumbnails(
                                pdfFile = file,
                                password = password,
                                maxThumbEdgePx = 260,
                                tempCacheDir = scratchDir,
                            )
                        }
                        recycleThumbnails()
                        val pageItems = (0 until inspect.pageCount).map { idx ->
                            ToolkitPageItem(
                                zeroBasedIndex = idx,
                                thumbnail = thumbs.getOrNull(idx),
                                isSelected = true,
                                rotationDegrees = 0,
                            )
                        }
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                activeTool = targetTool,
                                workingSource = source,
                                pages = pageItems,
                                rangeInput = if (inspect.pageCount == 1) "1" else "1-${inspect.pageCount}",
                            )
                        }
                    }
                    PdfToolkitTool.COMPRESS -> {
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                activeTool = PdfToolkitTool.COMPRESS,
                                workingSource = source,
                                result = null,
                            )
                        }
                    }
                    PdfToolkitTool.UNLOCK -> {
                        if (!inspect.isEncrypted && password == null) {
                            _uiState.update {
                                it.copy(
                                    isBusy = false,
                                    activeTool = PdfToolkitTool.UNLOCK,
                                    errorMessage = "\"$title\" is not password protected.",
                                )
                            }
                        } else {
                            runUnlockPdf(file, title, password.orEmpty())
                        }
                    }
                    else -> {
                        _uiState.update { it.copy(isBusy = false) }
                    }
                }
            }
            is PdfInspectResult.PasswordRequired -> {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        pendingPassword = PendingEncryptedPdf(file, title, targetTool, wrongPassword = false),
                    )
                }
            }
            is PdfInspectResult.Invalid -> {
                _uiState.update {
                    it.copy(isBusy = false, errorMessage = inspect.message)
                }
            }
        }
    }

    fun addLibraryDocumentToTool(doc: SavedDocumentEntity, targetTool: PdfToolkitTool) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    progressText = "Loading \"${doc.title}\"…",
                    progressFraction = null,
                    errorMessage = null,
                )
            }
            try {
                val pages = libraryRepository.readDocumentPageFiles(doc.id)
                if (pages.isEmpty()) {
                    _uiState.update { it.copy(isBusy = false, errorMessage = "No pages found in \"${doc.title}\"") }
                    return@launch
                }
                val tempPdf = File(scratchDir, "lib_${doc.id}_${System.currentTimeMillis()}.pdf")
                val built = withContext(Dispatchers.IO) {
                    PdfToolkitEngine.buildPdfFromImageFiles(pages, tempPdf, PdfCompression.HIGH)
                }
                if (targetTool == PdfToolkitTool.MERGE) {
                    val item = ToolkitSourceItem(
                        id = UUID.randomUUID().toString(),
                        title = doc.title,
                        pdfFile = built.file,
                        pageCount = built.pageCount,
                        sizeBytes = built.sizeBytes,
                        isFromLibrary = true,
                    )
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            activeTool = PdfToolkitTool.MERGE,
                            mergeSources = it.mergeSources + item,
                        )
                    }
                } else {
                    processSingleSourceFile(built.file, doc.title, password = null, targetTool = targetTool)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, errorMessage = e.message ?: "Failed to load document")
                }
            }
        }
    }

    // --- Merge Operations ---

    fun moveMergeSource(index: Int, delta: Int) {
        val list = _uiState.value.mergeSources.toMutableList()
        val target = index + delta
        if (index !in list.indices || target !in list.indices) return
        val item = list.removeAt(index)
        list.add(target, item)
        _uiState.update { it.copy(mergeSources = list) }
    }

    fun removeMergeSource(id: String) {
        _uiState.update { state ->
            state.copy(mergeSources = state.mergeSources.filterNot { it.id == id })
        }
    }

    fun setSaveMergedToLibrary(enabled: Boolean) {
        _uiState.update { it.copy(saveMergedToLibrary = enabled) }
    }

    fun executeMerge() {
        val sources = _uiState.value.mergeSources
        if (sources.size < 2) {
            _uiState.update { it.copy(errorMessage = "Add at least 2 PDFs or documents to merge.") }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, progressText = "Merging ${sources.size} PDFs…", errorMessage = null)
            }
            try {
                val outName = "Merged_${System.currentTimeMillis()}.pdf"
                val outFile = File(outputDir, outName)
                val specs = sources.map {
                    MergeSourceSpec.ExternalPdf(it.pdfFile, it.password, it.title)
                }
                val opResult = withContext(Dispatchers.IO) {
                    PdfToolkitEngine.mergePdfSources(specs, outFile, scratchDir) { done, total ->
                        _uiState.update {
                            it.copy(progressFraction = done.toFloat() / total.coerceAtLeast(1))
                        }
                    }
                }
                var savedTitle: String? = null
                if (_uiState.value.saveMergedToLibrary) {
                    savedTitle = "${sources.first().title} + ${sources.size - 1} merged"
                    val bitmaps = withContext(Dispatchers.IO) {
                        PdfToolkitEngine.renderPdfPagesToBitmaps(
                            pdfFile = opResult.file,
                            maxLongEdgePx = 1800,
                            tempCacheDir = scratchDir,
                        )
                    }
                    libraryRepository.saveBitmapsAsDocument(
                        title = savedTitle,
                        bitmaps = bitmaps,
                        tagsCsv = "Merged,PDF",
                        lastExportPath = opResult.file.absolutePath,
                    )
                    bitmaps.forEach { it.recycle() }
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        result = PdfToolkitResult(
                            files = listOf(opResult.file),
                            title = savedTitle ?: "Merged PDF",
                            pageCount = opResult.pageCount,
                            outputBytes = opResult.sizeBytes,
                            savedToLibraryTitle = savedTitle,
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, errorMessage = e.message ?: "Failed to merge PDFs")
                }
            }
        }
    }

    // --- Split & Organize Operations ---

    fun togglePageSelected(zeroBasedIndex: Int) {
        _uiState.update { state ->
            val updated = state.pages.map {
                if (it.zeroBasedIndex == zeroBasedIndex) it.copy(isSelected = !it.isSelected) else it
            }
            state.copy(pages = updated)
        }
    }

    fun selectAllPages(selectAll: Boolean) {
        _uiState.update { state ->
            state.copy(pages = state.pages.map { it.copy(isSelected = selectAll) })
        }
    }

    fun updateRangeInput(spec: String) {
        val total = _uiState.value.pages.size
        val parsed = PdfToolkitEngine.parsePageRangeSpec(spec, total).toSet()
        _uiState.update { state ->
            val updated = if (spec.isBlank()) {
                state.pages
            } else {
                state.pages.map { it.copy(isSelected = it.zeroBasedIndex in parsed) }
            }
            state.copy(rangeInput = spec, pages = updated)
        }
    }

    fun setSplitSeparateFiles(separate: Boolean) {
        _uiState.update { it.copy(splitSeparateFiles = separate) }
    }

    fun movePageItem(positionIndex: Int, delta: Int) {
        val list = _uiState.value.pages.toMutableList()
        val target = positionIndex + delta
        if (positionIndex !in list.indices || target !in list.indices) return
        val item = list.removeAt(positionIndex)
        list.add(target, item)
        _uiState.update { it.copy(pages = list) }
    }

    fun rotatePageItem(positionIndex: Int, deltaDegrees: Int) {
        _uiState.update { state ->
            val list = state.pages.toMutableList()
            val item = list.getOrNull(positionIndex) ?: return@update state
            list[positionIndex] = item.copy(
                rotationDegrees = ((item.rotationDegrees + deltaDegrees) % 360 + 360) % 360
            )
            state.copy(pages = list)
        }
    }

    fun removePageItem(positionIndex: Int) {
        if (_uiState.value.pages.size <= 1) {
            _uiState.update { it.copy(errorMessage = "A PDF must keep at least 1 page.") }
            return
        }
        _uiState.update { state ->
            val list = state.pages.toMutableList()
            val removed = list.removeAt(positionIndex)
            removed.thumbnail?.recycle()
            state.copy(pages = list)
        }
    }

    fun executeSplitOrOrganize(saveToLibrary: Boolean) {
        val state = _uiState.value
        val source = state.workingSource ?: return
        val isOrganize = state.activeTool == PdfToolkitTool.ORGANIZE
        val chosen = if (isOrganize) state.pages else state.pages.filter { it.isSelected }
        if (chosen.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Select at least 1 page.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, progressText = "Building PDF…", errorMessage = null) }
            try {
                if (!isOrganize && state.splitSeparateFiles) {
                    val indices = chosen.map { it.zeroBasedIndex }
                    val splitOutDir = File(outputDir, "split_${System.currentTimeMillis()}").apply { mkdirs() }
                    val results = withContext(Dispatchers.IO) {
                        PdfToolkitEngine.splitPdfToSinglePages(
                            sourceFile = source.pdfFile,
                            password = source.password,
                            selectedZeroBasedIndices = indices,
                            outputDir = splitOutDir,
                            baseName = source.title,
                        )
                    }
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            result = PdfToolkitResult(
                                files = results.map { r -> r.file },
                                title = "${source.title} (${results.size} PDFs)",
                                pageCount = results.size,
                                outputBytes = results.sumOf { r -> r.sizeBytes },
                            )
                        )
                    }
                } else {
                    val outFile = File(outputDir, "${source.title.replace(' ', '_')}_${System.currentTimeMillis()}.pdf")
                    val entries = chosen.map { PageTransformEntry(it.zeroBasedIndex, it.rotationDegrees) }
                    val opResult = withContext(Dispatchers.IO) {
                        PdfToolkitEngine.extractOrOrganizePdf(
                            sourceFile = source.pdfFile,
                            password = source.password,
                            pageEntries = entries,
                            outputPdf = outFile,
                        )
                    }
                    var savedTitle: String? = null
                    if (saveToLibrary) {
                        savedTitle = if (isOrganize) "${source.title} (Edited)" else "${source.title} (Extracted)"
                        val bitmaps = withContext(Dispatchers.IO) {
                            PdfToolkitEngine.renderPdfPagesToBitmaps(
                                pdfFile = opResult.file,
                                maxLongEdgePx = 1800,
                                tempCacheDir = scratchDir,
                            )
                        }
                        libraryRepository.saveBitmapsAsDocument(
                            title = savedTitle,
                            bitmaps = bitmaps,
                            tagsCsv = "PDF",
                            lastExportPath = opResult.file.absolutePath,
                        )
                        bitmaps.forEach { it.recycle() }
                    }
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            result = PdfToolkitResult(
                                files = listOf(opResult.file),
                                title = savedTitle ?: source.title,
                                pageCount = opResult.pageCount,
                                outputBytes = opResult.sizeBytes,
                                savedToLibraryTitle = savedTitle,
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, errorMessage = e.message ?: "Failed to process PDF pages")
                }
            }
        }
    }

    // --- Compress Operations ---

    fun setCompressionPreset(preset: PdfCompression) {
        _uiState.update { it.copy(compressionPreset = preset) }
    }

    fun executeCompress() {
        val state = _uiState.value
        val source = state.workingSource ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    progressText = "Compressing PDF…",
                    progressFraction = null,
                    errorMessage = null,
                )
            }
            try {
                val outFile = File(outputDir, "${source.title.replace(' ', '_')}_compressed_${System.currentTimeMillis()}.pdf")
                val res = withContext(Dispatchers.IO) {
                    PdfToolkitEngine.compressPdf(
                        sourceFile = source.pdfFile,
                        password = source.password,
                        compression = state.compressionPreset,
                        tempCacheDir = scratchDir,
                        outputPdf = outFile,
                    ) { done, total ->
                        _uiState.update {
                            it.copy(
                                progressText = "Compressing page $done of $total…",
                                progressFraction = done.toFloat() / total.coerceAtLeast(1),
                            )
                        }
                    }
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        result = PdfToolkitResult(
                            files = listOf(res.file),
                            title = "${source.title} (Compressed)",
                            pageCount = res.pageCount,
                            originalBytes = res.originalBytes,
                            outputBytes = res.compressedBytes,
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, errorMessage = e.message ?: "Compression failed")
                }
            }
        }
    }

    // --- Unlock Operations ---

    private suspend fun runUnlockPdf(file: File, title: String, password: String) {
        val outFile = File(outputDir, "${title.replace(' ', '_')}_unlocked_${System.currentTimeMillis()}.pdf")
        when (val unlockRes = withContext(Dispatchers.IO) { PdfToolkitEngine.unlockPdf(file, password, outFile) }) {
            is PdfUnlockResult.Success -> {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        activeTool = PdfToolkitTool.UNLOCK,
                        result = PdfToolkitResult(
                            files = listOf(unlockRes.file),
                            title = "$title (Unlocked)",
                            pageCount = unlockRes.pageCount,
                            outputBytes = unlockRes.sizeBytes,
                            isUnlockResult = true,
                        )
                    )
                }
            }
            PdfUnlockResult.WrongPassword -> {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        pendingPassword = PendingEncryptedPdf(file, title, PdfToolkitTool.UNLOCK, wrongPassword = true),
                    )
                }
            }
            PdfUnlockResult.NotEncrypted -> {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = "\"$title\" is already unlocked (no password set).",
                    )
                }
            }
            is PdfUnlockResult.Failed -> {
                _uiState.update {
                    it.copy(isBusy = false, errorMessage = unlockRes.message)
                }
            }
        }
    }

    fun openResultInDocumentStudio(result: PdfToolkitResult) {
        val file = result.files.firstOrNull() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, progressText = "Opening in Document Studio…") }
            try {
                val projectZip = File(scratchDir, "reopen_${System.currentTimeMillis()}.zip")
                val hasEmbeddedProject = withContext(Dispatchers.IO) {
                    PdfProjectCodec.extractProjectArchive(file, null, projectZip)
                }
                if (hasEmbeddedProject && onEditableProjectReadyCallback != null) {
                    _uiState.update { it.copy(isBusy = false, isOpen = false) }
                    onEditableProjectReadyCallback?.invoke(result.title, projectZip)
                    return@launch
                } else {
                    projectZip.delete()
                }
                val bitmaps = withContext(Dispatchers.IO) {
                    PdfToolkitEngine.renderPdfPagesToBitmaps(
                        pdfFile = file,
                        maxLongEdgePx = 2000,
                        tempCacheDir = scratchDir,
                    )
                }
                _uiState.update { it.copy(isBusy = false, isOpen = false) }
                onImportedReadyCallback?.invoke(result.title, bitmaps)
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, errorMessage = e.message ?: "Unable to open PDF") }
            }
        }
    }

    private fun recycleThumbnails() {
        _uiState.value.pages.forEach { it.thumbnail?.recycle() }
    }
}
