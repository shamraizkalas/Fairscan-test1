package org.fairscan.app.camscanner

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.fairscan.app.domain.PdfCompression
import org.fairscan.app.library.SavedDocumentEntity
import org.fairscan.app.ui.uriForFile
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToolkitDialog(
    uiState: PdfToolkitUiState,
    savedDocuments: List<SavedDocumentEntity>,
    viewModel: PdfToolkitViewModel,
) {
    val context = LocalContext.current
    var pendingSaveFile by remember { mutableStateOf<File?>(null) }

    val singlePdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.handlePickedPdfUris(listOf(uri), uiState.activeTool)
        }
    }

    val multiPdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.handlePickedPdfUris(uris, PdfToolkitTool.MERGE)
        }
    }

    val savePdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        val src = pendingSaveFile
        pendingSaveFile = null
        if (destUri != null && src != null && src.exists()) {
            try {
                context.contentResolver.openOutputStream(destUri)?.use { out ->
                    src.inputStream().use { input -> input.copyTo(out) }
                }
                Toast.makeText(context, "PDF saved successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (uiState.isOpen) {
        Dialog(
            onDismissRequest = { viewModel.closeToolkit() },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            BackHandler {
                if (uiState.activeTool != PdfToolkitTool.HUB) {
                    viewModel.selectTool(PdfToolkitTool.HUB)
                } else {
                    viewModel.closeToolkit()
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
                color = MaterialTheme.colorScheme.background,
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(
                                        text = uiState.activeTool.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = "100% Offline PDF Toolkit",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(
                                    onClick = {
                                        if (uiState.activeTool != PdfToolkitTool.HUB) {
                                            viewModel.selectTool(PdfToolkitTool.HUB)
                                        } else {
                                            viewModel.closeToolkit()
                                        }
                                    }
                                ) {
                                    Icon(
                                        if (uiState.activeTool != PdfToolkitTool.HUB) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                                        contentDescription = "Back"
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            )
                        )
                    }
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 680.dp)
                                .padding(horizontal = 16.dp)
                        ) {
                            uiState.errorMessage?.let { err ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = err,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(onClick = { viewModel.clearError() }) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Dismiss error",
                                                tint = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }
                            }

                            when (uiState.activeTool) {
                                PdfToolkitTool.HUB -> ToolkitHubGrid(
                                    onSelectTool = { tool ->
                                        if (tool == PdfToolkitTool.IMPORT) {
                                            viewModel.selectTool(PdfToolkitTool.IMPORT)
                                            singlePdfPicker.launch(arrayOf("application/pdf"))
                                        } else {
                                            viewModel.selectTool(tool)
                                        }
                                    }
                                )

                                PdfToolkitTool.IMPORT -> ToolkitHubGrid(
                                    onSelectTool = { tool ->
                                        if (tool == PdfToolkitTool.IMPORT) {
                                            singlePdfPicker.launch(arrayOf("application/pdf"))
                                        } else {
                                            viewModel.selectTool(tool)
                                        }
                                    }
                                )

                                PdfToolkitTool.MERGE -> MergeToolBody(
                                    uiState = uiState,
                                    savedDocuments = savedDocuments,
                                    onPickExternalPdfs = { multiPdfPicker.launch(arrayOf("application/pdf")) },
                                    onAddLibraryDoc = { doc -> viewModel.addLibraryDocumentToTool(doc, PdfToolkitTool.MERGE) },
                                    onMoveSource = viewModel::moveMergeSource,
                                    onRemoveSource = viewModel::removeMergeSource,
                                    onToggleSaveToLibrary = viewModel::setSaveMergedToLibrary,
                                    onExecuteMerge = viewModel::executeMerge,
                                    onOpenInStudio = viewModel::openResultInDocumentStudio,
                                    onSaveToPhone = { file ->
                                        pendingSaveFile = file
                                        savePdfLauncher.launch(file.name)
                                    },
                                    onShare = { files -> sharePdfFiles(context, files) },
                                    onReset = { viewModel.selectTool(PdfToolkitTool.MERGE) },
                                )

                                PdfToolkitTool.SPLIT, PdfToolkitTool.ORGANIZE -> SplitOrOrganizeToolBody(
                                    uiState = uiState,
                                    savedDocuments = savedDocuments,
                                    onPickExternalPdf = { singlePdfPicker.launch(arrayOf("application/pdf")) },
                                    onPickLibraryDoc = { doc -> viewModel.addLibraryDocumentToTool(doc, uiState.activeTool) },
                                    onTogglePage = viewModel::togglePageSelected,
                                    onSelectAll = viewModel::selectAllPages,
                                    onRangeChange = viewModel::updateRangeInput,
                                    onToggleSeparateFiles = viewModel::setSplitSeparateFiles,
                                    onMovePage = viewModel::movePageItem,
                                    onRotatePage = viewModel::rotatePageItem,
                                    onRemovePage = viewModel::removePageItem,
                                    onExecute = { saveLib -> viewModel.executeSplitOrOrganize(saveLib) },
                                    onOpenInStudio = viewModel::openResultInDocumentStudio,
                                    onSaveToPhone = { file ->
                                        pendingSaveFile = file
                                        savePdfLauncher.launch(file.name)
                                    },
                                    onShare = { files -> sharePdfFiles(context, files) },
                                    onReset = { viewModel.selectTool(uiState.activeTool) },
                                )

                                PdfToolkitTool.COMPRESS -> CompressToolBody(
                                    uiState = uiState,
                                    savedDocuments = savedDocuments,
                                    onPickExternalPdf = { singlePdfPicker.launch(arrayOf("application/pdf")) },
                                    onPickLibraryDoc = { doc -> viewModel.addLibraryDocumentToTool(doc, PdfToolkitTool.COMPRESS) },
                                    onSelectPreset = viewModel::setCompressionPreset,
                                    onExecuteCompress = viewModel::executeCompress,
                                    onSaveToPhone = { file ->
                                        pendingSaveFile = file
                                        savePdfLauncher.launch(file.name)
                                    },
                                    onShare = { files -> sharePdfFiles(context, files) },
                                    onReset = { viewModel.selectTool(PdfToolkitTool.COMPRESS) },
                                )

                                PdfToolkitTool.UNLOCK -> UnlockToolBody(
                                    uiState = uiState,
                                    onPickProtectedPdf = { singlePdfPicker.launch(arrayOf("application/pdf")) },
                                    onOpenInStudio = viewModel::openResultInDocumentStudio,
                                    onSaveToPhone = { file ->
                                        pendingSaveFile = file
                                        savePdfLauncher.launch(file.name)
                                    },
                                    onShare = { files -> sharePdfFiles(context, files) },
                                    onReset = { viewModel.selectTool(PdfToolkitTool.UNLOCK) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Progress Modal (works for both Quick Import PDF on HomeHub and inside Toolkit)
    if (uiState.isBusy) {
        PdfBusyProgressDialog(
            message = uiState.progressText.ifBlank { "Processing PDF…" },
            progress = uiState.progressFraction,
        )
    }

    // Password Prompt Dialog (works everywhere)
    uiState.pendingPassword?.let { pending ->
        PdfPasswordPromptDialog(
            title = pending.title,
            wrongPassword = pending.wrongPassword,
            onDismiss = viewModel::dismissPasswordPrompt,
            onSubmit = viewModel::submitPdfPassword,
        )
    }
}

@Composable
private fun ToolkitHubGrid(
    onSelectTool: (PdfToolkitTool) -> Unit,
) {
    data class ToolCardSpec(
        val tool: PdfToolkitTool,
        val subtitle: String,
        val icon: ImageVector,
        val accent: Color,
        val tag: String,
    )
    val tools = listOf(
        ToolCardSpec(
            PdfToolkitTool.IMPORT,
            "Open any phone PDF in ProScan Studio to sign, stamp, annotate, filter, or run OCR",
            Icons.Default.UploadFile,
            Color(0xFF00796B),
            "pdf_tool_import"
        ),
        ToolCardSpec(
            PdfToolkitTool.MERGE,
            "Combine multiple phone PDFs and saved documents in custom order",
            Icons.AutoMirrored.Filled.MergeType,
            Color(0xFF0277BD),
            "pdf_tool_merge"
        ),
        ToolCardSpec(
            PdfToolkitTool.SPLIT,
            "Pick pages visually or by range (1-3, 5) to extract or split PDFs",
            Icons.AutoMirrored.Filled.CallSplit,
            Color(0xFF6A1B9A),
            "pdf_tool_split"
        ),
        ToolCardSpec(
            PdfToolkitTool.ORGANIZE,
            "Reorder, rotate 90°, or delete pages inside any PDF or document",
            Icons.Default.Reorder,
            Color(0xFFEF6C00),
            "pdf_tool_organize"
        ),
        ToolCardSpec(
            PdfToolkitTool.COMPRESS,
            "Shrink heavy PDF file sizes with before & after MB comparison",
            Icons.Default.Compress,
            Color(0xFF2E7D32),
            "pdf_tool_compress"
        ),
        ToolCardSpec(
            PdfToolkitTool.UNLOCK,
            "Remove password encryption permanently from a protected PDF",
            Icons.Default.LockOpen,
            Color(0xFFC62828),
            "pdf_tool_unlock"
        ),
    )

    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(tools) { item ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectTool(item.tool) }
                    .testTag(item.tag),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(item.accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = item.tool.title,
                            tint = item.accent,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.tool.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePickerSection(
    browseLabel: String,
    savedDocuments: List<SavedDocumentEntity>,
    onPickExternal: () -> Unit,
    onSelectLibraryDoc: (SavedDocumentEntity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onPickExternal,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .testTag("pdf_browse_phone_button"),
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(browseLabel)
        }

        if (savedDocuments.isNotEmpty()) {
            Text(
                text = "Or pick from ProScan Library:",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(savedDocuments, key = { it.id }) { doc ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectLibraryDoc(doc) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = doc.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${doc.pageCount} pages",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = "Select",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MergeToolBody(
    uiState: PdfToolkitUiState,
    savedDocuments: List<SavedDocumentEntity>,
    onPickExternalPdfs: () -> Unit,
    onAddLibraryDoc: (SavedDocumentEntity) -> Unit,
    onMoveSource: (Int, Int) -> Unit,
    onRemoveSource: (String) -> Unit,
    onToggleSaveToLibrary: (Boolean) -> Unit,
    onExecuteMerge: () -> Unit,
    onOpenInStudio: (PdfToolkitResult) -> Unit,
    onSaveToPhone: (File) -> Unit,
    onShare: (List<File>) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    val result = uiState.result
    if (result != null) {
        ResultSummaryCard(
            result = result,
            canOpenInStudio = true,
            onOpenInStudio = { onOpenInStudio(result) },
            onSaveToPhone = { result.files.firstOrNull()?.let(onSaveToPhone) },
            onShare = { onShare(result.files) },
            onRunAnother = onReset,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SourcePickerSection(
            browseLabel = "Add Phone PDF(s)",
            savedDocuments = savedDocuments,
            onPickExternal = onPickExternalPdfs,
            onSelectLibraryDoc = onAddLibraryDoc,
        )

        HorizontalDivider()

        val totalPages = uiState.mergeSources.sumOf { it.pageCount }
        Text(
            text = "Merge Queue (${uiState.mergeSources.size} files • $totalPages pages)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )

        if (uiState.mergeSources.isEmpty()) {
            Text(
                text = "Select 2 or more PDFs or saved documents above to combine them in order.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            uiState.mergeSources.forEachIndexed { index, src ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = src.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${src.pageCount} pages • ${Formatter.formatShortFileSize(context, src.sizeBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { onMoveSource(index, -1) },
                            enabled = index > 0,
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
                        }
                        IconButton(
                            onClick = { onMoveSource(index, +1) },
                            enabled = index < uiState.mergeSources.lastIndex,
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Move down")
                        }
                        IconButton(onClick = { onRemoveSource(src.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove")
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = uiState.saveMergedToLibrary,
                    onCheckedChange = onToggleSaveToLibrary,
                )
                Text(
                    text = "Also save merged document to ProScan Library",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = onExecuteMerge,
                enabled = uiState.mergeSources.size >= 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .testTag("pdf_execute_button"),
            ) {
                Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Merge ${uiState.mergeSources.size} PDFs ($totalPages Pages)")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SplitOrOrganizeToolBody(
    uiState: PdfToolkitUiState,
    savedDocuments: List<SavedDocumentEntity>,
    onPickExternalPdf: () -> Unit,
    onPickLibraryDoc: (SavedDocumentEntity) -> Unit,
    onTogglePage: (Int) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onRangeChange: (String) -> Unit,
    onToggleSeparateFiles: (Boolean) -> Unit,
    onMovePage: (Int, Int) -> Unit,
    onRotatePage: (Int, Int) -> Unit,
    onRemovePage: (Int) -> Unit,
    onExecute: (Boolean) -> Unit,
    onOpenInStudio: (PdfToolkitResult) -> Unit,
    onSaveToPhone: (File) -> Unit,
    onShare: (List<File>) -> Unit,
    onReset: () -> Unit,
) {
    val isOrganize = uiState.activeTool == PdfToolkitTool.ORGANIZE
    val result = uiState.result
    if (result != null) {
        ResultSummaryCard(
            result = result,
            canOpenInStudio = result.files.size == 1,
            onOpenInStudio = { onOpenInStudio(result) },
            onSaveToPhone = { result.files.firstOrNull()?.let(onSaveToPhone) },
            onShare = { onShare(result.files) },
            onRunAnother = onReset,
        )
        return
    }

    if (uiState.workingSource == null) {
        SourcePickerSection(
            browseLabel = "Browse Phone PDF",
            savedDocuments = savedDocuments,
            onPickExternal = onPickExternalPdf,
            onSelectLibraryDoc = onPickLibraryDoc,
        )
        return
    }

    var saveToLibrary by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${uiState.workingSource.title} (${uiState.pages.size} pages)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReset) {
                Text("Change PDF")
            }
        }

        if (!isOrganize) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = uiState.rangeInput,
                    onValueChange = onRangeChange,
                    label = { Text("Page range (e.g. 1-3, 5)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = { onSelectAll(true) }) { Text("All") }
                OutlinedButton(onClick = { onSelectAll(false) }) { Text("None") }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = "Split into separate 1-page PDFs",
                    style = MaterialTheme.typography.bodySmall,
                )
                Switch(
                    checked = uiState.splitSeparateFiles,
                    onCheckedChange = onToggleSeparateFiles,
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(uiState.pages) { posIndex, page ->
                val selected = isOrganize || page.isSelected
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        width = if (selected) 2.5.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!isOrganize) onTogglePage(page.zeroBasedIndex)
                        },
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.74f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (page.thumbnail != null) {
                                Image(
                                    bitmap = page.thumbnail.asImageBitmap(),
                                    contentDescription = "Page ${page.zeroBasedIndex + 1}",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .rotate(page.rotationDegrees.toFloat()),
                                )
                            } else {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.Black.copy(alpha = 0.68f),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp),
                            ) {
                                Text(
                                    text = "P${page.zeroBasedIndex + 1}",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                            if (!isOrganize && page.isSelected) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(20.dp),
                                )
                            }
                        }
                        if (isOrganize) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                IconButton(
                                    onClick = { onMovePage(posIndex, -1) },
                                    enabled = posIndex > 0,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(Icons.Default.ArrowUpward, contentDescription = "Move earlier", modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = { onRotatePage(posIndex, 90) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(Icons.Default.RotateRight, contentDescription = "Rotate 90°", modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = { onMovePage(posIndex, +1) },
                                    enabled = posIndex < uiState.pages.lastIndex,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(Icons.Default.ArrowDownward, contentDescription = "Move later", modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = { onRemovePage(posIndex) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete page", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isOrganize || !uiState.splitSeparateFiles) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(checked = saveToLibrary, onCheckedChange = { saveToLibrary = it })
                Text("Also save copy to ProScan Library", style = MaterialTheme.typography.bodySmall)
            }
        }

        val activeCount = if (isOrganize) uiState.pages.size else uiState.pages.count { it.isSelected }
        Button(
            onClick = { onExecute(saveToLibrary) },
            enabled = activeCount > 0,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .heightIn(min = 50.dp)
                .testTag("pdf_execute_button"),
        ) {
            Text(
                when {
                    isOrganize -> "Save Organized PDF ($activeCount Pages)"
                    uiState.splitSeparateFiles -> "Split into $activeCount Separate PDFs"
                    else -> "Extract $activeCount Selected Pages"
                }
            )
        }
    }
}

@Composable
private fun CompressToolBody(
    uiState: PdfToolkitUiState,
    savedDocuments: List<SavedDocumentEntity>,
    onPickExternalPdf: () -> Unit,
    onPickLibraryDoc: (SavedDocumentEntity) -> Unit,
    onSelectPreset: (PdfCompression) -> Unit,
    onExecuteCompress: () -> Unit,
    onSaveToPhone: (File) -> Unit,
    onShare: (List<File>) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    val result = uiState.result
    if (result != null) {
        ResultSummaryCard(
            result = result,
            canOpenInStudio = false,
            onOpenInStudio = {},
            onSaveToPhone = { result.files.firstOrNull()?.let(onSaveToPhone) },
            onShare = { onShare(result.files) },
            onRunAnother = onReset,
        )
        return
    }

    val source = uiState.workingSource
    if (source == null) {
        SourcePickerSection(
            browseLabel = "Browse Phone PDF to Compress",
            savedDocuments = savedDocuments,
            onPickExternal = onPickExternalPdf,
            onSelectLibraryDoc = onPickLibraryDoc,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(source.title, fontWeight = FontWeight.Bold)
                    Text(
                        "${source.pageCount} pages • Current size: ${Formatter.formatShortFileSize(context, source.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onReset) { Text("Change") }
            }
        }

        Text("Compression Level", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                PdfCompression.SMALLEST to "Maximum Shrink (~1200px, lightest email/WhatsApp size)",
                PdfCompression.COMPACT to "Compact (~1700px, crisp screen reading + major savings)",
                PdfCompression.BALANCED to "Balanced (~2400px, great print clarity + moderate shrink)",
                PdfCompression.HIGH to "High Clarity (~3600px, clean archive quality)",
            ).forEach { (preset, desc) ->
                val selected = uiState.compressionPreset == preset
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectPreset(preset) },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(preset.label, fontWeight = FontWeight.Bold)
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Button(
            onClick = onExecuteCompress,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .testTag("pdf_execute_button"),
        ) {
            Icon(Icons.Default.Compress, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Compress PDF Now")
        }
    }
}

@Composable
private fun UnlockToolBody(
    uiState: PdfToolkitUiState,
    onPickProtectedPdf: () -> Unit,
    onOpenInStudio: (PdfToolkitResult) -> Unit,
    onSaveToPhone: (File) -> Unit,
    onShare: (List<File>) -> Unit,
    onReset: () -> Unit,
) {
    val result = uiState.result
    if (result != null) {
        ResultSummaryCard(
            result = result,
            canOpenInStudio = true,
            onOpenInStudio = { onOpenInStudio(result) },
            onSaveToPhone = { result.files.firstOrNull()?.let(onSaveToPhone) },
            onShare = { onShare(result.files) },
            onRunAnother = onReset,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.LockOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = "Remove PDF Password Permanently",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Choose a password-locked PDF, verify its current password once offline, and save an unlocked copy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onPickProtectedPdf,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .testTag("pdf_browse_phone_button"),
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Choose Protected PDF")
        }
    }
}

@Composable
private fun ResultSummaryCard(
    result: PdfToolkitResult,
    canOpenInStudio: Boolean,
    onOpenInStudio: () -> Unit,
    onSaveToPhone: () -> Unit,
    onShare: () -> Unit,
    onRunAnother: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (result.isUnlockResult) "PDF Unlocked!" else "PDF Ready!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${result.pageCount} pages • ${Formatter.formatShortFileSize(context, result.outputBytes)}" +
                        if (result.files.size > 1) " (${result.files.size} separate PDFs)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                result.originalBytes?.takeIf { it > 0 }?.let { orig ->
                    val diff = orig - result.outputBytes
                    val pct = ((diff.toDouble() / orig.toDouble()) * 100.0).toInt()
                    Text(
                        text = if (pct > 0) {
                            "Reduced from ${Formatter.formatShortFileSize(context, orig)} → ${Formatter.formatShortFileSize(context, result.outputBytes)} (-$pct%)"
                        } else {
                            "Original size: ${Formatter.formatShortFileSize(context, orig)}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                result.savedToLibraryTitle?.let { saved ->
                    Text(
                        text = "Saved to ProScan Library as \"$saved\"",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (canOpenInStudio) {
            Button(
                onClick = onOpenInStudio,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open in Editor (Sign, Stamp, OCR)")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (result.files.size == 1) {
                FilledTonalButton(
                    onClick = onSaveToPhone,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("pdf_save_button"),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Save PDF")
                }
            }
            Button(
                onClick = onShare,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("pdf_share_button"),
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (result.files.size > 1) "Share All PDFs" else "Share PDF")
            }
        }

        OutlinedButton(
            onClick = onRunAnother,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Done / Process Another PDF")
        }
    }
}

@Composable
fun PdfBusyProgressDialog(
    message: String,
    progress: Float?,
) {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (progress == null) {
                    CircularProgressIndicator()
                } else {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
fun PdfPasswordPromptDialog(
    title: String,
    wrongPassword: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter PDF Password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "\"$title\" is encrypted. Enter its password to unlock it offline.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("PDF Password") },
                    singleLine = true,
                    isError = wrongPassword,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle visibility"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (wrongPassword) {
                    Text(
                        text = "Incorrect password. Please try again.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(password) },
                enabled = password.isNotEmpty(),
            ) {
                Text("Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun sharePdfFiles(context: Context, files: List<File>) {
    if (files.isEmpty()) return
    val uris = files.map { uriForFile(context, it) }
    val intent = Intent().apply {
        action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
        type = "application/pdf"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size == 1) {
            putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    val chooser = Intent.createChooser(intent, "Share PDF")
    val resolveInfos = context.packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
    for (info in resolveInfos) {
        val pkg = info.activityInfo.packageName
        for (uri in uris) {
            context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    context.startActivity(chooser)
}
