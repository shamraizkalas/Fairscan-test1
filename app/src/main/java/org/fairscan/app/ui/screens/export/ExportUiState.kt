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
package org.fairscan.app.ui.screens.export

import android.net.Uri
import org.fairscan.app.domain.PdfCompression
import org.fairscan.app.domain.PdfPageNumberStyle
import org.fairscan.app.domain.PdfPageSize
import org.fairscan.app.domain.StudioExportFormat

data class ExportUiState(
    val format: StudioExportFormat = StudioExportFormat.PDF,
    val pageSize: PdfPageSize = PdfPageSize.AUTO_FIT,
    val compression: PdfCompression = PdfCompression.BALANCED,
    val pageNumberStyle: PdfPageNumberStyle = PdfPageNumberStyle.NONE,
    val headerText: String = "",
    val footerText: String = "",
    val passwordInput: String = "",
    val appliedPassword: String = "",
    val filename: String = "",
    val isUserDefinedFilename: Boolean = false,
    val isGenerating: Boolean = false,
    val progress: ExportProgress? = null,
    val ocrActivation: Boolean? = null,
    val ocrLanguagesSummary: String = "",
    val blackAndWhiteAsJpeg: Boolean = false,
    val isSaving: Boolean = false,
    val result: ExportResult? = null,
    val savedBundle: SavedBundle? = null,
    val hasShared: Boolean = false,
    val error: ExportError? = null,
) {
    val hasSavedOrShared get() = savedBundle != null || hasShared
    val isPasswordProtected get() = format == StudioExportFormat.PDF && appliedPassword.isNotBlank()
}

data class ExportProgress(
    val completedPages: Int,
    val totalPages: Int,
) {
    val progress = completedPages.toFloat() / totalPages.coerceAtLeast(1)
}

data class SavedItem(
    val uri: Uri,
    val fileName: String,
    val format: StudioExportFormat,
)

data class SavedBundle(
    val items: List<SavedItem>,
    val saveDir: SaveDir? = null,
)

data class SaveDir(
    val uri: Uri,
    val name: String?,
)

sealed class ExportError {

    data class OnPrepareOrShare(
        val message: String,
        val throwable: Throwable,
    ) : ExportError()

    data class OnSave(
        val messageRes: Int,
        val saveDir: SaveDir?,
        val throwable: Throwable? = null,
    ) : ExportError()
}
