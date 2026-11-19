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
package org.fairscan.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.Q
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.net.toFile
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fairscan.app.cloud.FreeMlKitEngine
import org.fairscan.app.data.FileLogger
import org.fairscan.app.data.ImageRepository
import org.fairscan.app.ui.Navigation
import org.fairscan.app.ui.Screen
import org.fairscan.app.ui.components.rememberCameraPermissionState
import org.fairscan.app.ui.screens.LibrariesScreen
import org.fairscan.app.ui.screens.ResumeScanScreen
import org.fairscan.app.ui.screens.about.AboutEvent
import org.fairscan.app.ui.screens.about.AboutScreen
import org.fairscan.app.ui.screens.about.AboutViewModel
import org.fairscan.app.ui.screens.about.createEmailWithImageIntent
import org.fairscan.app.ui.screens.camera.CameraEvent
import org.fairscan.app.ui.screens.camera.CameraScreen
import org.fairscan.app.ui.screens.camera.CameraViewModel
import org.fairscan.app.ui.screens.crop.CropInitState
import org.fairscan.app.ui.screens.crop.CropScreen
import org.fairscan.app.ui.screens.document.DocumentScreen
import org.fairscan.app.ui.screens.export.ExportActions
import org.fairscan.app.ui.screens.export.ExportEvent
import org.fairscan.app.ui.screens.export.ExportResult
import org.fairscan.app.ui.screens.export.ExportScreenWrapper
import org.fairscan.app.ui.screens.export.ExportViewModel
import org.fairscan.app.camscanner.AppVaultLockGate
import org.fairscan.app.camscanner.FileConverterDialog
import org.fairscan.app.camscanner.FileConverterViewModel
import org.fairscan.app.camscanner.PageEditState
import org.fairscan.app.camscanner.PdfToolkitDialog
import org.fairscan.app.camscanner.PdfToolkitTool
import org.fairscan.app.camscanner.PdfToolkitViewModel
import org.fairscan.app.ui.screens.home.HomeHubScreen
import org.fairscan.app.ui.screens.home.HomeHubViewModel
import org.fairscan.app.ui.screens.settings.OcrLanguagesScreen
import org.fairscan.app.ui.screens.settings.SettingsScreen
import org.fairscan.app.ui.screens.settings.SettingsUiState
import org.fairscan.app.ui.screens.settings.SettingsViewModel
import org.fairscan.app.ui.theme.FairScanTheme
import org.opencv.android.OpenCVLoader
import java.io.File

class MainActivity : FragmentActivity() {

    private lateinit var cameraViewModel: CameraViewModel
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContainer = (application as FairScanApp).appContainer
        appContainer.cloudAuthAndSyncManager.startAfterAppCheckInitialized()
        initLibraries()

        val launchMode = resolveLaunchMode(intent)

        val sessionViewModel: SessionViewModel by viewModels {
            SessionViewModelFactory(
                application = application,
                launchMode = launchMode,
                appContainer = appContainer
            )
        }

        val imageRepository = sessionViewModel.imageRepository
        viewModel = viewModels<MainViewModel> {
            appContainer.viewModelFactory {
                MainViewModel(
                    imageRepository = imageRepository,
                    logger = appContainer.logger,
                    launchMode = launchMode,
                    libraryRepository = appContainer.libraryRepository,
                    sessionDir = sessionViewModel.sessionDir,
                    ocrService = appContainer.ocrService,
                )
            }
        }.value

        val homeHubViewModel: HomeHubViewModel by viewModels {
            appContainer.homeHubViewModelFactory
        }
        val pdfToolkitViewModel: PdfToolkitViewModel by viewModels {
            appContainer.pdfToolkitViewModelFactory
        }
        val fileConverterViewModel: FileConverterViewModel by viewModels {
            appContainer.fileConverterViewModelFactory
        }
        val exportViewModel: ExportViewModel by viewModels {
            appContainer.viewModelFactory {
                ExportViewModel(appContainer, imageRepository)
            }
        }
        val aboutViewModel: AboutViewModel by viewModels {
            appContainer.viewModelFactory {
                AboutViewModel(appContainer, imageRepository)
            }
        }
        cameraViewModel = viewModels<CameraViewModel> { appContainer.cameraViewModelFactory }.value

        val settingsViewModel: SettingsViewModel
            by viewModels { appContainer.settingsViewModelFactory }
        lifecycleScope.launch(Dispatchers.IO) {
            exportViewModel.cleanUpOldPreparedFiles(1000 * 3600)
        }
        enableEdgeToEdge()
        setContent {
            val logger = appContainer.logger
            val context = LocalContext.current
            val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
            val liveAnalysisState by cameraViewModel.liveAnalysisState.collectAsStateWithLifecycle()
            val importState by cameraViewModel.importState.collectAsStateWithLifecycle()
            val document by viewModel.documentUiModel.collectAsStateWithLifecycle()
            val documentUiState by viewModel.documentUiState.collectAsStateWithLifecycle()
            val cropInitialState by viewModel.cropInitState.collectAsStateWithLifecycle()
            val exportUiState by exportViewModel.uiState.collectAsStateWithLifecycle()
            val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            val homeHubUiState by homeHubViewModel.uiState.collectAsStateWithLifecycle()
            val pdfToolkitUiState by pdfToolkitViewModel.uiState.collectAsStateWithLifecycle()
            val fileConverterUiState by fileConverterViewModel.uiState.collectAsStateWithLifecycle()
            val savedSignatures by homeHubViewModel.savedSignatures.collectAsStateWithLifecycle()
            val activeDocTitle by viewModel.activeDocumentTitle.collectAsStateWithLifecycle()
            val activePageCount by viewModel.activePageCount.collectAsStateWithLifecycle()
            val pendingOverlayAsset by viewModel.pendingOverlayAsset.collectAsStateWithLifecycle()
            val pageEditStates by viewModel.pageEditStates.collectAsStateWithLifecycle()
            val hybridConnectionState by appContainer.hybridModeController.state.collectAsStateWithLifecycle()
            val cloudSyncState by appContainer.cloudAuthAndSyncManager.uiState.collectAsStateWithLifecycle()
            val cameraPermission = rememberCameraPermissionState()
            val driveConsentLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                appContainer.cloudAuthAndSyncManager.handleDriveAuthorizationResult(
                    activity = this@MainActivity,
                    resultCode = result.resultCode,
                    data = result.data,
                )
            }
            val mlKitScannerLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                if (result.resultCode == RESULT_OK) {
                    val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                    val pages = scanResult?.pages.orEmpty()
                    if (pages.isNotEmpty()) {
                        lifecycleScope.launch {
                            val bitmaps = withContext(Dispatchers.IO) {
                                pages.mapNotNull { page ->
                                    runCatching {
                                        contentResolver.openInputStream(page.imageUri)?.use { stream ->
                                            BitmapFactory.decodeStream(stream)
                                        }
                                    }.getOrNull()
                                }
                            }
                            if (bitmaps.isNotEmpty()) {
                                viewModel.importBitmapsAsDocument("ML Kit Scan", bitmaps)
                            } else {
                                showToast("Could not decode scanned pages")
                            }
                        }
                    }
                }
            }
            LaunchedEffect(Unit) {
                appContainer.cloudAuthAndSyncManager.registerDriveConsentLauncher { sender ->
                    driveConsentLauncher.launch(IntentSenderRequest.Builder(sender).build())
                }
                appContainer.cloudAuthAndSyncManager.attemptAutoSignInIfOptedIn(this@MainActivity)
                pdfToolkitViewModel.registerImportHandler { title, bitmaps ->
                    viewModel.importBitmapsAsDocument(title, bitmaps)
                }
                pdfToolkitViewModel.registerEditableProjectHandler { title, zipFile ->
                    viewModel.importEditablePdfProject(title, zipFile)
                }
                fileConverterViewModel.registerImportHandler { title, bitmaps ->
                    viewModel.importBitmapsAsDocument(title, bitmaps)
                }
            }
            CollectCameraEvents(cameraViewModel, viewModel)
            CollectExportEvents(context, exportViewModel)
            CollectAboutEvents(context, aboutViewModel, imageRepository)

            FairScanTheme {
                var isAppVaultLocked by rememberSaveable {
                    mutableStateOf(appContainer.securityVaultManager.isAppLockEnabled())
                }
                val navigation = navigation(viewModel, launchMode)
                val onExportClick = if (launchMode == LaunchMode.EXTERNAL_SCAN_TO_PDF) {
                    {
                        lifecycleScope.launch {
                            try {
                                val result = exportViewModel.generatePdfForExternalCall()
                                sendActivityResult(result)
                            } catch (e: Exception) {
                                val message = "Export failed"
                                showToast(message)
                                appContainer.logger.e("MainActivity", message, e)
                                return@launch
                            }
                            viewModel.startNewDocument()
                            finish()
                        }
                        Unit
                    }
                } else {
                    navigation.toExportScreen
                }

                if (isAppVaultLocked) {
                    AppVaultLockGate(
                        vaultManager = appContainer.securityVaultManager,
                        onUnlocked = { isAppVaultLocked = false },
                    )
                } else when (currentScreen) {
                    null -> {
                        // waiting to load pages to get an initial screen
                    }
                    is Screen.Main.HomeHub -> {
                        val pickFromHome = rememberLauncherForActivityResult(
                            ActivityResultContracts.GetMultipleContents()
                        ) { uris ->
                            if (uris.isNotEmpty()) {
                                cameraViewModel.importPhotos(uris)
                                navigation.toCameraScreen()
                            }
                        }
                        val pickPdfFromHome = rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocument()
                        ) { uri ->
                            if (uri != null) {
                                pdfToolkitViewModel.handlePickedPdfUris(listOf(uri), PdfToolkitTool.IMPORT)
                            }
                        }
                        HomeHubScreen(
                            uiState = homeHubUiState,
                            activeSessionPageCount = activePageCount,
                            navigation = navigation,
                            onResumeActiveSession = {
                                if (activePageCount > 0) {
                                    navigation.toDocumentScreen()
                                }
                            },
                            onDiscardActiveSession = { viewModel.startNewDocument() },
                            onLaunchCaptureMode = { mode ->
                                cameraViewModel.setCaptureMode(mode)
                                navigation.toCameraScreen()
                            },
                            onImportPhotos = { pickFromHome.launch("image/*") },
                            onImportPdf = { pickPdfFromHome.launch(arrayOf("application/pdf")) },
                            onOpenPdfTools = { pdfToolkitViewModel.openHub() },
                            onOpenFileConverter = { tool -> fileConverterViewModel.openHub(tool) },
                            onOpenDocument = { doc -> viewModel.openSavedDocument(doc.id) },
                            onSearchQueryChange = homeHubViewModel::setSearchQuery,
                            onSelectAllDocs = homeHubViewModel::selectAllDocs,
                            onSelectFavorites = homeHubViewModel::selectFavorites,
                            onSelectTrash = homeHubViewModel::selectTrash,
                            onSelectFolder = homeHubViewModel::selectFolder,
                            onUnlockFolder = homeHubViewModel::unlockFolderAndSelect,
                            onToggleFolderLock = homeHubViewModel::toggleFolderLock,
                            onToggleTag = homeHubViewModel::toggleTag,
                            onSetSortOrder = homeHubViewModel::setSortOrder,
                            onToggleGridView = homeHubViewModel::toggleGridView,
                            onCreateFolder = homeHubViewModel::createFolder,
                            onDeleteFolder = homeHubViewModel::deleteFolder,
                            onRenameDocument = homeHubViewModel::renameDocument,
                            onMoveDocument = homeHubViewModel::moveDocumentToFolder,
                            onUpdateTags = homeHubViewModel::updateTags,
                            onToggleFavorite = homeHubViewModel::toggleFavorite,
                            onDeleteDocument = homeHubViewModel::deleteDocument,
                            onRestoreDocument = homeHubViewModel::restoreFromTrash,
                            onPermanentlyDeleteDocument = homeHubViewModel::permanentlyDeleteDocument,
                            onEmptyTrash = homeHubViewModel::emptyTrash,
                            savedSignatures = savedSignatures,
                            onSaveAsset = { bmp, label, type ->
                                viewModel.saveSignatureToLibrary(bmp, label, type)
                            },
                            onDeleteAsset = { sig -> viewModel.deleteSavedSignature(sig) },
                            onApplyAssetToDocument = { docId, bmp, label, type ->
                                viewModel.applySavedAssetToDocument(docId, bmp, label, type)
                            },
                            securityVaultManager = appContainer.securityVaultManager,
                            libraryRepository = appContainer.libraryRepository,
                            onOpenBitmapInStudio = { title, bmp ->
                                viewModel.importBitmapsAsDocument(title, listOf(bmp))
                            },
                            hybridState = hybridConnectionState,
                            cloudSyncState = cloudSyncState,
                            cloudAiGateway = appContainer.cloudAiGateway,
                            onSetHybridPreference = appContainer.hybridModeController::setPreference,
                            onToggleAiDocStructure = appContainer.hybridModeController::setAiStructuredConversion,
                            onToggleMlKitSegmentation = appContainer.hybridModeController::setUseMlKitSegmentation,
                            onRefreshNetwork = appContainer.hybridModeController::refreshConnectivity,
                            onLaunchMlKitScanner = {
                                FreeMlKitEngine.getDocumentScannerStartIntent(this@MainActivity)
                                    .addOnSuccessListener { sender ->
                                        mlKitScannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
                                    }
                                    .addOnFailureListener { e ->
                                        showToast(e.localizedMessage ?: "Google ML Kit Document Scanner unavailable on this device.")
                                    }
                            },
                            onGoogleSignIn = {
                                appContainer.cloudAuthAndSyncManager.signInWithGoogle(this@MainActivity)
                            },
                            onSyncNow = { appContainer.cloudAuthAndSyncManager.syncNow(this@MainActivity) },
                            onRestoreFromDrive = {
                                appContainer.cloudAuthAndSyncManager.restoreFromDrive(this@MainActivity)
                            },
                            onExportVisibleDrive = {
                                appContainer.cloudAuthAndSyncManager.exportVisibleBackupToDrive(this@MainActivity)
                            },
                            onSignOut = { appContainer.cloudAuthAndSyncManager.signOut() },
                            onDismissSyncMessage = { appContainer.cloudAuthAndSyncManager.clearMessages() },
                            onSavePersonalApiKey = appContainer.hybridModeController::setPersonalGeminiApiKey,
                            onClearPersonalApiKey = { appContainer.hybridModeController.setPersonalGeminiApiKey("") },
                        )
                        PdfToolkitDialog(
                            uiState = pdfToolkitUiState,
                            savedDocuments = homeHubUiState.documents,
                            viewModel = pdfToolkitViewModel,
                        )
                        FileConverterDialog(
                            uiState = fileConverterUiState,
                            savedDocuments = homeHubUiState.documents,
                            viewModel = fileConverterViewModel,
                        )
                    }
                    is Screen.Main.ResumeScan -> {
                        ResumeScanScreen(
                            currentDocument = documentUiState,
                            onResumeScan = navigation.toCameraScreen,
                            onStartNewScan = {
                                viewModel.startNewDocument()
                                navigation.toCameraScreen()
                            }
                        )
                    }
                    is Screen.Main.Camera -> {
                        val pickMultiple = rememberLauncherForActivityResult(
                            ActivityResultContracts.GetMultipleContents()
                        ) { uris ->
                            cameraViewModel.importPhotos(uris)
                        }
                        CameraScreen(
                            viewModel = viewModel,
                            cameraViewModel = cameraViewModel,
                            navigation = navigation,
                            liveAnalysisState = liveAnalysisState,
                            importState = importState,
                            onImageAnalyzed = { image -> cameraViewModel.liveAnalysis(image) },
                            onFinalizePressed = onExportClick,
                            cameraPermission = cameraPermission,
                            onImportClicked = {
                                cameraViewModel.onImportClicked()
                                pickMultiple.launch("image/*")
                            },
                            isOnlineActive = hybridConnectionState.isOnlineActive,
                            onAnalyzeQr = { payload -> appContainer.cloudAiGateway.analyzeQrPayload(payload) },
                        )
                    }
                    is Screen.Main.EditImage -> {
                        val cropPageId = (cropInitialState as? CropInitState.Ready)?.pageId
                            ?: documentUiState.currentPage?.key?.pageId
                            ?: ""
                        CropScreen(
                            pageId = cropPageId,
                            initState = cropInitialState,
                            navigation = navigation,
                            onUpdatePageQuad = { quad -> viewModel.setCurrentPageUserQuad(quad) },
                            onAutoDetectQuad = { bmp -> viewModel.runOpenCvAutoDetectForCrop(bmp) },
                        )
                    }
                    is Screen.Main.Document -> {
                        val currentPageId = documentUiState.currentPage?.key?.pageId
                        val currentEditState = currentPageId?.let { pageEditStates[it] } ?: PageEditState()
                        DocumentScreen(
                            uiState = documentUiState,
                            navigation = navigation,
                            onExportClick = onExportClick,
                            onDeleteImage = { viewModel.deleteCurrentPage() },
                            onRotateImage = { clockwise -> viewModel.rotateCurrentPage(clockwise) },
                            onColorModeSelected = { viewModel.setCurrentPageColorMode(it) },
                            onCropClick = { viewModel.onClickOnCropButton() },
                            onPageReorder = { id, newIndex -> viewModel.movePage(id, newIndex) },
                            onPageSelected = viewModel::onPageSelected,
                            onApplyBitmap = { bitmap -> viewModel.applyBitmapToCurrentPage(bitmap) },
                            onRunOcr = { bitmap -> appContainer.ocrService.extractFullText(bitmap) },
                            onRunOcrDetailed = { bitmap -> appContainer.ocrService.extractTextAndBoxes(bitmap) },
                            onExportCsv = { csvContent, suggestedFilename ->
                                lifecycleScope.launch {
                                    val csvFile = withContext(Dispatchers.IO) {
                                        appContainer.preparationDir.mkdirs()
                                        val safeName = suggestedFilename
                                            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                            .ifBlank { "table_export.csv" }
                                            .let { if (it.endsWith(".csv", ignoreCase = true)) it else "$it.csv" }
                                        File(appContainer.preparationDir, safeName).apply {
                                            writeText(csvContent, Charsets.UTF_8)
                                        }
                                    }
                                    shareSingleFile(csvFile, "text/csv")
                                }
                            },
                            onMergeIdCard = { front, back, remove ->
                                viewModel.mergeIdCardPages(front, back, remove)
                            },
                            savedSignatures = savedSignatures,
                            onSaveSignatureToLibrary = { bmp, label, type ->
                                viewModel.saveSignatureToLibrary(bmp, label, type)
                            },
                            onDeleteSavedSignature = { sig -> viewModel.deleteSavedSignature(sig) },
                            folders = homeHubUiState.folders,
                            activeDocumentTitle = activeDocTitle,
                            onSaveToLibrary = { title, folderId, tagsCsv ->
                                viewModel.saveActiveDocumentToLibrary(
                                    title = title,
                                    folderId = folderId,
                                    tagsCsv = tagsCsv
                                )
                            },
                            initialPendingOverlay = pendingOverlayAsset,
                            onConsumePendingOverlay = { viewModel.clearPendingOverlayAsset() },
                            libraryRepository = appContainer.libraryRepository,
                            onLoadAllPageBitmaps = { viewModel.loadAllCurrentPageBitmaps() },
                            currentPageEditState = currentEditState,
                            pageEditStates = pageEditStates,
                            resolveOverlayFile = imageRepository::overlayAssetFile,
                            onAddOverlayLayer = viewModel::addOverlayToCurrentPage,
                            onUpdateOverlayLayer = viewModel::updateOverlayOnCurrentPage,
                            onRemoveOverlayLayer = viewModel::removeOverlayFromCurrentPage,
                            onLoadOverlayForEditing = viewModel::loadOverlayForEditing,
                            onRenderPageWithoutLayers = viewModel::renderCurrentPageWithoutLayers,
                            onLoadCleanCurrentBitmap = viewModel::loadCleanCurrentPageBitmap,
                            onSetFilterLayer = viewModel::setFilterOnCurrentPage,
                            onApplyFilterToAllPages = { preset, adj ->
                                viewModel.applyFilterToPages(null, preset, adj)
                            },
                            onApplyFilterToPages = { ids, preset, adj ->
                                viewModel.applyFilterToPages(ids.toSet(), preset, adj)
                            },
                            onSetWatermarkLayer = viewModel::setWatermarkOnCurrentPage,
                            onApplyWatermarkToAllPages = viewModel::applyWatermarkToAllPages,
                            onAddCleanupEffect = viewModel::addCleanupEffectToCurrentPage,
                            onApplyCleanupToAllPages = viewModel::applyCleanupToAllPages,
                            onRemoveCleanupEffect = viewModel::removeCleanupEffectFromCurrentPage,
                            onSetAnnotationsLayer = viewModel::setAnnotationsOnCurrentPage,
                            onRemoveAnnotationLayer = viewModel::removeAnnotationFromCurrentPage,
                            onSetEraserStrokesLayer = viewModel::setEraserStrokesOnCurrentPage,
                            onRemoveEraserStrokeLayer = viewModel::removeEraserStrokeFromCurrentPage,
                            onClearAllEditsOnPage = viewModel::clearAllEditsOnCurrentPage,
                            onBatchRotatePages = { ids -> viewModel.rotatePages(ids, clockwise = true) },
                            onBatchDeletePages = { ids -> viewModel.deletePages(ids) },
                            onExtractSelectedPdf = { ids ->
                                lifecycleScope.launch {
                                    val outPdf = withContext(Dispatchers.IO) {
                                        appContainer.preparationDir.mkdirs()
                                        val baseName = activeDocTitle.ifBlank { "extracted_pages" }
                                            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                        File(
                                            appContainer.preparationDir,
                                            "${baseName}_${System.currentTimeMillis()}.pdf"
                                        )
                                    }
                                    val ok = viewModel.extractPagesToPdf(ids, outPdf)
                                    if (ok) {
                                        share(ExportResult.Pdf(outPdf, outPdf.length(), ids.size))
                                    } else {
                                        showToast(getString(R.string.error_occurred))
                                    }
                                }
                            },
                            hybridState = hybridConnectionState,
                            cloudAiGateway = appContainer.cloudAiGateway,
                            onSaveOcrTextToLibrary = viewModel::saveOcrTextForActiveDocument,
                        )
                    }
                    is Screen.Main.Export -> {
                        ExportScreenWrapper(
                            navigation = navigation,
                            uiState = exportUiState,
                            currentDocument = document,
                            exportActions = ExportActions(
                                prepareExportIfNeeded = exportViewModel::prepareExportIfNeeded,
                                setFilename = exportViewModel::setFilename,
                                setFormat = exportViewModel::setStudioFormat,
                                setPageSize = exportViewModel::setPageSize,
                                setCompression = exportViewModel::setCompression,
                                setPageNumberStyle = exportViewModel::setPageNumberStyle,
                                setHeaderText = exportViewModel::setHeaderText,
                                setFooterText = exportViewModel::setFooterText,
                                applyHeaderFooter = exportViewModel::applyHeaderFooter,
                                setPasswordInput = exportViewModel::setPasswordInput,
                                applyPassword = exportViewModel::applyPassword,
                                share = { exportViewModel.onShareClicked() },
                                save = { exportViewModel.onSaveClicked() },
                                open = { item -> openUri(item.uri, item.format.mimeType, logger) },
                                cancelPreparationJob = exportViewModel::cancelPreparationJob,
                            ),
                            onCloseScan = {
                                exportViewModel.resetFilename()
                                viewModel.startNewDocument()
                                viewModel.navigateTo(Screen.Main.HomeHub)
                            },
                            onExportSavedToLibrary = { filename, format, isLocked, path ->
                                viewModel.saveActiveDocumentToLibrary(
                                    title = filename,
                                    isLocked = isLocked,
                                    lastExportPath = path,
                                    lastExportFormat = format.name
                                )
                            }
                        )
                    }
                    is Screen.Overlay.About -> {
                        LaunchedEffect(Unit) {
                            aboutViewModel.refreshLastCapturedImageState()
                        }
                        val aboutUiState by aboutViewModel.uiState.collectAsStateWithLifecycle()
                        AboutScreen(
                            aboutUiState = aboutUiState,
                            onBack = navigation.back,
                            onCopyLogs = { aboutViewModel.onCopyLogsClicked() },
                            onContactWithLastImageClicked = {
                                aboutViewModel.onContactWithLastImageClicked()
                            },
                            onStartActivity = { intent ->
                                try {
                                    startActivity(intent)
                                } catch (_: ActivityNotFoundException) {
                                    showToast(getString(R.string.error_occurred))
                                }
                            },
                            onViewLibraries = navigation.toLibrariesScreen
                        )
                    }
                    is Screen.Overlay.Libraries -> {
                        LibrariesScreen(onBack = navigation.back)
                    }
                    is Screen.Overlay.Settings -> {
                        SettingsScreenWrapper(settingsUiState, settingsViewModel, navigation, logger)
                    }
                    is Screen.Overlay.OcrLanguages -> {
                        OcrLanguagesScreen(
                            uiState = settingsUiState,
                            onBack = navigation.back,
                            onLanguageClick = settingsViewModel::onLanguageClick,
                            onRemoveLanguage = settingsViewModel::onRemoveLanguage,
                            onCancelOcrDownload = settingsViewModel::cancelOcrDownload,
                        )
                    }
                }
            }
        }
    }

    private fun resolveLaunchMode(intent: Intent?): LaunchMode {
        return when (intent?.action) {
            "org.fairscan.app.action.SCAN_TO_PDF" -> LaunchMode.EXTERNAL_SCAN_TO_PDF
            "android.intent.action.GET_CONTENT" -> LaunchMode.EXTERNAL_SCAN_TO_PDF
            else -> LaunchMode.NORMAL
        }
    }

    private fun showToast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun SettingsScreenWrapper(
        settingsUiState: SettingsUiState,
        settingsViewModel: SettingsViewModel,
        nav: Navigation,
        logger: FileLogger,
    ) {
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    contentResolver.takePersistableUriPermission(uri, flags)
                    settingsViewModel.setExportDirUri(uri.toString())
                } catch (e: Exception) {
                    logger.e("Settings", "Failed to set export dir to $uri", e)
                    showToast(this.getString(R.string.error_file_picker_result))
                }
            }
        }
        LaunchedEffect(Unit) {
            settingsViewModel.refreshExportDirName()
        }
        SettingsScreen(
            settingsUiState,
            onDefaultColorModeChanged = { mode -> settingsViewModel.setDefaultColorMode(mode) },
            onChooseDirectoryClick = {
                try {
                    launcher.launch(null)
                } catch (e: Exception) {
                    val message = getString(R.string.error_file_picker_launch)
                    logger.e("Settings", message, e)
                    showToast(message)
                }
            },
            onResetExportDirClick = { settingsViewModel.setExportDirUri(null) },
            onExportFormatChanged = { format -> settingsViewModel.setExportFormat(format) },
            onExportQualityChanged = { quality -> settingsViewModel.setExportQuality(quality) },
            onDefaultFileNameStyleChanged = settingsViewModel::setDefaultFilenameStyle,
            navigation = nav,
        )
    }

    @Composable
    private fun CollectAboutEvents(
        context: Context,
        aboutViewModel: AboutViewModel,
        imageRepository: ImageRepository,
    ) {
        val clipboard = LocalClipboardManager.current
        val msgCopiedLogs = stringResource(R.string.copied_logs)
        LaunchedEffect(Unit) {
            aboutViewModel.events.collect { event ->
                when (event) {
                    is AboutEvent.CopyLogs -> {
                        clipboard.setText(AnnotatedString(event.logs))
                        showToast(msgCopiedLogs)
                    }
                    is AboutEvent.PrepareEmailWithLastImage -> {
                        val file = imageRepository.lastAddedSourceFile()
                        if (file != null) {
                            startActivity(createEmailWithImageIntent(context, file))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CollectExportEvents(
        context: Context,
        exportViewModel: ExportViewModel,
    ) {
        val storagePermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                exportViewModel.onSaveClicked()
            } else {
                showToast(this.getString(R.string.storage_permission_denied))
            }
        }
        LaunchedEffect(Unit) {
            exportViewModel.events.collect { event ->
                when (event) {
                    ExportEvent.RequestSave -> {
                        checkPermissionThen(storagePermissionLauncher) {
                            exportViewModel.onRequestSave(context)
                        }
                    }
                    is ExportEvent.Share -> {
                        share(event.result)
                    }
                }
            }
        }
    }

    @Composable
    private fun CollectCameraEvents(
        cameraViewModel: CameraViewModel,
        viewModel: MainViewModel,
    ) {
        LaunchedEffect(Unit) {
            cameraViewModel.events.collect { event ->
                when (event) {
                    is CameraEvent.ImageCaptured -> viewModel.handleImageCaptured(event.page, event.openCrop)
                }
            }
        }
    }

    private fun share(result: ExportResult) {
        if (result.files.isEmpty()) return

        val uris = result.files.map(::uriForFile)
        val intent = Intent().apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            type = result.format.mimeType
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        val chooser = Intent.createChooser(intent, getString(R.string.share_document))

        val resolveInfos = packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
        for (info in resolveInfos) {
            val pkg = info.activityInfo.packageName
            for (uri in uris) {
                grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        startActivity(chooser)
    }

    private fun shareSingleFile(file: File, mimeType: String) {
        val uri = uriForFile(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
        }
        val chooser = Intent.createChooser(intent, getString(R.string.share_document))
        val resolveInfos = packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
        for (info in resolveInfos) {
            grantUriPermission(info.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(chooser)
    }

    private fun sendActivityResult(result: ExportResult?) {
        val pdf = result as? ExportResult.Pdf ?: return

        val uri = uriForFile(pdf.file)
        val resultIntent = Intent().apply {
            data = uri
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        setResult(RESULT_OK, resultIntent)
    }

    private fun uriForFile(file: File): Uri {
        return org.fairscan.app.ui.uriForFile(this, file)
    }

    private fun checkPermissionThen(
        requestPermissionLauncher: ManagedActivityResultLauncher<String, Boolean>,
        action: () -> Unit,
    ) {
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (SDK_INT < Q && checkSelfPermission(this, permission) != PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(permission)
        } else {
            action()
        }
    }

    private fun openUri(fileUri: Uri?, mimeType: String, logger: FileLogger) {
        if (fileUri == null) return
        val uriToOpen: Uri =
            if (fileUri.scheme == ContentResolver.SCHEME_CONTENT) {
                fileUri
            } else {
                uriForFile(fileUri.toFile())
            }
        try {
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriToOpen, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, "file", uriToOpen)
            }
            val chooser = Intent.createChooser(openIntent, getString(R.string.open_file)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            showToast(getString(R.string.error_no_app))
        } catch (e: Exception) {
            val errorMessage =
                "Failed to open URI, scheme=${uriToOpen.scheme}, authority=${uriToOpen.authority}"
            logger.e("OpenUri", errorMessage, e)
            showToast(getString(R.string.error_occurred))
        }
    }

    private fun initLibraries() {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)

        if (!OpenCVLoader.initLocal()) {
            Log.e("OpenCV", "Initialization failed")
        } else {
            Log.d("OpenCV", "Initialization successful")
        }
    }

    private fun navigation(viewModel: MainViewModel, launchMode: LaunchMode): Navigation = Navigation(
        toCameraScreen = { viewModel.navigateTo(Screen.Main.Camera) },
        toEditImageScreen = { viewModel.navigateTo(Screen.Main.EditImage) },
        toDocumentScreen = { viewModel.navigateTo(Screen.Main.Document()) },
        toExportScreen = { viewModel.navigateTo(Screen.Main.Export) },
        toAboutScreen = { viewModel.navigateTo(Screen.Overlay.About) },
        toLibrariesScreen = { viewModel.navigateTo(Screen.Overlay.Libraries) },
        toSettingsScreen = if (launchMode == LaunchMode.EXTERNAL_SCAN_TO_PDF) null else {
            { viewModel.navigateTo(Screen.Overlay.Settings) }
        },
        toOcrLanguagesScreen = { viewModel.navigateTo(Screen.Overlay.OcrLanguages) },
        toHomeScreen = { viewModel.navigateTo(Screen.Main.HomeHub) },
        back = {
            val origin = viewModel.currentScreen.value
            viewModel.navigateBack()
            val destination = viewModel.currentScreen.value
            if (destination == origin && launchMode == LaunchMode.EXTERNAL_SCAN_TO_PDF) {
                setResult(RESULT_CANCELED)
                finish()
            }
        },
        shouldDisplayBackButton = {
            viewModel.currentScreen.value !is Screen.Main.HomeHub
        }
    )

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            if (viewModel.currentScreen.value is Screen.Main.Camera) {
                cameraViewModel.onVolumeKeyPressed()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
