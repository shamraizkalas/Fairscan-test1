package org.fairscan.app.ui.screens.document

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Approval
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.fairscan.app.camscanner.ImageAdjustments
import org.fairscan.app.camscanner.PageEditState
import org.fairscan.app.camscanner.ProFilterPreset
import java.io.File

@Composable
fun LayersManagerDialog(
    editState: PageEditState,
    resolveOverlayFile: ((String) -> File)?,
    onDismiss: () -> Unit,
    onMoveResizeOverlay: (overlayId: String) -> Unit,
    onRemoveOverlay: (overlayId: String) -> Unit,
    onEditWatermark: () -> Unit,
    onRemoveWatermark: () -> Unit,
    onEditFilter: () -> Unit,
    onResetFilter: () -> Unit,
    onRemoveCleanupEffect: (effectId: String) -> Unit,
    onRemoveEraserStroke: (strokeId: String) -> Unit,
    onClearAllEraserStrokes: () -> Unit,
    onRemoveAnnotation: (annotationId: String) -> Unit,
    onClearAllAnnotations: () -> Unit,
    onResetAllEdits: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 580.dp)
                .clip(RoundedCornerShape(20.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Edit Layers (${editState.totalEditCount})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Modify, reposition, or remove any saved edit",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close layers")
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                if (!editState.hasAnyEdits) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No edits applied on this page yet. Signatures, stamps, filters, watermarks, eraser strokes, and annotations stay editable here even after saving.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // 1. Placed Signatures / Stamps / QR Overlays
                        if (editState.overlays.isNotEmpty()) {
                            LayerSectionHeader("Signatures, Stamps & QR (${editState.overlays.size})")
                            editState.overlays.forEachIndexed { idx, ov ->
                                val thumb = remember(ov.assetFileName) {
                                    resolveOverlayFile?.invoke(ov.assetFileName)?.takeIf { it.exists() }?.let { f ->
                                        BitmapFactory.decodeFile(f.absolutePath)
                                    }
                                }
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(56.dp, 36.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.White),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (thumb != null) {
                                                Image(
                                                    bitmap = thumb.asImageBitmap(),
                                                    contentDescription = ov.label,
                                                    contentScale = ContentScale.Fit,
                                                    modifier = Modifier.padding(3.dp),
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = if (ov.isStamp) Icons.Default.Approval else Icons.Default.Draw,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = ov.label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "${if (ov.isStamp) "Stamp" else "Signature"} • Size ${(ov.scaleFactor * 100).toInt()}%",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = {
                                                onDismiss()
                                                onMoveResizeOverlay(ov.id)
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier.testTag("layer_move_overlay_$idx"),
                                        ) {
                                            Icon(Icons.Default.OpenWith, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Move", style = MaterialTheme.typography.labelSmall)
                                        }
                                        Spacer(Modifier.width(4.dp))
                                        IconButton(
                                            onClick = { onRemoveOverlay(ov.id) },
                                            modifier = Modifier.testTag("layer_remove_overlay_$idx"),
                                        ) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "Remove ${ov.label}",
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 2. Watermark Layer
                        editState.watermark?.let { wm ->
                            LayerSectionHeader("Watermark")
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(wm.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "Opacity ${(wm.opacity * 100).toInt()}% • ${wm.rotationDegrees.toInt()}°",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            onDismiss()
                                            onEditWatermark()
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    ) {
                                        Text("Edit", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = onRemoveWatermark,
                                        modifier = Modifier.testTag("layer_remove_watermark"),
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Remove Watermark", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        // 3. Filter & Adjustments
                        val filter = editState.filterLayer
                        if (filter != null && !filter.isDefault) {
                            LayerSectionHeader("Filter & Light")
                            val presetLabel = stringResource(filter.toPreset().labelRes)
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(presetLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        val extras = buildList {
                                            if (kotlin.math.abs(filter.brightness) >= 0.5f) add("Bright %+d".format(filter.brightness.toInt()))
                                            if (kotlin.math.abs(filter.contrast - 1f) >= 0.02f) add("Contrast %.2f".format(filter.contrast))
                                            if (filter.sharpness >= 0.02f) add("Sharp ${(filter.sharpness * 100).toInt()}%")
                                            if (kotlin.math.abs(filter.saturation - 1f) >= 0.02f) add("Sat ${(filter.saturation * 100).toInt()}%")
                                            if (kotlin.math.abs(filter.warmth) >= 0.5f) add("Warm %+d".format(filter.warmth.toInt()))
                                            if (kotlin.math.abs(filter.exposure) >= 0.02f) add("Exp %+.2f".format(filter.exposure))
                                            if (filter.fade >= 0.02f) add("Fade ${(filter.fade * 100).toInt()}%")
                                        }.joinToString(" • ").ifEmpty { "Preset filter active" }
                                        Text(
                                            extras,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            onDismiss()
                                            onEditFilter()
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    ) {
                                        Text("Edit", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = onResetFilter,
                                        modifier = Modifier.testTag("layer_reset_filter"),
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Reset filter", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        // 4. Cleanup Effects
                        if (editState.cleanupEffects.isNotEmpty()) {
                            LayerSectionHeader("Cleanup Effects (${editState.cleanupEffects.size})")
                            editState.cleanupEffects.forEachIndexed { idx, effect ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Default.CleaningServices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            effect.type.displayLabel,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(
                                            onClick = { onRemoveCleanupEffect(effect.id) },
                                            modifier = Modifier.testTag("layer_remove_cleanup_$idx"),
                                        ) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "Remove ${effect.type.displayLabel}", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }

                        // 5. Magic Eraser Strokes
                        if (editState.eraserStrokes.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LayerSectionHeader("Magic Eraser Strokes (${editState.eraserStrokes.size})")
                                androidx.compose.material3.TextButton(
                                    onClick = onClearAllEraserStrokes,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("layer_clear_eraser"),
                                ) {
                                    Text(
                                        text = "Clear All",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            editState.eraserStrokes.forEachIndexed { idx, stroke ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Default.CleaningServices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = "Eraser Stroke #${idx + 1}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(
                                            onClick = { onRemoveEraserStroke(stroke.id) },
                                            modifier = Modifier.testTag("layer_remove_eraser_$idx"),
                                        ) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "Remove eraser stroke", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }

                        // 6. Annotations
                        if (editState.annotations.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LayerSectionHeader("Annotations (${editState.annotations.size})")
                                androidx.compose.material3.TextButton(
                                    onClick = onClearAllAnnotations,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("layer_clear_annotations"),
                                ) {
                                    Text(
                                        text = "Clear All",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            editState.annotations.forEachIndexed { idx, ann ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Default.Brush, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = ann.summaryLabel(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(
                                            onClick = { onRemoveAnnotation(ann.id) },
                                            modifier = Modifier.testTag("layer_remove_annotation_$idx"),
                                        ) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete annotation", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        OutlinedButton(
                            onClick = {
                                onResetAllEdits()
                                onDismiss()
                            },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.testTag("layer_reset_all_btn"),
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Reset All Edits")
                        }
                        Button(onClick = onDismiss) {
                            Text("Done")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayerSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}
