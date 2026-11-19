package org.fairscan.app.camscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import org.fairscan.app.cloud.CloudAiGateway
import org.fairscan.app.cloud.HybridConnectionState
import org.fairscan.app.cloud.HybridModeController
import org.fairscan.app.cloud.HybridModePreference
import org.fairscan.app.domain.OcrService
import org.fairscan.app.domain.PdfCompression
import org.fairscan.app.library.LibraryRepository
import org.fairscan.app.library.SavedDocumentEntity
import java.io.File
import java.util.UUID

data class ConverterInputSpec(
    val id: String = UUID.randomUUID().toString(),
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val isFromLibrary: Boolean = false,
)

data class ConverterUiState(
    val isOpen: Boolean = false,
    val selectedCategory: ConverterCategory = ConverterCategory.ALL,
    val activeTool: ConverterTool? = null,
    val inputs: List<ConverterInputSpec> = emptyList(),
    val options: ConverterOptions = ConverterOptions(),
    val isBusy: Boolean = false,
    val progressText: String = "",
    val progressFraction: Float? = null,
    val result: ConverterOutputSummary? = null,
    val errorMessage: String? = null,
)

class FileConverterViewModel(
    context: Context,
    private val ocrService: OcrService,
    private val libraryRepository: LibraryRepository,
    private val cloudAiGateway: CloudAiGateway? = null,
    private val hybridModeController: HybridModeController? = null,
) : ViewModel() {

    val hybridState: StateFlow<HybridConnectionState>? = hybridModeController?.state

    fun setHybridPreference(pref: HybridModePreference) {
        hybridModeController?.setPreference(pref)
    }

    fun setAiStructuredConversion(enabled: Boolean) {
        hybridModeController?.setAiStructuredConversion(enabled)
    }

    fun setUseMlKitSegmentation(enabled: Boolean) {
        hybridModeController?.setUseMlKitSegmentation(enabled)
    }

    fun refreshNetwork() {
        hybridModeController?.refreshConnectivity()
    }

    private val appContext = context.applicationContext
    private val scratchDir = File(appContext.cacheDir, "converter_work").apply { mkdirs() }
    private val outputRootDir = File(appContext.cacheDir, "pdfs/converter").apply { mkdirs() }

    private val _uiState = MutableStateFlow(ConverterUiState())
    val uiState: StateFlow<ConverterUiState> = _uiState.asStateFlow()

    private var onImportBitmapsCallback: ((String, List<Bitmap>) -> Unit)? = null

    fun registerImportHandler(handler: (String, List<Bitmap>) -> Unit) {
        onImportBitmapsCallback = handler
    }

    fun openHub(initialTool: ConverterTool? = null) {
        _uiState.value = ConverterUiState(
            isOpen = true,
            selectedCategory = ConverterCategory.ALL,
            activeTool = initialTool,
        )
    }

    fun close() {
        _uiState.value = ConverterUiState(isOpen = false)
        viewModelScope.launch(Dispatchers.IO) {
            scratchDir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    fun selectCategory(category: ConverterCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun selectTool(tool: ConverterTool?) {
        _uiState.update {
            it.copy(
                activeTool = tool,
                inputs = emptyList(),
                result = null,
                errorMessage = null,
                progressText = "",
                progressFraction = null,
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearResult() {
        _uiState.update { it.copy(result = null, errorMessage = null) }
    }

    fun updateOptions(transform: (ConverterOptions) -> ConverterOptions) {
        _uiState.update { it.copy(options = transform(it.options)) }
    }

    fun removeInput(id: String) {
        _uiState.update { state ->
            state.copy(
                inputs = state.inputs.filterNot { it.id == id },
                result = null,
            )
        }
    }

    fun moveInput(index: Int, delta: Int) {
        val current = _uiState.value.inputs.toMutableList()
        val target = index + delta
        if (index !in current.indices || target !in current.indices) return
        val item = current.removeAt(index)
        current.add(target, item)
        _uiState.update { it.copy(inputs = current) }
    }

    fun addPickedUris(uris: List<Uri>) {
        val tool = _uiState.value.activeTool ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    progressText = "Loading selected file(s)…",
                    progressFraction = null,
                    errorMessage = null,
                    result = null,
                )
            }
            try {
                val added = withContext(Dispatchers.IO) {
                    val toProcess = if (tool.allowMultipleInputs) uris else listOf(uris.first())
                    toProcess.map { uri ->
                        val (file, name) = UniversalConverterEngine.copyInputUriToFile(
                            appContext,
                            uri,
                            scratchDir,
                        )
                        ConverterInputSpec(
                            file = file,
                            displayName = name,
                            sizeBytes = file.length(),
                            isFromLibrary = false,
                        )
                    }
                }
                _uiState.update { state ->
                    val merged = if (tool.allowMultipleInputs) state.inputs + added else added
                    state.copy(isBusy = false, inputs = merged)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, errorMessage = e.message ?: "Unable to read selected file.")
                }
            }
        }
    }

    fun addLibraryDocument(doc: SavedDocumentEntity) {
        val tool = _uiState.value.activeTool ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    progressText = "Preparing \"${doc.title}\"…",
                    progressFraction = null,
                    errorMessage = null,
                    result = null,
                )
            }
            try {
                val pagePairs = libraryRepository.readDocumentPageFiles(doc.id)
                val added = withContext(Dispatchers.IO) {
                    when (tool) {
                        ConverterTool.IMAGE_TO_PDF, ConverterTool.BATCH_IMAGE_CONVERT -> {
                            if (pagePairs.isEmpty()) error("No image pages found in \"${doc.title}\".")
                            pagePairs.mapIndexed { idx, (file, _) ->
                                val dest = File(scratchDir, "lib_${doc.id}_p${idx + 1}_${System.currentTimeMillis()}.jpg")
                                file.copyTo(dest, overwrite = true)
                                ConverterInputSpec(
                                    file = dest,
                                    displayName = "${doc.title} (Page ${idx + 1}).jpg",
                                    sizeBytes = dest.length(),
                                    isFromLibrary = true,
                                )
                            }
                        }
                        else -> {
                            val existingExport = doc.lastExportPath?.let { File(it) }
                            val sourcePdf = if (existingExport != null &&
                                existingExport.exists() &&
                                existingExport.extension.equals("pdf", ignoreCase = true) &&
                                PdfToolkitEngine.inspectPdf(existingExport) is PdfInspectResult.Ready
                            ) {
                                existingExport
                            } else {
                                if (pagePairs.isEmpty()) error("No pages found in \"${doc.title}\".")
                                val builtPdf = File(scratchDir, "lib_${doc.id}_${System.currentTimeMillis()}.pdf")
                                PdfToolkitEngine.buildPdfFromImageFiles(pagePairs, builtPdf, PdfCompression.HIGH)
                                builtPdf
                            }
                            listOf(
                                ConverterInputSpec(
                                    file = sourcePdf,
                                    displayName = "${doc.title}.pdf",
                                    sizeBytes = sourcePdf.length(),
                                    isFromLibrary = true,
                                )
                            )
                        }
                    }
                }
                _uiState.update { state ->
                    val merged = if (tool.allowMultipleInputs) state.inputs + added else added
                    state.copy(isBusy = false, inputs = merged)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, errorMessage = e.message ?: "Failed to load document.")
                }
            }
        }
    }

    fun executeConversion() {
        val state = _uiState.value
        val tool = state.activeTool ?: return
        val inputs = state.inputs
        if (inputs.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Please select an input file first.") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    progressText = "Starting ${tool.title}…",
                    progressFraction = null,
                    errorMessage = null,
                    result = null,
                )
            }
            try {
                val jobDir = File(outputRootDir, "job_${System.currentTimeMillis()}").apply { mkdirs() }
                val primary = inputs.first()
                val baseName = primary.displayName.substringBeforeLast('.').ifBlank { "converted" }
                val onProgress: (Int, Int, String) -> Unit = { done, total, msg ->
                    _uiState.update {
                        it.copy(
                            progressText = msg,
                            progressFraction = if (total > 0) done.toFloat() / total.toFloat() else null,
                        )
                    }
                }

                val hybrid = hybridModeController?.state?.value
                val canUseSmartLayout = cloudAiGateway != null
                val allowOfflineFallback = true

                val summary = withContext(Dispatchers.IO) {
                    when (tool) {
                        ConverterTool.PDF_TO_DOCX -> UniversalConverterEngine.convertPdfToWord(
                            pdfFile = primary.file,
                            baseName = baseName,
                            outputDir = jobDir,
                            options = state.options,
                            ocrService = ocrService,
                            cloudAiGateway = cloudAiGateway,
                            useCloudAi = canUseSmartLayout && state.options.useCloudAiStructure && (hybrid?.aiStructuredConversion != false),
                            allowOfflineFallback = allowOfflineFallback,
                            onProgress = onProgress,
                        )
                        ConverterTool.DOCX_TO_PDF -> UniversalConverterEngine.convertWordToPdf(
                            wordFile = primary.file,
                            baseName = baseName,
                            outputDir = jobDir,
                            options = state.options,
                            onProgress = onProgress,
                        )
                        ConverterTool.IMAGE_TO_PDF -> UniversalConverterEngine.convertImagesToPdf(
                            imageFiles = inputs.map { it.file },
                            baseName = if (inputs.size == 1) baseName else "${baseName}_bundle",
                            outputDir = jobDir,
                            options = state.options,
                            onProgress = onProgress,
                        )
                        ConverterTool.PDF_TO_IMAGES -> UniversalConverterEngine.convertPdfToImages(
                            pdfFile = primary.file,
                            baseName = baseName,
                            outputDir = jobDir,
                            options = state.options,
                            longVerticalImage = false,
                            onProgress = onProgress,
                        )
                        ConverterTool.PDF_TO_LONG_IMAGE -> UniversalConverterEngine.convertPdfToImages(
                            pdfFile = primary.file,
                            baseName = baseName,
                            outputDir = jobDir,
                            options = state.options,
                            longVerticalImage = true,
                            onProgress = onProgress,
                        )
                        ConverterTool.PDF_TO_TXT -> UniversalConverterEngine.convertPdfToTextOrHtml(
                            pdfFile = primary.file,
                            baseName = baseName,
                            outputDir = jobDir,
                            asHtml = false,
                            options = state.options,
                            ocrService = ocrService,
                            cloudAiGateway = cloudAiGateway,
                            useCloudAi = canUseSmartLayout && state.options.useCloudAiStructure && (hybrid?.aiStructuredConversion != false),
                            allowOfflineFallback = allowOfflineFallback,
                            onProgress = onProgress,
                        )
                        ConverterTool.PDF_TO_HTML -> UniversalConverterEngine.convertPdfToTextOrHtml(
                            pdfFile = primary.file,
                            baseName = baseName,
                            outputDir = jobDir,
                            asHtml = true,
                            options = state.options,
                            ocrService = ocrService,
                            cloudAiGateway = cloudAiGateway,
                            useCloudAi = canUseSmartLayout && state.options.useCloudAiStructure && (hybrid?.aiStructuredConversion != false),
                            allowOfflineFallback = allowOfflineFallback,
                            onProgress = onProgress,
                        )
                        ConverterTool.TEXT_DOC_TO_PDF, ConverterTool.CODE_TO_PDF -> UniversalConverterEngine.convertStructuredTextOrCodeToPdf(
                            inputFile = primary.file,
                            baseName = baseName,
                            outputDir = jobDir,
                            tool = tool,
                            options = state.options,
                            cloudAiGateway = cloudAiGateway,
                            polishWithAi = canUseSmartLayout && state.options.aiPolishText,
                            allowOfflineFallback = allowOfflineFallback,
                            onProgress = onProgress,
                        )
                        ConverterTool.BATCH_IMAGE_CONVERT -> UniversalConverterEngine.convertBatchImagesFormat(
                            imageFiles = inputs.map { it.file },
                            outputDir = jobDir,
                            options = state.options,
                            onProgress = onProgress,
                        )
                    }
                }

                _uiState.update {
                    it.copy(
                        isBusy = false,
                        progressText = "",
                        progressFraction = null,
                        result = summary,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        progressText = "",
                        progressFraction = null,
                        errorMessage = e.message ?: "Conversion failed. Check file format and try again.",
                    )
                }
            }
        }
    }

    fun openResultInDocumentStudio(summary: ConverterOutputSummary) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, progressText = "Opening in Document Studio…", progressFraction = null)
            }
            try {
                val bitmaps = withContext(Dispatchers.IO) {
                    if (summary.mimeType == "application/pdf") {
                        val pdfFile = summary.files.firstOrNull() ?: error("PDF file missing")
                        PdfToolkitEngine.renderPdfPagesToBitmaps(
                            pdfFile = pdfFile,
                            maxLongEdgePx = 2000,
                            tempCacheDir = scratchDir,
                        )
                    } else if (summary.mimeType.startsWith("image/")) {
                        summary.files.mapNotNull { f -> BitmapFactory.decodeFile(f.absolutePath) }
                    } else {
                        emptyList()
                    }
                }
                if (bitmaps.isEmpty()) {
                    _uiState.update {
                        it.copy(isBusy = false, errorMessage = "This file type cannot be opened in Document Studio.")
                    }
                    return@launch
                }
                val title = summary.files.firstOrNull()?.nameWithoutExtension ?: summary.tool.title
                _uiState.update { ConverterUiState(isOpen = false) }
                onImportBitmapsCallback?.invoke(title, bitmaps)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, errorMessage = e.message ?: "Unable to open in Studio.")
                }
            }
        }
    }

    companion object {
        fun toolSupportsLibraryDocs(tool: ConverterTool): Boolean = when (tool) {
            ConverterTool.PDF_TO_DOCX,
            ConverterTool.PDF_TO_IMAGES,
            ConverterTool.PDF_TO_LONG_IMAGE,
            ConverterTool.PDF_TO_TXT,
            ConverterTool.PDF_TO_HTML,
            ConverterTool.IMAGE_TO_PDF,
            ConverterTool.BATCH_IMAGE_CONVERT -> true
            else -> false
        }
    }
}
