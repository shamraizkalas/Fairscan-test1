package org.fairscan.app.ui.screens.document

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.fairscan.app.camscanner.EraserStroke
import kotlin.math.min

@Composable
fun MagicEraserCanvasOverlay(
    strokes: List<EraserStroke>,
    brushRadiusNorm: Float,
    onAddStroke: (EraserStroke) -> Unit,
    modifier: Modifier = Modifier,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var currentPoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }

    fun toNorm(offset: Offset): Pair<Float, Float> {
        val w = boxSize.width.coerceAtLeast(1).toFloat()
        val h = boxSize.height.coerceAtLeast(1).toFloat()
        return (offset.x / w).coerceIn(0f, 1f) to (offset.y / h).coerceIn(0f, 1f)
    }

    Box(
        modifier = modifier
            .onSizeChanged { boxSize = it }
            .pointerInput(brushRadiusNorm) {
                detectDragGestures(
                    onDragStart = { start ->
                        currentPoints = listOf(toNorm(start))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        currentPoints = currentPoints + toNorm(change.position)
                    },
                    onDragEnd = {
                        if (currentPoints.isNotEmpty()) {
                            onAddStroke(
                                EraserStroke(
                                    pointsNorm = currentPoints,
                                    brushRadiusNorm = brushRadiusNorm,
                                )
                            )
                        }
                        currentPoints = emptyList()
                    },
                    onDragCancel = {
                        currentPoints = emptyList()
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val baseDim = min(w, h).coerceAtLeast(1f)
            val brushColor = Color(0x88FF3D00)

            fun renderStroke(points: List<Pair<Float, Float>>, radiusNorm: Float) {
                if (points.isEmpty()) return
                val strokePx = (radiusNorm * baseDim * 2f).coerceAtLeast(10f)
                if (points.size == 1) {
                    drawCircle(
                        color = brushColor,
                        radius = strokePx / 2f,
                        center = Offset(points[0].first * w, points[0].second * h)
                    )
                } else {
                    val path = Path().apply {
                        moveTo(points.first().first * w, points.first().second * h)
                        for (i in 1 until points.size) {
                            lineTo(points[i].first * w, points[i].second * h)
                        }
                    }
                    drawPath(
                        path = path,
                        color = brushColor,
                        style = Stroke(
                            width = strokePx,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }

            strokes.forEach { renderStroke(it.pointsNorm, it.brushRadiusNorm) }
            if (currentPoints.isNotEmpty()) {
                renderStroke(currentPoints, brushRadiusNorm)
            }
        }
    }
}

@Composable
fun CleanupSubPanel(
    brushRadiusNorm: Float,
    hasEraserStrokes: Boolean,
    hasSavedEraserStrokes: Boolean = false,
    pageCount: Int = 1,
    onBrushRadiusChange: (Float) -> Unit,
    onUndoEraser: () -> Unit,
    onClearEraser: () -> Unit,
    onApplyEraser: () -> Unit,
    onFlattenShadows: () -> Unit,
    onEnhancePpt: () -> Unit,
    onEraseHandwriting: () -> Unit,
    onApplyCleanupAll: (org.fairscan.app.camscanner.CleanupEffectType) -> Unit = {},
    onOpenStitchCollage: () -> Unit,
    onOpenQrStamp: () -> Unit,
    onPrintDocument: () -> Unit,
) {
    var applyToAll by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        // Magic Eraser Brush controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Eraser Brush",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(78.dp)
            )
            Slider(
                value = brushRadiusNorm,
                onValueChange = onBrushRadiusChange,
                valueRange = 0.015f..0.065f,
                modifier = Modifier
                    .weight(1f)
                    .height(26.dp)
            )
            IconButton(
                onClick = onUndoEraser,
                enabled = hasEraserStrokes,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo eraser stroke", modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = onClearEraser,
                enabled = hasEraserStrokes,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(Icons.Default.Clear, contentDescription = "Clear eraser strokes", modifier = Modifier.size(18.dp))
            }
            Button(
                onClick = onApplyEraser,
                enabled = hasEraserStrokes || hasSavedEraserStrokes,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.testTag("cleanup_apply_eraser")
            ) {
                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Apply", style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(Modifier.height(4.dp))

        // 1-Tap Document Cleanup & Studio Layout Tools
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (pageCount > 1) {
                androidx.compose.material3.FilterChip(
                    selected = applyToAll,
                    onClick = { applyToAll = !applyToAll },
                    label = {
                        Text(
                            text = "All ($pageCount)",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.testTag("cleanup_apply_all_toggle")
                )
            }
            FilledTonalButton(
                onClick = {
                    if (applyToAll && pageCount > 1) {
                        onApplyCleanupAll(org.fairscan.app.camscanner.CleanupEffectType.SHADOW_FLATTEN)
                    } else {
                        onFlattenShadows()
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("cleanup_shadow_flatten")
            ) {
                Icon(Icons.Default.WbSunny, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Shadow Fix", style = MaterialTheme.typography.labelSmall)
            }
            FilledTonalButton(
                onClick = {
                    if (applyToAll && pageCount > 1) {
                        onApplyCleanupAll(org.fairscan.app.camscanner.CleanupEffectType.PPT_WHITEBOARD)
                    } else {
                        onEnhancePpt()
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("cleanup_ppt_enhance")
            ) {
                Icon(Icons.Default.Slideshow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("PPT Board", style = MaterialTheme.typography.labelSmall)
            }
            FilledTonalButton(
                onClick = {
                    if (applyToAll && pageCount > 1) {
                        onApplyCleanupAll(org.fairscan.app.camscanner.CleanupEffectType.HANDWRITING_ERASE)
                    } else {
                        onEraseHandwriting()
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("cleanup_erase_handwriting")
            ) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Clear Pen/Notes", style = MaterialTheme.typography.labelSmall)
            }
            FilledTonalButton(
                onClick = onOpenStitchCollage,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("cleanup_stitch_collage")
            ) {
                Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Stitch & Collage", style = MaterialTheme.typography.labelSmall)
            }
            FilledTonalButton(
                onClick = onOpenQrStamp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("cleanup_qr_stamp")
            ) {
                Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("QR Stamp", style = MaterialTheme.typography.labelSmall)
            }
            FilledTonalButton(
                onClick = onPrintDocument,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("cleanup_wireless_print")
            ) {
                Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Print", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
