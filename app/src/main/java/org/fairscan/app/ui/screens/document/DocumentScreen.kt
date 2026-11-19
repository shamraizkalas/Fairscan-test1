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
package org.fairscan.app.ui.screens.document

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.engawapg.lib.zoomable.ZoomState
import net.engawapg.lib.zoomable.zoomable
import org.fairscan.app.MainViewModel
import org.fairscan.app.R
import org.fairscan.app.cloud.CloudAiGateway
import org.fairscan.app.cloud.CloudOcrAnalysisResult
import org.fairscan.app.cloud.HybridConnectionState
import org.fairscan.app.cloud.HybridModePreference
import org.fairscan.app.camscanner.AiPhotoEditorDialog
import org.fairscan.app.camscanner.AnnotationElement
import org.fairscan.app.camscanner.AnnotationTool
import org.fairscan.app.camscanner.CleanupEffectType
import org.fairscan.app.camscanner.EraserLayer
import org.fairscan.app.camscanner.EraserStroke
import org.fairscan.app.camscanner.IdCardMergeDialog
import org.fairscan.app.camscanner.ImageAdjustments
import org.fairscan.app.camscanner.LayerCoordinateMapper
import org.fairscan.app.camscanner.LayoutCollagePrintDialog
import org.fairscan.app.camscanner.LayoutPrintEngine
import org.fairscan.app.camscanner.OcrResultDialog
import org.fairscan.app.camscanner.PageEditState
import org.fairscan.app.camscanner.ProFilterEngine
import org.fairscan.app.camscanner.ProFilterPreset
import org.fairscan.app.camscanner.QrGeneratorDialog
import org.fairscan.app.camscanner.SerializedAnnotation
import org.fairscan.app.camscanner.SignatureStampVaultDialog
import org.fairscan.app.camscanner.WatermarkDialog
import org.fairscan.app.camscanner.WatermarkLayer
import org.fairscan.app.camscanner.findActivity
import org.fairscan.app.data.ImageRepository
import org.fairscan.app.domain.PageViewKey
import org.fairscan.app.domain.Rotation
import org.fairscan.app.library.FolderEntity
import org.fairscan.app.library.LibraryRepository
import org.fairscan.app.library.SavedAssetType
import org.fairscan.app.library.SavedSignatureEntity
import org.fairscan.app.ui.Navigation
import org.fairscan.app.ui.components.CommonPageListState
import org.fairscan.app.ui.components.ConfirmationDialog
import org.fairscan.app.ui.components.MainActionButton
import org.fairscan.app.ui.components.MyScaffold
import org.fairscan.app.ui.dummyNavigation
import org.fairscan.app.ui.fakeDocument
import org.fairscan.app.ui.fakeImage
import org.fairscan.app.ui.theme.FairScanTheme
import org.fairscan.imageprocessing.ColorMode
import org.fairscan.imageprocessing.ColorMode.COLOR
import java.io.File
import kotlin.math.roundToInt

@Composable
fun DocumentScreen(
    uiState: DocumentUiState,
    navigation: Navigation,
    onExportClick: () -> Unit,
    onDeleteImage: () -> Unit,
    onRotateImage: (Boolean) -> Unit,
    onColorModeSelected: (ColorMode) -> Unit,
    onCropClick: () -> Unit,
    onPageReorder: (String, Int) -> Unit,
    onPageSelected: (Int) -> Unit,
    onApplyBitmap: (Bitmap) -> Unit = {},
    onRunOcr: (suspend (Bitmap) -> String)? = null,
    onRunOcrDetailed: (suspend (Bitmap) -> Pair<String, List<org.fairscan.imageprocessing.OcrTextBox>>)? = null,
    onExportCsv: ((csvContent: String, suggestedFilename: String) -> Unit)? = null,
    onMergeIdCard: (frontIndex: Int, backIndex: Int, removeOriginals: Boolean) -> Unit = { _, _, _ -> },
    savedSignatures: List<SavedSignatureEntity> = emptyList(),
    onSaveSignatureToLibrary: ((Bitmap, String, SavedAssetType) -> Unit)? = null,
    onDeleteSavedSignature: ((SavedSignatureEntity) -> Unit)? = null,
    folders: List<FolderEntity> = emptyList(),
    activeDocumentTitle: String = "",
    onSaveToLibrary: (title: String, folderId: String?, tagsCsv: String) -> Unit = { _, _, _ -> },
    initialPendingOverlay: MainViewModel.PendingOverlayAsset? = null,
    onConsumePendingOverlay: () -> Unit = {},
    libraryRepository: LibraryRepository? = null,
    onLoadAllPageBitmaps: (suspend () -> List<Bitmap>)? = null,
    currentPageEditState: PageEditState = PageEditState(),
    pageEditStates: Map<String, PageEditState> = emptyMap(),
    resolveOverlayFile: ((String) -> File)? = null,
    onAddOverlayLayer: (Bitmap, String, Boolean, Float, Float, Float, Float, Float) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onUpdateOverlayLayer: (String, Float, Float, Float, Float, Float) -> Unit = { _, _, _, _, _, _ -> },
    onRemoveOverlayLayer: (String) -> Unit = {},
    onLoadOverlayForEditing: (suspend (String) -> ImageRepository.VisualOverlayEditData?)? = null,
    onRenderPageWithoutLayers: (suspend (excludeFilter: Boolean, excludeAnnotations: Boolean, excludeEraser: Boolean) -> Bitmap?)? = null,
    onLoadCleanCurrentBitmap: (suspend () -> Bitmap?)? = null,
    onSetFilterLayer: (ProFilterPreset, ImageAdjustments) -> Unit = { _, _ -> },
    onApplyFilterToAllPages: ((ProFilterPreset, ImageAdjustments) -> Unit)? = null,
    onApplyFilterToPages: ((List<String>, ProFilterPreset, ImageAdjustments) -> Unit)? = null,
    onSetWatermarkLayer: (WatermarkLayer?) -> Unit = {},
    onApplyWatermarkToAllPages: ((WatermarkLayer?) -> Unit)? = null,
    onAddCleanupEffect: (CleanupEffectType) -> Unit = {},
    onApplyCleanupToAllPages: ((CleanupEffectType) -> Unit)? = null,
    onRemoveCleanupEffect: (String) -> Unit = {},
    onSetAnnotationsLayer: (List<SerializedAnnotation>) -> Unit = {},
    onRemoveAnnotationLayer: (String) -> Unit = {},
    onSetEraserStrokesLayer: (List<EraserLayer>) -> Unit = {},
    onRemoveEraserStrokeLayer: (String) -> Unit = {},
    onClearAllEditsOnPage: () -> Unit = {},
    onBatchRotatePages: ((List<String>) -> Unit)? = null,
    onBatchDeletePages: ((List<String>) -> Unit)? = null,
    onExtractSelectedPdf: ((List<String>) -> Unit)? = null,
    hybridState: HybridConnectionState = HybridConnectionState(),
    cloudAiGateway: CloudAiGateway? = null,
    onSaveOcrTextToLibrary: ((String) -> Unit)? = null,
) {
    val showDeletePageDialog = rememberSaveable { mutableStateOf(false) }
    var showWatermarkDialog by remember { mutableStateOf(false) }
    var showVaultDialog by remember { mutableStateOf(false) }
    var vaultInitialTab by remember { mutableStateOf(SavedAssetType.SIGNATURE) }
    var showIdMergeDialog by remember { mutableStateOf(false) }
    var showOcrDialog by remember { mutableStateOf(false) }
    var showSaveLibraryDialog by remember { mutableStateOf(false) }
    var showQrStampDialog by remember { mutableStateOf(false) }
    var showLayersDialog by remember { mutableStateOf(false) }
    var showPageGridDialog by remember { mutableStateOf(false) }
    var showAiPhotoEditorDialog by remember { mutableStateOf(false) }
    var stitchSourceBitmaps by remember { mutableStateOf<List<Bitmap>?>(null) }
    var isRunningOcr by remember { mutableStateOf(false) }
    var ocrText by remember { mutableStateOf("") }
    var ocrBoxes by remember { mutableStateOf<List<org.fairscan.imageprocessing.OcrTextBox>>(emptyList()) }
    var cloudOcrAnalysis by remember { mutableStateOf<CloudOcrAnalysisResult?>(null) }
    var ocrEngineLabel by remember { mutableStateOf("Offline Tesseract") }
    var ocrCloudError by remember { mutableStateOf<String?>(null) }
    var downloadedTranslationLangs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isComparingOriginal by remember { mutableStateOf(false) }
    var cleanOriginalBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(showOcrDialog, ocrText) {
        if (showOcrDialog && cloudAiGateway != null) {
            downloadedTranslationLangs = cloudAiGateway.getDownloadedTranslationLanguages()
        }
    }

    var activeOverlay by remember { mutableStateOf<MainViewModel.PendingOverlayAsset?>(null) }
    var editingOverlayId by remember { mutableStateOf<String?>(null) }
    var editingOverlayBgBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var overlayNormX by remember { mutableFloatStateOf(0.70f) }
    var overlayNormY by remember { mutableFloatStateOf(0.78f) }
    var overlayScale by remember { mutableFloatStateOf(0.32f) }
    var overlayRotation by remember { mutableFloatStateOf(0f) }
    var overlayOpacity by remember { mutableFloatStateOf(0.96f) }

    LaunchedEffect(initialPendingOverlay) {
        if (initialPendingOverlay != null) {
            editingOverlayId = null
            editingOverlayBgBitmap = null
            activeOverlay = initialPendingOverlay
            overlayNormX = 0.68f
            overlayNormY = 0.78f
            overlayScale = if (initialPendingOverlay.isStamp) 0.28f else 0.34f
            overlayRotation = if (initialPendingOverlay.isStamp) -8f else 0f
            overlayOpacity = if (initialPendingOverlay.isStamp) 0.92f else 1f
            onConsumePendingOverlay()
        }
    }

    var activeTab by remember { mutableStateOf(StudioTab.ADJUST) }
    var selectedPreset by remember { mutableStateOf(ProFilterPreset.ORIGINAL) }
    var adjustments by remember { mutableStateOf(ImageAdjustments()) }
    var filterThumbnails by remember { mutableStateOf<Map<ProFilterPreset, Bitmap>>(emptyMap()) }
    var filterPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var interactiveCleanBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var activeAnnotationTool by remember { mutableStateOf(AnnotationTool.PEN) }
    var activeAnnotationColor by remember { mutableIntStateOf(0xFF00897B.toInt()) }
    val annotationElements = remember { mutableStateListOf<AnnotationElement>() }
    val redoElements = remember { mutableStateListOf<AnnotationElement>() }
    var pendingTextCoords by remember { mutableStateOf<Offset?>(null) }
    var pendingStampCoords by remember { mutableStateOf<Offset?>(null) }

    val eraserStrokes = remember { mutableStateListOf<EraserStroke>() }
    var eraserBrushRadius by remember { mutableFloatStateOf(0.032f) }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val document = uiState.document
    val currentPageIndex = uiState.currentPageIndex
    val currentPage = uiState.currentPage
    val baseBitmap = currentPage?.bitmap
    val pageKey = currentPage?.key
    val displayRot = pageKey?.rotation ?: Rotation.R0

    suspend fun runOcrPass(bmp: Bitmap, useCloud: Boolean) {
        isRunningOcr = true
        try {
            if (useCloud && cloudAiGateway != null) {
                try {
                    val cloudRes = cloudAiGateway.analyzeDocumentPage(bmp)
                    cloudOcrAnalysis = cloudRes
                    ocrText = cloudRes.fullText
                    ocrBoxes = cloudRes.ocrBoxes
                    ocrEngineLabel = "ML Kit Fast (${cloudRes.detectedLanguage})"
                    ocrCloudError = null
                    onSaveOcrTextToLibrary?.invoke(cloudRes.fullText)
                    showOcrDialog = true
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ocrCloudError = "ML Kit Fast OCR unavailable (${e.localizedMessage ?: "error"}); using Offline Tesseract fallback."
                }
            } else {
                ocrCloudError = null
            }

            cloudOcrAnalysis = null
            if (onRunOcrDetailed != null) {
                val (text, boxes) = onRunOcrDetailed.invoke(bmp)
                ocrText = text
                ocrBoxes = boxes
            } else {
                ocrText = onRunOcr?.invoke(bmp) ?: ""
                ocrBoxes = emptyList()
            }
            ocrEngineLabel = if (ocrCloudError != null) "Offline Tesseract (Fallback)" else "Offline Tesseract"
            if (ocrText.isNotBlank()) {
                onSaveOcrTextToLibrary?.invoke(ocrText)
            }
            showOcrDialog = true
        } finally {
            isRunningOcr = false
        }
    }

    BackHandler {
        when {
            showAiPhotoEditorDialog -> showAiPhotoEditorDialog = false
            showPageGridDialog -> showPageGridDialog = false
            showLayersDialog -> showLayersDialog = false
            activeOverlay != null -> {
                activeOverlay = null
                editingOverlayId = null
                editingOverlayBgBitmap = null
            }
            else -> navigation.back()
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(currentPageIndex) {
        listState.scrollToItem(currentPageIndex)
    }

    // Sync interactive editor state with persisted non-destructive PageEditState whenever page/layers change
    LaunchedEffect(pageKey, currentPageEditState) {
        isComparingOriginal = false
        cleanOriginalBitmap = null
        selectedPreset = currentPageEditState.filterLayer?.toPreset() ?: ProFilterPreset.ORIGINAL
        adjustments = currentPageEditState.filterLayer?.toImageAdjustments() ?: ImageAdjustments()
        filterPreviewBitmap = null

        annotationElements.clear()
        redoElements.clear()
        currentPageEditState.annotations.forEach { layer ->
            val visualLayer = when (layer) {
                is SerializedAnnotation.PenStrokeLayer -> layer.copy(
                    points = layer.points.map { LayerCoordinateMapper.baseToVisualPoint(it.x, it.y, displayRot) }
                )
                is SerializedAnnotation.RedactBoxLayer -> {
                    val p1 = LayerCoordinateMapper.baseToVisualPoint(layer.leftNorm, layer.topNorm, displayRot)
                    val p2 = LayerCoordinateMapper.baseToVisualPoint(layer.rightNorm, layer.bottomNorm, displayRot)
                    layer.copy(
                        leftNorm = minOf(p1.x, p2.x),
                        topNorm = minOf(p1.y, p2.y),
                        rightNorm = maxOf(p1.x, p2.x),
                        bottomNorm = maxOf(p1.y, p2.y),
                    )
                }
                is SerializedAnnotation.TextAnnotationLayer -> {
                    val pt = LayerCoordinateMapper.baseToVisualPoint(layer.xNorm, layer.yNorm, displayRot)
                    layer.copy(xNorm = pt.x, yNorm = pt.y)
                }
                is SerializedAnnotation.BadgeStampLayer -> {
                    val pt = LayerCoordinateMapper.baseToVisualPoint(layer.xNorm, layer.yNorm, displayRot)
                    layer.copy(
                        xNorm = pt.x,
                        yNorm = pt.y,
                        rotationDeg = layer.rotationDeg + LayerCoordinateMapper.rotationDeltaToVisualDeg(displayRot),
                    )
                }
            }
            annotationElements.add(visualLayer.toRuntimeElement())
        }

        eraserStrokes.clear()
        currentPageEditState.eraserStrokes.forEach { layer ->
            val visualPoints = layer.points.map {
                val pt = LayerCoordinateMapper.baseToVisualPoint(it.x, it.y, displayRot)
                pt.x to pt.y
            }
            eraserStrokes.add(EraserStroke(pointsNorm = visualPoints, brushRadiusNorm = layer.brushRadiusNorm))
        }
    }

    LaunchedEffect(pageKey, baseBitmap) {
        if (baseBitmap != null) {
            val sourceForThumbs = if (currentPageEditState.hasActiveFilter) {
                onRenderPageWithoutLayers?.invoke(true, false, false) ?: baseBitmap
            } else baseBitmap
            filterThumbnails = withContext(Dispatchers.Default) {
                ProFilterEngine.generateFilterThumbnails(sourceForThumbs)
            }
        } else {
            filterThumbnails = emptyMap()
        }
    }

    // Load clean underlay when working interactively on Filters, Annotate, or Cleanup tab
    LaunchedEffect(pageKey, activeTab, currentPageEditState) {
        interactiveCleanBitmap = when {
            activeTab == StudioTab.FILTERS && currentPageEditState.hasActiveFilter ->
                onRenderPageWithoutLayers?.invoke(true, false, false)
            activeTab == StudioTab.ANNOTATE && currentPageEditState.annotations.isNotEmpty() ->
                onRenderPageWithoutLayers?.invoke(false, true, false)
            activeTab == StudioTab.CLEANUP && currentPageEditState.eraserStrokes.isNotEmpty() ->
                onRenderPageWithoutLayers?.invoke(false, false, true)
            else -> null
        }
    }

    // Recompute live filter/adjustment preview when on FILTERS tab
    LaunchedEffect(activeTab, selectedPreset, adjustments, baseBitmap, interactiveCleanBitmap) {
        val sourceBmp = if (currentPageEditState.hasActiveFilter) {
            interactiveCleanBitmap
        } else {
            baseBitmap
        }
        if (activeTab == StudioTab.FILTERS && sourceBmp != null &&
            (selectedPreset != ProFilterPreset.ORIGINAL || !adjustments.isDefault)
        ) {
            filterPreviewBitmap = withContext(Dispatchers.Default) {
                ProFilterEngine.applyFilterAndAdjustments(sourceBmp, selectedPreset, adjustments)
            }
        } else {
            filterPreviewBitmap = null
        }
    }

    MyScaffold(
        navigation = navigation,
        pageListState = CommonPageListState(
            document = document,
            onPageClick = { index -> onPageSelected(index) },
            onPageReorder = onPageReorder,
            currentPageIndex = currentPageIndex,
            listState = listState,
            showPageNumbers = true,
        ),
        bottomBar = {
            BottomBar(onExportClick, navigation.toCameraScreen)
        },
    ) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            // Document Canvas Area
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val displayedBitmap = when {
                    isComparingOriginal && cleanOriginalBitmap != null -> cleanOriginalBitmap
                    editingOverlayId != null && editingOverlayBgBitmap != null -> editingOverlayBgBitmap
                    activeTab == StudioTab.FILTERS && filterPreviewBitmap != null -> filterPreviewBitmap
                    activeTab == StudioTab.FILTERS || activeTab == StudioTab.ANNOTATE || activeTab == StudioTab.CLEANUP ->
                        interactiveCleanBitmap ?: baseBitmap
                    else -> baseBitmap
                }

                if (displayedBitmap != null && pageKey != null) {
                    val aspectRatio = (displayedBitmap.width.toFloat() / displayedBitmap.height.coerceAtLeast(1))
                        .coerceIn(0.3f, 3.0f)

                    val placement = activeOverlay
                    if (placement != null) {
                        val density = LocalDensity.current
                        BoxWithConstraints(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(12.dp)
                                .aspectRatio(aspectRatio)
                        ) {
                            val pageW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                            val pageH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                            Image(
                                bitmap = displayedBitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize()
                            )
                            val itemWpx = pageW * overlayScale
                            val bmpAspect = placement.bitmap.height.toFloat() / placement.bitmap.width.coerceAtLeast(1).toFloat()
                            val itemHpx = itemWpx * bmpAspect
                            val itemWdp = with(density) { itemWpx.toDp() }
                            val itemHdp = with(density) { itemHpx.toDp() }
                            val centerXpx = overlayNormX * pageW
                            val centerYpx = overlayNormY * pageH

                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            (centerXpx - itemWpx / 2f).roundToInt(),
                                            (centerYpx - itemHpx / 2f).roundToInt()
                                        )
                                    }
                                    .size(itemWdp, itemHdp)
                                    .graphicsLayer(
                                        rotationZ = overlayRotation,
                                        alpha = overlayOpacity
                                    )
                                    .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                                    .pointerInput(placement) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            overlayNormX = (overlayNormX + dragAmount.x / pageW).coerceIn(0.08f, 0.92f)
                                            overlayNormY = (overlayNormY + dragAmount.y / pageH).coerceIn(0.08f, 0.92f)
                                        }
                                    }
                            ) {
                                Image(
                                    bitmap = placement.bitmap.asImageBitmap(),
                                    contentDescription = placement.label,
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    } else if (activeTab == StudioTab.ANNOTATE) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(12.dp)
                                .aspectRatio(aspectRatio)
                        ) {
                            Image(
                                bitmap = displayedBitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                            AnnotationCanvasOverlay(
                                elements = annotationElements,
                                activeTool = activeAnnotationTool,
                                activeColorArgb = activeAnnotationColor,
                                onAddElement = { element ->
                                    annotationElements.add(element)
                                    redoElements.clear()
                                },
                                onRequestTextAt = { x, y -> pendingTextCoords = Offset(x, y) },
                                onRequestStampAt = { x, y -> pendingStampCoords = Offset(x, y) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else if (activeTab == StudioTab.CLEANUP) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(12.dp)
                                .aspectRatio(aspectRatio)
                        ) {
                            Image(
                                bitmap = displayedBitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize()
                            )
                            MagicEraserCanvasOverlay(
                                strokes = eraserStrokes,
                                brushRadiusNorm = eraserBrushRadius,
                                onAddStroke = { eraserStrokes.add(it) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        val zoomState = remember(pageKey, displayedBitmap) {
                            ZoomState(
                                contentSize = Size(displayedBitmap.width.toFloat(), displayedBitmap.height.toFloat())
                            )
                        }
                        Image(
                            bitmap = displayedBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                                .zoomable(zoomState)
                        )
                    }
                }

                if ((currentPage?.isLoading == true) || isRunningOcr) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.72f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showPageGridDialog = true }
                            .testTag("top_page_grid_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Default.GridView,
                                contentDescription = stringResource(R.string.page_grid_title),
                                tint = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "${currentPageIndex + 1} / ${document.pageCount()}",
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    val canCompare = (currentPageEditState.hasAnyEdits || filterPreviewBitmap != null) &&
                        activeOverlay == null
                    if (canCompare) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isComparingOriginal) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
                            },
                            shadowElevation = 2.dp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .pointerInput(pageKey, currentPageEditState) {
                                    detectTapGestures(
                                        onPress = {
                                            if (cleanOriginalBitmap == null) {
                                                coroutineScope.launch {
                                                    cleanOriginalBitmap = onLoadCleanCurrentBitmap?.invoke()
                                                        ?: interactiveCleanBitmap
                                                        ?: baseBitmap
                                                }
                                            }
                                            isComparingOriginal = true
                                            tryAwaitRelease()
                                            isComparingOriginal = false
                                        }
                                    )
                                }
                                .testTag("top_compare_original_btn")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Compare,
                                    contentDescription = stringResource(R.string.compare_hold_original),
                                    tint = if (isComparingOriginal) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (isComparingOriginal) {
                                        stringResource(R.string.compare_showing_original)
                                    } else {
                                        stringResource(R.string.compare_hold_original)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isComparingOriginal) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }

                    if (baseBitmap != null && activeOverlay == null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
                            shadowElevation = 2.dp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showAiPhotoEditorDialog = true }
                                .testTag("top_ai_photo_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.AutoFixHigh,
                                    contentDescription = stringResource(R.string.ai_photo_editor_title),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.ai_photo_short),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }

                    if (currentPageEditState.hasAnyEdits && activeOverlay == null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showLayersDialog = true }
                                .testTag("top_layers_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Layers,
                                    contentDescription = "Manage edit layers",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Layers (${currentPageEditState.totalEditCount})",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

            val placement = activeOverlay
            if (placement != null) {
                val editId = editingOverlayId
                OverlayPlacementControlBar(
                    label = placement.label,
                    scaleFactor = overlayScale,
                    rotationDeg = overlayRotation,
                    opacity = overlayOpacity,
                    onScaleChange = { overlayScale = it },
                    onRotationChange = { overlayRotation = it },
                    onOpacityChange = { overlayOpacity = it },
                    onCancel = {
                        activeOverlay = null
                        editingOverlayId = null
                        editingOverlayBgBitmap = null
                    },
                    onRemove = if (editId != null) {
                        {
                            onRemoveOverlayLayer(editId)
                            activeOverlay = null
                            editingOverlayId = null
                            editingOverlayBgBitmap = null
                            Toast.makeText(context, "${placement.label} removed", Toast.LENGTH_SHORT).show()
                        }
                    } else null,
                    onApply = {
                        val current = placement
                        if (editId != null) {
                            onUpdateOverlayLayer(
                                editId,
                                overlayNormX,
                                overlayNormY,
                                overlayScale,
                                overlayRotation,
                                overlayOpacity,
                            )
                            Toast.makeText(context, "${current.label} updated", Toast.LENGTH_SHORT).show()
                        } else {
                            onAddOverlayLayer(
                                current.bitmap,
                                current.label,
                                current.isStamp,
                                overlayNormX,
                                overlayNormY,
                                overlayScale,
                                overlayRotation,
                                overlayOpacity,
                            )
                            Toast.makeText(context, "${current.label} applied (editable)", Toast.LENGTH_SHORT).show()
                        }
                        activeOverlay = null
                        editingOverlayId = null
                        editingOverlayBgBitmap = null
                    }
                )
            } else {
                // 6-Tab Studio Tool Dock
                StudioToolDock(
                    activeTab = activeTab,
                    onSelectTab = { activeTab = it },
                    currentColorMode = currentPage?.colorMode,
                    canBeCropped = currentPage?.canBeCropped == true,
                    canMergeIdCard = document.pageCount() >= 2,
                    selectedPreset = selectedPreset,
                    filterThumbnails = filterThumbnails,
                    adjustments = adjustments,
                    activeAnnotationTool = activeAnnotationTool,
                    activeAnnotationColor = activeAnnotationColor,
                    canUndoAnnotation = annotationElements.isNotEmpty(),
                    canRedoAnnotation = redoElements.isNotEmpty(),
                    hasAnnotations = annotationElements.isNotEmpty(),
                    hasSavedFilter = currentPageEditState.hasActiveFilter,
                    hasSavedAnnotations = currentPageEditState.annotations.isNotEmpty(),
                    hasSavedEraserStrokes = currentPageEditState.eraserStrokes.isNotEmpty(),
                    editCount = currentPageEditState.totalEditCount,
                    pageCount = document.pageCount(),
                    onOpenLayers = { showLayersDialog = true },
                    onColorModeSelected = onColorModeSelected,
                    onCropClick = onCropClick,
                    onRotateImage = onRotateImage,
                    onDeleteImage = { showDeletePageDialog.value = true },
                    onSelectPreset = { selectedPreset = it },
                    onAdjustmentsChange = { adjustments = it },
                    onResetFilterAdjustments = {
                        selectedPreset = ProFilterPreset.ORIGINAL
                        adjustments = ImageAdjustments()
                        filterPreviewBitmap = null
                        if (currentPageEditState.hasActiveFilter) {
                            onSetFilterLayer(ProFilterPreset.ORIGINAL, ImageAdjustments())
                        }
                    },
                    onApplyFilterAdjustments = {
                        onSetFilterLayer(selectedPreset, adjustments)
                        Toast.makeText(context, "Filter layer saved", Toast.LENGTH_SHORT).show()
                    },
                    onApplyFilterToAllPages = {
                        onApplyFilterToAllPages?.invoke(selectedPreset, adjustments)
                        Toast.makeText(
                            context,
                            "Filter applied to all ${document.pageCount()} pages",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onSelectAnnotationTool = { activeAnnotationTool = it },
                    onSelectAnnotationColor = { activeAnnotationColor = it },
                    onUndoAnnotation = {
                        if (annotationElements.isNotEmpty()) {
                            redoElements.add(annotationElements.removeAt(annotationElements.lastIndex))
                        }
                    },
                    onRedoAnnotation = {
                        if (redoElements.isNotEmpty()) {
                            annotationElements.add(redoElements.removeAt(redoElements.lastIndex))
                        }
                    },
                    onClearAnnotations = {
                        annotationElements.clear()
                        redoElements.clear()
                    },
                    onApplyAnnotations = {
                        val serialized = annotationElements.map { SerializedAnnotation.fromRuntimeElement(it) }
                        onSetAnnotationsLayer(serialized)
                        Toast.makeText(
                            context,
                            if (serialized.isEmpty()) "Annotations cleared" else "Annotations saved (editable)",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onSignatureClick = {
                        vaultInitialTab = SavedAssetType.SIGNATURE
                        showVaultDialog = true
                    },
                    onStampVaultClick = {
                        vaultInitialTab = SavedAssetType.STAMP
                        showVaultDialog = true
                    },
                    onWatermarkClick = { showWatermarkDialog = true },
                    onIdMergeClick = { showIdMergeDialog = true },
                    onSaveToLibraryClick = { showSaveLibraryDialog = true },
                    onOcrClick = {
                        baseBitmap?.let { bmp ->
                            coroutineScope.launch {
                                runOcrPass(bmp, useCloud = cloudAiGateway != null)
                            }
                        }
                    },
                    onOpenOcrLanguages = navigation.toOcrLanguagesScreen,
                    eraserBrushRadius = eraserBrushRadius,
                    hasEraserStrokes = eraserStrokes.isNotEmpty(),
                    onEraserBrushRadiusChange = { eraserBrushRadius = it },
                    onUndoEraser = {
                        if (eraserStrokes.isNotEmpty()) eraserStrokes.removeAt(eraserStrokes.lastIndex)
                    },
                    onClearEraser = { eraserStrokes.clear() },
                    onApplyEraser = {
                        val layers = eraserStrokes.map { EraserLayer.fromEraserStroke(it) }
                        onSetEraserStrokesLayer(layers)
                        Toast.makeText(
                            context,
                            if (layers.isEmpty()) "Eraser strokes cleared" else "Magic eraser updated",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFlattenShadows = { onAddCleanupEffect(CleanupEffectType.SHADOW_FLATTEN) },
                    onEnhancePpt = { onAddCleanupEffect(CleanupEffectType.PPT_WHITEBOARD) },
                    onEraseHandwriting = { onAddCleanupEffect(CleanupEffectType.HANDWRITING_ERASE) },
                    onApplyCleanupToAllPages = { type ->
                        onApplyCleanupToAllPages?.invoke(type)
                        Toast.makeText(
                            context,
                            "${type.displayLabel} applied to all ${document.pageCount()} pages",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onOpenStitchCollage = {
                        coroutineScope.launch {
                            val list = onLoadAllPageBitmaps?.invoke()
                                ?: listOfNotNull(baseBitmap)
                            stitchSourceBitmaps = list
                        }
                    },
                    onOpenQrStamp = { showQrStampDialog = true },
                    onPrintDocument = {
                        coroutineScope.launch {
                            val list = onLoadAllPageBitmaps?.invoke()
                                ?: listOfNotNull(baseBitmap)
                            context.findActivity()?.let { act ->
                                LayoutPrintEngine.printBitmaps(
                                    activity = act,
                                    jobName = activeDocumentTitle.ifBlank { "ProScan Document" },
                                    bitmaps = list
                                )
                            }
                        }
                    },
                    onOpenAiPhotoEditor = { showAiPhotoEditorDialog = true },
                )
            }
        }

        if (showLayersDialog) {
            LayersManagerDialog(
                editState = currentPageEditState,
                resolveOverlayFile = resolveOverlayFile,
                onDismiss = { showLayersDialog = false },
                onMoveResizeOverlay = { overlayId ->
                    val loader = onLoadOverlayForEditing ?: return@LayersManagerDialog
                    coroutineScope.launch {
                        val editData = loader(overlayId) ?: return@launch
                        editingOverlayId = editData.overlayId
                        editingOverlayBgBitmap = editData.pagePreviewWithoutOverlay
                        activeOverlay = MainViewModel.PendingOverlayAsset(
                            bitmap = editData.overlayBitmap,
                            label = editData.label,
                            isStamp = editData.isStamp,
                        )
                        overlayNormX = editData.visualNormX
                        overlayNormY = editData.visualNormY
                        overlayScale = editData.visualScaleFactor
                        overlayRotation = editData.visualRotationDeg
                        overlayOpacity = editData.opacity
                    }
                },
                onRemoveOverlay = onRemoveOverlayLayer,
                onEditWatermark = { showWatermarkDialog = true },
                onRemoveWatermark = { onSetWatermarkLayer(null) },
                onEditFilter = { activeTab = StudioTab.FILTERS },
                onResetFilter = { onSetFilterLayer(ProFilterPreset.ORIGINAL, ImageAdjustments()) },
                onRemoveCleanupEffect = onRemoveCleanupEffect,
                onRemoveEraserStroke = onRemoveEraserStrokeLayer,
                onClearAllEraserStrokes = { onSetEraserStrokesLayer(emptyList()) },
                onRemoveAnnotation = onRemoveAnnotationLayer,
                onClearAllAnnotations = { onSetAnnotationsLayer(emptyList()) },
                onResetAllEdits = onClearAllEditsOnPage,
            )
        }

        if (showDeletePageDialog.value) {
            ConfirmationDialog(
                title = stringResource(R.string.delete_page),
                message = stringResource(R.string.delete_page_warning),
                showDialog = showDeletePageDialog
            ) { onDeleteImage() }
        }

        pendingTextCoords?.let { coords ->
            AnnotationTextDialog(
                onDismiss = { pendingTextCoords = null },
                onConfirm = { label ->
                    annotationElements.add(
                        AnnotationElement.TextAnnotation(
                            text = label,
                            xNorm = coords.x,
                            yNorm = coords.y,
                            colorArgb = activeAnnotationColor
                        )
                    )
                    redoElements.clear()
                    pendingTextCoords = null
                }
            )
        }

        pendingStampCoords?.let { coords ->
            StampPickerDialog(
                onDismiss = { pendingStampCoords = null },
                onSelectStamp = { label, colorArgb ->
                    annotationElements.add(
                        AnnotationElement.BadgeStamp(
                            label = label,
                            xNorm = coords.x,
                            yNorm = coords.y,
                            colorArgb = colorArgb
                        )
                    )
                    redoElements.clear()
                    pendingStampCoords = null
                }
            )
        }

        if (showWatermarkDialog) {
            WatermarkDialog(
                existingWatermark = currentPageEditState.watermark,
                pageCount = document.pageCount(),
                onDismiss = { showWatermarkDialog = false },
                onApplyWatermark = { text, opacity, rotation, tileRepeated ->
                    onSetWatermarkLayer(
                        WatermarkLayer(
                            text = text,
                            opacity = opacity,
                            rotationDegrees = rotation,
                            tileRepeated = tileRepeated,
                        )
                    )
                },
                onApplyWatermarkAll = onApplyWatermarkToAllPages?.let { applyAll ->
                    { text, opacity, rotation, tileRepeated ->
                        applyAll(
                            WatermarkLayer(
                                text = text,
                                opacity = opacity,
                                rotationDegrees = rotation,
                                tileRepeated = tileRepeated,
                            )
                        )
                        Toast.makeText(
                            context,
                            "Watermark applied to all ${document.pageCount()} pages",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onRemoveWatermark = { onSetWatermarkLayer(null) },
            )
        }

        if (showVaultDialog) {
            SignatureStampVaultDialog(
                savedAssets = savedSignatures,
                initialTab = vaultInitialTab,
                isApplyingToOpenDocument = true,
                onSaveAsset = { bmp, lbl, type ->
                    onSaveSignatureToLibrary?.invoke(bmp, lbl, type)
                },
                onDeleteAsset = { asset ->
                    onDeleteSavedSignature?.invoke(asset)
                },
                onApplyOnOpenDocument = { bmp, lbl, type ->
                    editingOverlayId = null
                    editingOverlayBgBitmap = null
                    activeOverlay = MainViewModel.PendingOverlayAsset(
                        bitmap = bmp,
                        label = lbl,
                        isStamp = type == SavedAssetType.STAMP
                    )
                    overlayNormX = 0.68f
                    overlayNormY = 0.78f
                    overlayScale = if (type == SavedAssetType.STAMP) 0.28f else 0.34f
                    overlayRotation = if (type == SavedAssetType.STAMP) -8f else 0f
                    overlayOpacity = if (type == SavedAssetType.STAMP) 0.92f else 1f
                },
                onDismiss = { showVaultDialog = false }
            )
        }

        if (showIdMergeDialog && document.pageCount() >= 2) {
            IdCardMergeDialog(
                pageCount = document.pageCount(),
                currentPageIndex = currentPageIndex,
                onDismiss = { showIdMergeDialog = false },
                onMerge = onMergeIdCard
            )
        }

        if (showOcrDialog) {
            OcrResultDialog(
                extractedText = ocrText,
                ocrBoxes = ocrBoxes,
                cloudAnalysis = cloudOcrAnalysis,
                ocrEngineLabel = ocrEngineLabel,
                isOnlineActive = hybridState.isOnlineActive && cloudAiGateway != null,
                downloadedLangCodes = downloadedTranslationLangs,
                cloudError = ocrCloudError,
                onTranslateText = cloudAiGateway?.let { gw ->
                    { sourceText, targetLang ->
                        gw.translateOrSummarizeText(
                            sourceText = sourceText,
                            targetLanguage = targetLang,
                            includeSummary = true,
                        ).also {
                            downloadedTranslationLangs = gw.getDownloadedTranslationLanguages()
                        }
                    }
                },
                onRerunOfflineOcr = baseBitmap?.let { bmp ->
                    {
                        coroutineScope.launch { runOcrPass(bmp, useCloud = false) }
                        Unit
                    }
                },
                onRerunCloudOcr = if (cloudAiGateway != null && baseBitmap != null) {
                    {
                        coroutineScope.launch { runOcrPass(baseBitmap, useCloud = true) }
                        Unit
                    }
                } else null,
                onDismiss = { showOcrDialog = false },
                onShareText = { text ->
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        putExtra(Intent.EXTRA_TEXT, text)
                        type = "text/plain"
                    }
                    context.startActivity(
                        Intent.createChooser(sendIntent, "Share Extracted Text")
                    )
                },
                onExportCsv = onExportCsv,
                onOpenOcrLanguages = navigation.toOcrLanguagesScreen
            )
        }

        if (showSaveLibraryDialog) {
            SaveToLibraryDialog(
                initialTitle = activeDocumentTitle.ifBlank { "Scan ${document.pageCount()}p" },
                folders = folders,
                onDismiss = { showSaveLibraryDialog = false },
                onSave = { title, folderId, tagsCsv ->
                    onSaveToLibrary(title, folderId, tagsCsv)
                    Toast.makeText(context, context.getString(R.string.studio_saved_toast), Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (showQrStampDialog) {
            QrGeneratorDialog(
                canApplyToOpenDocument = true,
                isOnlineActive = hybridState.isOnlineActive && cloudAiGateway != null,
                onAnalyzeQr = cloudAiGateway?.let { gw -> { payload -> gw.analyzeQrPayload(payload) } },
                onApplyQrToDocument = { qrBmp, label ->
                    editingOverlayId = null
                    editingOverlayBgBitmap = null
                    activeOverlay = MainViewModel.PendingOverlayAsset(
                        bitmap = qrBmp,
                        label = label,
                        isStamp = true
                    )
                    overlayNormX = 0.78f
                    overlayNormY = 0.80f
                    overlayScale = 0.24f
                    overlayRotation = 0f
                    overlayOpacity = 1f
                },
                onDismiss = { showQrStampDialog = false }
            )
        }

        val currentStitchBitmaps = stitchSourceBitmaps
        if (currentStitchBitmaps != null && libraryRepository != null) {
            LayoutCollagePrintDialog(
                initialBitmaps = currentStitchBitmaps,
                libraryRepository = libraryRepository,
                onDismiss = { stitchSourceBitmaps = null }
            )
        }

        if (showPageGridDialog) {
            PageGridDialog(
                document = document,
                currentPageIndex = currentPageIndex,
                pageEditStates = pageEditStates,
                onSelectPage = onPageSelected,
                onReorderPage = onPageReorder,
                onBatchRotate = { ids ->
                    onBatchRotatePages?.invoke(ids)
                    Toast.makeText(context, "Rotated ${ids.size} page(s)", Toast.LENGTH_SHORT).show()
                },
                onBatchApplyCurrentFilter = { ids ->
                    onApplyFilterToPages?.invoke(ids, selectedPreset, adjustments)
                    Toast.makeText(
                        context,
                        "Filter applied to ${ids.size} page(s)",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onExtractSelectedPdf = { ids ->
                    onExtractSelectedPdf?.invoke(ids)
                    showPageGridDialog = false
                },
                onBatchDelete = { ids ->
                    onBatchDeletePages?.invoke(ids)
                },
                onDismiss = { showPageGridDialog = false }
            )
        }

        if (showAiPhotoEditorDialog && baseBitmap != null) {
            AiPhotoEditorDialog(
                initialBitmap = baseBitmap,
                isDocumentStudioMode = true,
                hybridState = hybridState,
                cloudAiGateway = cloudAiGateway,
                onDismiss = { showAiPhotoEditorDialog = false },
                onApplyToDocument = { edited ->
                    onApplyBitmap(edited)
                    Toast.makeText(context, "AI Photo edits applied", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

@Composable
private fun BottomBar(
    onExportClick: () -> Unit,
    onAddPageClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedButton(
            onClick = onAddPageClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.add_page),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        MainActionButton(
            onClick = onExportClick,
            icon = Icons.Default.Done,
            text = stringResource(R.string.export),
        )
    }
}

@Composable
@Preview
@Preview(name = "Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
fun DocumentScreenPreview() {
    FairScanTheme {
        val image = fakeImage("gallica.bnf.fr-bpt6k5530456s-1", LocalContext.current).toBitmap()
        val document = fakeDocument(
            listOf(1, 2).map { "gallica.bnf.fr-bpt6k5530456s-$it" }.toImmutableList(),
            LocalContext.current
        )
        val key = PageViewKey("123", Rotation.R0, null, 0)
        DocumentScreen(
            uiState = DocumentUiState(1, CurrentPageUiState(key, image, COLOR, true), document),
            navigation = dummyNavigation(),
            onExportClick = {},
            onDeleteImage = {},
            onRotateImage = { _ -> },
            onColorModeSelected = {},
            onCropClick = {},
            onPageReorder = { _, _ -> },
            onPageSelected = { _ -> },
        )
    }
}
