package org.fairscan.app.camscanner

import androidx.compose.ui.geometry.Offset

enum class AnnotationTool {
    PEN,
    HIGHLIGHTER,
    REDACT_BLUR,
    REDACT_BLACK,
    TEXT,
    STAMP,
}

sealed class AnnotationElement {
    data class PenStroke(
        val pointsNorm: List<Offset>,
        val colorArgb: Int,
        val strokeWidthNorm: Float,
        val isHighlighter: Boolean = false,
    ) : AnnotationElement()

    data class RedactBox(
        val leftNorm: Float,
        val topNorm: Float,
        val rightNorm: Float,
        val bottomNorm: Float,
        val isBlur: Boolean = true,
    ) : AnnotationElement()

    data class TextAnnotation(
        val text: String,
        val xNorm: Float,
        val yNorm: Float,
        val colorArgb: Int,
        val sizeScale: Float = 1f,
    ) : AnnotationElement()

    data class BadgeStamp(
        val label: String,
        val xNorm: Float,
        val yNorm: Float,
        val colorArgb: Int,
        val rotationDeg: Float = -14f,
    ) : AnnotationElement()
}

val DEFAULT_STAMPS = listOf(
    "APPROVED" to 0xFF00897B.toInt(),
    "CONFIDENTIAL" to 0xFFD32F2F.toInt(),
    "PAID" to 0xFF1565C0.toInt(),
    "VERIFIED" to 0xFF2E7D32.toInt(),
    "ORIGINAL" to 0xFF00897B.toInt(),
    "COPY" to 0xFF1565C0.toInt(),
)
