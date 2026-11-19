package org.fairscan.app.camscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import org.fairscan.app.domain.Rotation
import java.io.File

/**
 * Deterministic non-destructive compositor.
 * Always reconstructs the rendered page cleanly from the untouched base scan plus active layers.
 */
object PageCompositor {

    fun compose(
        cleanBase: Bitmap,
        editState: PageEditState,
        resolveOverlayFile: (String) -> File,
    ): Bitmap {
        if (!editState.hasAnyEdits) {
            return cleanBase.copy(Bitmap.Config.ARGB_8888, false)
        }

        var working = cleanBase.copy(Bitmap.Config.ARGB_8888, true)

        fun replaceWorking(next: Bitmap) {
            if (next !== working) {
                working.recycle()
                working = next
            }
        }

        // 1. Cleanup effects (shadow flatten / PPT whiteboard / handwriting cleanup)
        for (effect in editState.cleanupEffects) {
            val next = when (effect.type) {
                CleanupEffectType.SHADOW_FLATTEN -> ScanCleanupEngine.flattenShadows(working)
                CleanupEffectType.PPT_WHITEBOARD -> ScanCleanupEngine.enhancePptWhiteboard(working, cropTo16By9 = false)
                CleanupEffectType.HANDWRITING_ERASE -> ScanCleanupEngine.eraseHandwriting(working)
            }
            replaceWorking(next)
        }

        // 2. Magic eraser strokes
        if (editState.eraserStrokes.isNotEmpty()) {
            val strokes = editState.eraserStrokes.map { it.toEraserStroke() }
            val next = ScanCleanupEngine.applyMagicEraserBrush(working, strokes)
            replaceWorking(next)
        }

        // 3. Color filter preset + adjustments
        val filter = editState.filterLayer
        if (filter != null && !filter.isDefault) {
            val next = ProFilterEngine.applyFilterAndAdjustments(
                source = working,
                preset = filter.toPreset(),
                adjustments = filter.toImageAdjustments(),
            )
            replaceWorking(next)
        }

        // 4. Vector annotations (pen, highlight, redaction, text, badge stamps)
        if (editState.annotations.isNotEmpty()) {
            val runtime = editState.annotations.map { it.toRuntimeElement() }
            val next = AnnotationRenderer.bakeAnnotations(working, runtime)
            replaceWorking(next)
        }

        // 5. Placed image overlays (signatures, scanned/office stamps, QR stamps)
        for (overlay in editState.overlays) {
            val assetFile = resolveOverlayFile(overlay.assetFileName)
            if (!assetFile.exists()) continue
            val overlayBmp = BitmapFactory.decodeFile(assetFile.absolutePath) ?: continue
            try {
                val next = SignatureHelper.mergeSignatureOnBitmap(
                    pageBitmap = working,
                    signatureBitmap = overlayBmp,
                    positionNormalizedX = overlay.normX,
                    positionNormalizedY = overlay.normY,
                    scaleFactor = overlay.scaleFactor,
                    rotationDeg = overlay.rotationDeg,
                    opacity = overlay.opacity,
                )
                replaceWorking(next)
            } finally {
                overlayBmp.recycle()
            }
        }

        // 6. Page Watermark
        val wm = editState.watermark
        if (wm != null && wm.text.isNotBlank()) {
            val next = WatermarkHelper.applyWatermark(
                source = working,
                text = wm.text,
                opacity = wm.opacity,
                rotationDegrees = wm.rotationDegrees,
                tileRepeated = wm.tileRepeated,
            )
            replaceWorking(next)
        }

        return working
    }

    fun rotateBitmap(bitmap: Bitmap, rotation: Rotation): Bitmap {
        if (rotation == Rotation.R0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
