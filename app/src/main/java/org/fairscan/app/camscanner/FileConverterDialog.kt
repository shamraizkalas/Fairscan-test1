package org.fairscan.app.camscanner

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.fairscan.app.cloud.HybridConnectionState
import org.fairscan.app.cloud.HybridModeStatusPill
import org.fairscan.app.cloud.HybridModeSwitcherDialog
import org.fairscan.app.domain.PdfPageSize
import org.fairscan.app.library.SavedDocumentEntity
import org.fairscan.app.ui.uriForFile
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileConverterDialog(
    uiState: ConverterUiState,
    savedDocuments: List<SavedDocumentEntity>,
    viewModel: FileConverterViewModel,
) {
    if (!uiState.isOpen) return

    val context = LocalContext.current
    var pendingSaveSource by remember { mutableStateOf<File?>(null) }
    var showHybridDialog by remember { mutableStateOf(false) }
    val hybridState = viewModel.hybridState?.collectAsStateWithLifecycle()?.value ?: HybridConnectionState()

    val singleFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.addPickedUris(listOf(uri))
        }
    }

    val multiFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addPickedUris(uris)
        }
    }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { destUri ->
        val src = pendingSaveSource
        pendingSaveSource = null
        if (destUri != null && src != null && src.exists()) {
            try {
                context.contentResolver.openOutputStream(destUri)?.use { out ->
                    src.inputStream().use { input -> input.copyTo(out) }
                }
                Toast.makeText(context, "Saved \"${src.name}\"", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!uiState.isBusy) viewModel.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BackHandler(enabled = !uiState.isBusy) {
            if (uiState.activeTool != null) {
                viewModel.selectTool(null)
            } else {
                viewModel.close()
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
                                    text = uiState.activeTool?.title ?: "File Converter Studio",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = uiState.activeTool?.shortBadge?.let {
                                        if (hybridState.isOnlineActive) "$it • Free ML Kit + Drive" else "$it • Unlimited On-Device"
                                    } ?: "10 Converters • Free Smart Layout",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    if (uiState.activeTool != null) {
                                        viewModel.selectTool(null)
                                    } else {
                                        viewModel.close()
                                    }
                                }
                            ) {
                                Icon(
                                    if (uiState.activeTool != null) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                                    contentDescription = if (uiState.activeTool != null) "Back to converter hub" else "Close converter",
                                )
                            }
                        },
                        actions = {
                            if (viewModel.hybridState != null) {
                                HybridModeStatusPill(
                                    state = hybridState,
                                    onClick = { showHybridDialog = true },
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                    )
                },
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
                            .widthIn(max = 720.dp)
                            .padding(horizontal = 16.dp),
                    ) {
                        uiState.errorMessage?.let { err ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 10.dp),
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
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                }
                            }
                        }

                        val activeTool = uiState.activeTool
                        if (activeTool == null) {
                            ConverterHubGrid(
                                selectedCategory = uiState.selectedCategory,
                                onSelectCategory = viewModel::selectCategory,
                                onSelectTool = { viewModel.selectTool(it) },
                            )
                        } else {
                            ConverterToolWorkspace(
                                tool = activeTool,
                                uiState = uiState,
                                savedDocuments = savedDocuments,
                                onPickPhoneFiles = {
                                    if (activeTool.allowMultipleInputs) {
                                        multiFilePicker.launch(activeTool.mimeTypes)
                                    } else {
                                        singleFilePicker.launch(activeTool.mimeTypes)
                                    }
                                },
                                onAddLibraryDoc = viewModel::addLibraryDocument,
                                onRemoveInput = viewModel::removeInput,
                                onMoveInput = viewModel::moveInput,
                                onUpdateOptions = viewModel::updateOptions,
                                onExecute = viewModel::executeConversion,
                                onSaveFile = { file ->
                                    pendingSaveSource = file
                                    saveDocumentLauncher.launch(file.name)
                                },
                                onShareFiles = { files, mimeType ->
                                    shareConvertedFiles(context, files, mimeType)
                                },
                                onOpenInStudio = viewModel::openResultInDocumentStudio,
                                onResetResult = viewModel::clearResult,
                            )
                        }
                    }

                    if (uiState.isBusy) {
                        PdfBusyProgressDialog(
                            message = uiState.progressText.ifBlank { "Converting…" },
                            progress = uiState.progressFraction,
                        )
                    }
                }
            }
        }
    }

    if (showHybridDialog && viewModel.hybridState != null) {
        HybridModeSwitcherDialog(
            state = hybridState,
            onSelectPreference = viewModel::setHybridPreference,
            onToggleAiDocStructure = viewModel::setAiStructuredConversion,
            onToggleMlKitSegmentation = viewModel::setUseMlKitSegmentation,
            onRefreshNetwork = viewModel::refreshNetwork,
            onDismiss = { showHybridDialog = false },
        )
    }
}

@Composable
private fun ConverterHubGrid(
    selectedCategory: ConverterCategory,
    onSelectCategory: (ConverterCategory) -> Unit,
    onSelectTool: (ConverterTool) -> Unit,
) {
    val tools = remember(selectedCategory) {
        if (selectedCategory == ConverterCategory.ALL) {
            ConverterTool.entries
        } else {
            ConverterTool.entries.filter { it.category == selectedCategory }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Hero Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary,
                        )
                    )
                )
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Transform,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Universal Offline Converter",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = "PDF ↔ Word DOCX/DOC, Images ↔ PDF, OCR Text, HTML, CSV, JSON & Code — on-device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.92f),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Category Filter Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConverterCategory.entries.forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { onSelectCategory(cat) },
                    label = { Text(cat.label) },
                    modifier = Modifier.testTag("conv_cat_${cat.name.lowercase()}"),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(tools, key = { it.name }) { tool ->
                val accent = Color(tool.accentHex)
                Card(
                    onClick = { onSelectTool(tool) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("conv_tool_${tool.name.lowercase()}"),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(accent.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = toolIcon(tool),
                                    contentDescription = tool.title,
                                    tint = accent,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = accent.copy(alpha = 0.12f),
                            ) {
                                Text(
                                    text = tool.shortBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = accent,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                        Text(
                            text = tool.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = tool.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConverterToolWorkspace(
    tool: ConverterTool,
    uiState: ConverterUiState,
    savedDocuments: List<SavedDocumentEntity>,
    onPickPhoneFiles: () -> Unit,
    onAddLibraryDoc: (SavedDocumentEntity) -> Unit,
    onRemoveInput: (String) -> Unit,
    onMoveInput: (Int, Int) -> Unit,
    onUpdateOptions: ((ConverterOptions) -> ConverterOptions) -> Unit,
    onExecute: () -> Unit,
    onSaveFile: (File) -> Unit,
    onShareFiles: (List<File>, String) -> Unit,
    onOpenInStudio: (ConverterOutputSummary) -> Unit,
    onResetResult: () -> Unit,
) {
    val context = LocalContext.current
    val result = uiState.result
    if (result != null) {
        ConverterResultSection(
            result = result,
            onSaveFile = onSaveFile,
            onShareFiles = onShareFiles,
            onOpenInStudio = { onOpenInStudio(result) },
            onConvertAnother = onResetResult,
        )
        return
    }

    val accent = Color(tool.accentHex)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Source Selection Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (tool.allowMultipleInputs) "1. Select Input Files" else "1. Select Input File",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = tool.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Button(
                    onClick = onPickPhoneFiles,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("conv_browse_phone_btn"),
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (tool.allowMultipleInputs) "Add Files from Phone" else "Browse Phone File"
                    )
                }

                if (FileConverterViewModel.toolSupportsLibraryDocs(tool) && savedDocuments.isNotEmpty()) {
                    Text(
                        text = "Or pick from ProScan Library:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(savedDocuments.take(12), key = { it.id }) { doc ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier
                                    .clickable { onAddLibraryDoc(doc) }
                                    .testTag("conv_lib_doc_${doc.id}"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.Description,
                                        contentDescription = null,
                                        tint = accent,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Column {
                                        Text(
                                            text = doc.title,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${doc.pageCount}p",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (uiState.inputs.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        uiState.inputs.forEachIndexed { idx, item ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        tint = accent,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.displayName,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = Formatter.formatShortFileSize(context, item.sizeBytes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (tool.allowMultipleInputs && uiState.inputs.size > 1) {
                                        IconButton(
                                            onClick = { onMoveInput(idx, -1) },
                                            enabled = idx > 0,
                                        ) {
                                            Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
                                        }
                                        IconButton(
                                            onClick = { onMoveInput(idx, 1) },
                                            enabled = idx < uiState.inputs.lastIndex,
                                        ) {
                                            Icon(Icons.Default.ArrowDownward, contentDescription = "Move down")
                                        }
                                    }
                                    IconButton(onClick = { onRemoveInput(item.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove input")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Conversion Options Card
        ConverterOptionsCard(
            tool = tool,
            options = uiState.options,
            onUpdateOptions = onUpdateOptions,
        )

        // Primary Convert Action Button
        Button(
            onClick = onExecute,
            enabled = uiState.inputs.isNotEmpty() && !uiState.isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("conv_execute_btn"),
        ) {
            Icon(Icons.Default.Transform, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Convert Now (${tool.shortBadge})",
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ConverterOptionsCard(
    tool: ConverterTool,
    options: ConverterOptions,
    onUpdateOptions: ((ConverterOptions) -> ConverterOptions) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "2. Conversion Settings",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            if (tool == ConverterTool.PDF_TO_DOCX) {
                Text("Word Output Mode", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DocxOutputMode.entries.forEach { mode ->
                        FilterChip(
                            selected = options.docxOutputMode == mode,
                            onClick = { onUpdateOptions { it.copy(docxOutputMode = mode) } },
                            label = { Text(mode.label) },
                        )
                    }
                }
            }

            if (tool == ConverterTool.PDF_TO_DOCX ||
                tool == ConverterTool.PDF_TO_TXT ||
                tool == ConverterTool.PDF_TO_HTML
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Smart Layout Reconstruction (Free)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "Rebuilds headings, lists & Word tables automatically offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = options.useCloudAiStructure,
                        onCheckedChange = { enabled -> onUpdateOptions { it.copy(useCloudAiStructure = enabled) } },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("On-Device OCR Fallback (ML Kit + Tesseract)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "Automatically read text from scanned/image PDF pages offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = options.useOcrFallback,
                        onCheckedChange = { enabled -> onUpdateOptions { it.copy(useOcrFallback = enabled) } },
                    )
                }
            }

            if (tool == ConverterTool.TEXT_DOC_TO_PDF) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Smart Line & Heading Polish (Free)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "Cleans broken line wraps and formats notes offline before rendering PDF",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = options.aiPolishText,
                        onCheckedChange = { enabled -> onUpdateOptions { it.copy(aiPolishText = enabled) } },
                    )
                }
            }

            if (tool == ConverterTool.DOCX_TO_PDF ||
                tool == ConverterTool.IMAGE_TO_PDF ||
                tool == ConverterTool.TEXT_DOC_TO_PDF ||
                tool == ConverterTool.CODE_TO_PDF
            ) {
                Text("PDF Page Size", style = MaterialTheme.typography.labelMedium)
                val sizes = if (tool == ConverterTool.IMAGE_TO_PDF) {
                    listOf(PdfPageSize.A4, PdfPageSize.LETTER, PdfPageSize.AUTO_FIT)
                } else {
                    listOf(PdfPageSize.A4, PdfPageSize.LETTER)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sizes.forEach { sz ->
                        FilterChip(
                            selected = options.pdfPageSize == sz,
                            onClick = { onUpdateOptions { it.copy(pdfPageSize = sz) } },
                            label = { Text(sz.label) },
                        )
                    }
                }
            }

            if (tool == ConverterTool.PDF_TO_IMAGES ||
                tool == ConverterTool.PDF_TO_LONG_IMAGE ||
                tool == ConverterTool.BATCH_IMAGE_CONVERT
            ) {
                Text("Target Image Format", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TargetImageFormat.entries.forEach { fmt ->
                        FilterChip(
                            selected = options.imageFormat == fmt,
                            onClick = { onUpdateOptions { it.copy(imageFormat = fmt) } },
                            label = { Text(fmt.label) },
                        )
                    }
                }
            }

            if (tool == ConverterTool.IMAGE_TO_PDF ||
                tool == ConverterTool.PDF_TO_IMAGES ||
                tool == ConverterTool.PDF_TO_LONG_IMAGE ||
                tool == ConverterTool.BATCH_IMAGE_CONVERT
            ) {
                Text(
                    text = "Quality: ${options.imageQuality}%",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = options.imageQuality.toFloat(),
                    onValueChange = { v -> onUpdateOptions { it.copy(imageQuality = v.roundToInt()) } },
                    valueRange = 50f..100f,
                )

                Text("Max Image Resolution", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageResizeCap.entries.forEach { cap ->
                        FilterChip(
                            selected = options.resizeCap == cap,
                            onClick = { onUpdateOptions { it.copy(resizeCap = cap) } },
                            label = { Text(cap.label) },
                        )
                    }
                }
            }

            if (tool == ConverterTool.CODE_TO_PDF) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show Line Numbers", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "Prefix each line with monospace line numbers",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = options.includeLineNumbers,
                        onCheckedChange = { show -> onUpdateOptions { it.copy(includeLineNumbers = show) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConverterResultSection(
    result: ConverterOutputSummary,
    onSaveFile: (File) -> Unit,
    onShareFiles: (List<File>, String) -> Unit,
    onOpenInStudio: () -> Unit,
    onConvertAnother: () -> Unit,
) {
    val context = LocalContext.current
    val firstFile = result.files.firstOrNull()
    val previewBmp = remember(result) {
        if (result.mimeType.startsWith("image/") && firstFile != null && firstFile.exists()) {
            BitmapFactory.decodeFile(firstFile.absolutePath)
        } else null
    }
    val canOpenInStudio = result.mimeType == "application/pdf" || result.mimeType.startsWith("image/")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
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
                            text = "Conversion Complete!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (result.summaryNote.isNotBlank()) {
                        Text(
                            text = result.summaryNote,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Text(
                        text = "${result.files.size} file(s) • ${Formatter.formatShortFileSize(context, result.totalBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (previewBmp != null) {
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Image(
                        bitmap = previewBmp.asImageBitmap(),
                        contentDescription = "Converted image preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .background(Color.Black.copy(alpha = 0.05f)),
                    )
                }
            }
        }

        result.textPreview?.takeIf { it.isNotBlank() }?.let { snippet ->
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Output Preview",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = snippet,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (canOpenInStudio) {
            item {
                Button(
                    onClick = onOpenInStudio,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("conv_open_studio_btn"),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open in Document Studio")
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val saveTarget = result.zipArchive ?: firstFile
                if (saveTarget != null) {
                    FilledTonalButton(
                        onClick = { onSaveFile(saveTarget) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("conv_save_btn"),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (result.zipArchive != null) "Save ZIP" else "Save File")
                    }
                }
                Button(
                    onClick = { onShareFiles(result.files, result.mimeType) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("conv_share_btn"),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (result.files.size > 1) "Share All (${result.files.size})" else "Share")
                }
            }
        }

        result.zipArchive?.let { zip ->
            item {
                OutlinedButton(
                    onClick = { onShareFiles(listOf(zip), "application/zip") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Archive, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Share ZIP Archive (${Formatter.formatShortFileSize(context, zip.length())})")
                }
            }
        }

        item {
            OutlinedButton(
                onClick = onConvertAnother,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text("Convert Another File")
            }
        }
    }
}

private fun toolIcon(tool: ConverterTool): ImageVector = when (tool) {
    ConverterTool.PDF_TO_DOCX -> Icons.AutoMirrored.Filled.Article
    ConverterTool.DOCX_TO_PDF -> Icons.Default.PictureAsPdf
    ConverterTool.IMAGE_TO_PDF -> Icons.Default.PhotoLibrary
    ConverterTool.PDF_TO_IMAGES -> Icons.Default.Collections
    ConverterTool.PDF_TO_LONG_IMAGE -> Icons.Default.ViewAgenda
    ConverterTool.PDF_TO_TXT -> Icons.Default.TextFields
    ConverterTool.PDF_TO_HTML -> Icons.Default.Public
    ConverterTool.TEXT_DOC_TO_PDF -> Icons.Default.TableChart
    ConverterTool.CODE_TO_PDF -> Icons.Default.Code
    ConverterTool.BATCH_IMAGE_CONVERT -> Icons.Default.SwapHoriz
}

private fun shareConvertedFiles(context: Context, files: List<File>, mimeType: String) {
    if (files.isEmpty()) return
    val uris = files.map { uriForFile(context, it) }
    val intent = Intent().apply {
        action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
        type = mimeType
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size == 1) {
            putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    val chooser = Intent.createChooser(intent, "Share Converted File")
    val resolveInfos = context.packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
    for (info in resolveInfos) {
        val pkg = info.activityInfo.packageName
        for (uri in uris) {
            context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    context.startActivity(chooser)
}
