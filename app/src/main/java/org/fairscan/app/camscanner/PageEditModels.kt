package org.fairscan.app.camscanner

import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.fairscan.app.data.NormalizedQuad
import org.fairscan.app.domain.Rotation
import org.fairscan.imageprocessing.ColorMode
import java.util.UUID

@Serializable
data class NormalizedPointF(
    val x: Float,
    val y: Float,
)

@Serializable
data class EraserLayer(
    val id: String = UUID.randomUUID().toString(),
    val points: List<NormalizedPointF>,
    val brushRadiusNorm: Float = 0.032f,
) {
    fun toEraserStroke(): EraserStroke = EraserStroke(
        pointsNorm = points.map { it.x to it.y },
        brushRadiusNorm = brushRadiusNorm,
    )

    companion object {
        fun fromEraserStroke(stroke: EraserStroke, id: String = UUID.randomUUID().toString()): EraserLayer =
            EraserLayer(
                id = id,
                points = stroke.pointsNorm.map { NormalizedPointF(it.first, it.second) },
                brushRadiusNorm = stroke.brushRadiusNorm,
            )
    }
}

@Serializable
data class FilterLayer(
    val presetName: String = ProFilterPreset.ORIGINAL.name,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val sharpness: Float = 0f,
    val saturation: Float = 1f,
    val warmth: Float = 0f,
    val exposure: Float = 0f,
    val fade: Float = 0f,
) {
    val isDefault: Boolean
        get() = presetName == ProFilterPreset.ORIGINAL.name &&
            toImageAdjustments().isDefault

    fun toPreset(): ProFilterPreset =
        ProFilterPreset.entries.firstOrNull { it.name == presetName } ?: ProFilterPreset.ORIGINAL

    fun toImageAdjustments(): ImageAdjustments =
        ImageAdjustments(
            brightness = brightness,
            contrast = contrast,
            sharpness = sharpness,
            saturation = saturation,
            warmth = warmth,
            exposure = exposure,
            fade = fade,
        )
}

@Serializable
enum class CleanupEffectType(val displayLabel: String) {
    SHADOW_FLATTEN("Shadow Cleanup"),
    PPT_WHITEBOARD("PPT Whiteboard"),
    HANDWRITING_ERASE("Handwriting Eraser"),
}

@Serializable
data class CleanupEffectLayer(
    val id: String = UUID.randomUUID().toString(),
    val type: CleanupEffectType,
)

@Serializable
sealed class SerializedAnnotation {
    abstract val id: String

    @Serializable
    @SerialName("pen")
    data class PenStrokeLayer(
        override val id: String = UUID.randomUUID().toString(),
        val points: List<NormalizedPointF>,
        val colorArgb: Int,
        val strokeWidthNorm: Float,
        val isHighlighter: Boolean = false,
    ) : SerializedAnnotation()

    @Serializable
    @SerialName("redact")
    data class RedactBoxLayer(
        override val id: String = UUID.randomUUID().toString(),
        val leftNorm: Float,
        val topNorm: Float,
        val rightNorm: Float,
        val bottomNorm: Float,
        val isBlur: Boolean = true,
    ) : SerializedAnnotation()

    @Serializable
    @SerialName("text")
    data class TextAnnotationLayer(
        override val id: String = UUID.randomUUID().toString(),
        val text: String,
        val xNorm: Float,
        val yNorm: Float,
        val colorArgb: Int,
        val sizeScale: Float = 1f,
    ) : SerializedAnnotation()

    @Serializable
    @SerialName("badge_stamp")
    data class BadgeStampLayer(
        override val id: String = UUID.randomUUID().toString(),
        val label: String,
        val xNorm: Float,
        val yNorm: Float,
        val colorArgb: Int,
        val rotationDeg: Float = -14f,
    ) : SerializedAnnotation()

    fun summaryLabel(): String = when (this) {
        is PenStrokeLayer -> if (isHighlighter) "Highlight Stroke" else "Pen Stroke"
        is RedactBoxLayer -> if (isBlur) "Blur Redaction" else "Blackout Box"
        is TextAnnotationLayer -> "Text: \"$text\""
        is BadgeStampLayer -> "Badge: $label"
    }

    fun toRuntimeElement(): AnnotationElement = when (this) {
        is PenStrokeLayer -> AnnotationElement.PenStroke(
            pointsNorm = points.map { Offset(it.x, it.y) },
            colorArgb = colorArgb,
            strokeWidthNorm = strokeWidthNorm,
            isHighlighter = isHighlighter,
        )
        is RedactBoxLayer -> AnnotationElement.RedactBox(
            leftNorm = leftNorm,
            topNorm = topNorm,
            rightNorm = rightNorm,
            bottomNorm = bottomNorm,
            isBlur = isBlur,
        )
        is TextAnnotationLayer -> AnnotationElement.TextAnnotation(
            text = text,
            xNorm = xNorm,
            yNorm = yNorm,
            colorArgb = colorArgb,
            sizeScale = sizeScale,
        )
        is BadgeStampLayer -> AnnotationElement.BadgeStamp(
            label = label,
            xNorm = xNorm,
            yNorm = yNorm,
            colorArgb = colorArgb,
            rotationDeg = rotationDeg,
        )
    }

    companion object {
        fun fromRuntimeElement(
            el: AnnotationElement,
            id: String = UUID.randomUUID().toString(),
        ): SerializedAnnotation = when (el) {
            is AnnotationElement.PenStroke -> PenStrokeLayer(
                id = id,
                points = el.pointsNorm.map { NormalizedPointF(it.x, it.y) },
                colorArgb = el.colorArgb,
                strokeWidthNorm = el.strokeWidthNorm,
                isHighlighter = el.isHighlighter,
            )
            is AnnotationElement.RedactBox -> RedactBoxLayer(
                id = id,
                leftNorm = el.leftNorm,
                topNorm = el.topNorm,
                rightNorm = el.rightNorm,
                bottomNorm = el.bottomNorm,
                isBlur = el.isBlur,
            )
            is AnnotationElement.TextAnnotation -> TextAnnotationLayer(
                id = id,
                text = el.text,
                xNorm = el.xNorm,
                yNorm = el.yNorm,
                colorArgb = el.colorArgb,
                sizeScale = el.sizeScale,
            )
            is AnnotationElement.BadgeStamp -> BadgeStampLayer(
                id = id,
                label = el.label,
                xNorm = el.xNorm,
                yNorm = el.yNorm,
                colorArgb = el.colorArgb,
                rotationDeg = el.rotationDeg,
            )
        }
    }
}

@Serializable
data class ImageOverlayLayer(
    val id: String = UUID.randomUUID().toString(),
    val assetFileName: String,
    val label: String,
    val isStamp: Boolean,
    val normX: Float,
    val normY: Float,
    val scaleFactor: Float,
    val rotationDeg: Float = 0f,
    val opacity: Float = 1f,
)

@Serializable
data class WatermarkLayer(
    val text: String,
    val opacity: Float = 0.35f,
    val rotationDegrees: Float = -30f,
    val tileRepeated: Boolean = true,
)

@Serializable
data class PageEditState(
    val eraserStrokes: List<EraserLayer> = emptyList(),
    val cleanupEffects: List<CleanupEffectLayer> = emptyList(),
    val filterLayer: FilterLayer? = null,
    val annotations: List<SerializedAnnotation> = emptyList(),
    val overlays: List<ImageOverlayLayer> = emptyList(),
    val watermark: WatermarkLayer? = null,
) {
    val hasActiveFilter: Boolean
        get() = filterLayer != null && !filterLayer.isDefault

    val hasAnyEdits: Boolean
        get() = eraserStrokes.isNotEmpty() ||
            cleanupEffects.isNotEmpty() ||
            hasActiveFilter ||
            annotations.isNotEmpty() ||
            overlays.isNotEmpty() ||
            watermark != null

    val totalEditCount: Int
        get() = eraserStrokes.size +
            cleanupEffects.size +
            (if (hasActiveFilter) 1 else 0) +
            annotations.size +
            overlays.size +
            (if (watermark != null) 1 else 0)
}

@Serializable
data class EditablePdfPageEntry(
    val id: String,
    val baseFileName: String,
    val sourceFileName: String? = null,
    val baseRotationDegrees: Int = 0,
    val manualRotationDegrees: Int = 0,
    val quad: NormalizedQuad? = null,
    val userQuad: NormalizedQuad? = null,
    val isColored: Boolean = true,
    val colorMode: ColorMode = ColorMode.COLOR,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    val editState: PageEditState = PageEditState(),
)

@Serializable
data class EditablePdfProjectManifest(
    val formatVersion: Int = 1,
    val title: String = "",
    val pages: List<EditablePdfPageEntry> = emptyList(),
)

/** Coordinate conversion helpers between visual (manually rotated) page coordinates and R0 base coordinates. */
object LayerCoordinateMapper {
    /** Converts normalized point from visual (display) space rotated by [displayRotation] back to R0 base space. */
    fun visualToBasePoint(x: Float, y: Float, displayRotation: Rotation): NormalizedPointF {
        return when (displayRotation) {
            Rotation.R0 -> NormalizedPointF(x, y)
            Rotation.R90 -> NormalizedPointF(y, 1f - x)
            Rotation.R180 -> NormalizedPointF(1f - x, 1f - y)
            Rotation.R270 -> NormalizedPointF(1f - y, x)
        }
    }

    /** Converts normalized point from R0 base space into visual space rotated by [displayRotation]. */
    fun baseToVisualPoint(x: Float, y: Float, displayRotation: Rotation): NormalizedPointF {
        return when (displayRotation) {
            Rotation.R0 -> NormalizedPointF(x, y)
            Rotation.R90 -> NormalizedPointF(1f - y, x)
            Rotation.R180 -> NormalizedPointF(1f - x, 1f - y)
            Rotation.R270 -> NormalizedPointF(y, 1f - x)
        }
    }

    fun rotationDeltaToBaseDeg(displayRotation: Rotation): Float =
        ((-displayRotation.degrees % 360) + 360) % 360f

    fun rotationDeltaToVisualDeg(displayRotation: Rotation): Float =
        displayRotation.degrees.toFloat()
}
