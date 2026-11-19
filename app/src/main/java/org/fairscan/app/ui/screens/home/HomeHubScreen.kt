package org.fairscan.app.ui.screens.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fairscan.app.R
import org.fairscan.app.camscanner.AiPhotoEditorDialog
import org.fairscan.app.camscanner.AppVaultLockGate
import org.fairscan.app.camscanner.ConverterTool
import org.fairscan.app.camscanner.IdPhotoMakerDialog
import org.fairscan.app.camscanner.LayoutCollagePrintDialog
import org.fairscan.app.camscanner.LayoutPrintEngine
import org.fairscan.app.camscanner.QrGeneratorDialog
import org.fairscan.app.camscanner.SecurityVaultManager
import org.fairscan.app.camscanner.SetVaultPinDialog
import org.fairscan.app.camscanner.SignatureStampVaultDialog
import org.fairscan.app.camscanner.VaultAndBackupDialog
import org.fairscan.app.camscanner.findActivity
import org.fairscan.app.camscanner.findFragmentActivity
import org.fairscan.app.cloud.CloudAiGateway
import org.fairscan.app.cloud.CloudSyncUiState
import org.fairscan.app.cloud.HybridConnectionState
import org.fairscan.app.cloud.HybridModePreference
import org.fairscan.app.cloud.HybridModeStatusPill
import org.fairscan.app.cloud.HybridModeSwitcherDialog
import org.fairscan.app.library.FolderEntity
import org.fairscan.app.library.LibraryRepository
import org.fairscan.app.library.SavedAssetType
import org.fairscan.app.library.SavedDocumentEntity
import org.fairscan.app.library.SavedSignatureEntity
import org.fairscan.app.ui.Navigation
import org.fairscan.app.ui.components.AppOverflowMenu
import org.fairscan.app.ui.screens.camera.CaptureMode
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeHubScreen(
    uiState: HomeHubUiState,
    activeSessionPageCount: Int,
    navigation: Navigation,
    onResumeActiveSession: () -> Unit,
    onDiscardActiveSession: () -> Unit,
    onLaunchCaptureMode: (CaptureMode) -> Unit,
    onImportPhotos: () -> Unit,
    onImportPdf: () -> Unit = {},
    onOpenPdfTools: () -> Unit = {},
    onOpenFileConverter: (ConverterTool?) -> Unit = {},
    onOpenDocument: (SavedDocumentEntity) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectAllDocs: () -> Unit,
    onSelectFavorites: () -> Unit,
    onSelectTrash: () -> Unit = {},
    onSelectFolder: (String) -> Unit,
    onUnlockFolder: (String) -> Unit = {},
    onToggleFolderLock: (String, Boolean) -> Unit = { _, _ -> },
    onToggleTag: (String) -> Unit,
    onSetSortOrder: (DocumentSortOrder) -> Unit,
    onToggleGridView: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onRenameDocument: (String, String) -> Unit,
    onMoveDocument: (String, String?) -> Unit,
    onUpdateTags: (String, String) -> Unit,
    onToggleFavorite: (SavedDocumentEntity) -> Unit,
    onDeleteDocument: (SavedDocumentEntity) -> Unit,
    onRestoreDocument: (SavedDocumentEntity) -> Unit = {},
    onPermanentlyDeleteDocument: (SavedDocumentEntity) -> Unit = {},
    onEmptyTrash: () -> Unit = {},
    savedSignatures: List<SavedSignatureEntity> = emptyList(),
    onSaveAsset: (android.graphics.Bitmap, String, SavedAssetType) -> Unit = { _, _, _ -> },
    onDeleteAsset: (SavedSignatureEntity) -> Unit = {},
    onApplyAssetToDocument: (docId: String?, android.graphics.Bitmap, String, SavedAssetType) -> Unit = { _, _, _, _ -> },
    securityVaultManager: SecurityVaultManager? = null,
    libraryRepository: LibraryRepository? = null,
    onOpenBitmapInStudio: (String, Bitmap) -> Unit = { _, _ -> },
    hybridState: HybridConnectionState = HybridConnectionState(),
    cloudSyncState: CloudSyncUiState = CloudSyncUiState(),
    cloudAiGateway: CloudAiGateway? = null,
    onSetHybridPreference: (HybridModePreference) -> Unit = {},
    onToggleAiDocStructure: (Boolean) -> Unit = {},
    onToggleMlKitSegmentation: (Boolean) -> Unit = {},
    onRefreshNetwork: () -> Unit = {},
    onLaunchMlKitScanner: () -> Unit = {},
    onGoogleSignIn: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    onRestoreFromDrive: () -> Unit = {},
    onExportVisibleDrive: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onDismissSyncMessage: () -> Unit = {},
    onSavePersonalApiKey: (String) -> Unit = {},
    onClearPersonalApiKey: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by rememberSaveable { mutableStateOf(HomeNavTab.HOME) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameDocTarget by remember { mutableStateOf<SavedDocumentEntity?>(null) }
    var tagsDocTarget by remember { mutableStateOf<SavedDocumentEntity?>(null) }
    var moveDocTarget by remember { mutableStateOf<SavedDocumentEntity?>(null) }
    var deleteDocTarget by remember { mutableStateOf<SavedDocumentEntity?>(null) }
    var permDeleteDocTarget by remember { mutableStateOf<SavedDocumentEntity?>(null) }
    var showEmptyTrashConfirm by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showSignStampVault by remember { mutableStateOf(false) }
    var showHybridModeDialog by remember { mutableStateOf(false) }

    var showVaultBackupDialog by remember { mutableStateOf(false) }
    var showStitchCollageDialog by remember { mutableStateOf(false) }
    var showQrGeneratorDialog by remember { mutableStateOf(false) }
    var showIdPhotoDialog by remember { mutableStateOf(false) }
    var showAiPhotoEditorDialog by remember { mutableStateOf(false) }

    var folderPinGateTarget by remember { mutableStateOf<FolderEntity?>(null) }
    var pendingLockFolderAfterPin by remember { mutableStateOf<FolderEntity?>(null) }

    val handlePrintDocument: (SavedDocumentEntity) -> Unit = { doc ->
        val repo = libraryRepository
        val act = context.findActivity()
        if (repo != null && act != null) {
            scope.launch {
                val files = repo.readDocumentPageFiles(doc.id)
                val bitmaps = withContext(Dispatchers.IO) {
                    files.mapNotNull { (f, _) -> BitmapFactory.decodeFile(f.absolutePath) }
                }
                if (bitmaps.isNotEmpty()) {
                    LayoutPrintEngine.printBitmaps(act, doc.title, bitmaps)
                } else {
                    Toast.makeText(context, "No printable pages found", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val handleFolderChipClick: (FolderEntity) -> Unit = { folder ->
        val isLockedAndSealed = folder.isLocked && folder.id !in uiState.unlockedFolderIds
        if (!isLockedAndSealed) {
            onSelectFolder(folder.id)
        } else if (securityVaultManager == null || !securityVaultManager.hasPin()) {
            onUnlockFolder(folder.id)
        } else {
            val fragAct = context.findFragmentActivity()
            if (fragAct != null && securityVaultManager.isBiometricEnabled() && securityVaultManager.canUseBiometrics(context)) {
                securityVaultManager.authenticateBiometric(
                    activity = fragAct,
                    title = "Unlock \"${folder.name}\"",
                    subtitle = "Verify fingerprint or use Vault PIN",
                    negativeText = "Use PIN",
                    onSuccess = { onUnlockFolder(folder.id) },
                    onUsePin = { folderPinGateTarget = folder },
                    onError = { folderPinGateTarget = folder }
                )
            } else {
                folderPinGateTarget = folder
            }
        }
    }

    val handleLockFolderToggle: (FolderEntity) -> Unit = { folder ->
        if (folder.isLocked) {
            onToggleFolderLock(folder.id, false)
            Toast.makeText(context, "\"${folder.name}\" unlocked", Toast.LENGTH_SHORT).show()
        } else if (securityVaultManager != null && !securityVaultManager.hasPin()) {
            pendingLockFolderAfterPin = folder
        } else {
            onToggleFolderLock(folder.id, true)
            Toast.makeText(context, "\"${folder.name}\" locked with Vault PIN", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.secondary
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = when (selectedTab) {
                                    HomeNavTab.HOME -> stringResource(R.string.app_name)
                                    HomeNavTab.FILES -> "Files & Folders"
                                    HomeNavTab.TOOLS -> "CamScanner Tools"
                                    HomeNavTab.ME -> "Studio & Settings"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "By KALAS • ${uiState.totalDocumentCount} saved",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    HybridModeStatusPill(
                        state = hybridState,
                        onClick = { showHybridModeDialog = true },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    if (securityVaultManager != null && libraryRepository != null) {
                        IconButton(
                            onClick = { showVaultBackupDialog = true },
                            modifier = Modifier.testTag("home_vault_backup_btn")
                        ) {
                            Icon(Icons.Default.Security, contentDescription = stringResource(R.string.camscanner_vault_backup))
                        }
                    }
                    if (selectedTab == HomeNavTab.FILES) {
                        IconButton(onClick = onToggleGridView) {
                            Icon(
                                if (uiState.isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                contentDescription = "Toggle layout"
                            )
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
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
                    AppOverflowMenu(navigation)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            CamScannerBottomBar(
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                onCameraScanClick = { onLaunchCaptureMode(CaptureMode.DOCUMENT) }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (selectedTab) {
                HomeNavTab.HOME -> {
                    CamScannerHomeTabContent(
                        uiState = uiState,
                        activeSessionPageCount = activeSessionPageCount,
                        onResumeActiveSession = onResumeActiveSession,
                        onDiscardActiveSession = onDiscardActiveSession,
                        onSearchQueryChange = onSearchQueryChange,
                        onToggleGridView = onToggleGridView,
                        onSetSortOrder = onSetSortOrder,
                        onLaunchCaptureMode = onLaunchCaptureMode,
                        onLaunchMlKitScanner = onLaunchMlKitScanner,
                        onImportPdf = onImportPdf,
                        onOpenPdfTools = onOpenPdfTools,
                        onOpenFileConverter = onOpenFileConverter,
                        onOpenSignStampVault = { showSignStampVault = true },
                        onOpenIdPhotoMaker = { showIdPhotoDialog = true },
                        onOpenAllTools = { selectedTab = HomeNavTab.TOOLS },
                        onOpenAllFiles = { selectedTab = HomeNavTab.FILES },
                        onOpenDocument = onOpenDocument,
                        onToggleFavorite = onToggleFavorite,
                        onRenameDoc = { renameDocTarget = it },
                        onEditTagsDoc = { tagsDocTarget = it },
                        onMoveDoc = { moveDocTarget = it },
                        onPrintDoc = handlePrintDocument,
                        onDeleteDoc = { deleteDocTarget = it },
                    )
                }

                HomeNavTab.FILES -> {
                    FilesExplorerTab(
                        uiState = uiState,
                        onSearchQueryChange = onSearchQueryChange,
                        onSelectAllDocs = onSelectAllDocs,
                        onSelectFavorites = onSelectFavorites,
                        onSelectTrash = onSelectTrash,
                        onSelectFolder = handleFolderChipClick,
                        onToggleFolderLock = handleLockFolderToggle,
                        onToggleTag = onToggleTag,
                        onNewFolderClick = { showNewFolderDialog = true },
                        onDeleteFolder = onDeleteFolder,
                        onEmptyTrashClick = { showEmptyTrashConfirm = true },
                        onStartScan = { onLaunchCaptureMode(CaptureMode.DOCUMENT) },
                        onOpenDocument = onOpenDocument,
                        onToggleFavorite = onToggleFavorite,
                        onRename = { renameDocTarget = it },
                        onEditTags = { tagsDocTarget = it },
                        onMoveFolder = { moveDocTarget = it },
                        onPrint = handlePrintDocument,
                        onDelete = { deleteDocTarget = it },
                        onRestore = onRestoreDocument,
                        onDeleteForever = { permDeleteDocTarget = it },
                    )
                }

                HomeNavTab.TOOLS -> {
                    CamScannerToolsTabContent(
                        onLaunchCaptureMode = onLaunchCaptureMode,
                        onLaunchMlKitScanner = onLaunchMlKitScanner,
                        onImportPhotos = onImportPhotos,
                        onImportPdf = onImportPdf,
                        onOpenPdfTools = onOpenPdfTools,
                        onOpenSignStampVault = { showSignStampVault = true },
                        onOpenIdPhotoMaker = { showIdPhotoDialog = true },
                        onOpenStitchCollage = { showStitchCollageDialog = true },
                        onOpenQrStudio = { showQrGeneratorDialog = true },
                        onOpenVaultBackup = { showVaultBackupDialog = true },
                        onOpenAiPhotoEditor = { showAiPhotoEditorDialog = true },
                        onOpenFileConverter = onOpenFileConverter,
                    )
                }

                HomeNavTab.ME -> {
                    CamScannerMeTabContent(
                        docCount = uiState.totalDocumentCount,
                        folderCount = uiState.folders.size,
                        assetCount = savedSignatures.size,
                        trashCount = uiState.trashCount,
                        navigation = navigation,
                        onOpenVaultBackup = { showVaultBackupDialog = true },
                        onOpenTrash = {
                            onSelectTrash()
                            selectedTab = HomeNavTab.FILES
                        },
                        hybridState = hybridState,
                        cloudSyncState = cloudSyncState,
                        onOpenHybridMode = { showHybridModeDialog = true },
                        onGoogleSignIn = onGoogleSignIn,
                        onSyncNow = onSyncNow,
                        onRestoreFromDrive = onRestoreFromDrive,
                        onExportVisibleDrive = onExportVisibleDrive,
                        onSignOut = onSignOut,
                        onDismissSyncMessage = onDismissSyncMessage,
                        onSavePersonalApiKey = onSavePersonalApiKey,
                        onClearPersonalApiKey = onClearPersonalApiKey,
                    )
                }
            }
        }
    }

    if (showNewFolderDialog) {
        TextInputDialog(
            title = stringResource(R.string.home_new_folder),
            label = "Folder Name",
            initialValue = "",
            onDismiss = { showNewFolderDialog = false },
            onConfirm = {
                onCreateFolder(it)
                showNewFolderDialog = false
            }
        )
    }

    renameDocTarget?.let { target ->
        TextInputDialog(
            title = "Rename Document",
            label = "Title",
            initialValue = target.title,
            onDismiss = { renameDocTarget = null },
            onConfirm = {
                onRenameDocument(target.id, it)
                renameDocTarget = null
            }
        )
    }

    tagsDocTarget?.let { target ->
        TextInputDialog(
            title = "Edit Tags",
            label = "Comma-separated tags (e.g. Tax, Receipt, CNIC)",
            initialValue = target.tagsCsv,
            onDismiss = { tagsDocTarget = null },
            onConfirm = {
                onUpdateTags(target.id, it)
                tagsDocTarget = null
            }
        )
    }

    moveDocTarget?.let { target ->
        MoveToFolderDialog(
            folders = uiState.folders,
            currentFolderId = target.folderId,
            onDismiss = { moveDocTarget = null },
            onSelectFolder = { folderId ->
                onMoveDocument(target.id, folderId)
                moveDocTarget = null
            }
        )
    }

    deleteDocTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteDocTarget = null },
            title = { Text("Move to Recycle Bin?") },
            text = { Text("\"${target.title}\" will stay in Trash for 30 days so you can restore it anytime.") },
            confirmButton = {
                Button(onClick = {
                    onDeleteDocument(target)
                    deleteDocTarget = null
                }) { Text("Move to Trash") }
            },
            dismissButton = {
                TextButton(onClick = { deleteDocTarget = null }) { Text("Cancel") }
            }
        )
    }

    permDeleteDocTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { permDeleteDocTarget = null },
            title = { Text("Delete Forever?") },
            text = { Text("Permanently erase \"${target.title}\"? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        onPermanentlyDeleteDocument(target)
                        permDeleteDocTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete Forever") }
            },
            dismissButton = {
                TextButton(onClick = { permDeleteDocTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (showEmptyTrashConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashConfirm = false },
            title = { Text("Empty Trash Bin?") },
            text = { Text("Permanently remove all ${uiState.trashCount} items in Trash?") },
            confirmButton = {
                Button(
                    onClick = {
                        onEmptyTrash()
                        showEmptyTrashConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Empty Trash") }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showSignStampVault) {
        SignatureStampVaultDialog(
            savedAssets = savedSignatures,
            initialTab = SavedAssetType.SIGNATURE,
            isApplyingToOpenDocument = false,
            savedDocuments = uiState.documents,
            activeSessionPageCount = activeSessionPageCount,
            onSaveAsset = onSaveAsset,
            onDeleteAsset = onDeleteAsset,
            onApplyOnLibraryDocument = onApplyAssetToDocument,
            onDismiss = { showSignStampVault = false }
        )
    }

    if (showVaultBackupDialog && securityVaultManager != null && libraryRepository != null) {
        VaultAndBackupDialog(
            vaultManager = securityVaultManager,
            libraryRepository = libraryRepository,
            docCount = uiState.totalDocumentCount,
            folderCount = uiState.folders.size,
            assetCount = savedSignatures.size,
            onDismiss = { showVaultBackupDialog = false }
        )
    }

    if (showStitchCollageDialog && libraryRepository != null) {
        LayoutCollagePrintDialog(
            savedDocuments = uiState.documents,
            libraryRepository = libraryRepository,
            onDismiss = { showStitchCollageDialog = false }
        )
    }

    if (showQrGeneratorDialog) {
        QrGeneratorDialog(
            canApplyToOpenDocument = activeSessionPageCount > 0,
            isOnlineActive = hybridState.isOnlineActive && cloudAiGateway != null,
            onAnalyzeQr = cloudAiGateway?.let { gw -> { payload -> gw.analyzeQrPayload(payload) } },
            onApplyQrToDocument = if (activeSessionPageCount > 0) {
                { qrBmp, label ->
                    onApplyAssetToDocument(null, qrBmp, label, SavedAssetType.STAMP)
                }
            } else null,
            onDismiss = { showQrGeneratorDialog = false }
        )
    }

    if (showIdPhotoDialog) {
        IdPhotoMakerDialog(onDismiss = { showIdPhotoDialog = false })
    }

    if (showAiPhotoEditorDialog) {
        AiPhotoEditorDialog(
            initialBitmap = null,
            isDocumentStudioMode = false,
            savedDocuments = uiState.documents,
            libraryRepository = libraryRepository,
            hybridState = hybridState,
            cloudAiGateway = cloudAiGateway,
            onDismiss = { showAiPhotoEditorDialog = false },
            onOpenAsNewDocument = onOpenBitmapInStudio,
        )
    }

    if (showHybridModeDialog) {
        HybridModeSwitcherDialog(
            state = hybridState,
            onSelectPreference = onSetHybridPreference,
            onToggleAiDocStructure = onToggleAiDocStructure,
            onToggleMlKitSegmentation = onToggleMlKitSegmentation,
            onRefreshNetwork = onRefreshNetwork,
            onDismiss = { showHybridModeDialog = false },
        )
    }

    folderPinGateTarget?.let { targetFolder ->
        if (securityVaultManager != null) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { folderPinGateTarget = null },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                AppVaultLockGate(
                    vaultManager = securityVaultManager,
                    title = "Unlock \"${targetFolder.name}\"",
                    subtitle = "Enter Vault PIN or verify Fingerprint",
                    onUnlocked = {
                        onUnlockFolder(targetFolder.id)
                        folderPinGateTarget = null
                    },
                    onDismissOptional = { folderPinGateTarget = null }
                )
            }
        }
    }

    pendingLockFolderAfterPin?.let { folder ->
        if (securityVaultManager != null) {
            SetVaultPinDialog(
                title = "Create Vault PIN to Lock Folder",
                onDismiss = { pendingLockFolderAfterPin = null },
                onPinConfirmed = { newPin ->
                    securityVaultManager.setPin(newPin)
                    onToggleFolderLock(folder.id, true)
                    pendingLockFolderAfterPin = null
                    Toast.makeText(context, "PIN set & \"${folder.name}\" locked!", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
private fun FilesExplorerTab(
    uiState: HomeHubUiState,
    onSearchQueryChange: (String) -> Unit,
    onSelectAllDocs: () -> Unit,
    onSelectFavorites: () -> Unit,
    onSelectTrash: () -> Unit,
    onSelectFolder: (FolderEntity) -> Unit,
    onToggleFolderLock: (FolderEntity) -> Unit,
    onToggleTag: (String) -> Unit,
    onNewFolderClick: () -> Unit,
    onDeleteFolder: (String) -> Unit,
    onEmptyTrashClick: () -> Unit,
    onStartScan: () -> Unit,
    onOpenDocument: (SavedDocumentEntity) -> Unit,
    onToggleFavorite: (SavedDocumentEntity) -> Unit,
    onRename: (SavedDocumentEntity) -> Unit,
    onEditTags: (SavedDocumentEntity) -> Unit,
    onMoveFolder: (SavedDocumentEntity) -> Unit,
    onPrint: (SavedDocumentEntity) -> Unit,
    onDelete: (SavedDocumentEntity) -> Unit,
    onRestore: (SavedDocumentEntity) -> Unit,
    onDeleteForever: (SavedDocumentEntity) -> Unit,
) {
    val inTrash = uiState.folderFilter == FolderFilter.TRASH
    Column(modifier = Modifier.fillMaxSize()) {
        CamScannerSearchPill(
            query = uiState.searchQuery,
            onQueryChange = onSearchQueryChange,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        FoldersAndTagsBar(
            uiState = uiState,
            onSelectAllDocs = onSelectAllDocs,
            onSelectFavorites = onSelectFavorites,
            onSelectTrash = onSelectTrash,
            onSelectFolder = onSelectFolder,
            onToggleFolderLock = onToggleFolderLock,
            onToggleTag = onToggleTag,
            onNewFolderClick = onNewFolderClick,
            onDeleteFolder = onDeleteFolder
        )

        if (inTrash) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Recycle Bin • Items auto-delete after 30 days",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (uiState.documents.isNotEmpty()) {
                        TextButton(
                            onClick = onEmptyTrashClick,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Empty Trash", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (uiState.documents.isEmpty()) {
            HomeEmptyState(
                inTrash = inTrash,
                onStartScan = onStartScan,
                modifier = Modifier.weight(1f)
            )
        } else if (uiState.isGridView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(uiState.documents, key = { it.id }) { doc ->
                    DocumentGridCard(
                        doc = doc,
                        inTrash = inTrash,
                        searchQuery = uiState.searchQuery,
                        onClick = { if (!inTrash) onOpenDocument(doc) },
                        onToggleFavorite = { onToggleFavorite(doc) },
                        onRename = { onRename(doc) },
                        onEditTags = { onEditTags(doc) },
                        onMoveFolder = { onMoveFolder(doc) },
                        onPrint = { onPrint(doc) },
                        onDelete = { onDelete(doc) },
                        onRestore = { onRestore(doc) },
                        onDeleteForever = { onDeleteForever(doc) },
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(uiState.documents, key = { it.id }) { doc ->
                    DocumentListCard(
                        doc = doc,
                        inTrash = inTrash,
                        searchQuery = uiState.searchQuery,
                        onClick = { if (!inTrash) onOpenDocument(doc) },
                        onToggleFavorite = { onToggleFavorite(doc) },
                        onRename = { onRename(doc) },
                        onEditTags = { onEditTags(doc) },
                        onMoveFolder = { onMoveFolder(doc) },
                        onPrint = { onPrint(doc) },
                        onDelete = { onDelete(doc) },
                        onRestore = { onRestore(doc) },
                        onDeleteForever = { onDeleteForever(doc) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FoldersAndTagsBar(
    uiState: HomeHubUiState,
    onSelectAllDocs: () -> Unit,
    onSelectFavorites: () -> Unit,
    onSelectTrash: () -> Unit,
    onSelectFolder: (FolderEntity) -> Unit,
    onToggleFolderLock: (FolderEntity) -> Unit,
    onToggleTag: (String) -> Unit,
    onNewFolderClick: () -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = uiState.folderFilter == FolderFilter.ALL,
                onClick = onSelectAllDocs,
                label = { Text(stringResource(R.string.home_all_docs)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
            FilterChip(
                selected = uiState.folderFilter == FolderFilter.FAVORITES,
                onClick = onSelectFavorites,
                leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(stringResource(R.string.home_favorites)) }
            )
            uiState.folders.forEach { folder ->
                val selected = uiState.folderFilter == FolderFilter.CUSTOM && uiState.selectedFolderId == folder.id
                FilterChip(
                    selected = selected,
                    onClick = { onSelectFolder(folder) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (folder.isLocked) Icons.Default.Lock else Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (folder.isLocked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    label = { Text(folder.name) },
                    trailingIcon = if (selected) {
                        {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (folder.isLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                    contentDescription = if (folder.isLocked) "Unlock folder" else "Lock folder",
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onToggleFolderLock(folder) }
                                )
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Delete folder",
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onDeleteFolder(folder.id) }
                                )
                            }
                        }
                    } else null
                )
            }
            FilterChip(
                selected = uiState.folderFilter == FolderFilter.TRASH,
                onClick = onSelectTrash,
                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = {
                    val suffix = if (uiState.trashCount > 0) " (${uiState.trashCount})" else ""
                    Text(stringResource(R.string.home_trash) + suffix)
                },
                modifier = Modifier.testTag("chip_trash_bin")
            )
            FilterChip(
                selected = false,
                onClick = onNewFolderClick,
                leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(stringResource(R.string.home_new_folder)) }
            )
        }

        if (uiState.allTags.isNotEmpty() && uiState.folderFilter != FolderFilter.TRASH) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                uiState.allTags.forEach { tag ->
                    FilterChip(
                        selected = uiState.selectedTag == tag,
                        onClick = { onToggleTag(tag) },
                        leadingIcon = { Icon(Icons.Default.Label, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun DocumentGridCard(
    doc: SavedDocumentEntity,
    inTrash: Boolean,
    searchQuery: String = "",
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onEditTags: () -> Unit,
    onMoveFolder: () -> Unit,
    onPrint: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val coverBitmap = remember(doc.coverPath) {
        doc.coverPath?.let { BitmapFactory.decodeFile(it) }
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.85f)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap.asImageBitmap(),
                        contentDescription = doc.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        modifier = Modifier
                            .size(48.dp)
                            .align(Alignment.Center)
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (doc.isLocked) {
                        Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.65f)) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "Password Protected",
                                tint = Color(0xFFFFD54F),
                                modifier = Modifier
                                    .padding(4.dp)
                                    .size(14.dp)
                            )
                        }
                    }
                    Surface(shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.65f)) {
                        Text(
                            text = if (inTrash) "${LibraryRepository.remainingTrashDays(doc.deletedAt)}d left" else "${doc.pageCount}p",
                            color = if (inTrash) Color(0xFFFF8A80) else Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = doc.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (!inTrash) {
                        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(28.dp)) {
                            Icon(
                                if (doc.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                                contentDescription = "Favorite",
                                tint = if (doc.isFavorite) Color(0xFFFFA000) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Options", modifier = Modifier.size(18.dp))
                        }
                        DocumentActionMenu(
                            expanded = menuExpanded,
                            inTrash = inTrash,
                            onDismiss = { menuExpanded = false },
                            onRename = onRename,
                            onEditTags = onEditTags,
                            onMoveFolder = onMoveFolder,
                            onPrint = onPrint,
                            onDelete = onDelete,
                            onRestore = onRestore,
                            onDeleteForever = onDeleteForever,
                        )
                    }
                }
                if (inTrash) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = onRestore, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text("Restore", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(
                            onClick = onDeleteForever,
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Erase", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    Text(
                        text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(doc.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (doc.tagsCsv.isNotBlank()) {
                        Text(
                            text = doc.tagsCsv.split(",").filter { it.isNotBlank() }.joinToString(" ") { "#${it.trim()}" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val snippet = remember(doc.ocrText, searchQuery) {
                        LibraryRepository.extractSearchSnippet(doc, searchQuery)
                    }
                    if (snippet != null) {
                        Text(
                            text = "Text: $snippet",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DocumentListCard(
    doc: SavedDocumentEntity,
    inTrash: Boolean,
    searchQuery: String = "",
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onEditTags: () -> Unit,
    onMoveFolder: () -> Unit,
    onPrint: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val coverBitmap = remember(doc.coverPath) {
        doc.coverPath?.let { BitmapFactory.decodeFile(it) }
    }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp, 70.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                contentAlignment = Alignment.Center
            ) {
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap.asImageBitmap(),
                        contentDescription = doc.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = doc.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (doc.isLocked) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFFFA000), modifier = Modifier.size(14.dp))
                    }
                }
                Text(
                    text = if (inTrash) {
                        "${doc.pageCount} pages • Auto-purges in ${LibraryRepository.remainingTrashDays(doc.deletedAt)}d"
                    } else {
                        "${doc.pageCount} pages • ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(doc.updatedAt))}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!inTrash && doc.tagsCsv.isNotBlank()) {
                    Text(
                        text = doc.tagsCsv.split(",").filter { it.isNotBlank() }.joinToString(" ") { "#${it.trim()}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!inTrash) {
                    val snippet = remember(doc.ocrText, searchQuery) {
                        LibraryRepository.extractSearchSnippet(doc, searchQuery)
                    }
                    if (snippet != null) {
                        Text(
                            text = "Text: $snippet",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (inTrash) {
                IconButton(onClick = onRestore) {
                    Icon(Icons.Default.RestoreFromTrash, contentDescription = "Restore", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDeleteForever) {
                    Icon(Icons.Default.DeleteForever, contentDescription = "Delete forever", tint = MaterialTheme.colorScheme.error)
                }
            } else {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (doc.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (doc.isFavorite) Color(0xFFFFA000) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options")
                    }
                    DocumentActionMenu(
                        expanded = menuExpanded,
                        inTrash = false,
                        onDismiss = { menuExpanded = false },
                        onRename = onRename,
                        onEditTags = onEditTags,
                        onMoveFolder = onMoveFolder,
                        onPrint = onPrint,
                        onDelete = onDelete,
                        onRestore = onRestore,
                        onDeleteForever = onDeleteForever,
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentActionMenu(
    expanded: Boolean,
    inTrash: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onEditTags: () -> Unit,
    onMoveFolder: () -> Unit,
    onPrint: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (inTrash) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.RestoreFromTrash, contentDescription = null) },
                text = { Text("Restore") },
                onClick = { onDismiss(); onRestore() }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                text = { Text("Delete Forever", color = MaterialTheme.colorScheme.error) },
                onClick = { onDismiss(); onDeleteForever() }
            )
        } else {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                text = { Text("Rename") },
                onClick = { onDismiss(); onRename() }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.Label, contentDescription = null) },
                text = { Text("Tags") },
                onClick = { onDismiss(); onEditTags() }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                text = { Text("Move to Folder") },
                onClick = { onDismiss(); onMoveFolder() }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.Print, contentDescription = null) },
                text = { Text("Print") },
                onClick = { onDismiss(); onPrint() }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                text = { Text("Move to Trash", color = MaterialTheme.colorScheme.error) },
                onClick = { onDismiss(); onDelete() }
            )
        }
    }
}

@Composable
internal fun HomeEmptyState(
    inTrash: Boolean,
    onStartScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (inTrash) Icons.Default.DeleteSweep else Icons.Default.CameraAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (inTrash) "Recycle Bin is empty" else stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (inTrash) "Deleted documents stay here for 30 days." else stringResource(R.string.home_empty_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!inTrash) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = onStartScan) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Start Scanning")
            }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(value) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun MoveToFolderDialog(
    folders: List<FolderEntity>,
    currentFolderId: String?,
    onDismiss: () -> Unit,
    onSelectFolder: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to Folder") },
        text = {
            Column {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    text = { Text("No Folder (Root)" + if (currentFolderId == null) " ✓" else "") },
                    onClick = { onSelectFolder(null) }
                )
                folders.forEach { folder ->
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                        text = { Text(folder.name + if (currentFolderId == folder.id) " ✓" else "") },
                        onClick = { onSelectFolder(folder.id) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
