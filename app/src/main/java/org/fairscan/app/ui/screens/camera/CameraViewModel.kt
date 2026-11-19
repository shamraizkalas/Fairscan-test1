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

import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fairscan.app.AppContainer
import org.fairscan.app.camscanner.ProFilterEngine
import org.fairscan.app.camscanner.QrScannerHelper
import org.fairscan.app.camscanner.ScanCleanupEngine
import org.fairscan.app.domain.CapturedPage
import org.fairscan.app.domain.ExportQuality
import org.fairscan.app.domain.Jpeg
import org.fairscan.app.domain.PageMetadata
import org.fairscan.app.domain.Rotation
import org.fairscan.app.platform.extractDocumentFromBitmap
import org.fairscan.imageprocessing.ColorMode
import org.fairscan.imageprocessing.ImageSize
import org.fairscan.imageprocessing.Mode
import org.fairscan.imageprocessing.OpticalMeasures
import org.fairscan.imageprocessing.Point
import org.fairscan.imageprocessing.Quad
import org.fairscan.imageprocessing.createQuad
import org.fairscan.imageprocessing.detectDocumentQuad
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException

sealed interface CameraEvent {
    data class ImageCaptured(
        val page: CapturedPage,
        val openCrop: Boolean = false,
    ) : CameraEvent
}

class CameraViewModel(appContainer: AppContainer): ViewModel() {

    private val imageSegmentationService = appContainer.imageSegmentationService
    private val settingsRepository = appContainer.settingsRepository
    private val imageLoader = appContainer.imageLoader
    private val logger = appContainer.logger

    private val _events = MutableSharedFlow<CameraEvent>()
    val events = _events.asSharedFlow()

    private var _liveAnalysisState = MutableStateFlow(LiveAnalysisState())
    val liveAnalysisState: StateFlow<LiveAnalysisState> = _liveAnalysisState.asStateFlow()
    private var quadStabilizer = QuadStabilizer()

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState
    private var importJob: Job? = null

    private val _isTorchEnabled = MutableStateFlow(false)
    val isTorchEnabled: StateFlow<Boolean> = _isTorchEnabled

    private val _captureMode = MutableStateFlow(CaptureMode.DOCUMENT)
    val captureMode: StateFlow<CaptureMode> = _captureMode.asStateFlow()

    private val _isGridEnabled = MutableStateFlow(false)
    val isGridEnabled: StateFlow<Boolean> = _isGridEnabled.asStateFlow()

    private val _timerSeconds = MutableStateFlow(0)
    val timerSeconds: StateFlow<Int> = _timerSeconds.asStateFlow()

    private val _isHdEnabled = MutableStateFlow(false)
    val isHdEnabled: StateFlow<Boolean> = _isHdEnabled.asStateFlow()

    private val _detectedQrCode = MutableStateFlow<String?>(null)
    val detectedQrCode: StateFlow<String?> = _detectedQrCode.asStateFlow()

    private var lastQrCheckTimestamp = 0L

    private val _volumeKeyEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val volumeKeyEvent = _volumeKeyEvent.asSharedFlow()

    private val _isAutoCaptureEnabled = MutableStateFlow(false)
    val isAutoCaptureEnabled: StateFlow<Boolean> = _isAutoCaptureEnabled.asStateFlow()

    private val _autoCaptureProgress = MutableStateFlow(0f)
    val autoCaptureProgress: StateFlow<Float> = _autoCaptureProgress.asStateFlow()

    private val _autoCaptureTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val autoCaptureTrigger = _autoCaptureTrigger.asSharedFlow()

    private var stableAutoFrameCount = 0
    private var lastAutoCaptureTimestamp = 0L

    fun onVolumeKeyPressed() {
        _volumeKeyEvent.tryEmit(Unit)
    }

    fun toggleAutoCapture() {
        _isAutoCaptureEnabled.value = !_isAutoCaptureEnabled.value
        stableAutoFrameCount = 0
        _autoCaptureProgress.value = 0f
    }

    fun setCaptureMode(mode: CaptureMode) {
        _captureMode.value = mode
        if (mode != CaptureMode.QR_SCAN) {
            _detectedQrCode.value = null
        } else {
            resetLiveAnalysis()
        }
    }

    fun toggleGrid() {
        _isGridEnabled.value = !_isGridEnabled.value
    }

    fun cycleTimer() {
        _timerSeconds.value = when (_timerSeconds.value) {
            0 -> 3
            3 -> 5
            else -> 0
        }
    }

    fun toggleHd() {
        _isHdEnabled.value = !_isHdEnabled.value
    }

    fun dismissQrCode() {
        _detectedQrCode.value = null
    }

    fun resetLiveAnalysis() {
        quadStabilizer = QuadStabilizer()
        stableAutoFrameCount = 0
        _autoCaptureProgress.value = 0f
        _liveAnalysisState.value = LiveAnalysisState()
    }

    fun onCapturePressed(frozenImage: Bitmap) {
        _captureState.value = CaptureState.Capturing(frozenImage)
        resetLiveAnalysis()
    }

    private fun onCaptureProcessed(captured: CapturedPage?, extraPages: List<CapturedPage> = emptyList()) {
        val current = _captureState.value
        _captureState.value = when {
            current is CaptureState.Capturing && captured != null ->
                CaptureState.CapturePreview(current.frozenImage, captured, extraPages)
            current is CaptureState.Capturing ->
                CaptureState.CaptureError(current.frozenImage)
            else -> CaptureState.Idle
        }
    }

    fun liveAnalysis(imageProxy: ImageProxy) {
        if (_captureState.value !is CaptureState.Idle || _importState.value !is ImportState.Idle) {
            imageProxy.close()
            return
        }

        if (_captureMode.value == CaptureMode.QR_SCAN) {
            val now = System.currentTimeMillis()
            if (now - lastQrCheckTimestamp < 350L || _detectedQrCode.value != null) {
                imageProxy.close()
                return
            }
            lastQrCheckTimestamp = now
            viewModelScope.launch {
                try {
                    val decoded = withContext(Dispatchers.Default) {
                        val bmp = imageProxy.toBitmap()
                        QrScannerHelper.detectQrFromBitmap(bmp)
                    }
                    if (!decoded.isNullOrBlank()) {
                        _detectedQrCode.value = decoded
                    }
                } catch (e: Exception) {
                    logger.e("QR", "QR scan frame failed", e)
                } finally {
                    imageProxy.close()
                }
            }
            return
        }

        viewModelScope.launch {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val result = withContext(Dispatchers.IO) {
                imageSegmentationService.runSegmentationAndReturn(imageProxy.toBitmap())
            }

            result?.let {
                val segmentation = result.segmentation
                val maskSize = segmentation.maskSize()
                val originalSize = ImageSize(imageProxy.width, imageProxy.height)
                val rawQuad = withContext(Dispatchers.Default) {
                    detectDocumentQuad(segmentation, originalSize, Mode.LIVE_ANALYSIS)
                        ?.rotate90(rotationDegrees / 90, maskSize)
                }
                val binaryMaskProvider = { ->
                    var binaryMask: Bitmap = segmentation.toBinaryMask()
                    if (rotationDegrees != 0) {
                        binaryMask = rotateBitmap(binaryMask, rotationDegrees.toFloat())
                    }
                    binaryMask
                }
                val stableQuad = quadStabilizer.update(rawQuad)
                _liveAnalysisState.value = LiveAnalysisState(
                    inferenceTime = result.inferenceTime,
                    binaryMaskProvider = binaryMaskProvider,
                    maskSize = maskSize,
                    stableQuad = stableQuad,
                )

                val now = System.currentTimeMillis()
                if (_isAutoCaptureEnabled.value &&
                    stableQuad != null &&
                    now - lastAutoCaptureTimestamp > 2500L
                ) {
                    stableAutoFrameCount++
                    val requiredFrames = 6
                    _autoCaptureProgress.value =
                        (stableAutoFrameCount.toFloat() / requiredFrames).coerceIn(0f, 1f)
                    if (stableAutoFrameCount >= requiredFrames) {
                        lastAutoCaptureTimestamp = now
                        stableAutoFrameCount = 0
                        _autoCaptureProgress.value = 0f
                        _autoCaptureTrigger.tryEmit(Unit)
                    }
                } else {
                    stableAutoFrameCount = 0
                    _autoCaptureProgress.value = 0f
                }
            }

            imageProxy.close()
        }
    }

    fun onImageCaptured(imageProxy: ImageProxy?, opticalMeasures: OpticalMeasures?) {
        if (imageProxy != null) {
            viewModelScope.launch {
                try {
                    val source = imageProxy.toBitmap()
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val quality = if (_isHdEnabled.value) ExportQuality.HIGH else ExportQuality.BALANCED
                    val page = processCapturedImage(
                        source, rotationDegrees, opticalMeasures, Mode.CAPTURE, quality
                    )
                    imageProxy.close()
                    when (_captureMode.value) {
                        CaptureMode.BOOK_SPLIT -> {
                            val (leftPage, rightPage) = withContext(Dispatchers.Default) {
                                splitCapturedSpread(page, opticalMeasures)
                            }
                            onCaptureProcessed(leftPage, listOf(rightPage))
                        }
                        CaptureMode.PPT_BOARD -> {
                            val enhanced = withContext(Dispatchers.Default) {
                                transformCapturedPage(page) { bmp ->
                                    ScanCleanupEngine.enhancePptWhiteboard(bmp, cropTo16By9 = false)
                                }
                            }
                            onCaptureProcessed(enhanced)
                        }
                        CaptureMode.TEST_PAPER -> {
                            val cleaned = withContext(Dispatchers.Default) {
                                transformCapturedPage(page) { bmp ->
                                    ScanCleanupEngine.eraseHandwriting(bmp)
                                }
                            }
                            onCaptureProcessed(cleaned)
                        }
                        else -> {
                            onCaptureProcessed(page)
                        }
                    }
                } catch (e: RuntimeException) {
                    logger.e("Camera", "Failed to process captured image", e)
                    onCaptureProcessed(null)
                }
            }
        } else {
            onCaptureProcessed(null)
        }
    }

    private fun splitCapturedSpread(
        spreadPage: CapturedPage,
        opticalMeasures: OpticalMeasures?,
    ): Pair<CapturedPage, CapturedPage> {
        val spreadBitmap = spreadPage.pageImage.toBitmap()
        val (leftBmp, rightBmp) = ProFilterEngine.splitBookSpread(spreadBitmap)
        spreadBitmap.recycle()

        fun buildHalf(half: Bitmap): CapturedPage {
            val out = ByteArrayOutputStream()
            half.compress(Bitmap.CompressFormat.JPEG, 92, out)
            val jpeg = Jpeg(out.toByteArray())
            val fullQuad = createQuad(
                listOf(Point(0.0, 0.0), Point(0.0, 1.0), Point(1.0, 1.0), Point(1.0, 0.0))
            )
            val meta = PageMetadata(
                normalizedQuad = fullQuad,
                baseRotation = Rotation.R0,
                autoColorMode = ColorMode.COLOR,
                sourceSize = ImageSize(half.width, half.height),
                opticalMeasures = opticalMeasures,
            )
            return CapturedPage(
                pageImage = jpeg,
                sourceJpeg = CompletableDeferred(jpeg),
                metadata = meta,
                colorMode = ColorMode.COLOR,
            )
        }

        val left = buildHalf(leftBmp)
        val right = buildHalf(rightBmp)
        leftBmp.recycle()
        rightBmp.recycle()
        return left to right
    }

    private fun transformCapturedPage(
        original: CapturedPage,
        transform: (Bitmap) -> Bitmap,
    ): CapturedPage {
        val srcBmp = original.pageImage.toBitmap()
        val updatedBmp = transform(srcBmp)
        if (updatedBmp !== srcBmp) srcBmp.recycle()
        val out = ByteArrayOutputStream()
        updatedBmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        updatedBmp.recycle()
        return original.copy(pageImage = Jpeg(out.toByteArray()))
    }

    private suspend fun processCapturedImage(
        source: Bitmap,
        rotationDegrees: Int,
        opticalMeasures: OpticalMeasures?,
        mode: Mode,
        exportQuality: ExportQuality = ExportQuality.BALANCED,
    ): CapturedPage = withContext(Dispatchers.IO) {
        val segmentation = imageSegmentationService.runSegmentationAndReturn(source)
        val mask = segmentation?.segmentation
        val originalSize = ImageSize(source.width, source.height)
        val quad = mask?.let { detectDocumentQuad(mask, originalSize, mode) }
        val defaultColorMode = settingsRepository.defaultColorMode.first()
        val result = extractDocumentFromBitmap(
            source, quad, rotationDegrees, mask, viewModelScope, defaultColorMode, opticalMeasures, exportQuality
        )
        return@withContext result
    }

    fun addProcessedImage() {
        val current = _captureState.value
        if (current is CaptureState.CapturePreview) {
            val shouldOpenCrop = _captureMode.value !in setOf(
                CaptureMode.BATCH,
                CaptureMode.ID_CARD,
                CaptureMode.BOOK_SPLIT,
            ) && current.extraPages.isEmpty()
            viewModelScope.launch {
                _events.emit(CameraEvent.ImageCaptured(current.capturedPage, openCrop = shouldOpenCrop))
                current.extraPages.forEach { extra ->
                    _events.emit(CameraEvent.ImageCaptured(extra, openCrop = false))
                }
            }
        }
        _captureState.value = CaptureState.Idle
    }

    fun afterCaptureError() {
        _captureState.value = CaptureState.Idle
    }

    fun logError(message: String, throwable: Throwable) {
        viewModelScope.launch {
            logger.e("Camera", message, throwable)
        }
    }

    fun setTorchEnabled(enabled: Boolean) {
        _isTorchEnabled.value = enabled
    }

    fun importPhotos(uris: List<Uri>) {
        importJob?.cancel()
        if (uris.isEmpty()) {
            _importState.value = ImportState.Idle
            return
        }
        importJob = viewModelScope.launch {
            _importState.value = ImportState.Importing(0, uris.size)
            val quality = if (_isHdEnabled.value) ExportQuality.HIGH else ExportQuality.BALANCED
            uris.forEachIndexed { index, uri ->
                ensureActive()
                try {
                    val photoToImport = imageLoader.load(uri)
                    ensureActive()
                    val page = processCapturedImage(photoToImport, 0, null, Mode.IMPORT, quality)
                    ensureActive()
                    _events.emit(CameraEvent.ImageCaptured(page, openCrop = (uris.size == 1)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e("Import", "Failed to import image: $uri", e)
                }
                _importState.value = ImportState.Importing(index + 1, uris.size)
            }
            _importState.value = ImportState.Idle
        }
    }

    fun onImportClicked() {
        _importState.value = ImportState.Selecting
        resetLiveAnalysis()
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _importState.value = ImportState.Idle
    }
}

sealed class CaptureState {
    open val frozenImage: Bitmap? = null

    object Idle : CaptureState()
    data class Capturing(override val frozenImage: Bitmap) : CaptureState()
    data class CaptureError(override val frozenImage: Bitmap) : CaptureState()
    data class CapturePreview(
        override val frozenImage: Bitmap,
        val capturedPage: CapturedPage,
        val extraPages: List<CapturedPage> = emptyList(),
    ) : CaptureState()
}

fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(angle)
    return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true)
}
