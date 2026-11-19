package org.fairscan.app.ui.screens.document

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Approval
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.fairscan.app.R
import org.fairscan.app.camscanner.AnnotationElement
import org.fairscan.app.camscanner.AnnotationTool
import org.fairscan.app.camscanner.DEFAULT_STAMPS
import org.fairscan.app.camscanner.FilterCategory
import org.fairscan.app.camscanner.ImageAdjustments
import org.fairscan.app.camscanner.ProFilterPreset
import org.fairscan.app.library.FolderEntity
import org.fairscan.imageprocessing.ColorMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class StudioTab(val titleRes: Int, val icon: ImageVector) {
    ADJUST(R.string.studio_tab_crop, Icons.Default.Tune),
    FILTERS(R.string.studio_tab_filters, Icons.Default.AutoFixHigh),
    CLEANUP(R.string.studio_tab_cleanup, Icons.Default.CleaningServices),
    ANNOTATE(R.string.studio_tab_annotate, Icons.Default.Edit),
    SIGN_MARK(R.string.studio_tab_sign, Icons.Default.Draw),
    OCR(R.string.studio_tab_ocr, Icons.Default.TextFields),
}

@Composable
fun AnnotationCanvasOverlay(
    elements: List<AnnotationElement>,
    activeTool: AnnotationTool,
    activeColorArgb: Int,
    onAddElement: (AnnotationElement) -> Unit,
    onRequestTextAt: (normX: Float, normY: Float) -> Unit,
    onRequestStampAt: (normX: Float, normY: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }

    fun toNorm(offset: Offset): Offset {
        val w = boxSize.width.coerceAtLeast(1).toFloat()
        val h = boxSize.height.coerceAtLeast(1).toFloat()
        return Offset(
            (offset.x / w).coerceIn(0f, 1f),
            (offset.y / h).coerceIn(0f, 1f)
        )
    }

    Box(
        modifier = modifier
            .onSizeChanged { boxSize = it }
            .pointerInput(activeTool, activeColorArgb) {
                if (activeTool == AnnotationTool.TEXT || activeTool == AnnotationTool.STAMP) {
                    detectTapGestures { offset ->
                        val norm = toNorm(offset)
                        if (activeTool == AnnotationTool.TEXT) {
                            onRequestTextAt(norm.x, norm.y)
                        } else {
                            onRequestStampAt(norm.x, norm.y)
                        }
                    }
                } else {
                    detectDragGestures(
                        onDragStart = { start ->
                            val norm = toNorm(start)
                            if (activeTool == AnnotationTool.PEN || activeTool == AnnotationTool.HIGHLIGHTER) {
                                currentStroke = listOf(norm)
                            } else {
                                dragStart = norm
                                dragEnd = norm
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val norm = toNorm(change.position)
                            if (activeTool == AnnotationTool.PEN || activeTool == AnnotationTool.HIGHLIGHTER) {
                                currentStroke = currentStroke + norm
                            } else {
                                dragEnd = norm
                            }
                        },
                        onDragEnd = {
                            if (activeTool == AnnotationTool.PEN || activeTool == AnnotationTool.HIGHLIGHTER) {
                                if (currentStroke.size >= 2) {
                                    onAddElement(
                                        AnnotationElement.PenStroke(
                                            pointsNorm = currentStroke,
                                            colorArgb = activeColorArgb,
                                            strokeWidthNorm = 0.008f,
                                            isHighlighter = activeTool == AnnotationTool.HIGHLIGHTER
                                        )
                                    )
                                }
                                currentStroke = emptyList()
                            } else {
                                val s = dragStart
                                val e = dragEnd
                                if (s != null && e != null && abs(e.x - s.x) > 0.02f && abs(e.y - s.y) > 0.02f) {
                                    onAddElement(
                                        AnnotationElement.RedactBox(
                                            leftNorm = min(s.x, e.x),
                                            topNorm = min(s.y, e.y),
                                            rightNorm = max(s.x, e.x),
                                            bottomNorm = max(s.y, e.y),
                                            isBlur = activeTool == AnnotationTool.REDACT_BLUR
                                        )
                                    )
                                }
                                dragStart = null
                                dragEnd = null
                            }
                        },
                        onDragCancel = {
                            currentStroke = emptyList()
                            dragStart = null
                            dragEnd = null
                        }
                    )
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val baseDim = min(w, h)

            fun drawStroke(points: List<Offset>, colorArgb: Int, isHighlighter: Boolean, widthNorm: Float) {
                if (points.size < 2) return
                val path = Path().apply {
                    moveTo(points.first().x * w, points.first().y * h)
                    for (i in 1 until points.size) {
                        lineTo(points[i].x * w, points[i].y * h)
                    }
                }
                drawPath(
                    path = path,
                    color = Color(colorArgb).copy(alpha = if (isHighlighter) 0.42f else 1f),
                    style = Stroke(
                        width = if (isHighlighter) (widthNorm * baseDim * 2.8f).coerceAtLeast(10f)
                        else (widthNorm * baseDim).coerceAtLeast(3f),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            for (el in elements) {
                when (el) {
                    is AnnotationElement.PenStroke -> {
                        drawStroke(el.pointsNorm, el.colorArgb, el.isHighlighter, el.strokeWidthNorm)
                    }
                    is AnnotationElement.RedactBox -> {
                        val left = min(el.leftNorm, el.rightNorm) * w
                        val top = min(el.topNorm, el.bottomNorm) * h
                        val boxW = abs(el.rightNorm - el.leftNorm) * w
                        val boxH = abs(el.bottomNorm - el.topNorm) * h
                        drawRect(
                            color = if (el.isBlur) Color(0xAA90A4AE) else Color.Black,
                            topLeft = Offset(left, top),
                            size = Size(boxW, boxH)
                        )
                        if (el.isBlur) {
                            drawRect(
                                color = Color.White,
                                topLeft = Offset(left, top),
                                size = Size(boxW, boxH),
                                style = Stroke(width = 2f)
                            )
                        }
                    }
                    is AnnotationElement.TextAnnotation -> {
                        val cx = el.xNorm * w
                        val cy = el.yNorm * h
                        drawCircle(
                            color = Color(el.colorArgb),
                            radius = 12f,
                            center = Offset(cx, cy)
                        )
                    }
                    is AnnotationElement.BadgeStamp -> {
                        val cx = el.xNorm * w
                        val cy = el.yNorm * h
                        drawRect(
                            color = Color(el.colorArgb).copy(alpha = 0.25f),
                            topLeft = Offset(cx - 56f, cy - 20f),
                            size = Size(112f, 40f)
                        )
                        drawRect(
                            color = Color(el.colorArgb),
                            topLeft = Offset(cx - 56f, cy - 20f),
                            size = Size(112f, 40f),
                            style = Stroke(width = 3f)
                        )
                    }
                }
            }

            if (currentStroke.size >= 2) {
                drawStroke(
                    currentStroke,
                    activeColorArgb,
                    activeTool == AnnotationTool.HIGHLIGHTER,
                    0.008f
                )
            }

            val s = dragStart
            val e = dragEnd
            if (s != null && e != null) {
                val left = min(s.x, e.x) * w
                val top = min(s.y, e.y) * h
                val boxW = abs(e.x - s.x) * w
                val boxH = abs(e.y - s.y) * h
                drawRect(
                    color = if (activeTool == AnnotationTool.REDACT_BLUR) Color(0x8890A4AE) else Color(0xBB000000),
                    topLeft = Offset(left, top),
                    size = Size(boxW, boxH)
                )
            }
        }

        // Render text & stamp labels interactively above Canvas
        if (boxSize.width > 0 && boxSize.height > 0) {
            elements.forEach { el ->
                when (el) {
                    is AnnotationElement.TextAnnotation -> {
                        Surface(
                            color = Color.White.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, Color(el.colorArgb)),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(
                                    start = ((el.xNorm * boxSize.width) / LocalContextDensity()).dp.coerceAtLeast(0.dp),
                                    top = ((el.yNorm * boxSize.height) / LocalContextDensity()).dp.coerceAtLeast(0.dp)
                                )
                        ) {
                            Text(
                                text = el.text,
                                color = Color(el.colorArgb),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    is AnnotationElement.BadgeStamp -> {
                        Surface(
                            color = Color.Transparent,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(2.dp, Color(el.colorArgb)),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(
                                    start = (((el.xNorm * boxSize.width) / LocalContextDensity()) - 40f).dp.coerceAtLeast(0.dp),
                                    top = (((el.yNorm * boxSize.height) / LocalContextDensity()) - 14f).dp.coerceAtLeast(0.dp)
                                )
                        ) {
                            Text(
                                text = el.label,
                                color = Color(el.colorArgb),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun LocalContextDensity(): Float = androidx.compose.ui.platform.LocalDensity.current.density

@Composable
fun StudioToolDock(
    activeTab: StudioTab,
    onSelectTab: (StudioTab) -> Unit,
    currentColorMode: ColorMode?,
    canBeCropped: Boolean,
    canMergeIdCard: Boolean,
    selectedPreset: ProFilterPreset,
    filterThumbnails: Map<ProFilterPreset, Bitmap>,
    adjustments: ImageAdjustments,
    activeAnnotationTool: AnnotationTool,
    activeAnnotationColor: Int,
    canUndoAnnotation: Boolean,
    canRedoAnnotation: Boolean,
    hasAnnotations: Boolean,
    hasSavedFilter: Boolean = false,
    hasSavedAnnotations: Boolean = false,
    hasSavedEraserStrokes: Boolean = false,
    editCount: Int = 0,
    pageCount: Int = 1,
    onOpenLayers: () -> Unit = {},
    onColorModeSelected: (ColorMode) -> Unit,
    onCropClick: () -> Unit,
    onRotateImage: (Boolean) -> Unit,
    onDeleteImage: () -> Unit,
    onSelectPreset: (ProFilterPreset) -> Unit,
    onAdjustmentsChange: (ImageAdjustments) -> Unit,
    onResetFilterAdjustments: () -> Unit,
    onApplyFilterAdjustments: () -> Unit,
    onApplyFilterToAllPages: () -> Unit = {},
    onSelectAnnotationTool: (AnnotationTool) -> Unit,
    onSelectAnnotationColor: (Int) -> Unit,
    onUndoAnnotation: () -> Unit,
    onRedoAnnotation: () -> Unit,
    onClearAnnotations: () -> Unit,
    onApplyAnnotations: () -> Unit,
    onSignatureClick: () -> Unit,
    onStampVaultClick: () -> Unit,
    onWatermarkClick: () -> Unit,
    onIdMergeClick: () -> Unit,
    onSaveToLibraryClick: () -> Unit,
    onOcrClick: () -> Unit,
    onOpenOcrLanguages: () -> Unit,
    eraserBrushRadius: Float = 0.032f,
    hasEraserStrokes: Boolean = false,
    onEraserBrushRadiusChange: (Float) -> Unit = {},
    onUndoEraser: () -> Unit = {},
    onClearEraser: () -> Unit = {},
    onApplyEraser: () -> Unit = {},
    onFlattenShadows: () -> Unit = {},
    onEnhancePpt: () -> Unit = {},
    onEraseHandwriting: () -> Unit = {},
    onApplyCleanupToAllPages: (org.fairscan.app.camscanner.CleanupEffectType) -> Unit = {},
    onOpenStitchCollage: () -> Unit = {},
    onOpenQrStamp: () -> Unit = {},
    onPrintDocument: () -> Unit = {},
    onOpenAiPhotoEditor: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)) {
            // Sub-panel content for active tab
            when (activeTab) {
                StudioTab.ADJUST -> AdjustSubPanel(
                    currentColorMode = currentColorMode,
                    canBeCropped = canBeCropped,
                    onColorModeSelected = onColorModeSelected,
                    onCropClick = onCropClick,
                    onRotateImage = onRotateImage,
                    onDeleteImage = onDeleteImage,
                    onOpenAiPhotoEditor = onOpenAiPhotoEditor,
                )

                StudioTab.FILTERS -> FiltersSubPanel(
                    selectedPreset = selectedPreset,
                    thumbnails = filterThumbnails,
                    adjustments = adjustments,
                    hasSavedFilter = hasSavedFilter,
                    pageCount = pageCount,
                    onSelectPreset = onSelectPreset,
                    onAdjustmentsChange = onAdjustmentsChange,
                    onReset = onResetFilterAdjustments,
                    onApply = onApplyFilterAdjustments,
                    onApplyAll = onApplyFilterToAllPages,
                )

                StudioTab.CLEANUP -> CleanupSubPanel(
                    brushRadiusNorm = eraserBrushRadius,
                    hasEraserStrokes = hasEraserStrokes,
                    hasSavedEraserStrokes = hasSavedEraserStrokes,
                    pageCount = pageCount,
                    onBrushRadiusChange = onEraserBrushRadiusChange,
                    onUndoEraser = onUndoEraser,
                    onClearEraser = onClearEraser,
                    onApplyEraser = onApplyEraser,
                    onFlattenShadows = onFlattenShadows,
                    onEnhancePpt = onEnhancePpt,
                    onEraseHandwriting = onEraseHandwriting,
                    onApplyCleanupAll = onApplyCleanupToAllPages,
                    onOpenStitchCollage = onOpenStitchCollage,
                    onOpenQrStamp = onOpenQrStamp,
                    onPrintDocument = onPrintDocument,
                )

                StudioTab.ANNOTATE -> AnnotateSubPanel(
                    activeTool = activeAnnotationTool,
                    activeColor = activeAnnotationColor,
                    canUndo = canUndoAnnotation,
                    canRedo = canRedoAnnotation,
                    hasAnnotations = hasAnnotations,
                    hasSavedAnnotations = hasSavedAnnotations,
                    onSelectTool = onSelectAnnotationTool,
                    onSelectColor = onSelectAnnotationColor,
                    onUndo = onUndoAnnotation,
                    onRedo = onRedoAnnotation,
                    onClear = onClearAnnotations,
                    onApply = onApplyAnnotations
                )

                StudioTab.SIGN_MARK -> SignMarkSubPanel(
                    canMergeIdCard = canMergeIdCard,
                    editCount = editCount,
                    onOpenLayers = onOpenLayers,
                    onSignatureClick = onSignatureClick,
                    onStampVaultClick = onStampVaultClick,
                    onWatermarkClick = onWatermarkClick,
                    onIdMergeClick = onIdMergeClick,
                    onSaveToLibraryClick = onSaveToLibraryClick
                )

                StudioTab.OCR -> OcrSubPanel(
                    onOcrClick = onOcrClick,
                    onOpenOcrLanguages = onOpenOcrLanguages
                )
            }

            Spacer(Modifier.height(6.dp))

            // CamScanner Icon-Above-Label Studio Tab Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StudioTab.entries.forEach { tab ->
                    val selected = activeTab == tab
                    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                else Color.Transparent
                            )
                            .clickable { onSelectTab(tab) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("studio_tab_${tab.name.lowercase()}")
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = stringResource(tab.titleRes),
                            tint = tint,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(tab.titleRes),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = tint,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdjustSubPanel(
    currentColorMode: ColorMode?,
    canBeCropped: Boolean,
    onColorModeSelected: (ColorMode) -> Unit,
    onCropClick: () -> Unit,
    onRotateImage: (Boolean) -> Unit,
    onDeleteImage: () -> Unit,
    onOpenAiPhotoEditor: () -> Unit = {},
) {
    var modeMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(
            onClick = onOpenAiPhotoEditor,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.testTag("studio_btn_ai_photo"),
        ) {
            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.ai_photo_short), style = MaterialTheme.typography.labelSmall)
        }
        if (canBeCropped) {
            FilledTonalButton(onClick = onCropClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.crop), style = MaterialTheme.typography.labelSmall)
            }
        }
        FilledTonalButton(onClick = { onRotateImage(false) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            Icon(Icons.Default.RotateLeft, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("90° L", style = MaterialTheme.typography.labelSmall)
        }
        FilledTonalButton(onClick = { onRotateImage(true) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("90° R", style = MaterialTheme.typography.labelSmall)
        }
        if (currentColorMode != null) {
            Box {
                FilledTonalButton(
                    onClick = { modeMenuOpen = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.color_mode), style = MaterialTheme.typography.labelSmall)
                }
                DropdownMenu(expanded = modeMenuOpen, onDismissRequest = { modeMenuOpen = false }) {
                    ColorMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.name.replace('_', ' ')) },
                            onClick = {
                                onColorModeSelected(mode)
                                modeMenuOpen = false
                            },
                            trailingIcon = {
                                if (currentColorMode == mode) Icon(Icons.Default.Check, contentDescription = null)
                            }
                        )
                    }
                }
            }
        }
        OutlinedButton(onClick = onDeleteImage, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FiltersSubPanel(
    selectedPreset: ProFilterPreset,
    thumbnails: Map<ProFilterPreset, Bitmap>,
    adjustments: ImageAdjustments,
    hasSavedFilter: Boolean = false,
    pageCount: Int = 1,
    onSelectPreset: (ProFilterPreset) -> Unit,
    onAdjustmentsChange: (ImageAdjustments) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onApplyAll: () -> Unit = {},
) {
    var selectedCategory by remember { mutableStateOf(FilterCategory.ALL) }
    var showTuneSliders by remember { mutableStateOf(false) }

    val visiblePresets = remember(selectedCategory) {
        ProFilterPreset.forCategory(selectedCategory)
    }

    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        // Header row: Category filter chips + Presets / Tune mode pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterCategory.entries.forEach { category ->
                    val isCatSelected = selectedCategory == category && !showTuneSliders
                    val countSuffix = if (category == FilterCategory.ALL) {
                        " (${ProFilterPreset.entries.size})"
                    } else {
                        " (${ProFilterPreset.forCategory(category).size})"
                    }
                    FilterChip(
                        selected = isCatSelected,
                        onClick = {
                            selectedCategory = category
                            showTuneSliders = false
                        },
                        label = {
                            Text(
                                text = stringResource(category.labelRes) + countSuffix,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        },
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("filter_category_${category.name.lowercase()}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            FilterChip(
                selected = showTuneSliders,
                onClick = { showTuneSliders = !showTuneSliders },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                },
                label = {
                    Text(
                        text = if (!adjustments.isDefault) "Tune •" else "Tune",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                modifier = Modifier
                    .height(32.dp)
                    .testTag("filter_tune_toggle_btn"),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }

        Spacer(Modifier.height(6.dp))

        if (!showTuneSliders) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visiblePresets, key = { it.name }) { preset ->
                    val selected = selectedPreset == preset
                    val thumb = thumbnails[preset]
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(68.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable { onSelectPreset(preset) }
                            .padding(4.dp)
                            .testTag("filter_preset_${preset.name.lowercase()}")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(58.dp, 48.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = stringResource(preset.labelRes),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            if (selected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(11.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(preset.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 148.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                FilterSliderRow(
                    label = stringResource(R.string.adjust_brightness),
                    value = adjustments.brightness,
                    valueRange = -50f..50f,
                    valueText = "%+d".format(adjustments.brightness.toInt()),
                    testTag = "slider_brightness",
                    onValueChange = { onAdjustmentsChange(adjustments.copy(brightness = it)) },
                )
                FilterSliderRow(
                    label = stringResource(R.string.adjust_contrast),
                    value = adjustments.contrast,
                    valueRange = 0.60f..1.60f,
                    valueText = "%.2fx".format(adjustments.contrast),
                    testTag = "slider_contrast",
                    onValueChange = { onAdjustmentsChange(adjustments.copy(contrast = it)) },
                )
                FilterSliderRow(
                    label = stringResource(R.string.adjust_sharpness),
                    value = adjustments.sharpness,
                    valueRange = 0f..1f,
                    valueText = "${(adjustments.sharpness * 100).toInt()}%",
                    testTag = "slider_sharpness",
                    onValueChange = { onAdjustmentsChange(adjustments.copy(sharpness = it)) },
                )
                FilterSliderRow(
                    label = stringResource(R.string.adjust_saturation),
                    value = adjustments.saturation,
                    valueRange = 0f..2f,
                    valueText = "${(adjustments.saturation * 100).toInt()}%",
                    testTag = "slider_saturation",
                    onValueChange = { onAdjustmentsChange(adjustments.copy(saturation = it)) },
                )
                FilterSliderRow(
                    label = stringResource(R.string.adjust_warmth),
                    value = adjustments.warmth,
                    valueRange = -50f..50f,
                    valueText = "%+d".format(adjustments.warmth.toInt()),
                    testTag = "slider_warmth",
                    onValueChange = { onAdjustmentsChange(adjustments.copy(warmth = it)) },
                )
                FilterSliderRow(
                    label = stringResource(R.string.adjust_exposure),
                    value = adjustments.exposure,
                    valueRange = -1f..1f,
                    valueText = "%+.2f".format(adjustments.exposure),
                    testTag = "slider_exposure",
                    onValueChange = { onAdjustmentsChange(adjustments.copy(exposure = it)) },
                )
                FilterSliderRow(
                    label = stringResource(R.string.adjust_fade),
                    value = adjustments.fade,
                    valueRange = 0f..1f,
                    valueText = "${(adjustments.fade * 100).toInt()}%",
                    testTag = "slider_fade",
                    onValueChange = { onAdjustmentsChange(adjustments.copy(fade = it)) },
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(selectedPreset.labelRes) +
                    if (!adjustments.isDefault) " + Tuned" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onReset,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.testTag("filter_reset_btn"),
            ) {
                Text("Reset", style = MaterialTheme.typography.labelSmall)
            }
            if (pageCount > 1) {
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    onClick = onApplyAll,
                    enabled = selectedPreset != ProFilterPreset.ORIGINAL || !adjustments.isDefault || hasSavedFilter,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("filter_apply_all_btn"),
                ) {
                    Text(stringResource(R.string.apply_all_pages), style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = onApply,
                enabled = selectedPreset != ProFilterPreset.ORIGINAL || !adjustments.isDefault || hasSavedFilter,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                modifier = Modifier.testTag("filter_save_btn"),
            ) {
                Text("Save", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun FilterSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    testTag: String,
    onValueChange: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(74.dp),
            maxLines = 1,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
                .testTag(testTag),
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .width(44.dp)
                .padding(start = 4.dp),
        )
    }
}

@Composable
private fun AnnotateSubPanel(
    activeTool: AnnotationTool,
    activeColor: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    hasAnnotations: Boolean,
    hasSavedAnnotations: Boolean = false,
    onSelectTool: (AnnotationTool) -> Unit,
    onSelectColor: (Int) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClear: () -> Unit,
    onApply: () -> Unit,
) {
    val tools = listOf(
        Triple(AnnotationTool.PEN, R.string.tool_pen, Icons.Default.Brush),
        Triple(AnnotationTool.HIGHLIGHTER, R.string.tool_highlighter, Icons.Default.FormatColorFill),
        Triple(AnnotationTool.REDACT_BLUR, R.string.tool_redact_blur, Icons.Default.BlurOn),
        Triple(AnnotationTool.REDACT_BLACK, R.string.tool_redact_black, Icons.Default.Security),
        Triple(AnnotationTool.TEXT, R.string.tool_text, Icons.Default.Title),
        Triple(AnnotationTool.STAMP, R.string.tool_stamp, Icons.Default.Approval),
    )
    val palette = listOf(
        0xFF00897B.toInt(),
        0xFF0D47A1.toInt(),
        0xFFD32F2F.toInt(),
        0xFFFFEB3B.toInt(),
        0xFF1A1A1A.toInt(),
    )

    Column(modifier = Modifier.padding(horizontal = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tools.forEach { (tool, labelRes, icon) ->
                FilterChip(
                    selected = activeTool == tool,
                    onClick = { onSelectTool(tool) },
                    leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp)) },
                    label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                palette.forEach { argb ->
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(
                                width = if (activeColor == argb) 2.5.dp else 1.dp,
                                color = if (activeColor == argb) MaterialTheme.colorScheme.primary else Color.LightGray,
                                shape = CircleShape
                            )
                            .clickable { onSelectColor(argb) }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onClear, enabled = hasAnnotations, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                }
                Button(
                    onClick = onApply,
                    enabled = hasAnnotations || hasSavedAnnotations,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("annotate_save_btn")
                ) {
                    Text("Save", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SignMarkSubPanel(
    canMergeIdCard: Boolean,
    editCount: Int = 0,
    onOpenLayers: () -> Unit = {},
    onSignatureClick: () -> Unit,
    onStampVaultClick: () -> Unit,
    onWatermarkClick: () -> Unit,
    onIdMergeClick: () -> Unit,
    onSaveToLibraryClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalButton(
            onClick = onSignatureClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.testTag("studio_btn_signature")
        ) {
            Icon(Icons.Default.Draw, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.camscanner_signature), style = MaterialTheme.typography.labelSmall)
        }
        FilledTonalButton(
            onClick = onStampVaultClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.testTag("studio_btn_stamp")
        ) {
            Icon(Icons.Default.Approval, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.camscanner_scan_stamp), style = MaterialTheme.typography.labelSmall)
        }
        FilledTonalButton(onClick = onWatermarkClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.camscanner_watermark), style = MaterialTheme.typography.labelSmall)
        }
        if (canMergeIdCard) {
            FilledTonalButton(onClick = onIdMergeClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Icon(Icons.Default.Badge, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.camscanner_merge_id), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (editCount > 0) {
            FilledTonalButton(
                onClick = onOpenLayers,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("studio_btn_layers")
            ) {
                Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Layers ($editCount)", style = MaterialTheme.typography.labelSmall)
            }
        }
        Button(
            onClick = onSaveToLibraryClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.testTag("studio_save_library")
        ) {
            Icon(Icons.Default.BookmarkAdded, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.studio_save_to_library), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun OverlayPlacementControlBar(
    label: String,
    scaleFactor: Float,
    rotationDeg: Float,
    opacity: Float,
    onScaleChange: (Float) -> Unit,
    onRotationChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Drag \"$label\" anywhere on page",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Drag anywhere on the page, resize, and adjust angle",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onRemove != null) {
                        IconButton(
                            onClick = onRemove,
                            modifier = Modifier.testTag("overlay_remove_button")
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Remove $label",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onCancel,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Cancel", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = onApply,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("overlay_apply_button")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Apply", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Size ${(scaleFactor * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(66.dp))
                Slider(
                    value = scaleFactor,
                    onValueChange = onScaleChange,
                    valueRange = 0.12f..0.75f,
                    modifier = Modifier.weight(1f).height(26.dp)
                )
                Text("Angle ${rotationDeg.toInt()}°", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(68.dp))
                Slider(
                    value = rotationDeg,
                    onValueChange = onRotationChange,
                    valueRange = -45f..45f,
                    modifier = Modifier.weight(1f).height(26.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Ink ${(opacity * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(66.dp))
                Slider(
                    value = opacity,
                    onValueChange = onOpacityChange,
                    valueRange = 0.35f..1f,
                    modifier = Modifier.weight(1f).height(26.dp)
                )
                TextButton(
                    onClick = { onRotationChange(0f) },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("0° Straight", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun OcrSubPanel(
    onOcrClick: () -> Unit,
    onOpenOcrLanguages: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onOcrClick,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.camscanner_ocr), style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(
            onClick = onOpenOcrLanguages,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.settings_ocr_languages), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun AnnotationTextDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Text Annotation") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Note or Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank()
            ) { Text("Place") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StampPickerDialog(
    onDismiss: () -> Unit,
    onSelectStamp: (label: String, colorArgb: Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Stamp") },
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DEFAULT_STAMPS.forEach { (label, colorArgb) ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(2.dp, Color(colorArgb)),
                        modifier = Modifier.clickable { onSelectStamp(label, colorArgb) }
                    ) {
                        Text(
                            text = label,
                            color = Color(colorArgb),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaveToLibraryDialog(
    initialTitle: String,
    folders: List<FolderEntity>,
    onDismiss: () -> Unit,
    onSave: (title: String, folderId: String?, tagsCsv: String) -> Unit,
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var tagsCsv by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.studio_save_to_library)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Document Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tagsCsv,
                    onValueChange = { tagsCsv = it },
                    label = { Text("Tags (comma separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (folders.isNotEmpty()) {
                    Text("Folder:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = selectedFolderId == null,
                            onClick = { selectedFolderId = null },
                            label = { Text("Root") }
                        )
                        folders.forEach { folder ->
                            FilterChip(
                                selected = selectedFolderId == folder.id,
                                onClick = { selectedFolderId = folder.id },
                                label = { Text(folder.name) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(title, selectedFolderId, tagsCsv)
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
