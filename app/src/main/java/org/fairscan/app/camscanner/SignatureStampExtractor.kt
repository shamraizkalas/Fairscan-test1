package org.fairscan.app.camscanner

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class StampInkColor(val label: String, val colorArgb: Int?) {
    ORIGINAL("Original Ink", null),
    BLACK("Black", 0xFF141414.toInt()),
    NAVY("Navy Blue", 0xFF0D47A1.toInt()),
    RED("Stamp Red", 0xFFC62828.toInt()),
    TEAL("Emerald", 0xFF00695C.toInt()),
}

object SignatureStampExtractor {

    fun extractTransparentAsset(
        source: Bitmap,
        cropLeftNorm: Float = 0f,
        cropTopNorm: Float = 0f,
        cropRightNorm: Float = 1f,
        cropBottomNorm: Float = 1f,
        inkColor: StampInkColor = StampInkColor.ORIGINAL,
        sensitivity: Float = 0f,
        maxDimensionPx: Int = 1100,
    ): Bitmap? {
        if (source.width <= 0 || source.height <= 0) return null

        val safeSource = if (source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val leftN = cropLeftNorm.coerceIn(0f, 0.95f)
        val topN = cropTopNorm.coerceIn(0f, 0.95f)
        val rightN = cropRightNorm.coerceIn(leftN + 0.05f, 1f)
        val bottomN = cropBottomNorm.coerceIn(topN + 0.05f, 1f)

        val x = (leftN * safeSource.width).roundToInt().coerceIn(0, safeSource.width - 1)
        val y = (topN * safeSource.height).roundToInt().coerceIn(0, safeSource.height - 1)
        val w = ((rightN - leftN) * safeSource.width).roundToInt().coerceIn(1, safeSource.width - x)
        val h = ((bottomN - topN) * safeSource.height).roundToInt().coerceIn(1, safeSource.height - y)

        val cropped = Bitmap.createBitmap(safeSource, x, y, w, h)
        if (safeSource !== source) safeSource.recycle()

        val scale = minOf(maxDimensionPx.toFloat() / cropped.width, maxDimensionPx.toFloat() / cropped.height, 1f)
        val working = if (scale < 1f) {
            val scaled = Bitmap.createScaledBitmap(
                cropped,
                (cropped.width * scale).roundToInt().coerceAtLeast(16),
                (cropped.height * scale).roundToInt().coerceAtLeast(16),
                true
            )
            cropped.recycle()
            scaled
        } else {
            cropped
        }

        val width = working.width
        val height = working.height
        val pixels = IntArray(width * height)
        working.getPixels(pixels, 0, width, 0, 0, width, height)

        // Estimate paper background brightness using a luminance histogram (85th percentile)
        val lumaHist = IntArray(256)
        val sampleStep = max(1, pixels.size / 12000)
        var sampledCount = 0
        var idx = 0
        while (idx < pixels.size) {
            val c = pixels[idx]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val luma = (0.299f * r + 0.587f * g + 0.114f * b).roundToInt().coerceIn(0, 255)
            lumaHist[luma]++
            sampledCount++
            idx += sampleStep
        }

        val targetRank = (sampledCount * 0.85f).roundToInt().coerceAtLeast(1)
        var cumulative = 0
        var paperLuma = 235f
        for (bin in 0..255) {
            cumulative += lumaHist[bin]
            if (cumulative >= targetRank) {
                paperLuma = bin.toFloat().coerceIn(135f, 255f)
                break
            }
        }

        // Sensitivity shifts cutoff: higher sensitivity picks up lighter ink strokes
        val lowCutoff = (24f - sensitivity * 42f).coerceIn(6f, 68f)
        val highCutoff = (lowCutoff + 42f).coerceIn(lowCutoff + 12f, 145f)
        val range = highCutoff - lowCutoff

        val tintColor = inkColor.colorArgb
        val tintR = if (tintColor != null) Color.red(tintColor) else 0
        val tintG = if (tintColor != null) Color.green(tintColor) else 0
        val tintB = if (tintColor != null) Color.blue(tintColor) else 0

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF

            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            val maxCh = max(r, max(g, b))
            val minCh = min(r, min(g, b))
            val chroma = (maxCh - minCh).toFloat()

            // Ink strength blends darkness relative to paper AND color saturation (for red/blue stamps)
            val inkStrength = (paperLuma - luma) + chroma * 0.90f
            val alphaNorm = ((inkStrength - lowCutoff) / range).coerceIn(0f, 1f)

            if (alphaNorm < 0.04f) {
                pixels[i] = Color.TRANSPARENT
            } else {
                val outAlpha = (alphaNorm * 255f).roundToInt().coerceIn(0, 255)
                if (tintColor != null) {
                    pixels[i] = Color.argb(outAlpha, tintR, tintG, tintB)
                } else {
                    // Gently boost color vividness so stamped ink looks crisp on white documents
                    val boost = 1.18f
                    val avg = (r + g + b) / 3f
                    val outR = (avg + (r - avg) * boost).roundToInt().coerceIn(0, 255)
                    val outG = (avg + (g - avg) * boost).roundToInt().coerceIn(0, 255)
                    val outB = (avg + (b - avg) * boost).roundToInt().coerceIn(0, 255)
                    pixels[i] = Color.argb(outAlpha, outR, outG, outB)
                }
            }
        }

        val extracted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        extracted.setPixels(pixels, 0, width, 0, 0, width, height)
        working.recycle()

        val trimmed = trimTransparentBounds(extracted, padding = 10)
        if (trimmed !== extracted) extracted.recycle()
        return trimmed
    }

    fun trimTransparentBounds(source: Bitmap, padding: Int = 8): Bitmap? {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        for (py in 0 until h) {
            val rowOffset = py * w
            for (px in 0 until w) {
                val alpha = (pixels[rowOffset + px] ushr 24) and 0xFF
                if (alpha > 12) {
                    if (px < minX) minX = px
                    if (px > maxX) maxX = px
                    if (py < minY) minY = py
                    if (py > maxY) maxY = py
                }
            }
        }

        if (maxX < minX || maxY < minY) return null

        val left = (minX - padding).coerceAtLeast(0)
        val top = (minY - padding).coerceAtLeast(0)
        val right = (maxX + padding + 1).coerceAtMost(w)
        val bottom = (maxY + padding + 1).coerceAtMost(h)
        val outW = (right - left).coerceAtLeast(1)
        val outH = (bottom - top).coerceAtLeast(1)

        return Bitmap.createBitmap(source, left, top, outW, outH)
    }
}
