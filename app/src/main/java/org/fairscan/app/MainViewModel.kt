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
package org.fairscan.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fairscan.app.camscanner.CleanupEffectType
import org.fairscan.app.camscanner.EraserLayer
import org.fairscan.app.camscanner.IdCardHelper
import org.fairscan.app.camscanner.ImageAdjustments
import org.fairscan.app.camscanner.PageEditState
import org.fairscan.app.camscanner.PdfToolkitEngine
import org.fairscan.app.camscanner.ProFilterPreset
import org.fairscan.app.camscanner.SerializedAnnotation
import org.fairscan.app.camscanner.WatermarkLayer
import org.fairscan.app.data.ImageRepository
import org.fairscan.app.data.Logger
import org.fairscan.app.domain.CapturedPage
import org.fairscan.app.domain.OcrService
import org.fairscan.app.domain.Rotation
import org.fairscan.app.domain.ScanPage
import org.fairscan.app.library.LibraryRepository
import org.fairscan.app.library.SavedAssetType
import org.fairscan.app.library.SavedSignatureEntity
import org.fairscan.app.platform.detectDocumentQuadOpenCv
import org.fairscan.app.ui.NavigationState
import org.fairscan.app.ui.Screen
import org.fairscan.app.ui.screens.crop.CropInitState
import org.fairscan.app.ui.screens.document.CurrentPageUiState
import org.fairscan.app.ui.screens.document.DocumentUiState
import org.fairscan.app.ui.state.DocumentUiModel
import org.fairscan.app.ui.state.PageThumbnail
import org.fairscan.imageprocessing.ColorMode
import org.fairscan.imageprocessing.ImageSize
import org.fairscan.imageprocessing.Point
import org.fairscan.imageprocessing.Quad
import java.io.File
import kotlin.math.min

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    val imageRepository: ImageRepository,
    logger: Logger,
    launchMode: LaunchMode = LaunchMode.NORMAL,
    private val libraryRepository: LibraryRepository? = null,
    private val sessionDir: File? = null,
    private val ocrService: OcrService? = null,
) : ViewModel() {

    private val _navigationState = MutableStateFlow<NavigationState?>(null)
    val currentScreen: StateFlow<Screen?> = _navigationState.map { it?.current }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _pages = MutableStateFlow<List<ScanPage>>(emptyList())
    val activePageCount: StateFlow<Int> = _pages.map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val pageEditStates: StateFlow<Map<String, PageEditState>> = imageRepository.pageEditStates
    private val _layerRevision = MutableStateFlow(0L)

    private val _activeDocumentId = MutableStateFlow<String?>(null)
    val activeDocumentId: StateFlow<String?> = _activeDocumentId.asStateFlow()

    private val _activeDocumentTitle = MutableStateFlow("")
    val activeDocumentTitle: StateFlow<String> = _activeDocumentTitle.asStateFlow()

    data class PendingOverlayAsset(
        val bitmap: Bitmap,
        val label: String,
        val isStamp: Boolean,
    )

    private val _pendingOverlayAsset = MutableStateFlow<PendingOverlayAsset?>(null)
    val pendingOverlayAsset: StateFlow<PendingOverlayAsset?> = _pendingOverlayAsset.asStateFlow()

    init {
        viewModelScope.launch {
            val pages = imageRepository.pages()
            _pages.value = pages
            _navigationState.value = when (launchMode) {
                LaunchMode.EXTERNAL_SCAN_TO_PDF -> NavigationState.initial(Screen.Main.Camera)
                LaunchMode.NORMAL -> NavigationState.initial(Screen.Main.HomeHub)
            }
        }
    }

    val documentUiModel: StateFlow<DocumentUiModel> =
        combine(_pages, _layerRevision) { pages, _ ->
            pages.map {
                val jpeg = imageRepository.getThumbnail(it.key())
                PageThumbnail(it.key(), jpeg)
            }.toImmutableList()
        }
            .flowOn(Dispatchers.IO)
            .map { DocumentUiModel(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = DocumentUiModel()
            )

    private val _currentPageIndex = MutableStateFlow(0)
    private val _loadingPageId = MutableStateFlow<String?>(null)
    private val currentPageUiState: Flow<CurrentPageUiState?> =
        combine(_currentPageIndex, _pages, _loadingPageId, _layerRevision) { index, pages, loadingId, _ ->
            val page = pages.getOrNull(index)
            Pair(page, loadingId)
        }
            .mapLatest { (page, loadingId) ->
                page?.let {
                    val isLoading = (it.id == loadingId)
                    val canBeCropped = page.metadata != null
                    val bitmap = try {
                        imageRepository.image(it.key())?.toBitmap()
                    } catch (e: Exception) {
                        logger.e("MainViewModel", "Failed to load image for ${it.id}", e)
                        null
                    }
                    CurrentPageUiState(it.key(), bitmap, it.colorMode, canBeCropped, isLoading)
                }
            }
            .flowOn(Dispatchers.IO)

    val documentUiState: StateFlow<DocumentUiState> =
        combine(_currentPageIndex, currentPageUiState, documentUiModel) { index, page, document ->
            DocumentUiState(index, page, document)
        }
            .stateIn(
                viewModelScope, SharingStarted.Eagerly,
                DocumentUiState(0, null, DocumentUiModel())
            )

    fun onPageSelected(index: Int) {
        _currentPageIndex.value = index
    }

    fun navigateTo(destination: Screen) {
        if (destination is Screen.Main.Document) {
            if (_pages.value.isEmpty()) return
            _currentPageIndex.value = min(_pages.value.size - 1, destination.initialPage)
        }
        _navigationState.update { it?.navigateTo(destination) }
    }

    fun navigateBack() {
        _navigationState.update { stack -> stack?.navigateBack() }
    }

    fun rotateCurrentPage(clockwise: Boolean) {
        viewModelScope.launch {
            val pages = withContext(Dispatchers.IO) {
                imageRepository.rotate(currentPage().id, clockwise)
                imageRepository.pages()
            }
            _pages.value = pages
        }
    }

    fun movePage(id: String, newIndex: Int) {
        viewModelScope.launch {
            val pages = withContext(Dispatchers.IO) {
                imageRepository.movePage(id, newIndex)
                imageRepository.pages()
            }
            _pages.value = pages
        }
    }

    fun deleteCurrentPage() {
        viewModelScope.launch {
            val pages = withContext(Dispatchers.IO) {
                imageRepository.delete(currentPage().id)
                imageRepository.pages()
            }

            if (pages.isEmpty()) {
                navigateTo(Screen.Main.Camera)
                _currentPageIndex.value = 0
            } else if (_currentPageIndex.value >= pages.size) {
                _currentPageIndex.value = pages.size - 1
            }
            _pages.value = pages
        }
    }

    fun applyBitmapToCurrentPage(bitmap: Bitmap) {
        viewModelScope.launch {
            val currentPage = currentPage()
            _loadingPageId.value = currentPage.id
            val pages = withContext(Dispatchers.IO) {
                imageRepository.applyBitmapToPage(currentPage.id, bitmap)
                imageRepository.pages()
            }
            _pages.value = pages
            _layerRevision.value++
            _loadingPageId.value = null
        }
    }

    // --- Non-Destructive Layer Editing Operations ---

    private fun runLayerAction(action: suspend (pageId: String) -> Unit) {
        val page = _pages.value.getOrNull(_currentPageIndex.value) ?: return
        viewModelScope.launch {
            _loadingPageId.value = page.id
            try {
                withContext(Dispatchers.IO) {
                    action(page.id)
                }
                _layerRevision.value++
            } finally {
                _loadingPageId.value = null
            }
        }
    }

    fun addOverlayToCurrentPage(
        bitmap: Bitmap,
        label: String,
        isStamp: Boolean,
        normX: Float,
        normY: Float,
        scaleFactor: Float,
        rotationDeg: Float,
        opacity: Float,
    ) = runLayerAction { pageId ->
        imageRepository.addImageOverlay(
            pageId = pageId,
            bitmap = bitmap,
            label = label,
            isStamp = isStamp,
            visualNormX = normX,
            visualNormY = normY,
            scaleFactor = scaleFactor,
            visualRotationDeg = rotationDeg,
            opacity = opacity,
        )
    }

    fun updateOverlayOnCurrentPage(
        overlayId: String,
        normX: Float,
        normY: Float,
        scaleFactor: Float,
        rotationDeg: Float,
        opacity: Float,
    ) = runLayerAction { pageId ->
        imageRepository.updateImageOverlay(
            pageId = pageId,
            overlayId = overlayId,
            visualNormX = normX,
            visualNormY = normY,
            scaleFactor = scaleFactor,
            visualRotationDeg = rotationDeg,
            opacity = opacity,
        )
    }

    fun removeOverlayFromCurrentPage(overlayId: String) = runLayerAction { pageId ->
        imageRepository.removeImageOverlay(pageId, overlayId)
    }

    suspend fun loadOverlayForEditing(overlayId: String): ImageRepository.VisualOverlayEditData? {
        val page = _pages.value.getOrNull(_currentPageIndex.value) ?: return null
        return imageRepository.getVisualOverlayForEditing(page.id, overlayId)
    }

    suspend fun renderCurrentPageWithoutLayers(
        excludeFilter: Boolean = false,
        excludeAnnotations: Boolean = false,
        excludeEraser: Boolean = false,
    ): Bitmap? {
        val page = _pages.value.getOrNull(_currentPageIndex.value) ?: return null
        return imageRepository.renderPageWithoutLayers(
            pageId = page.id,
            excludeFilter = excludeFilter,
            excludeAnnotations = excludeAnnotations,
            excludeEraser = excludeEraser,
        )
    }

    fun setFilterOnCurrentPage(preset: ProFilterPreset, adjustments: ImageAdjustments) =
        runLayerAction { pageId ->
            imageRepository.setFilterAndAdjustments(pageId, preset, adjustments)
        }

    fun setWatermarkOnCurrentPage(watermark: WatermarkLayer?) = runLayerAction { pageId ->
        imageRepository.setWatermark(pageId, watermark)
    }

    fun addCleanupEffectToCurrentPage(type: CleanupEffectType) = runLayerAction { pageId ->
        imageRepository.addCleanupEffect(pageId, type)
    }

    fun removeCleanupEffectFromCurrentPage(effectId: String) = runLayerAction { pageId ->
        imageRepository.removeCleanupEffect(pageId, effectId)
    }

    fun setAnnotationsOnCurrentPage(annotations: List<SerializedAnnotation>) =
        runLayerAction { pageId ->
            imageRepository.setVisualAnnotations(pageId, annotations)
        }

    fun removeAnnotationFromCurrentPage(annotationId: String) = runLayerAction { pageId ->
        imageRepository.removeAnnotation(pageId, annotationId)
    }

    fun setEraserStrokesOnCurrentPage(strokes: List<EraserLayer>) = runLayerAction { pageId ->
        imageRepository.setVisualEraserStrokes(pageId, strokes)
    }

    fun removeEraserStrokeFromCurrentPage(strokeId: String) = runLayerAction { pageId ->
        imageRepository.removeEraserStroke(pageId, strokeId)
    }

    fun clearAllEditsOnCurrentPage() = runLayerAction { pageId ->
        imageRepository.clearAllEditsOnPage(pageId)
    }

    suspend fun loadCleanCurrentPageBitmap(): Bitmap? {
        val page = _pages.value.getOrNull(_currentPageIndex.value) ?: return null
        return imageRepository.getCleanDisplayBitmap(page.id)
    }

    fun applyFilterToPages(
        pageIds: Set<String>?,
        preset: ProFilterPreset,
        adjustments: ImageAdjustments,
    ) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                imageRepository.applyFilterToPages(pageIds, preset, adjustments)
            }
            _layerRevision.value++
        }
    }

    fun applyWatermarkToAllPages(watermark: WatermarkLayer?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                imageRepository.applyWatermarkToPages(null, watermark)
            }
            _layerRevision.value++
        }
    }

    fun applyCleanupToAllPages(type: CleanupEffectType) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                imageRepository.applyCleanupToPages(null, type)
            }
            _layerRevision.value++
        }
    }

    fun rotatePages(pageIds: Collection<String>, clockwise: Boolean = true) {
        if (pageIds.isEmpty()) return
        viewModelScope.launch {
            val pages = withContext(Dispatchers.IO) {
                imageRepository.rotatePages(pageIds, clockwise)
                imageRepository.pages()
            }
            _pages.value = pages
            _layerRevision.value++
        }
    }

    fun deletePages(pageIds: Collection<String>) {
        if (pageIds.isEmpty()) return
        viewModelScope.launch {
            val pages = withContext(Dispatchers.IO) {
                imageRepository.deletePages(pageIds)
                imageRepository.pages()
            }
            if (pages.isEmpty()) {
                navigateTo(Screen.Main.Camera)
                _currentPageIndex.value = 0
            } else if (_currentPageIndex.value >= pages.size) {
                _currentPageIndex.value = pages.size - 1
            }
            _pages.value = pages
            _layerRevision.value++
        }
    }

    suspend fun extractPagesToPdf(pageIds: List<String>, outPdf: File): Boolean =
        withContext(Dispatchers.IO) {
            val files = imageRepository.getProcessedPageFiles(pageIds)
            if (files.isEmpty()) return@withContext false
            val res = PdfToolkitEngine.buildPdfFromImageFiles(files, outPdf)
            res.pageCount > 0 && res.file.exists()
        }

    suspend fun runOpenCvAutoDetectForCrop(bitmap: Bitmap): Quad? = withContext(Dispatchers.Default) {
        detectDocumentQuadOpenCv(bitmap)
    }

    suspend fun loadAllCurrentPageBitmaps(): List<Bitmap> = withContext(Dispatchers.IO) {
        _pages.value.mapNotNull { page ->
            runCatching { imageRepository.image(page.key())?.toBitmap() }.getOrNull()
        }
    }

    fun mergeIdCardPages(frontIndex: Int, backIndex: Int, removeOriginals: Boolean) {
        viewModelScope.launch {
            val currentList = _pages.value
            val frontPage = currentList.getOrNull(frontIndex) ?: return@launch
            val backPage = currentList.getOrNull(backIndex) ?: return@launch
            _loadingPageId.value = frontPage.id
            val result = withContext(Dispatchers.IO) {
                val frontBitmap = imageRepository.image(frontPage.key())?.toBitmap()
                val backBitmap = imageRepository.image(backPage.key())?.toBitmap()
                if (frontBitmap == null || backBitmap == null) {
                    return@withContext null
                }
                val merged = IdCardHelper.mergeFrontAndBack(frontBitmap, backBitmap)
                val newIndex = imageRepository.mergeIdCardPages(
                    frontId = frontPage.id,
                    backId = backPage.id,
                    mergedBitmap = merged,
                    removeOriginals = removeOriginals
                )
                val updatedPages = imageRepository.pages()
                Pair(updatedPages, newIndex)
            }
            if (result != null) {
                _pages.value = result.first
                _currentPageIndex.value = result.second.coerceIn(0, (result.first.size - 1).coerceAtLeast(0))
                _layerRevision.value++
            }
            _loadingPageId.value = null
        }
    }

    fun setCurrentPageColorMode(colorMode: ColorMode) {
        viewModelScope.launch {
            val currentPage = currentPage()
            if (currentPage.colorMode != colorMode) {
                _loadingPageId.value = currentPage.id
                val pages = withContext(Dispatchers.IO) {
                    imageRepository.setColorMode(currentPage.id, colorMode)
                    imageRepository.pages()
                }
                _pages.value = pages
                _layerRevision.value++
                _loadingPageId.value = null
            }
        }
    }

    fun setCurrentPageUserQuad(userQuad: Quad) {
        viewModelScope.launch {
            val currentPage = currentPage()
            val totalRotation = currentPage.totalRotation()
            val rotateIterations = (4 - totalRotation.degrees / 90) % 4
            val newQuad = userQuad.rotate90(rotateIterations, ImageSize(1, 1))
            _loadingPageId.value = currentPage.id
            val pages = withContext(Dispatchers.IO) {
                imageRepository.setUserQuad(currentPage.id, newQuad)
                imageRepository.pages()
            }
            _pages.value = pages
            _layerRevision.value++
            _loadingPageId.value = null
        }
    }

    private fun currentPage(): ScanPage {
        val index = _currentPageIndex.value
        val pages = _pages.value
        return pages.getOrNull(index) ?: throw IllegalStateException(
            "No current page for index $index (${pages.size} pages)"
        )
    }

    fun startNewDocument() {
        _pages.value = persistentListOf()
        _activeDocumentId.value = null
        _activeDocumentTitle.value = ""
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                imageRepository.clear()
            }
            _layerRevision.value++
        }
    }

    fun openSavedDocument(docId: String) {
        val repo = libraryRepository ?: return
        val dir = sessionDir ?: return
        viewModelScope.launch {
            val doc = repo.loadDocumentIntoSession(docId, dir) ?: return@launch
            imageRepository.reloadFromDisk()
            val loadedPages = imageRepository.pages()
            if (loadedPages.isNotEmpty()) {
                _pages.value = loadedPages
                _activeDocumentId.value = doc.id
                _activeDocumentTitle.value = doc.title
                _currentPageIndex.value = 0
                _layerRevision.value++
                navigateTo(Screen.Main.Document(0))
            }
        }
    }

    fun importBitmapsAsDocument(title: String, bitmaps: List<Bitmap>) {
        if (bitmaps.isEmpty()) return
        viewModelScope.launch {
            val loadedPages = withContext(Dispatchers.IO) {
                imageRepository.clear()
                bitmaps.forEach { bmp ->
                    imageRepository.addImportedBitmapPage(bmp)
                    bmp.recycle()
                }
                imageRepository.pages()
            }
            if (loadedPages.isNotEmpty()) {
                _pages.value = loadedPages
                _activeDocumentId.value = null
                _activeDocumentTitle.value = title.ifBlank { "Imported PDF" }
                _currentPageIndex.value = 0
                _layerRevision.value++
                navigateTo(Screen.Main.Document(0))
            }
        }
    }

    fun importEditablePdfProject(title: String, projectZipFile: File) {
        viewModelScope.launch {
            val restoredTitle = try {
                withContext(Dispatchers.IO) {
                    imageRepository.importProjectArchive(projectZipFile)
                }
            } finally {
                projectZipFile.delete()
            }
            val loadedPages = withContext(Dispatchers.IO) { imageRepository.pages() }
            if (restoredTitle != null && loadedPages.isNotEmpty()) {
                _pages.value = loadedPages
                _activeDocumentId.value = null
                _activeDocumentTitle.value = restoredTitle.ifBlank { title.ifBlank { "Imported PDF" } }
                _currentPageIndex.value = 0
                _layerRevision.value++
                navigateTo(Screen.Main.Document(0))
            }
        }
    }

    fun saveActiveDocumentToLibrary(
        title: String,
        folderId: String? = null,
        tagsCsv: String = "",
        isLocked: Boolean = false,
        lastExportPath: String? = null,
        lastExportFormat: String = "PDF",
    ) {
        val repo = libraryRepository ?: return
        val dir = sessionDir ?: return
        val currentPages = _pages.value
        if (currentPages.isEmpty()) return
        viewModelScope.launch {
            val coverBmp = withContext(Dispatchers.IO) {
                imageRepository.image(currentPages.first().key())?.toBitmap()
            }
            val saved = repo.saveWorkingSessionAsDocument(
                sessionRootDir = dir,
                title = title,
                folderId = folderId,
                tagsCsv = tagsCsv,
                existingDocId = _activeDocumentId.value,
                isLocked = isLocked,
                lastExportPath = lastExportPath,
                lastExportFormat = lastExportFormat,
                coverBitmap = coverBmp,
                pageCount = currentPages.size,
            )
            coverBmp?.recycle()
            _activeDocumentId.value = saved.id
            _activeDocumentTitle.value = saved.title

            val activeOcr = ocrService
            if (activeOcr != null && activeOcr.isConfigured()) {
                launch(Dispatchers.Default) {
                    val combined = StringBuilder()
                    for (page in currentPages.take(12)) {
                        val bmp = withContext(Dispatchers.IO) {
                            runCatching { imageRepository.image(page.key())?.toBitmap() }.getOrNull()
                        } ?: continue
                        val pageText = try {
                            activeOcr.extractFullText(bmp)
                        } catch (_: Exception) {
                            ""
                        } finally {
                            bmp.recycle()
                        }
                        if (pageText.isNotBlank()) {
                            if (combined.isNotEmpty()) combined.append('\n')
                            combined.append(pageText.trim())
                        }
                        if (combined.length >= 24_000) break
                    }
                    val indexedText = combined.toString().trim()
                    if (indexedText.isNotEmpty()) {
                        repo.updateDocumentOcrText(saved.id, indexedText)
                    }
                }
            }
        }
    }

    fun saveOcrTextForActiveDocument(text: String) {
        val docId = _activeDocumentId.value ?: return
        val repo = libraryRepository ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            repo.updateDocumentOcrText(docId, clean.take(24_000))
        }
    }

    fun saveSignatureToLibrary(
        bitmap: Bitmap,
        label: String = "Signature",
        assetType: SavedAssetType = SavedAssetType.SIGNATURE,
    ) {
        val repo = libraryRepository ?: return
        viewModelScope.launch {
            repo.saveSignature(bitmap, label, assetType)
        }
    }

    fun deleteSavedSignature(sig: SavedSignatureEntity) {
        val repo = libraryRepository ?: return
        viewModelScope.launch {
            repo.deleteSignature(sig)
        }
    }

    fun applySavedAssetToDocument(
        docId: String?,
        bitmap: Bitmap,
        label: String,
        assetType: SavedAssetType,
    ) {
        _pendingOverlayAsset.value = PendingOverlayAsset(
            bitmap = bitmap,
            label = label,
            isStamp = assetType == SavedAssetType.STAMP,
        )
        if (docId == null) {
            navigateTo(Screen.Main.Document(0))
        } else {
            openSavedDocument(docId)
        }
    }

    fun clearPendingOverlayAsset() {
        _pendingOverlayAsset.value = null
    }

    fun handleImageCaptured(capturedPage: CapturedPage, openCrop: Boolean = false) {
        viewModelScope.launch {
            val pages = withContext(Dispatchers.IO) {
                val sourceJpeg = capturedPage.sourceJpeg.await()
                imageRepository.add(
                    capturedPage.pageImage,
                    sourceJpeg,
                    capturedPage.metadata,
                    capturedPage.colorMode,
                )
                imageRepository.pages()
            }
            _pages.value = pages
            val newIndex = (pages.size - 1).coerceAtLeast(0)
            _currentPageIndex.value = newIndex
            _layerRevision.value++
            if (openCrop && pages.isNotEmpty()) {
                onClickOnCropButton(targetPageIndex = newIndex)
            }
        }
    }

    private val _cropInitState = MutableStateFlow<CropInitState>(CropInitState.Loading)
    val cropInitState: StateFlow<CropInitState> = _cropInitState

    private var cropInitialStateJob: Job? = null
    fun onClickOnCropButton(targetPageIndex: Int? = null) {
        cropInitialStateJob?.cancel()
        cropInitialStateJob = viewModelScope.launch {
            _cropInitState.value = CropInitState.Loading

            val page = targetPageIndex?.let { _pages.value.getOrNull(it) } ?: currentPage()
            if (targetPageIndex != null) {
                _currentPageIndex.value = targetPageIndex
            }
            val metadata = page.metadata
            val rotation = page.totalRotation()

            val bitmap = withContext(Dispatchers.IO) {
                val source = imageRepository.source(page.id)
                val bytes = source?.bytes ?: return@withContext null

                val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (original != null && rotation != Rotation.R0) {
                    val matrix = Matrix().apply { postRotate(rotation.degrees.toFloat()) }
                    Bitmap.createBitmap(
                        original, 0, 0, original.width, original.height, matrix, true
                    )
                } else {
                    original
                }
            }

            val fallbackQuad = Quad(
                Point(0.0, 0.0),
                Point(1.0, 0.0),
                Point(1.0, 1.0),
                Point(0.0, 1.0),
            )
            val quad = (metadata?.normalizedQuad ?: fallbackQuad).rotate90(
                rotation.degrees / 90,
                ImageSize(1, 1)
            )

            _cropInitState.value = if (bitmap == null)
                CropInitState.Error
            else
                CropInitState.Ready(page.id, bitmap, quad)
            navigateTo(Screen.Main.EditImage)
        }
    }
}
