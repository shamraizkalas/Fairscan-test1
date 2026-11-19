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
package org.fairscan.app.ui.screens.camera

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fairscan.app.MainViewModel
import org.fairscan.app.R
import org.fairscan.app.domain.CapturedPage
import org.fairscan.app.domain.Jpeg
import org.fairscan.app.domain.PageMetadata
import org.fairscan.app.domain.Rotation.R0
import org.fairscan.app.ui.Navigation
import org.fairscan.app.ui.Screen
import org.fairscan.app.ui.components.AppOverflowMenu
import org.fairscan.app.ui.components.CameraPermissionState
import org.fairscan.app.ui.components.CommonPageList
import org.fairscan.app.ui.components.CommonPageListState
import org.fairscan.app.ui.components.pageCountText
import org.fairscan.app.ui.dummyNavigation
import org.fairscan.app.ui.fakeDocument
import org.fairscan.app.ui.theme.FairScanTheme
import org.fairscan.imageprocessing.ColorMode
import org.fairscan.imageprocessing.Point
import org.fairscan.imageprocessing.Quad

const val CAPTURED_IMAGE_DISPLAY_DURATION = 1500L
const val BATCH_IMAGE_DISPLAY_DURATION = 600L
const val ANIMATION_DURATION = 200

@Composable
fun CameraScreen(
    viewModel: MainViewModel,
    cameraViewModel: CameraViewModel,
    navigation: Navigation,
    liveAnalysisState: LiveAnalysisState,
    importState: ImportState,
    onImageAnalyzed: (ImageProxy) -> Unit,
    onFinalizePressed: () -> Unit,
    cameraPermission: CameraPermissionState,
    onImportClicked: () -> Unit,
    isOnlineActive: Boolean = false,
    onAnalyzeQr: (suspend (String) -> String)? = null,
) {
    BackHandler { navigation.back() }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val document by viewModel.documentUiModel.collectAsStateWithLifecycle()
    val thumbnailCoords = remember { mutableStateOf(Offset.Zero) }
    var isDebugMode by remember { mutableStateOf(false) }
    val isTorchEnabled by cameraViewModel.isTorchEnabled.collectAsStateWithLifecycle()
    val captureMode by cameraViewModel.captureMode.collectAsStateWithLifecycle()
    val isGridEnabled by cameraViewModel.isGridEnabled.collectAsStateWithLifecycle()
    val timerSeconds by cameraViewModel.timerSeconds.collectAsStateWithLifecycle()
    val isHdEnabled by cameraViewModel.isHdEnabled.collectAsStateWithLifecycle()
    val detectedQrCode by cameraViewModel.detectedQrCode.collectAsStateWithLifecycle()
    val isAutoCaptureEnabled by cameraViewModel.isAutoCaptureEnabled.collectAsStateWithLifecycle()
    val autoCaptureProgress by cameraViewModel.autoCaptureProgress.collectAsStateWithLifecycle()
    var countdownRemaining by remember { mutableIntStateOf(0) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }
    var torchReapplied by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var minZoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoomRatio by remember { mutableFloatStateOf(4f) }
    var exposureIndex by remember { mutableIntStateOf(0) }
    var minExposureIndex by remember { mutableIntStateOf(0) }
    var maxExposureIndex by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val captureController = remember { CameraCaptureController() }
    DisposableEffect(captureController) {
        captureController.onCapabilitiesChanged = { minZ, maxZ, minE, maxE ->
            minZoomRatio = minZ
            maxZoomRatio = maxZ
            zoomRatio = zoomRatio.coerceIn(minZ, maxZ)
            minExposureIndex = minE
            maxExposureIndex = maxE
            exposureIndex = exposureIndex.coerceIn(minE, maxE)
        }
        onDispose {
            captureController.onCapabilitiesChanged = null
            countdownJob?.cancel()
            captureController.shutdown()
            torchReapplied = false
        }
    }
    LaunchedEffect(captureController.cameraControl, isTorchEnabled) {
        captureController.cameraControl?.enableTorch(isTorchEnabled)
    }

    val captureState by cameraViewModel.captureState.collectAsStateWithLifecycle()
    if (captureState is CaptureState.CapturePreview) {
        LaunchedEffect(captureState, captureMode) {
            val waitMs = when (captureMode) {
                CaptureMode.BATCH -> BATCH_IMAGE_DISPLAY_DURATION
                CaptureMode.ID_CARD, CaptureMode.BOOK_SPLIT -> CAPTURED_IMAGE_DISPLAY_DURATION
                else -> 320L
            }
            delay(waitMs)
            cameraViewModel.addProcessedImage()
        }
    }

    var showDetectionError by remember { mutableStateOf(false) }
    LaunchedEffect(captureState) {
        if (captureState is CaptureState.CaptureError) {
            showDetectionError = true
            delay(1000)
            showDetectionError = false
            cameraViewModel.afterCaptureError()
        }
    }

    LaunchedEffect(Unit) {
        cameraViewModel.resetLiveAnalysis()
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraViewModel.cancelImport()
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(document.pageCount()) {
        if (!document.isEmpty()) {
            listState.animateScrollToItem(document.lastIndex())
        }
    }

    val triggerActualCapture = {
        previewView?.bitmap?.let {
            Log.i("FairScan", "Pressed <Capture>")
            cameraViewModel.onCapturePressed(it)
            captureController.takePicture(
                onImageCaptured = { imageProxy, opticalMeasures ->
                    cameraViewModel.onImageCaptured(imageProxy, opticalMeasures)
                }
            )
        }
        Unit
    }

    val onCapture = {
        if (captureMode == CaptureMode.QR_SCAN) {
            // QR scan happens automatically on live frames
        } else if (countdownRemaining > 0) {
            countdownJob?.cancel()
            countdownRemaining = 0
        } else if (timerSeconds > 0) {
            countdownJob?.cancel()
            countdownJob = scope.launch {
                for (sec in timerSeconds downTo 1) {
                    countdownRemaining = sec
                    delay(1000L)
                }
                countdownRemaining = 0
                triggerActualCapture()
            }
        } else {
            triggerActualCapture()
        }
    }

    LaunchedEffect(Unit) {
        cameraViewModel.volumeKeyEvent.collect {
            onCapture()
        }
    }

    LaunchedEffect(Unit) {
        cameraViewModel.autoCaptureTrigger.collect {
            if (captureState is CaptureState.Idle &&
                importState is ImportState.Idle &&
                cameraPermission.isGranted &&
                captureMode != CaptureMode.QR_SCAN &&
                countdownRemaining == 0
            ) {
                triggerActualCapture()
            }
        }
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    CameraScreenScaffold(
        cameraPreview = {
            CameraPreview(
                onImageAnalyzed = {
                    onImageAnalyzed(it)
                    if (!torchReapplied) {
                        captureController.cameraControl?.enableTorch(isTorchEnabled)
                        torchReapplied = true
                    }
                },
                captureController = captureController,
                onPreviewViewReady = { view ->
                    previewView = view
                    captureController.previewView = view
                },
                cameraPermission = cameraPermission,
                onError = { message, throwable -> cameraViewModel.logError(message, throwable) }
            )
        },
        pageListState = CommonPageListState(
            document = document,
            onPageClick = { index -> viewModel.navigateTo(Screen.Main.Document(index)) },
            onPageReorder = { id, index -> viewModel.movePage(id, index) },
            listState = listState,
            showPageNumbers = false,
            onLastItemPosition = { offset -> thumbnailCoords.value = offset },
        ),
        cameraUiState = CameraUiState(
            pageCount = document.pageCount(),
            liveAnalysisState = liveAnalysisState,
            captureState = captureState,
            importState = importState,
            showCaptureError = showDetectionError,
            isLandscape = isLandscape,
            isDebugMode = isDebugMode,
            isTorchEnabled = isTorchEnabled,
            captureMode = captureMode,
            isGridEnabled = isGridEnabled,
            timerSeconds = timerSeconds,
            isHdEnabled = isHdEnabled,
            detectedQrCode = detectedQrCode,
            countdownRemaining = countdownRemaining,
            isAutoCaptureEnabled = isAutoCaptureEnabled,
            autoCaptureProgress = autoCaptureProgress,
            zoomRatio = zoomRatio,
            minZoomRatio = minZoomRatio,
            maxZoomRatio = maxZoomRatio,
            exposureIndex = exposureIndex,
            minExposureIndex = minExposureIndex,
            maxExposureIndex = maxExposureIndex,
        ),
        onCapture = onCapture,
        onFinalizePressed = onFinalizePressed,
        onDebugModeSwitched = { isDebugMode = !isDebugMode },
        onTorchSwitched = { cameraViewModel.setTorchEnabled(!isTorchEnabled) },
        onCaptureModeSelected = { cameraViewModel.setCaptureMode(it) },
        onGridToggled = { cameraViewModel.toggleGrid() },
        onTimerCycled = { cameraViewModel.cycleTimer() },
        onHdToggled = { cameraViewModel.toggleHd() },
        onAutoCaptureToggled = { cameraViewModel.toggleAutoCapture() },
        onZoomChanged = { ratio ->
            zoomRatio = ratio.coerceIn(minZoomRatio, maxZoomRatio)
            captureController.setZoomRatio(zoomRatio)
        },
        onExposureChanged = { index ->
            exposureIndex = index.coerceIn(minExposureIndex, maxExposureIndex)
            captureController.setExposureIndex(exposureIndex)
        },
        onCopyQr = { value ->
            clipboardManager.setText(AnnotatedString(value))
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        },
        onOpenQrUrl = { value ->
            try {
                val url = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.error_no_app), Toast.LENGTH_SHORT).show()
            }
        },
        onDismissQr = { cameraViewModel.dismissQrCode() },
        thumbnailCoords = thumbnailCoords,
        navigation = navigation,
        captureController = captureController,
        isCameraPermissionGranted = cameraPermission.isGranted,
        onRequestCameraPermission = { cameraPermission.request() },
        onImportClicked = onImportClicked,
        isOnlineActive = isOnlineActive,
        onAnalyzeQr = onAnalyzeQr,
    )
}

@Composable
private fun CameraScreenScaffold(
    cameraPreview: @Composable () -> Unit,
    pageListState: CommonPageListState,
    cameraUiState: CameraUiState,
    onCapture: () -> Unit,
    onFinalizePressed: () -> Unit,
    onDebugModeSwitched: () -> Unit,
    onTorchSwitched: () -> Unit,
    onCaptureModeSelected: (CaptureMode) -> Unit,
    onGridToggled: () -> Unit,
    onTimerCycled: () -> Unit,
    onHdToggled: () -> Unit,
    onAutoCaptureToggled: () -> Unit,
    onZoomChanged: (Float) -> Unit,
    onExposureChanged: (Int) -> Unit,
    onCopyQr: (String) -> Unit,
    onOpenQrUrl: (String) -> Unit,
    onDismissQr: () -> Unit,
    thumbnailCoords: MutableState<Offset>,
    navigation: Navigation,
    captureController: CameraCaptureController,
    isCameraPermissionGranted: Boolean,
    onRequestCameraPermission: () -> Unit,
    onImportClicked: () -> Unit,
    isOnlineActive: Boolean = false,
    onAnalyzeQr: (suspend (String) -> String)? = null,
) {
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var showExposurePanel by remember { mutableStateOf(false) }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1000)
            focusPoint = null
        }
    }

    var tapCount by remember { mutableLongStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    val tapThreshold = 500L
    val onPageCountClick = {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTapTime < tapThreshold) {
            tapCount++
            if (tapCount >= 3) {
                onDebugModeSwitched()
                tapCount = 0
            }
        } else {
            tapCount = 1
        }
        lastTapTime = currentTime
    }

    val darkBg = Color(0xFF0B0F12)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(darkBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Dark Studio HUD Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D1216))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = navigation.back) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = Color.White
                    )
                }

                CameraHudToolbar(
                    isTorchEnabled = cameraUiState.isTorchEnabled,
                    isGridEnabled = cameraUiState.isGridEnabled,
                    timerSeconds = cameraUiState.timerSeconds,
                    isHdEnabled = cameraUiState.isHdEnabled,
                    isAutoCaptureEnabled = cameraUiState.isAutoCaptureEnabled,
                    showExposureControl = cameraUiState.maxExposureIndex > cameraUiState.minExposureIndex,
                    isExposurePanelOpen = showExposurePanel,
                    exposureIndex = cameraUiState.exposureIndex,
                    onTorchSwitched = onTorchSwitched,
                    onGridToggled = onGridToggled,
                    onTimerCycled = onTimerCycled,
                    onHdToggled = onHdToggled,
                    onAutoCaptureToggled = onAutoCaptureToggled,
                    onExposurePanelToggled = { showExposurePanel = !showExposurePanel },
                )

                AppOverflowMenu(navigation, iconTint = Color.White)
            }

            // Camera Viewfinder Area
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (cameraUiState.importState is ImportState.Selecting) {
                    // photo picker active
                } else if (cameraUiState.importState is ImportState.Importing) {
                    ImportInProgress(cameraUiState.importState, Modifier.fillMaxSize())
                } else if (!isCameraPermissionGranted) {
                    CameraPermissionRationale(onRequestCameraPermission, Modifier.fillMaxSize())
                } else {
                    CameraPreviewBox(
                        cameraPreview = cameraPreview,
                        cameraUiState = cameraUiState,
                        focusPoint = focusPoint,
                        showExposurePanel = showExposurePanel,
                        onZoomChanged = onZoomChanged,
                        onExposureChanged = onExposureChanged,
                        onCopyQr = onCopyQr,
                        onOpenQrUrl = onOpenQrUrl,
                        onDismissQr = onDismissQr,
                        isOnlineActive = isOnlineActive,
                        onAnalyzeQr = onAnalyzeQr,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    focusPoint = offset
                                    captureController.tapToFocus(offset)
                                    onPageCountClick()
                                }
                            }
                    )
                }
            }

            // Bottom CamScanner Control Deck (Thumbnails + Mode Strip + Shutter Bar)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0E1317))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(top = 8.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (cameraUiState.pageCount > 0) {
                    CommonPageList(
                        state = pageListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    )
                }

                CaptureModeSelectorStrip(
                    selectedMode = cameraUiState.captureMode,
                    onSelectMode = onCaptureModeSelected,
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Import Photos Button
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onImportClicked)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.AddPhotoAlternate,
                                contentDescription = stringResource(R.string.import_photos),
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = stringResource(R.string.import_photos),
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    // Center Shutter Button
                    if (cameraUiState.captureMode != CaptureMode.QR_SCAN && isCameraPermissionGranted) {
                        CaptureButton(
                            onClick = onCapture,
                            modifier = Modifier.testTag("capture_button")
                        )
                    } else {
                        Spacer(Modifier.size(76.dp))
                    }

                    // Done / Pages Button
                    val hasPages = cameraUiState.pageCount > 0
                    Button(
                        onClick = onFinalizePressed,
                        enabled = hasPages,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00B388),
                            contentColor = Color.White,
                            disabledContainerColor = Color.White.copy(alpha = 0.10f),
                            disabledContentColor = Color.White.copy(alpha = 0.45f)
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier.testTag("camera_done_btn")
                    ) {
                        Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (hasPages) "${cameraUiState.pageCount}" else "0",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (cameraUiState.captureState is CaptureState.CapturePreview) {
            val page = cameraUiState.captureState.capturedPage.pageImage.toBitmap()
            CapturedImage(
                image = page.asImageBitmap(),
                thumbnailCoords = thumbnailCoords,
                displayDurationMs = if (cameraUiState.captureMode == CaptureMode.BATCH) {
                    BATCH_IMAGE_DISPLAY_DURATION
                } else {
                    CAPTURED_IMAGE_DISPLAY_DURATION
                }
            )
        }
    }
}

@Composable
fun ImportInProgress(state: ImportState.Importing, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = LocalContext.current.resources.getQuantityString(
                    R.plurals.importing_photos,
                    state.total,
                    state.total
                ),
                color = Color.White
            )

            Spacer(Modifier.height(16.dp))

            if (state.total > 1) {
                LinearProgressIndicator(
                    progress = { state.processed.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CameraPreviewBox(
    cameraPreview: @Composable (() -> Unit),
    cameraUiState: CameraUiState,
    focusPoint: Offset?,
    showExposurePanel: Boolean,
    onZoomChanged: (Float) -> Unit,
    onExposureChanged: (Int) -> Unit,
    onCopyQr: (String) -> Unit,
    onOpenQrUrl: (String) -> Unit,
    onDismissQr: () -> Unit,
    isOnlineActive: Boolean = false,
    onAnalyzeQr: (suspend (String) -> String)? = null,
    modifier: Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CameraPreviewWithOverlay(
            cameraPreview,
            cameraUiState,
            Modifier.align(Alignment.Center)
        )

        if (cameraUiState.isGridEnabled) {
            CameraGridOverlay()
        }

        when (cameraUiState.captureMode) {
            CaptureMode.ID_CARD -> IdCardGuideOverlay(cameraUiState.pageCount)
            CaptureMode.BOOK_SPLIT -> BookSplitOverlay()
            CaptureMode.PPT_BOARD -> ModeBannerOverlay("PPT / Whiteboard: Auto-removes glare & boosts slide text")
            CaptureMode.TEST_PAPER -> ModeBannerOverlay("Test Paper: Auto-erases blue/red/pencil handwriting")
            CaptureMode.QR_SCAN -> QrScannerOverlay(
                detectedQrCode = cameraUiState.detectedQrCode,
                onCopy = onCopyQr,
                onOpenUrl = onOpenQrUrl,
                onDismiss = onDismissQr,
                isOnlineActive = isOnlineActive,
                onAnalyzeQr = onAnalyzeQr,
            )
            else -> Unit
        }

        if (cameraUiState.isAutoCaptureEnabled && cameraUiState.captureMode != CaptureMode.QR_SCAN) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.72f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (cameraUiState.autoCaptureProgress > 0f) {
                            stringResource(R.string.camera_auto_steady)
                        } else {
                            stringResource(R.string.camera_auto_capture)
                        },
                        color = if (cameraUiState.autoCaptureProgress > 0f) Color(0xFF1DE9B6) else Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (cameraUiState.autoCaptureProgress > 0f) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { cameraUiState.autoCaptureProgress },
                            color = Color(0xFF1DE9B6),
                            trackColor = Color.White.copy(alpha = 0.22f),
                            modifier = Modifier.width(160.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
        }

        if (cameraUiState.countdownRemaining > 0) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(
                    text = "${cameraUiState.countdownRemaining}",
                    color = Color.White,
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 34.dp, vertical = 18.dp)
                )
            }
        }

        if (cameraUiState.isDebugMode) {
            MessageBox(cameraUiState.liveAnalysisState.inferenceTime)
        }
        FocusOverlay(focusPoint)

        if (cameraUiState.captureMode != CaptureMode.QR_SCAN) {
            CameraZoomAndExposureBar(
                zoomRatio = cameraUiState.zoomRatio,
                minZoomRatio = cameraUiState.minZoomRatio,
                maxZoomRatio = cameraUiState.maxZoomRatio,
                showExposurePanel = showExposurePanel,
                exposureIndex = cameraUiState.exposureIndex,
                minExposureIndex = cameraUiState.minExposureIndex,
                maxExposureIndex = cameraUiState.maxExposureIndex,
                onZoomChanged = onZoomChanged,
                onExposureChanged = onExposureChanged,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        if (cameraUiState.captureMode == CaptureMode.BATCH && cameraUiState.pageCount > 0) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF00B388).copy(alpha = 0.92f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
            ) {
                Text(
                    text = "Batch: ${cameraUiState.pageCount}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun CameraZoomAndExposureBar(
    zoomRatio: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    showExposurePanel: Boolean,
    exposureIndex: Int,
    minExposureIndex: Int,
    maxExposureIndex: Int,
    onZoomChanged: (Float) -> Unit,
    onExposureChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandZoomSlider by remember { mutableStateOf(false) }
    val presets = listOf(1f, 2f, 3f).filter { it <= maxZoomRatio + 0.05f && it >= minZoomRatio - 0.05f }
    Column(
        modifier = modifier.widthIn(max = 360.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AnimatedVisibility(visible = showExposurePanel && maxExposureIndex > minExposureIndex) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.72f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Exposure,
                        contentDescription = stringResource(R.string.camera_exposure),
                        tint = Color(0xFF1DE9B6),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (exposureIndex > 0) "+$exposureIndex" else "$exposureIndex",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Slider(
                        value = exposureIndex.toFloat(),
                        onValueChange = { onExposureChanged(kotlin.math.round(it).toInt()) },
                        valueRange = minExposureIndex.toFloat()..maxExposureIndex.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF1DE9B6),
                            activeTrackColor = Color(0xFF1DE9B6),
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier.weight(1f).testTag("camera_exposure_slider")
                    )
                }
            }
        }

        AnimatedVisibility(visible = expandZoomSlider && maxZoomRatio > minZoomRatio + 0.1f) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.72f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = String.format(java.util.Locale.US, "%.1fx", zoomRatio),
                        color = Color(0xFF1DE9B6),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Slider(
                        value = zoomRatio.coerceIn(minZoomRatio, maxZoomRatio),
                        onValueChange = onZoomChanged,
                        valueRange = minZoomRatio..maxZoomRatio.coerceAtLeast(minZoomRatio + 0.1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF1DE9B6),
                            activeTrackColor = Color(0xFF1DE9B6),
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier.weight(1f).testTag("camera_zoom_slider")
                    )
                }
            }
        }

        if (maxZoomRatio > minZoomRatio + 0.1f) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.Black.copy(alpha = 0.62f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    presets.forEach { preset ->
                        val selected = kotlin.math.abs(zoomRatio - preset) < 0.22f
                        Surface(
                            onClick = { onZoomChanged(preset.coerceIn(minZoomRatio, maxZoomRatio)) },
                            shape = CircleShape,
                            color = if (selected) Color(0xFF1DE9B6) else Color.White.copy(alpha = 0.14f),
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${preset.toInt()}x",
                                    color = if (selected) Color(0xFF0A1412) else Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Surface(
                        onClick = { expandZoomSlider = !expandZoomSlider },
                        shape = RoundedCornerShape(16.dp),
                        color = if (expandZoomSlider) Color(0xFF1DE9B6).copy(alpha = 0.22f) else Color.Transparent,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("zoom_slider_toggle")
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = String.format(java.util.Locale.US, "%.1fx", zoomRatio),
                                color = Color(0xFF1DE9B6),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraHudToolbar(
    isTorchEnabled: Boolean,
    isGridEnabled: Boolean,
    timerSeconds: Int,
    isHdEnabled: Boolean,
    isAutoCaptureEnabled: Boolean,
    showExposureControl: Boolean,
    isExposurePanelOpen: Boolean,
    exposureIndex: Int,
    onTorchSwitched: () -> Unit,
    onGridToggled: () -> Unit,
    onTimerCycled: () -> Unit,
    onHdToggled: () -> Unit,
    onAutoCaptureToggled: () -> Unit,
    onExposurePanelToggled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.Black.copy(alpha = 0.58f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(
                onClick = onTorchSwitched,
                modifier = Modifier.testTag("hud_torch")
            ) {
                Icon(
                    imageVector = if (isTorchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = stringResource(
                        if (isTorchEnabled) R.string.turn_off_torch else R.string.turn_on_torch
                    ),
                    tint = if (isTorchEnabled) Color(0xFFFFD54F) else Color.White
                )
            }
            IconButton(
                onClick = onGridToggled,
                modifier = Modifier.testTag("hud_grid")
            ) {
                Icon(
                    imageVector = if (isGridEnabled) Icons.Default.GridOn else Icons.Default.GridOff,
                    contentDescription = stringResource(R.string.camera_grid),
                    tint = if (isGridEnabled) Color(0xFF1DE9B6) else Color.White
                )
            }
            TextButtonCompact(
                onClick = onTimerCycled,
                active = timerSeconds > 0,
                modifier = Modifier.testTag("hud_timer")
            ) {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = stringResource(R.string.camera_timer),
                    tint = if (timerSeconds > 0) Color(0xFF1DE9B6) else Color.White,
                    modifier = Modifier.size(18.dp)
                )
                if (timerSeconds > 0) {
                    Spacer(Modifier.width(3.dp))
                    Text("${timerSeconds}s", color = Color(0xFF1DE9B6), style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButtonCompact(
                onClick = onAutoCaptureToggled,
                active = isAutoCaptureEnabled,
                modifier = Modifier.testTag("hud_auto_capture")
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = stringResource(R.string.camera_auto_capture),
                    tint = if (isAutoCaptureEnabled) Color(0xFF1DE9B6) else Color.White,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = stringResource(R.string.camera_auto_capture),
                    color = if (isAutoCaptureEnabled) Color(0xFF1DE9B6) else Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isAutoCaptureEnabled) FontWeight.Bold else FontWeight.Normal
                )
            }
            if (showExposureControl) {
                TextButtonCompact(
                    onClick = onExposurePanelToggled,
                    active = isExposurePanelOpen || exposureIndex != 0,
                    modifier = Modifier.testTag("hud_exposure")
                ) {
                    Icon(
                        imageVector = Icons.Default.Exposure,
                        contentDescription = stringResource(R.string.camera_exposure),
                        tint = if (isExposurePanelOpen || exposureIndex != 0) Color(0xFF1DE9B6) else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    if (exposureIndex != 0) {
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = if (exposureIndex > 0) "+$exposureIndex" else "$exposureIndex",
                            color = Color(0xFF1DE9B6),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            TextButtonCompact(
                onClick = onHdToggled,
                active = isHdEnabled,
                modifier = Modifier.testTag("hud_hd")
            ) {
                Icon(
                    imageVector = Icons.Default.Hd,
                    contentDescription = stringResource(R.string.camera_hd),
                    tint = if (isHdEnabled) Color(0xFF1DE9B6) else Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun TextButtonCompact(
    onClick: () -> Unit,
    active: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (active) Color.White.copy(alpha = 0.14f) else Color.Transparent,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

@Composable
private fun CaptureModeSelectorStrip(
    selectedMode: CaptureMode,
    onSelectMode: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        Pair(CaptureMode.DOCUMENT, R.string.camscanner_doc),
        Pair(CaptureMode.BATCH, R.string.camscanner_batch),
        Pair(CaptureMode.ID_CARD, R.string.camscanner_id_card),
        Pair(CaptureMode.BOOK_SPLIT, R.string.camscanner_book_split),
        Pair(CaptureMode.PPT_BOARD, R.string.camscanner_ppt_mode),
        Pair(CaptureMode.TEST_PAPER, R.string.camscanner_test_paper),
        Pair(CaptureMode.QR_SCAN, R.string.camscanner_qr_scan),
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (mode, labelRes) ->
            val selected = selectedMode == mode
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelectMode(mode) }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .testTag("mode_chip_${mode.name.lowercase()}")
            ) {
                Text(
                    text = stringResource(labelRes),
                    color = if (selected) Color(0xFF00E5B0) else Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(width = 18.dp, height = 3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (selected) Color(0xFF00E5B0) else Color.Transparent)
                )
            }
        }
    }
}

@Composable
private fun CameraGridOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stroke = 1.5f
        val lineColor = Color.White.copy(alpha = 0.42f)
        val thirdW = size.width / 3f
        val thirdH = size.height / 3f
        drawLine(lineColor, Offset(thirdW, 0f), Offset(thirdW, size.height), strokeWidth = stroke)
        drawLine(lineColor, Offset(thirdW * 2f, 0f), Offset(thirdW * 2f, size.height), strokeWidth = stroke)
        drawLine(lineColor, Offset(0f, thirdH), Offset(size.width, thirdH), strokeWidth = stroke)
        drawLine(lineColor, Offset(0f, thirdH * 2f), Offset(size.width, thirdH * 2f), strokeWidth = stroke)
    }
}

@Composable
private fun BookSplitOverlay() {
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val midX = size.width / 2f
            drawLine(
                color = Color(0xFF1DE9B6),
                start = Offset(midX, size.height * 0.12f),
                end = Offset(midX, size.height * 0.76f),
                strokeWidth = 5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f), 0f)
            )
        }
        Surface(
            color = Color.Black.copy(alpha = 0.72f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp)
        ) {
            Text(
                text = "Align book spine on center line (Auto-splits Left & Right)",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun ModeBannerOverlay(message: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = Color.Black.copy(alpha = 0.72f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp)
        ) {
            Text(
                text = message,
                color = Color(0xFF1DE9B6),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun QrScannerOverlay(
    detectedQrCode: String?,
    onCopy: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
    isOnlineActive: Boolean = false,
    onAnalyzeQr: (suspend (String) -> String)? = null,
) {
    val qrScope = rememberCoroutineScope()
    var aiSummary by remember(detectedQrCode) { mutableStateOf<String?>(null) }
    var aiError by remember(detectedQrCode) { mutableStateOf<String?>(null) }
    var isChecking by remember(detectedQrCode) { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 80.dp)
                .size(220.dp)
                .border(3.dp, Color(0xFF1DE9B6), RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
        )
        if (detectedQrCode != null) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 10.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.qr_detected_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = detectedQrCode,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    aiSummary?.let { insight ->
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = insight,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                    aiError?.let { err ->
                        Spacer(Modifier.height(6.dp))
                        Text(err, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onCopy(detectedQrCode) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.qr_copy))
                        }
                        if (onAnalyzeQr != null) {
                            OutlinedButton(
                                onClick = {
                                    aiError = null
                                    isChecking = true
                                    qrScope.launch {
                                        try {
                                            aiSummary = onAnalyzeQr(detectedQrCode)
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            aiError = e.localizedMessage ?: "QR safety check failed."
                                        } finally {
                                            isChecking = false
                                        }
                                    }
                                },
                                enabled = !isChecking,
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (isChecking) "Checking…" else "Smart Check")
                            }
                        }
                        val looksLikeUrl = detectedQrCode.startsWith("http://", true) ||
                            detectedQrCode.startsWith("https://", true) ||
                            detectedQrCode.contains(".")
                        if (looksLikeUrl) {
                            Button(onClick = { onOpenUrl(detectedQrCode) }) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.qr_open_link))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IdCardGuideOverlay(pageCount: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 90.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (pageCount % 2 == 0)
                        "Step 1: Scan Front Side"
                    else
                        "Step 2: Scan Back Side",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.586f)
                    .border(2.5.dp, Color(0xFF1DE9B6), RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.08f))
            )
        }
    }
}

@Composable
private fun CapturedImage(
    image: ImageBitmap,
    thumbnailCoords: MutableState<Offset>,
    displayDurationMs: Long = CAPTURED_IMAGE_DISPLAY_DURATION,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxSize(),
    ) {}

    var isAnimating by remember { mutableStateOf(false) }
    LaunchedEffect(image, displayDurationMs) {
        delay((displayDurationMs - ANIMATION_DURATION).coerceAtLeast(100L))
        isAnimating = true
    }
    var targetOffsetX by remember { mutableFloatStateOf(0f) }
    var targetOffsetY by remember { mutableFloatStateOf(0f) }

    val transition = updateTransition(targetState = isAnimating, label = "captureAnimation")
    val tween = tween<Float>(durationMillis = ANIMATION_DURATION)
    val offsetX by transition.animateFloat({ tween }, "offsetX") { if (it) targetOffsetX else 0f }
    val offsetY by transition.animateFloat({ tween }, "offsetY") { if (it) targetOffsetY else 0f }
    val scale by transition.animateFloat({ tween }, "scale") { if (it) 0.3f else 1f }

    val density = LocalDensity.current
    Box(
        contentAlignment = Alignment.BottomStart,
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                val centerX = bounds.left + bounds.width / 2
                val centerY = bounds.top + bounds.height / 2
                with(density) {
                    targetOffsetX = thumbnailCoords.value.x - centerX
                    targetOffsetY = thumbnailCoords.value.y - centerY
                }
            }
    ) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .graphicsLayer {
                    translationX = offsetX
                    translationY = offsetY
                    scaleX = scale
                    scaleY = scale
                }
        )
    }
}

@Composable
fun CaptureButton(onClick: () -> Unit, modifier: Modifier) {
    val ringColor = Color(0xFF00D4A1)
    Box(
        modifier = modifier
            .size(76.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .border(width = 4.dp, color = ringColor, shape = CircleShape)
        )
        Box(
            modifier = Modifier
                .size(58.dp)
                .background(color = Color.White, shape = CircleShape)
        )
    }
}

@Composable
private fun CameraPreviewWithOverlay(
    cameraPreview: @Composable () -> Unit,
    cameraUiState: CameraUiState,
    modifier: Modifier,
) {
    val captureState = cameraUiState.captureState

    var showShutter by remember { mutableStateOf(false) }
    LaunchedEffect(captureState.frozenImage) {
        if (captureState.frozenImage != null) {
            showShutter = true
            delay(200)
            showShutter = false
        }
    }

    Box(
        modifier = if (cameraUiState.isLandscape) {
            modifier.fillMaxHeight().aspectRatio(4f / 3f)
        } else {
            modifier.fillMaxWidth().aspectRatio(3f / 4f)
        }
    ) {
        cameraPreview()
        if (cameraUiState.captureMode != CaptureMode.QR_SCAN) {
            AnalysisOverlay(cameraUiState.liveAnalysisState, cameraUiState.isDebugMode)
        }
        captureState.frozenImage?.let {
            Image(bitmap = it.asImageBitmap(), contentDescription = null)
        }
        if (showShutter) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.6f))
            )
        }
        if (cameraUiState.showCaptureError) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.error_occurred),
                    color = Color.White,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun FocusOverlay(focusPoint: Offset?) {
    if (focusPoint == null) return
    val scale = remember(focusPoint) { Animatable(1.38f) }
    val alpha = remember(focusPoint) { Animatable(1f) }
    LaunchedEffect(focusPoint) {
        scale.animateTo(1f, animationSpec = tween(durationMillis = 220))
        delay(550)
        alpha.animateTo(0f, animationSpec = tween(durationMillis = 230))
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = 38f * scale.value
        val reticleColor = Color(0xFF1DE9B6).copy(alpha = alpha.value)
        drawCircle(
            color = reticleColor,
            radius = radius,
            center = focusPoint,
            style = Stroke(width = 3.5f)
        )
        drawCircle(
            color = reticleColor.copy(alpha = alpha.value * 0.85f),
            radius = 5f,
            center = focusPoint
        )
    }
}

@Composable
fun MessageBox(inferenceTime: Long) {
    Text(
        text = if (inferenceTime == 0L) "" else "Segmentation time: $inferenceTime ms",
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
        color = Color.Gray,
    )
}

@Composable
private fun CameraPermissionRationale(onRequestCameraPermission: () -> Unit, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.camera_permission_rationale))
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onRequestCameraPermission,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(stringResource(R.string.grant_permission))
                }
            }
        }
    }
}

@Preview(name = "Pixel 1", showSystemUi = true, device = Devices.PIXEL)
@Composable
fun CameraScreenPreview() {
    ScreenPreview(CaptureState.Idle)
}

@Preview(showBackground = true, widthDp = 640, heightDp = 320)
@Composable
fun CameraScreenPreviewInLandscapeMode() {
    ScreenPreview(CaptureState.Idle, rotationDegrees = 90f)
}

@Composable
private fun ScreenPreview(
    captureState: CaptureState,
    importState: ImportState = ImportState.Idle,
    rotationDegrees: Float = 0f,
    isCameraPermissionGranted: Boolean = true,
) {
    FairScanTheme {
        val thumbnailCoords = remember { mutableStateOf(Offset.Zero) }
        CameraScreenScaffold(
            cameraPreview = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.DarkGray),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Image(
                        debugImage("uncropped/img01.jpg").toBitmap().asImageBitmap(),
                        modifier = Modifier.rotate(rotationDegrees),
                        contentDescription = null
                    )
                }
            },
            pageListState = CommonPageListState(
                document = fakeDocument(
                    listOf(1, 2).map { "gallica.bnf.fr-bpt6k5530456s-$it" }.toImmutableList(),
                    LocalContext.current
                ),
                onPageClick = {},
                onPageReorder = { _, _ -> },
                listState = LazyListState(),
                showPageNumbers = false,
            ),
            cameraUiState = CameraUiState(
                pageCount = 4,
                LiveAnalysisState(),
                captureState,
                importState,
                false,
                rotationDegrees > 0,
                false,
                false
            ),
            onCapture = {},
            onFinalizePressed = {},
            onDebugModeSwitched = {},
            onTorchSwitched = {},
            onCaptureModeSelected = {},
            onGridToggled = {},
            onTimerCycled = {},
            onHdToggled = {},
            onAutoCaptureToggled = {},
            onZoomChanged = {},
            onExposureChanged = {},
            onCopyQr = {},
            onOpenQrUrl = {},
            onDismissQr = {},
            thumbnailCoords = thumbnailCoords,
            navigation = dummyNavigation(),
            captureController = CameraCaptureController(),
            isCameraPermissionGranted = isCameraPermissionGranted,
            onRequestCameraPermission = {},
            onImportClicked = {},
        )
    }
}

@Composable
private fun debugImage(imgName: String): Jpeg {
    val context = LocalContext.current
    return Jpeg(context.assets.open(imgName).readBytes())
}
