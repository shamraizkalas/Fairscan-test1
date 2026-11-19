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
import androidx.compose.runtime.Immutable
import org.fairscan.imageprocessing.ImageSize
import org.fairscan.imageprocessing.Quad

enum class CaptureMode {
    DOCUMENT,
    BATCH,
    ID_CARD,
    BOOK_SPLIT,
    QR_SCAN,
    PPT_BOARD,
    TEST_PAPER,
}

@Immutable
data class LiveAnalysisState(
    val inferenceTime: Long = 0L,
    val maskSize: ImageSize? = null,
    val binaryMaskProvider: () -> Bitmap? = { -> null },
    val stableQuad: Quad? = null,
)

sealed class ImportState {
    object Idle : ImportState()
    object Selecting : ImportState()
    data class Importing(val processed: Int, val total: Int) : ImportState()
}

data class CameraUiState(
    val pageCount: Int,
    val liveAnalysisState: LiveAnalysisState,
    val captureState: CaptureState,
    val importState: ImportState,
    val showCaptureError: Boolean,
    val isLandscape: Boolean,
    val isDebugMode: Boolean,
    val isTorchEnabled: Boolean,
    val captureMode: CaptureMode = CaptureMode.DOCUMENT,
    val isGridEnabled: Boolean = false,
    val timerSeconds: Int = 0,
    val isHdEnabled: Boolean = false,
    val detectedQrCode: String? = null,
    val countdownRemaining: Int = 0,
    val isAutoCaptureEnabled: Boolean = false,
    val autoCaptureProgress: Float = 0f,
    val zoomRatio: Float = 1f,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 4f,
    val exposureIndex: Int = 0,
    val minExposureIndex: Int = 0,
    val maxExposureIndex: Int = 0,
)
