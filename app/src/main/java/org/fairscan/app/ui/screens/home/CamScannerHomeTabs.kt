package org.fairscan.app.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Approval
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Portrait
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DocumentScanner
import org.fairscan.app.R
import org.fairscan.app.camscanner.ConverterTool
import org.fairscan.app.cloud.CloudSyncAccountCard
import org.fairscan.app.cloud.CloudSyncUiState
import org.fairscan.app.cloud.HybridConnectionState
import org.fairscan.app.cloud.PersonalByokKeyCard
import org.fairscan.app.library.SavedDocumentEntity
import org.fairscan.app.ui.Navigation
import org.fairscan.app.ui.screens.camera.CaptureMode

enum class HomeNavTab(val label: String) {
    HOME("Home"),
    FILES("Files"),
    TOOLS("Tools"),
    ME("Me"),
}

data class CamToolGridItem(
    val title: String,
    val icon: ImageVector,
    val accent: Color,
    val tag: String,
    val onClick: () -> Unit,
)

@Composable
fun CamScannerBottomBar(
    selectedTab: HomeNavTab,
    onSelectTab: (HomeNavTab) -> Unit,
    onCameraScanClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            tonalElevation = 3.dp,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(64.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                BottomNavItem(
                    selected = selectedTab == HomeNavTab.HOME,
                    activeIcon = Icons.Filled.Home,
                    inactiveIcon = Icons.Outlined.Home,
                    label = HomeNavTab.HOME.label,
                    onClick = { onSelectTab(HomeNavTab.HOME) },
                    modifier = Modifier.weight(1f).testTag("nav_tab_home")
                )
                BottomNavItem(
                    selected = selectedTab == HomeNavTab.FILES,
                    activeIcon = Icons.Filled.Folder,
                    inactiveIcon = Icons.Outlined.Folder,
                    label = HomeNavTab.FILES.label,
                    onClick = { onSelectTab(HomeNavTab.FILES) },
                    modifier = Modifier.weight(1f).testTag("nav_tab_files")
                )
                // Center spacer reserved for elevated Camera FAB
                Spacer(Modifier.width(72.dp))
                BottomNavItem(
                    selected = selectedTab == HomeNavTab.TOOLS,
                    activeIcon = Icons.Filled.GridView,
                    inactiveIcon = Icons.Filled.Apps,
                    label = HomeNavTab.TOOLS.label,
                    onClick = { onSelectTab(HomeNavTab.TOOLS) },
                    modifier = Modifier.weight(1f).testTag("nav_tab_tools")
                )
                BottomNavItem(
                    selected = selectedTab == HomeNavTab.ME,
                    activeIcon = Icons.Filled.Person,
                    inactiveIcon = Icons.Outlined.Person,
                    label = HomeNavTab.ME.label,
                    onClick = { onSelectTab(HomeNavTab.ME) },
                    modifier = Modifier.weight(1f).testTag("nav_tab_me")
                )
            }
        }

        // Signature CamScanner Elevated Center Emerald Camera Button
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-16).dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .size(64.dp)
                .shadow(10.dp, CircleShape, ambientColor = MaterialTheme.colorScheme.primary, spotColor = MaterialTheme.colorScheme.primary)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary
                        )
                    )
                )
                .border(3.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .clickable(onClick = onCameraScanClick)
                .testTag("home_fab_scan"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "Scan",
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    selected: Boolean,
    activeIcon: ImageVector,
    inactiveIcon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (selected) activeIcon else inactiveIcon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(23.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = tint,
            maxLines = 1
        )
    }
}

@Composable
fun CamScannerSearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        shadowElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_search_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_search_input")
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun CamScannerHomeTabContent(
    uiState: HomeHubUiState,
    activeSessionPageCount: Int,
    onResumeActiveSession: () -> Unit,
    onDiscardActiveSession: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleGridView: () -> Unit,
    onSetSortOrder: (DocumentSortOrder) -> Unit,
    onLaunchCaptureMode: (CaptureMode) -> Unit,
    onLaunchMlKitScanner: () -> Unit = {},
    onImportPdf: () -> Unit,
    onOpenPdfTools: () -> Unit,
    onOpenFileConverter: (ConverterTool?) -> Unit = {},
    onOpenSignStampVault: () -> Unit,
    onOpenIdPhotoMaker: () -> Unit,
    onOpenAllTools: () -> Unit,
    onOpenAllFiles: () -> Unit,
    onOpenDocument: (SavedDocumentEntity) -> Unit,
    onToggleFavorite: (SavedDocumentEntity) -> Unit,
    onRenameDoc: (SavedDocumentEntity) -> Unit,
    onEditTagsDoc: (SavedDocumentEntity) -> Unit,
    onMoveDoc: (SavedDocumentEntity) -> Unit,
    onPrintDoc: (SavedDocumentEntity) -> Unit,
    onDeleteDoc: (SavedDocumentEntity) -> Unit,
) {
    var showSortMenu by remember { mutableStateOf(false) }

    val quickTools = listOf(
        CamToolGridItem(stringResource(R.string.camscanner_doc), Icons.Default.CameraAlt, Color(0xFF00B388), "mode_doc") {
            onLaunchCaptureMode(CaptureMode.DOCUMENT)
        },
        CamToolGridItem(stringResource(R.string.camscanner_pdf_tools), Icons.Default.PictureAsPdf, Color(0xFFE53935), "mode_pdf_tools", onOpenPdfTools),
        CamToolGridItem(stringResource(R.string.file_converter_short), Icons.Default.Transform, Color(0xFF1E88E5), "mode_file_converter") {
            onOpenFileConverter(null)
        },
        CamToolGridItem(stringResource(R.string.camscanner_import_pdf), Icons.Default.UploadFile, Color(0xFF00897B), "mode_import_pdf", onImportPdf),
        CamToolGridItem(stringResource(R.string.camscanner_id_card), Icons.Default.Badge, Color(0xFF5E35B1), "mode_id") {
            onLaunchCaptureMode(CaptureMode.ID_CARD)
        },
        CamToolGridItem(stringResource(R.string.camscanner_sign_stamp), Icons.Default.Approval, Color(0xFFD81B60), "mode_sign_stamp", onOpenSignStampVault),
        CamToolGridItem(stringResource(R.string.camscanner_id_photo), Icons.Default.Portrait, Color(0xFF3949AB), "mode_id_photo", onOpenIdPhotoMaker),
        CamToolGridItem("All Tools", Icons.Default.Apps, Color(0xFF546E7A), "mode_all_tools", onOpenAllTools),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        CamScannerSearchPill(
            query = uiState.searchQuery,
            onQueryChange = onSearchQueryChange,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        AnimatedVisibility(visible = activeSessionPageCount > 0) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.home_resume_banner, activeSessionPageCount),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDiscardActiveSession) { Text("Discard") }
                    Button(onClick = onResumeActiveSession) { Text(stringResource(R.string.home_resume_action)) }
                }
            }
        }

        // CamScanner 4x2 Quick Tools Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)) {
                quickTools.chunked(4).forEachIndexed { rowIdx, rowItems ->
                    if (rowIdx > 0) Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        rowItems.forEach { item ->
                            CamToolGridCell(item = item, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Google ML Kit AI Document Scanner Quick Strip
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onLaunchMlKitScanner)
                .testTag("mode_mlkit_scan_home"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.DocumentScanner,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Google ML Kit AI Scanner (Free)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Auto-clean shadows, fingers & corners into Studio",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Recent Files Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Recent Files",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${uiState.documents.size}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenAllFiles) {
                    Text("Folders", style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = onToggleGridView, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (uiState.isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                        contentDescription = "Toggle layout",
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box {
                    IconButton(onClick = { showSortMenu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort", modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        DocumentSortOrder.entries.forEach { order ->
                            DropdownMenuItem(
                                text = { Text(order.label) },
                                onClick = {
                                    onSetSortOrder(order)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        if (uiState.documents.isEmpty()) {
            HomeEmptyState(
                inTrash = false,
                onStartScan = { onLaunchCaptureMode(CaptureMode.DOCUMENT) },
                modifier = Modifier.weight(1f)
            )
        } else if (uiState.isGridView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(uiState.documents, key = { it.id }) { doc ->
                    DocumentGridCard(
                        doc = doc,
                        inTrash = false,
                        searchQuery = uiState.searchQuery,
                        onClick = { onOpenDocument(doc) },
                        onToggleFavorite = { onToggleFavorite(doc) },
                        onRename = { onRenameDoc(doc) },
                        onEditTags = { onEditTagsDoc(doc) },
                        onMoveFolder = { onMoveDoc(doc) },
                        onPrint = { onPrintDoc(doc) },
                        onDelete = { onDeleteDoc(doc) },
                        onRestore = {},
                        onDeleteForever = {},
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(uiState.documents, key = { it.id }) { doc ->
                    DocumentListCard(
                        doc = doc,
                        inTrash = false,
                        searchQuery = uiState.searchQuery,
                        onClick = { onOpenDocument(doc) },
                        onToggleFavorite = { onToggleFavorite(doc) },
                        onRename = { onRenameDoc(doc) },
                        onEditTags = { onEditTagsDoc(doc) },
                        onMoveFolder = { onMoveDoc(doc) },
                        onPrint = { onPrintDoc(doc) },
                        onDelete = { onDeleteDoc(doc) },
                        onRestore = {},
                        onDeleteForever = {},
                    )
                }
            }
        }
    }
}

@Composable
fun CamScannerToolsTabContent(
    onLaunchCaptureMode: (CaptureMode) -> Unit,
    onLaunchMlKitScanner: () -> Unit = {},
    onImportPhotos: () -> Unit,
    onImportPdf: () -> Unit,
    onOpenPdfTools: () -> Unit,
    onOpenSignStampVault: () -> Unit,
    onOpenIdPhotoMaker: () -> Unit,
    onOpenStitchCollage: () -> Unit,
    onOpenQrStudio: () -> Unit,
    onOpenVaultBackup: () -> Unit,
    onOpenAiPhotoEditor: () -> Unit = {},
    onOpenFileConverter: (ConverterTool?) -> Unit = {},
) {
    val scanTools = listOf(
        CamToolGridItem(stringResource(R.string.camscanner_doc), Icons.Default.CameraAlt, Color(0xFF00B388), "mode_doc") {
            onLaunchCaptureMode(CaptureMode.DOCUMENT)
        },
        CamToolGridItem("ML Kit AI Scan", Icons.Default.DocumentScanner, Color(0xFF00897B), "mode_mlkit_scan", onLaunchMlKitScanner),
        CamToolGridItem(stringResource(R.string.camscanner_batch), Icons.Default.Collections, Color(0xFF0288D1), "mode_batch") {
            onLaunchCaptureMode(CaptureMode.BATCH)
        },
        CamToolGridItem(stringResource(R.string.camscanner_id_card), Icons.Default.Badge, Color(0xFF5E35B1), "mode_id") {
            onLaunchCaptureMode(CaptureMode.ID_CARD)
        },
        CamToolGridItem(stringResource(R.string.camscanner_book_split), Icons.Default.MenuBook, Color(0xFFFB8C00), "mode_book") {
            onLaunchCaptureMode(CaptureMode.BOOK_SPLIT)
        },
        CamToolGridItem(stringResource(R.string.camscanner_ppt_mode), Icons.Default.Slideshow, Color(0xFF1565C0), "mode_ppt") {
            onLaunchCaptureMode(CaptureMode.PPT_BOARD)
        },
        CamToolGridItem(stringResource(R.string.camscanner_test_paper), Icons.Default.CleaningServices, Color(0xFF00838F), "mode_test_paper") {
            onLaunchCaptureMode(CaptureMode.TEST_PAPER)
        },
        CamToolGridItem(stringResource(R.string.camscanner_qr_scan), Icons.Default.QrCodeScanner, Color(0xFF2E7D32), "mode_qr") {
            onLaunchCaptureMode(CaptureMode.QR_SCAN)
        },
    )

    val converterTools = listOf(
        CamToolGridItem("All Converters", Icons.Default.Transform, Color(0xFF1565C0), "conv_shortcut_hub") {
            onOpenFileConverter(null)
        },
        CamToolGridItem("PDF → Word", Icons.Default.Description, Color(0xFF1E88E5), "conv_shortcut_pdf_doc") {
            onOpenFileConverter(ConverterTool.PDF_TO_DOCX)
        },
        CamToolGridItem("Word → PDF", Icons.Default.PictureAsPdf, Color(0xFFE53935), "conv_shortcut_doc_pdf") {
            onOpenFileConverter(ConverterTool.DOCX_TO_PDF)
        },
        CamToolGridItem("Image → PDF", Icons.Default.PhotoLibrary, Color(0xFF00897B), "conv_shortcut_img_pdf") {
            onOpenFileConverter(ConverterTool.IMAGE_TO_PDF)
        },
        CamToolGridItem("PDF → Image", Icons.Default.Collections, Color(0xFF8E24AA), "conv_shortcut_pdf_img") {
            onOpenFileConverter(ConverterTool.PDF_TO_IMAGES)
        },
        CamToolGridItem("MD/CSV → PDF", Icons.Default.TableChart, Color(0xFF2E7D32), "conv_shortcut_table_pdf") {
            onOpenFileConverter(ConverterTool.TEXT_DOC_TO_PDF)
        },
        CamToolGridItem("Code → PDF", Icons.Default.Code, Color(0xFF00838F), "conv_shortcut_code_pdf") {
            onOpenFileConverter(ConverterTool.CODE_TO_PDF)
        },
        CamToolGridItem("Batch Images", Icons.Default.SwapHoriz, Color(0xFFD81B60), "conv_shortcut_batch_img") {
            onOpenFileConverter(ConverterTool.BATCH_IMAGE_CONVERT)
        },
    )

    val pdfTools = listOf(
        CamToolGridItem(stringResource(R.string.camscanner_pdf_tools), Icons.Default.PictureAsPdf, Color(0xFFE53935), "mode_pdf_tools", onOpenPdfTools),
        CamToolGridItem(stringResource(R.string.camscanner_import_pdf), Icons.Default.UploadFile, Color(0xFF00897B), "mode_import_pdf", onImportPdf),
        CamToolGridItem(stringResource(R.string.import_photos), Icons.Default.AddPhotoAlternate, Color(0xFF455A64), "mode_import", onImportPhotos),
    )

    val studioTools = listOf(
        CamToolGridItem(stringResource(R.string.ai_photo_editor_title), Icons.Default.AutoFixHigh, Color(0xFF6A1B9A), "mode_ai_photo_editor", onOpenAiPhotoEditor),
        CamToolGridItem(stringResource(R.string.camscanner_sign_stamp), Icons.Default.Approval, Color(0xFFD81B60), "mode_sign_stamp", onOpenSignStampVault),
        CamToolGridItem(stringResource(R.string.camscanner_id_photo), Icons.Default.Portrait, Color(0xFF3949AB), "mode_id_photo", onOpenIdPhotoMaker),
        CamToolGridItem(stringResource(R.string.camscanner_collage_stitch), Icons.Default.GridView, Color(0xFF7B1FA2), "mode_stitch_collage", onOpenStitchCollage),
        CamToolGridItem(stringResource(R.string.camscanner_qr_studio), Icons.Default.QrCode2, Color(0xFF2E7D32), "mode_qr_studio", onOpenQrStudio),
        CamToolGridItem(stringResource(R.string.camscanner_vault_backup), Icons.Default.Security, Color(0xFF37474F), "mode_vault_backup", onOpenVaultBackup),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ToolboxSectionCard(title = "Smart File Converter Studio", items = converterTools)
        ToolboxSectionCard(title = "PDF Toolkit & Import", items = pdfTools)
        ToolboxSectionCard(title = "Smart Scan Modes", items = scanTools)
        ToolboxSectionCard(title = "Sign, Photo & Security Studio", items = studioTools)
    }
}

@Composable
private fun ToolboxSectionCard(
    title: String,
    items: List<CamToolGridItem>,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            items.chunked(4).forEachIndexed { idx, row ->
                if (idx > 0) Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { item ->
                        CamToolGridCell(item = item, modifier = Modifier.weight(1f))
                    }
                    repeat(4 - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CamToolGridCell(
    item: CamToolGridItem,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = item.onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp)
            .testTag(item.tag),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(item.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.title,
                tint = item.accent,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun CamScannerMeTabContent(
    docCount: Int,
    folderCount: Int,
    assetCount: Int,
    trashCount: Int,
    navigation: Navigation,
    onOpenVaultBackup: () -> Unit,
    onOpenTrash: () -> Unit,
    hybridState: HybridConnectionState = HybridConnectionState(),
    cloudSyncState: CloudSyncUiState = CloudSyncUiState(),
    onOpenHybridMode: () -> Unit = {},
    onGoogleSignIn: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    onRestoreFromDrive: () -> Unit = {},
    onExportVisibleDrive: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onDismissSyncMessage: () -> Unit = {},
    onSavePersonalApiKey: (String) -> Unit = {},
    onClearPersonalApiKey: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // CamScanner-style Studio Banner Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary
                        )
                    )
                )
                .padding(18.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "By KALAS • Free ML Kit + Google Drive Studio",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MeStatChip("$docCount Docs", Modifier.weight(1f))
                    MeStatChip("$folderCount Folders", Modifier.weight(1f))
                    MeStatChip("$assetCount Signs", Modifier.weight(1f))
                }
            }
        }

        CloudSyncAccountCard(
            syncState = cloudSyncState,
            hybridState = hybridState,
            onSignInClick = onGoogleSignIn,
            onSyncNowClick = onSyncNow,
            onRestoreFromDriveClick = onRestoreFromDrive,
            onExportVisibleDriveClick = onExportVisibleDrive,
            onSignOutClick = onSignOut,
            onDismissMessage = onDismissSyncMessage,
        )

        PersonalByokKeyCard(
            savedApiKey = hybridState.personalGeminiApiKey,
            onSaveKey = onSavePersonalApiKey,
            onClearKey = onClearPersonalApiKey,
        )

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                MeMenuRow(
                    icon = Icons.Default.CloudSync,
                    title = stringResource(R.string.hybrid_mode_title),
                    subtitle = "${hybridState.preference.label} • ${hybridState.shortBadgeLabel}",
                    tint = Color(0xFF00897B),
                    onClick = onOpenHybridMode
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                MeMenuRow(
                    icon = Icons.Default.Lock,
                    title = "Vault Lock & Fingerprint",
                    subtitle = "App lock & private folder security",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onOpenVaultBackup
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                MeMenuRow(
                    icon = Icons.Default.Archive,
                    title = "Offline ZIP Backup & Restore",
                    subtitle = "Export or restore documents & stamps",
                    tint = Color(0xFF1E88E5),
                    onClick = onOpenVaultBackup
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                MeMenuRow(
                    icon = Icons.Default.DeleteSweep,
                    title = "Recycle Bin (30 Days)",
                    subtitle = if (trashCount > 0) "$trashCount deleted documents" else "No deleted items",
                    tint = Color(0xFFE53935),
                    onClick = onOpenTrash
                )
            }
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                MeMenuRow(
                    icon = Icons.Default.Translate,
                    title = stringResource(R.string.settings_ocr_languages),
                    subtitle = "Manage offline Tesseract & translation packs",
                    tint = Color(0xFF7B1FA2),
                    onClick = navigation.toOcrLanguagesScreen
                )
                navigation.toSettingsScreen?.let { toSettings ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    MeMenuRow(
                        icon = Icons.Default.Settings,
                        title = stringResource(R.string.settings),
                        subtitle = "Scan defaults, export folder & quality",
                        tint = Color(0xFF455A64),
                        onClick = toSettings
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                MeMenuRow(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.about),
                    subtitle = "ProScan by KALAS",
                    tint = Color(0xFF00897B),
                    onClick = navigation.toAboutScreen
                )
            }
        }
    }
}

@Composable
private fun MeStatChip(label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.18f),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp)
        )
    }
}

@Composable
private fun MeMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
