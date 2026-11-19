package org.fairscan.app.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

enum class PdfPageSize(val label: String, val widthMm: Double?, val heightMm: Double?) {
    AUTO_FIT("Auto", null, null),
    A4("A4", 210.0, 297.0),
    LETTER("Letter", 215.9, 279.4),
    LEGAL("Legal", 215.9, 355.6),
}

enum class PdfCompression(
    val label: String,
    val jpegQuality: Int,
    val maxLongEdgePx: Int,
) {
    HIGH("High", 94, 3600),
    BALANCED("Balanced", 82, 2400),
    COMPACT("Compact", 65, 1700),
    SMALLEST("Smallest", 45, 1200),
}

enum class StudioExportFormat(val label: String, val mimeType: String, val extension: String) {
    PDF("PDF", "application/pdf", "pdf"),
    JPEG("JPG", "image/jpeg", "jpg"),
    TXT("TXT", "text/plain", "txt"),
    DOC("Word (.doc)", "application/msword", "doc"),
}

enum class PdfPageNumberStyle(val label: String) {
    NONE("Off"),
    BOTTOM_CENTER("Bottom Center"),
    BOTTOM_RIGHT("Bottom Right"),
}

data class PdfExportOptions(
    val pageSize: PdfPageSize = PdfPageSize.AUTO_FIT,
    val compression: PdfCompression = PdfCompression.BALANCED,
    val password: String = "",
    val pageNumberStyle: PdfPageNumberStyle = PdfPageNumberStyle.NONE,
    val headerText: String = "",
    val footerText: String = "",
)

fun recompressForPdf(bytes: ByteArray, compression: PdfCompression): ByteArray {
    if (compression == PdfCompression.HIGH) return bytes
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
    val maxEdge = maxOf(bmp.width, bmp.height)
    val scaled = if (maxEdge > compression.maxLongEdgePx) {
        val ratio = compression.maxLongEdgePx.toFloat() / maxEdge
        Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * ratio).toInt().coerceAtLeast(1),
            (bmp.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    } else bmp
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, compression.jpegQuality, out)
    if (scaled !== bmp) scaled.recycle()
    bmp.recycle()
    return out.toByteArray()
}
