package org.fairscan.app.camscanner

import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.fairscan.app.R
import org.fairscan.app.cloud.CloudOcrAnalysisResult
import org.fairscan.app.cloud.HybridEngineBadge
import org.fairscan.imageprocessing.OcrTextBox
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatermarkDialog(
    existingWatermark: WatermarkLayer? = null,
    pageCount: Int = 1,
    onDismiss: () -> Unit,
    onApplyWatermark: (text: String, opacity: Float, rotation: Float, tileRepeated: Boolean) -> Unit,
    onApplyWatermarkAll: ((text: String, opacity: Float, rotation: Float, tileRepeated: Boolean) -> Unit)? = null,
    onRemoveWatermark: (() -> Unit)? = null,
) {
    var text by remember(existingWatermark) { mutableStateOf(existingWatermark?.text ?: "CONFIDENTIAL") }
    var opacity by remember(existingWatermark) { mutableFloatStateOf(existingWatermark?.opacity ?: 0.30f) }
    var rotation by remember(existingWatermark) { mutableFloatStateOf(existingWatermark?.rotationDegrees ?: -30f) }
    var tileRepeated by remember(existingWatermark) { mutableStateOf(existingWatermark?.tileRepeated ?: true) }
    var applyAllPages by remember { mutableStateOf(false) }

    val presets = listOf(
        "CONFIDENTIAL",
        "FOR OFFICIAL USE",
        "DO NOT COPY",
        "ORIGINAL",
        "VERIFIED",
        "DRAFT"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("Watermark")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Preset Text:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = text == preset,
                            onClick = { text = preset },
                            label = { Text(preset, style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Watermark Text") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Opacity: ${(opacity * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    valueRange = 0.1f..0.8f
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Rotation Angle: ${rotation.toInt()}°",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = rotation,
                    onValueChange = { rotation = it },
                    valueRange = -60f..60f
                )

                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { tileRepeated = !tileRepeated }
                ) {
                    Checkbox(
                        checked = tileRepeated,
                        onCheckedChange = { tileRepeated = it }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Tile Repeated Across Page",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (pageCount > 1 && onApplyWatermarkAll != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { applyAllPages = !applyAllPages }
                    ) {
                        Checkbox(
                            checked = applyAllPages,
                            onCheckedChange = { applyAllPages = it }
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${stringResource(R.string.apply_all_pages)} ($pageCount)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (existingWatermark != null && onRemoveWatermark != null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            onRemoveWatermark()
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("watermark_remove_btn")
                    ) {
                        Text("Remove Watermark", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        if (applyAllPages && onApplyWatermarkAll != null) {
                            onApplyWatermarkAll(text, opacity, rotation, tileRepeated)
                        } else {
                            onApplyWatermark(text, opacity, rotation, tileRepeated)
                        }
                        onDismiss()
                    }
                }
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

enum class SignaturePosition(
    val label: String,
    val normX: Float,
    val normY: Float
) {
    BOTTOM_RIGHT("Bottom Right", 0.74f, 0.84f),
    BOTTOM_CENTER("Bottom Center", 0.50f, 0.84f),
    BOTTOM_LEFT("Bottom Left", 0.26f, 0.84f),
    CENTER("Center", 0.50f, 0.50f),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignatureDialog(
    savedSignatures: List<org.fairscan.app.library.SavedSignatureEntity> = emptyList(),
    onSaveSignatureToLibrary: ((Bitmap) -> Unit)? = null,
    onDeleteSavedSignature: ((org.fairscan.app.library.SavedSignatureEntity) -> Unit)? = null,
    onDismiss: () -> Unit,
    onApplySignature: (Bitmap, normX: Float, normY: Float, scaleFactor: Float) -> Unit
) {
    val paths = remember { mutableStateListOf<SignaturePath>() }
    var currentPathPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    var selectedColor by remember { mutableStateOf(Color(0xFF003366)) } // Fountain Navy Blue
    var strokeWidth by remember { mutableFloatStateOf(9f) }
    var selectedPosition by remember { mutableStateOf(SignaturePosition.BOTTOM_RIGHT) }
    var scaleFactor by remember { mutableFloatStateOf(0.32f) }
    var saveForReuse by remember { mutableStateOf(true) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val colorOptions = listOf(
        Color(0xFF003366) to "Navy Blue",
        Color(0xFF1A1A1A) to "Black",
        Color(0xFFCC0000) to "Red"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Draw,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Draw Signature",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row {
                        IconButton(
                            onClick = {
                                if (paths.isNotEmpty()) paths.removeAt(paths.lastIndex)
                            },
                            enabled = paths.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Undo, contentDescription = "Undo")
                        }
                        IconButton(
                            onClick = { paths.clear() },
                            enabled = paths.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Canvas Pad
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(selectedColor, strokeWidth) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    currentPathPoints = listOf(offset)
                                },
                                onDrag = { change, _ ->
                                    currentPathPoints = currentPathPoints + change.position
                                },
                                onDragEnd = {
                                    if (currentPathPoints.size >= 2) {
                                        paths.add(
                                            SignaturePath(
                                                points = currentPathPoints,
                                                color = selectedColor.toArgb(),
                                                strokeWidth = strokeWidth
                                            )
                                        )
                                    }
                                    currentPathPoints = emptyList()
                                },
                                onDragCancel = {
                                    currentPathPoints = emptyList()
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        for (sigPath in paths) {
                            if (sigPath.points.size < 2) continue
                            for (i in 0 until sigPath.points.size - 1) {
                                drawLine(
                                    color = Color(sigPath.color),
                                    start = sigPath.points[i],
                                    end = sigPath.points[i + 1],
                                    strokeWidth = sigPath.strokeWidth,
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                        if (currentPathPoints.size >= 2) {
                            for (i in 0 until currentPathPoints.size - 1) {
                                drawLine(
                                    color = selectedColor,
                                    start = currentPathPoints[i],
                                    end = currentPathPoints[i + 1],
                                    strokeWidth = strokeWidth,
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }

                    if (paths.isEmpty() && currentPathPoints.isEmpty()) {
                        Text(
                            text = "Sign here with finger",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.LightGray,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Color picker & thickness
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        colorOptions.forEach { (color, _) ->
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (selectedColor == color) 3.dp else 1.dp,
                                        color = if (selectedColor == color) MaterialTheme.colorScheme.primary else Color.LightGray,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColor = color }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5f to "Thin", 9f to "Med", 14f to "Bold").forEach { (width, label) ->
                            FilterChip(
                                selected = strokeWidth == width,
                                onClick = { strokeWidth = width },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Position on Page:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SignaturePosition.entries.forEach { pos ->
                            FilterChip(
                                selected = selectedPosition == pos,
                                onClick = { selectedPosition = pos },
                                label = { Text(pos.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Size: ${(scaleFactor * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = scaleFactor,
                        onValueChange = { scaleFactor = it },
                        valueRange = 0.18f..0.55f
                    )

                    if (onSaveSignatureToLibrary != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { saveForReuse = !saveForReuse }
                        ) {
                            Checkbox(
                                checked = saveForReuse,
                                onCheckedChange = { saveForReuse = it }
                            )
                            Text(
                                text = "Save signature for reuse",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (savedSignatures.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Saved Signatures (Tap to place):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            savedSignatures.forEach { saved ->
                                val bmp = remember(saved.imagePath) {
                                    android.graphics.BitmapFactory.decodeFile(saved.imagePath)
                                }
                                if (bmp != null) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color.White,
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant
                                        ),
                                        modifier = Modifier.clickable {
                                            onApplySignature(
                                                bmp,
                                                selectedPosition.normX,
                                                selectedPosition.normY,
                                                scaleFactor
                                            )
                                            onDismiss()
                                        }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            androidx.compose.foundation.Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = saved.label,
                                                modifier = Modifier
                                                    .width(68.dp)
                                                    .height(34.dp)
                                            )
                                            if (onDeleteSavedSignature != null) {
                                                IconButton(
                                                    onClick = { onDeleteSavedSignature(saved) },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Clear,
                                                        contentDescription = "Remove",
                                                        modifier = Modifier.size(14.dp),
                                                        tint = Color.Gray
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val sigBitmap = SignatureHelper.createSignatureBitmap(
                                paths,
                                canvasSize.width,
                                canvasSize.height
                            )
                            if (sigBitmap != null) {
                                if (saveForReuse) {
                                    onSaveSignatureToLibrary?.invoke(sigBitmap)
                                }
                                onApplySignature(
                                    sigBitmap,
                                    selectedPosition.normX,
                                    selectedPosition.normY,
                                    scaleFactor
                                )
                                onDismiss()
                            }
                        },
                        enabled = paths.isNotEmpty()
                    ) {
                        Text("Place Signature")
                    }
                }
            }
        }
    }
}

@Composable
fun IdCardMergeDialog(
    pageCount: Int,
    currentPageIndex: Int,
    onDismiss: () -> Unit,
    onMerge: (frontIndex: Int, backIndex: Int, removeOriginals: Boolean) -> Unit
) {
    val defaultFront = if (currentPageIndex >= pageCount - 1) (pageCount - 2).coerceAtLeast(0) else currentPageIndex
    val defaultBack = (defaultFront + 1).coerceAtMost(pageCount - 1)

    var frontIndex by remember { mutableIntStateOf(defaultFront) }
    var backIndex by remember { mutableIntStateOf(defaultBack) }
    var removeOriginals by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Badge,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("Merge ID Card")
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Combine front and back scans onto one A4 printable sheet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Front Side Page: Page ${frontIndex + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0 until pageCount).take(5).forEach { idx ->
                        FilterChip(
                            selected = frontIndex == idx,
                            onClick = { frontIndex = idx },
                            label = { Text("${idx + 1}") }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Back Side Page: Page ${backIndex + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0 until pageCount).take(5).forEach { idx ->
                        FilterChip(
                            selected = backIndex == idx,
                            onClick = { backIndex = idx },
                            label = { Text("${idx + 1}") }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { removeOriginals = !removeOriginals }
                ) {
                    Checkbox(
                        checked = removeOriginals,
                        onCheckedChange = { removeOriginals = it }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Remove original separate pages",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onMerge(frontIndex, backIndex, removeOriginals)
                    onDismiss()
                },
                enabled = frontIndex != backIndex
            ) {
                Text("Merge")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OcrResultDialog(
    extractedText: String,
    ocrBoxes: List<OcrTextBox> = emptyList(),
    cloudAnalysis: CloudOcrAnalysisResult? = null,
    ocrEngineLabel: String = "Offline Tesseract",
    isOnlineActive: Boolean = false,
    downloadedLangCodes: Set<String> = emptySet(),
    cloudError: String? = null,
    onTranslateText: (suspend (text: String, targetLang: String) -> String)? = null,
    onRerunOfflineOcr: (() -> Unit)? = null,
    onRerunCloudOcr: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onShareText: (String) -> Unit,
    onExportCsv: ((csvContent: String, suggestedFilename: String) -> Unit)? = null,
    onOpenOcrLanguages: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var isTtsReady by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }
    var speechRate by remember { mutableFloatStateOf(1.0f) }

    // Free ML Kit Offline Translation state (59+ languages including Urdu)
    val translationLanguages = remember {
        listOf("Urdu", "English", "Arabic", "Hindi", "Spanish", "French", "German", "Turkish", "Chinese")
    }
    var targetLanguage by remember { mutableStateOf("Urdu") }
    var isTranslating by remember { mutableStateOf(false) }
    var translatedOutput by remember(extractedText) { mutableStateOf("") }
    var translationError by remember { mutableStateOf<String?>(null) }

    val receiptResult = remember(extractedText, ocrBoxes, cloudAnalysis) {
        cloudAnalysis?.receiptResult?.takeIf { it.hasAnyField }
            ?: DocumentDataExtractor.extractReceiptData(extractedText, ocrBoxes)
    }
    val tableResult = remember(extractedText, ocrBoxes, cloudAnalysis) {
        val cloudCsv = cloudAnalysis?.tableCsv?.trim().orEmpty()
        if (cloudCsv.isNotEmpty()) {
            parseCsvToTableResult(cloudCsv)
        } else {
            DocumentDataExtractor.extractTableToCsv(ocrBoxes, extractedText)
        }
    }

    val ttsHolder = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        var instance: TextToSpeech? = null
        instance = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                instance?.language = Locale.getDefault()
                instance?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { isSpeaking = true }
                    override fun onDone(utteranceId: String?) { isSpeaking = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { isSpeaking = false }
                })
                isTtsReady = true
            }
        }
        ttsHolder.value = instance
        onDispose {
            instance?.stop()
            instance?.shutdown()
            ttsHolder.value = null
        }
    }

    val wordCount = remember(extractedText) {
        if (extractedText.isBlank()) 0 else extractedText.trim().split("\\s+".toRegex()).size
    }

    val activeCopyPayload = remember(selectedTab, extractedText, receiptResult, tableResult, translatedOutput, cloudAnalysis) {
        when (selectedTab) {
            1 -> buildString {
                receiptResult.merchant?.let { appendLine("Merchant: $it") }
                receiptResult.date?.let { appendLine("Date: $it") }
                receiptResult.invoiceNumber?.let { appendLine("Invoice #: $it") }
                receiptResult.subtotal?.let { appendLine("Subtotal: $it") }
                receiptResult.tax?.let { appendLine("Tax: $it") }
                receiptResult.total?.let { appendLine("Total: $it") }
                if (receiptResult.lineItems.isNotEmpty()) {
                    appendLine("Items:")
                    receiptResult.lineItems.forEach { appendLine("- ${it.description}: ${it.amount}") }
                }
            }.trim().ifEmpty { extractedText }
            2 -> tableResult.csv.ifEmpty { extractedText }
            3 -> translatedOutput.ifBlank { cloudAnalysis?.summary.orEmpty() }.ifBlank { extractedText }
            else -> extractedText
        }
    }

    AlertDialog(
        onDismissRequest = {
            ttsHolder.value?.stop()
            onDismiss()
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("OCR & Data Extractor", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "$wordCount words",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HybridEngineBadge(
                        isOnlineActive = cloudAnalysis != null,
                        onlineLabel = "ML Kit Fast (${cloudAnalysis?.detectedLanguage ?: "Auto"})",
                        offlineLabel = ocrEngineLabel,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (cloudAnalysis == null && onRerunCloudOcr != null) {
                            TextButton(
                                onClick = onRerunCloudOcr,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("ML Kit Fast OCR", style = MaterialTheme.typography.labelSmall)
                            }
                        } else if (cloudAnalysis != null && onRerunOfflineOcr != null) {
                            TextButton(
                                onClick = onRerunOfflineOcr,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("Tesseract OCR", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                cloudError?.let { err ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                if (extractedText.isNotBlank()) {
                    PrimaryTabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0; copied = false },
                            text = { Text(stringResource(R.string.ocr_tab_text), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1; copied = false },
                            text = { Text(stringResource(R.string.ocr_tab_receipt), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2; copied = false },
                            text = { Text(stringResource(R.string.ocr_tab_table_csv), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                        Tab(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3; copied = false },
                            text = { Text(stringResource(R.string.ocr_tab_ai_translate), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 370.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (extractedText.isBlank()) {
                        Text(
                            text = "No text detected on this page, or no OCR language pack is enabled yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (cloudError != null && onRerunOfflineOcr != null) {
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = onRerunOfflineOcr,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Run Offline Tesseract OCR")
                            }
                        }
                        if (onOpenOcrLanguages != null) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    onDismiss()
                                    onOpenOcrLanguages()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Download OCR Languages")
                            }
                        }
                    } else if (selectedTab == 0) {
                        // Offline Read Aloud (TTS) Playback Bar
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            val tts = ttsHolder.value ?: return@FilledTonalButton
                                            if (isSpeaking) {
                                                tts.stop()
                                                isSpeaking = false
                                            } else {
                                                tts.setSpeechRate(speechRate)
                                                tts.speak(
                                                    extractedText.take(3900),
                                                    TextToSpeech.QUEUE_FLUSH,
                                                    null,
                                                    "proscan_ocr_tts"
                                                )
                                                isSpeaking = true
                                            }
                                        },
                                        enabled = isTtsReady,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.testTag("ocr_read_aloud_btn")
                                    ) {
                                        Icon(
                                            imageVector = if (isSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (isSpeaking) "Stop" else "Read Aloud", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        listOf(0.75f, 1.0f, 1.25f, 1.5f).forEach { rate ->
                                            FilterChip(
                                                selected = speechRate == rate,
                                                onClick = {
                                                    speechRate = rate
                                                    if (isSpeaking) {
                                                        val tts = ttsHolder.value
                                                        tts?.stop()
                                                        tts?.setSpeechRate(rate)
                                                        tts?.speak(
                                                            extractedText.take(3900),
                                                            TextToSpeech.QUEUE_FLUSH,
                                                            null,
                                                            "proscan_ocr_tts"
                                                        )
                                                    }
                                                },
                                                label = { Text("${rate}x", style = MaterialTheme.typography.labelSmall) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = extractedText,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    } else if (selectedTab == 1) {
                        // Structured Receipt / Invoice Parser
                        if (!receiptResult.hasAnyField) {
                            Text(
                                text = "No structured receipt totals or invoice numbers detected on this page.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            if (receiptResult.total != null) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.ReceiptLong,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "Total Amount",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Text(
                                            text = receiptResult.total,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                            }

                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    ReceiptMetaRow("Merchant", receiptResult.merchant)
                                    ReceiptMetaRow("Date", receiptResult.date)
                                    ReceiptMetaRow("Invoice #", receiptResult.invoiceNumber)
                                    ReceiptMetaRow("Subtotal", receiptResult.subtotal)
                                    ReceiptMetaRow("Tax / VAT", receiptResult.tax)
                                }
                            }

                            if (receiptResult.lineItems.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "Detected Items (${receiptResult.lineItems.size})",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(6.dp))
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        receiptResult.lineItems.forEachIndexed { idx, item ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = item.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = item.amount,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            if (idx < receiptResult.lineItems.lastIndex) {
                                                HorizontalDivider(thickness = 0.5.dp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (selectedTab == 2) {
                        // Table to CSV Extractor
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.TableChart,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "${tableResult.rows.size} rows × ${tableResult.columnCount} cols",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (onExportCsv != null && !tableResult.isEmpty) {
                                FilledTonalButton(
                                    onClick = {
                                        onExportCsv(
                                            tableResult.csv,
                                            "ProScan_Table_${System.currentTimeMillis()}.csv"
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("ocr_export_csv_btn")
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.ocr_export_csv), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (tableResult.isEmpty) {
                            Text(
                                text = "No tabular rows detected.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                Column(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                ) {
                                    tableResult.rows.take(30).forEachIndexed { rIdx, row ->
                                        Row(
                                            modifier = Modifier.background(
                                                if (rIdx == 0) {
                                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                                } else if (rIdx % 2 == 1) {
                                                    MaterialTheme.colorScheme.surface
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                                                }
                                            )
                                        ) {
                                            for (c in 0 until tableResult.columnCount) {
                                                Text(
                                                    text = row.getOrElse(c) { "" }.ifEmpty { "—" },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = if (rIdx == 0) FontWeight.Bold else FontWeight.Normal,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier
                                                        .width(106.dp)
                                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                                )
                                            }
                                        }
                                        if (rIdx < minOf(tableResult.rows.lastIndex, 29)) {
                                            HorizontalDivider(thickness = 0.5.dp)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Tab 3: Local Key Points Summary & Free ML Kit Multilingual Translation
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val summaryToShow = cloudAnalysis?.summary?.takeIf { it.isNotBlank() }
                                ?: remember(extractedText) {
                                    org.fairscan.app.cloud.FreeMlKitEngine.summarizeTextLocally(extractedText)
                                }
                            if (summaryToShow.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "Key Points Summary (Free On-Device)",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = summaryToShow,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }

                            Text(
                                text = "Translate Free With ML Kit (59+ Languages):",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                translationLanguages.forEach { lang ->
                                    FilterChip(
                                        selected = targetLanguage == lang,
                                        onClick = { targetLanguage = lang },
                                        label = { Text(lang, style = MaterialTheme.typography.labelSmall) },
                                    )
                                }
                            }

                            val targetPackCode = remember(targetLanguage) {
                                org.fairscan.app.cloud.FreeMlKitEngine.mapDisplayLanguageToCode(targetLanguage)
                            }
                            val isPackInstalled = targetPackCode in downloadedLangCodes
                            Text(
                                text = when {
                                    isPackInstalled -> "✓ $targetLanguage offline pack installed — works 100% offline & unlimited"
                                    isOnlineActive -> "Downloads $targetLanguage pack (~30 MB) once, then works forever offline"
                                    else -> "Requires a one-time ~30 MB download for $targetLanguage if not cached yet"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isPackInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (onTranslateText != null) {
                                Button(
                                    onClick = {
                                        translationError = null
                                        isTranslating = true
                                        scope.launch {
                                            try {
                                                translatedOutput = onTranslateText(extractedText, targetLanguage)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                translationError = e.localizedMessage ?: "Translation failed."
                                            } finally {
                                                isTranslating = false
                                            }
                                        }
                                    },
                                    enabled = !isTranslating,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ocr_translate_btn"),
                                ) {
                                    if (isTranslating) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Translating to $targetLanguage…")
                                    } else {
                                        Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(17.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Translate to $targetLanguage (Free)")
                                    }
                                }
                            }

                            translationError?.let { err ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = err,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(10.dp),
                                    )
                                }
                            }

                            if (translatedOutput.isNotBlank()) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "$targetLanguage Result",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            if (isTtsReady) {
                                                TextButton(
                                                    onClick = {
                                                        val tts = ttsHolder.value ?: return@TextButton
                                                        if (isSpeaking) {
                                                            tts.stop()
                                                            isSpeaking = false
                                                        } else {
                                                            tts.setSpeechRate(speechRate)
                                                            tts.speak(
                                                                translatedOutput.take(3900),
                                                                TextToSpeech.QUEUE_FLUSH,
                                                                null,
                                                                "proscan_ocr_translate_tts"
                                                            )
                                                            isSpeaking = true
                                                        }
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                ) {
                                                    Icon(
                                                        if (isSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(15.dp),
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(if (isSpeaking) "Stop" else "Read", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                        Text(
                                            text = translatedOutput,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                OutlinedButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(activeCopyPayload))
                        copied = true
                    },
                    enabled = activeCopyPayload.isNotBlank()
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (copied) "Copied!" else "Copy")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onShareText(activeCopyPayload) },
                    enabled = activeCopyPayload.isNotBlank()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                ttsHolder.value?.stop()
                onDismiss()
            }) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ReceiptMetaRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun parseCsvToTableResult(csv: String): CsvTableResult {
    val lines = csv.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return CsvTableResult(emptyList(), "", 0)
    val rows = lines.map { line ->
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    cells.add(cur.toString().trim())
                    cur.setLength(0)
                }
                else -> cur.append(ch)
            }
            i++
        }
        cells.add(cur.toString().trim())
        cells
    }
    val colCount = rows.maxOfOrNull { it.size } ?: 0
    return CsvTableResult(rows = rows, csv = csv, columnCount = colCount)
}
