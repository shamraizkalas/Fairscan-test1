package org.fairscan.app.camscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Approval
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.fairscan.app.library.SavedAssetType
import org.fairscan.app.library.SavedDocumentEntity
import org.fairscan.app.library.SavedSignatureEntity
import org.fairscan.app.platform.loadBitmapFromUri
import kotlin.math.max

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignatureStampVaultDialog(
    savedAssets: List<SavedSignatureEntity>,
    initialTab: SavedAssetType = SavedAssetType.SIGNATURE,
    isApplyingToOpenDocument: Boolean,
    savedDocuments: List<SavedDocumentEntity> = emptyList(),
    activeSessionPageCount: Int = 0,
    onSaveAsset: (bitmap: Bitmap, label: String, assetType: SavedAssetType) -> Unit,
    onDeleteAsset: (SavedSignatureEntity) -> Unit,
    onApplyOnOpenDocument: ((bitmap: Bitmap, label: String, assetType: SavedAssetType) -> Unit)? = null,
    onApplyOnLibraryDocument: ((docId: String?, bitmap: Bitmap, label: String, assetType: SavedAssetType) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember(initialTab) { mutableStateOf(initialTab) }
    var showScannerDialog by remember { mutableStateOf(false) }
    var scannerInitialBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showDrawDialog by remember { mutableStateOf(false) }

    // For HomeHub mode: selecting which saved document (or active session) to apply onto
    var pendingPickDocAsset by remember { mutableStateOf<Triple<Bitmap, String, SavedAssetType>?>(null) }

    val signatures = remember(savedAssets) {
        savedAssets.filter { it.assetType != SavedAssetType.STAMP.name }
    }
    val stamps = remember(savedAssets) {
        savedAssets.filter { it.assetType == SavedAssetType.STAMP.name }
    }
    val visibleList = if (selectedTab == SavedAssetType.SIGNATURE) signatures else stamps

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val bmp = loadBitmapFromUri(context.contentResolver, uri, maxPixels = 2_400_000)
                    scannerInitialBitmap = bmp
                    showScannerDialog = true
                } catch (_: Exception) {
                    Toast.makeText(context, "Could not open image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 680.dp)
                .clip(RoundedCornerShape(20.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Signatures & Stamps",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isApplyingToOpenDocument) {
                                "Tap any saved item to place on document"
                            } else {
                                "Scan, save & apply on any document"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Signatures vs Stamps tabs
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedTab == SavedAssetType.SIGNATURE,
                        onClick = { selectedTab = SavedAssetType.SIGNATURE },
                        leadingIcon = { Icon(Icons.Default.Draw, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Signatures (${signatures.size})") },
                        modifier = Modifier.testTag("vault_tab_signatures")
                    )
                    FilterChip(
                        selected = selectedTab == SavedAssetType.STAMP,
                        onClick = { selectedTab = SavedAssetType.STAMP },
                        leadingIcon = { Icon(Icons.Default.Approval, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Stamps (${stamps.size})") },
                        modifier = Modifier.testTag("vault_tab_stamps")
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Creation action buttons
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            scannerInitialBitmap = null
                            showScannerDialog = true
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("vault_btn_scan_camera")
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Scan Camera", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("vault_btn_import_photo")
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("From Photo", style = MaterialTheme.typography.labelSmall)
                    }

                    if (selectedTab == SavedAssetType.SIGNATURE) {
                        OutlinedButton(
                            onClick = { showDrawDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("vault_btn_draw_signature")
                        ) {
                            Icon(Icons.Default.Draw, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Draw", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (visibleList.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = if (selectedTab == SavedAssetType.STAMP) Icons.Default.Approval else Icons.Default.Draw,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(34.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = if (selectedTab == SavedAssetType.STAMP) {
                                        "No scanned stamps saved yet"
                                    } else {
                                        "No saved signatures yet"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Use Scan Camera or From Photo above to extract a clean transparent ${if (selectedTab == SavedAssetType.STAMP) "stamp" else "signature"} from paper.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        visibleList.forEach { entity ->
                            val bmp = remember(entity.imagePath) {
                                BitmapFactory.decodeFile(entity.imagePath)
                            }
                            if (bmp != null) {
                                SavedAssetRowCard(
                                    entity = entity,
                                    bitmap = bmp,
                                    isApplyInDocMode = isApplyingToOpenDocument,
                                    onApply = {
                                        val type = if (entity.assetType == SavedAssetType.STAMP.name) {
                                            SavedAssetType.STAMP
                                        } else {
                                            SavedAssetType.SIGNATURE
                                        }
                                        if (isApplyingToOpenDocument && onApplyOnOpenDocument != null) {
                                            onApplyOnOpenDocument(bmp, entity.label, type)
                                            onDismiss()
                                        } else {
                                            pendingPickDocAsset = Triple(bmp, entity.label, type)
                                        }
                                    },
                                    onDelete = { onDeleteAsset(entity) }
                                )
                            }
                        }
                    }

                    // Quick built-in office stamps section on Stamps tab
                    if (selectedTab == SavedAssetType.STAMP) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Quick Office Stamps:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DEFAULT_STAMPS.forEach { (stampText, colorArgb) ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.8.dp, Color(colorArgb)),
                                    modifier = Modifier.clickable {
                                        val stampBmp = renderQuickOfficeStampBitmap(stampText, colorArgb)
                                        if (isApplyingToOpenDocument && onApplyOnOpenDocument != null) {
                                            onApplyOnOpenDocument(stampBmp, stampText, SavedAssetType.STAMP)
                                            onDismiss()
                                        } else {
                                            pendingPickDocAsset = Triple(stampBmp, stampText, SavedAssetType.STAMP)
                                        }
                                    }
                                ) {
                                    Text(
                                        text = stampText,
                                        color = Color(colorArgb),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showScannerDialog) {
        ScanSignatureStampDialog(
            initialAssetType = selectedTab,
            initialSourceBitmap = scannerInitialBitmap,
            showApplyButton = isApplyingToOpenDocument || savedDocuments.isNotEmpty() || activeSessionPageCount > 0,
            onDismiss = {
                showScannerDialog = false
                scannerInitialBitmap = null
            },
            onSaveAsset = { bmp, label, type, applyNow ->
                onSaveAsset(bmp, label, type)
                selectedTab = type
                if (applyNow) {
                    if (isApplyingToOpenDocument && onApplyOnOpenDocument != null) {
                        onApplyOnOpenDocument(bmp, label, type)
                        onDismiss()
                    } else {
                        pendingPickDocAsset = Triple(bmp, label, type)
                    }
                } else {
                    Toast.makeText(context, "Saved to library", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showDrawDialog) {
        SignatureDialog(
            savedSignatures = emptyList(),
            onSaveSignatureToLibrary = null,
            onDeleteSavedSignature = null,
            onDismiss = { showDrawDialog = false },
            onApplySignature = { sigBmp, _, _, _ ->
                onSaveAsset(sigBmp, "Drawn Signature", SavedAssetType.SIGNATURE)
                if (isApplyingToOpenDocument && onApplyOnOpenDocument != null) {
                    onApplyOnOpenDocument(sigBmp, "Drawn Signature", SavedAssetType.SIGNATURE)
                    onDismiss()
                } else if (savedDocuments.isNotEmpty() || activeSessionPageCount > 0) {
                    pendingPickDocAsset = Triple(sigBmp, "Drawn Signature", SavedAssetType.SIGNATURE)
                }
            }
        )
    }

    pendingPickDocAsset?.let { (bmp, label, type) ->
        PickTargetDocumentDialog(
            assetLabel = label,
            savedDocuments = savedDocuments,
            activeSessionPageCount = activeSessionPageCount,
            onSelectActiveSession = {
                pendingPickDocAsset = null
                onApplyOnLibraryDocument?.invoke(null, bmp, label, type)
                onDismiss()
            },
            onSelectSavedDocument = { docId ->
                pendingPickDocAsset = null
                onApplyOnLibraryDocument?.invoke(docId, bmp, label, type)
                onDismiss()
            },
            onDismiss = { pendingPickDocAsset = null }
        )
    }
}

@Composable
private fun SavedAssetRowCard(
    entity: SavedSignatureEntity,
    bitmap: Bitmap,
    isApplyInDocMode: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onApply)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(86.dp, 54.dp)
                    .clip(RoundedCornerShape(10.dp))
            ) {
                CheckerboardBackground(modifier = Modifier.fillMaxSize())
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = entity.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entity.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (entity.assetType == SavedAssetType.STAMP.name) "Stamp" else "Signature",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Button(
                onClick = onApply,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isApplyInDocMode) "Place" else "Apply",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun PickTargetDocumentDialog(
    assetLabel: String,
    savedDocuments: List<SavedDocumentEntity>,
    activeSessionPageCount: Int,
    onSelectActiveSession: () -> Unit,
    onSelectSavedDocument: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply \"$assetLabel\" to Document") },
        text = {
            if (savedDocuments.isEmpty() && activeSessionPageCount <= 0) {
                Text(
                    text = "Scan or import a document first, then apply this saved signature or stamp from the Sign & Mark studio tab.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (activeSessionPageCount > 0) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onSelectActiveSession)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Description, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text("Current Active Scan", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                        Text("$activeSessionPageCount page(s)", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    items(savedDocuments, key = { it.id }) { doc ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSavedDocument(doc.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(doc.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${doc.pageCount} page(s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
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

fun renderQuickOfficeStampBitmap(label: String, colorArgb: Int): Bitmap {
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorArgb
        textSize = 78f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        alpha = 235
    }
    val bounds = Rect()
    textPaint.getTextBounds(label, 0, label.length, bounds)

    val padX = 48
    val padY = 30
    val w = max(240, bounds.width() + padX * 2)
    val h = max(110, bounds.height() + padY * 2)

    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorArgb
        style = Paint.Style.STROKE
        strokeWidth = 10f
        alpha = 235
    }
    val rect = RectF(10f, 10f, (w - 10).toFloat(), (h - 10).toFloat())
    canvas.drawRoundRect(rect, 22f, 22f, strokePaint)
    canvas.drawText(label, (w - bounds.width()) / 2f - bounds.left, (h + bounds.height()) / 2f - bounds.bottom, textPaint)
    return bmp
}
