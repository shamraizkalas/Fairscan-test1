package org.fairscan.app.camscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class IdPhotoSizePreset(
    val label: String,
    val aspectRatio: Float,
    val outputWidthPx: Int,
    val outputHeightPx: Int,
) {
    PASSPORT_35X45("35 × 45 mm", 35f / 45f, 413, 531),
    US_VISA_2X2("2 × 2 inch", 1f, 600, 600),
    CNIC_25X35("25 × 35 mm", 25f / 35f, 295, 413),
    SQUARE_1X1("1 × 1 inch", 1f, 300, 300),
}

enum class IdPhotoBackground(val label: String, val colorArgb: Int?) {
    ORIGINAL("Original", null),
    PURE_WHITE("White", Color.WHITE),
    STUDIO_BLUE("Studio Blue", 0xFF1565C0.toInt()),
    PASSPORT_BLUE("Sky Blue", 0xFF81D4FA.toInt()),
    WARM_CREAM("Cream", 0xFFFDF8EC.toInt()),
}

enum class IdPrintSheetType(val label: String, val widthPx: Int, val heightPx: Int) {
    SHEET_4X6("4 × 6 Photo Sheet", 1200, 1800),
    SHEET_A4("A4 Printable Sheet", 1654, 2339),
}

object IdPhotoEngine {

    /**
     * Crops portrait cleanly to selected official aspect ratio and optionally replaces background.
     */
    fun generateIdPhoto(
        source: Bitmap,
        preset: IdPhotoSizePreset,
        background: IdPhotoBackground,
        zoom: Float = 1.0f,
        offsetXNorm: Float = 0f,
        offsetYNorm: Float = 0f,
        brightness: Float = 6f,
    ): Bitmap {
        val cropped = cropPortraitToRatio(
            source = source,
            targetWidth = preset.outputWidthPx,
            targetHeight = preset.outputHeightPx,
            zoom = zoom,
            offsetXNorm = offsetXNorm,
            offsetYNorm = offsetYNorm,
            brightness = brightness,
        )
        val bgColor = background.colorArgb ?: return cropped

        val swapped = try {
            replaceBackgroundGrabCut(cropped, bgColor)
        } catch (_: UnsatisfiedLinkError) {
            replaceBackgroundBorderMatte(cropped, bgColor)
        }
        if (swapped !== cropped) cropped.recycle()
        return swapped
    }

    internal fun cropPortraitToRatio(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        zoom: Float,
        offsetXNorm: Float,
        offsetYNorm: Float,
        brightness: Float,
    ): Bitmap {
        val out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        val scaleBase = max(
            targetWidth.toFloat() / source.width.coerceAtLeast(1),
            targetHeight.toFloat() / source.height.coerceAtLeast(1)
        )
        val effectiveScale = scaleBase * zoom.coerceIn(0.8f, 2.5f)
        val drawW = source.width * effectiveScale
        val drawH = source.height * effectiveScale
        val centerX = targetWidth / 2f + offsetXNorm * targetWidth * 0.35f
        val centerY = targetHeight / 2f + offsetYNorm * targetHeight * 0.35f
        val destRect = RectF(
            centerX - drawW / 2f,
            centerY - drawH / 2f,
            centerX + drawW / 2f,
            centerY + drawH / 2f,
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            if (brightness != 0f) {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix(
                        floatArrayOf(
                            1f, 0f, 0f, 0f, brightness,
                            0f, 1f, 0f, 0f, brightness,
                            0f, 0f, 1f, 0f, brightness,
                            0f, 0f, 0f, 1f, 0f,
                        )
                    )
                )
            }
        }
        canvas.drawBitmap(source, null, destRect, paint)
        return out
    }

    private fun replaceBackgroundGrabCut(portrait: Bitmap, bgArgb: Int): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(portrait, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()

        val w = bgr.cols()
        val h = bgr.rows()
        // Run GrabCut on downscaled 280px max image for speed & smoothness, then upscale matte
        val scale = min(280.0 / max(w, h), 1.0)
        val smallW = (w * scale).roundToInt().coerceAtLeast(48)
        val smallH = (h * scale).roundToInt().coerceAtLeast(48)
        val smallBgr = Mat()
        Imgproc.resize(bgr, smallBgr, org.opencv.core.Size(smallW.toDouble(), smallH.toDouble()))

        val mask = Mat(smallH, smallW, CvType.CV_8UC1, Scalar(Imgproc.GC_PR_BGD.toDouble()))
        val rect = Rect(
            (smallW * 0.14).roundToInt().coerceAtLeast(2),
            (smallH * 0.08).roundToInt().coerceAtLeast(2),
            (smallW * 0.72).roundToInt().coerceAtMost(smallW - 4),
            (smallH * 0.88).roundToInt().coerceAtMost(smallH - 4),
        )
        val bgModel = Mat()
        val fgModel = Mat()
        Imgproc.grabCut(smallBgr, mask, rect, bgModel, fgModel, 3, Imgproc.GC_INIT_WITH_RECT)
        smallBgr.release()
        bgModel.release()
        fgModel.release()

        // Binary foreground mask: GC_FGD (1) or GC_PR_FGD (3)
        val fgMaskSmall = Mat(smallH, smallW, CvType.CV_8UC1)
        val maskBytes = ByteArray(smallW * smallH)
        mask.get(0, 0, maskBytes)
        for (i in maskBytes.indices) {
            val v = maskBytes[i].toInt() and 0xFF
            maskBytes[i] = if (v == Imgproc.GC_FGD || v == Imgproc.GC_PR_FGD) 255.toByte() else 0.toByte()
        }
        fgMaskSmall.put(0, 0, maskBytes)
        mask.release()

        val fgMaskFull = Mat()
        Imgproc.resize(fgMaskSmall, fgMaskFull, org.opencv.core.Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        fgMaskSmall.release()

        val feathered = Mat()
        Imgproc.GaussianBlur(fgMaskFull, feathered, org.opencv.core.Size(7.0, 7.0), 0.0)
        fgMaskFull.release()

        val alphaBytes = ByteArray(w * h)
        feathered.get(0, 0, alphaBytes)
        feathered.release()
        bgr.release()

        val pixels = IntArray(w * h)
        portrait.getPixels(pixels, 0, w, 0, 0, w, h)
        val bgR = Color.red(bgArgb)
        val bgG = Color.green(bgArgb)
        val bgB = Color.blue(bgArgb)

        for (i in pixels.indices) {
            val a = (alphaBytes[i].toInt() and 0xFF) / 255f
            val p = pixels[i]
            val r = (Color.red(p) * a + bgR * (1f - a)).roundToInt().coerceIn(0, 255)
            val g = (Color.green(p) * a + bgG * (1f - a)).roundToInt().coerceIn(0, 255)
            val b = (Color.blue(p) * a + bgB * (1f - a)).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(255, r, g, b)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun replaceBackgroundBorderMatte(portrait: Bitmap, bgArgb: Int): Bitmap {
        val w = portrait.width
        val h = portrait.height
        val pixels = IntArray(w * h)
        portrait.getPixels(pixels, 0, w, 0, 0, w, h)
        val corner = pixels[0]
        val cr = Color.red(corner)
        val cg = Color.green(corner)
        val cb = Color.blue(corner)
        for (i in pixels.indices) {
            val p = pixels[i]
            val dist = kotlin.math.abs(Color.red(p) - cr) +
                kotlin.math.abs(Color.green(p) - cg) +
                kotlin.math.abs(Color.blue(p) - cb)
            if (dist < 75) {
                pixels[i] = bgArgb
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Tiles multiple passport/visa copies onto a printable 4x6 or A4 sheet with crisp cut guides.
     */
    fun createPrintableIdSheet(
        photo: Bitmap,
        sheetType: IdPrintSheetType = IdPrintSheetType.SHEET_A4,
        copies: Int = 8,
        addCutGuides: Boolean = true,
    ): Bitmap {
        val sheet = Bitmap.createBitmap(sheetType.widthPx, sheetType.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)

        val margin = 72f
        val gap = 36f
        val cols = if (sheetType == IdPrintSheetType.SHEET_4X6) 2 else 3
        val rows = kotlin.math.ceil(copies / cols.toFloat()).toInt().coerceAtLeast(1)

        val maxTileW = (sheetType.widthPx - margin * 2 - gap * (cols - 1)) / cols
        val maxTileH = (sheetType.heightPx - margin * 2 - gap * (rows - 1)) / rows
        val photoAspect = photo.width.toFloat() / photo.height.coerceAtLeast(1)
        val tileW: Float
        val tileH: Float
        if (maxTileW / maxTileH > photoAspect) {
            tileH = maxTileH
            tileW = tileH * photoAspect
        } else {
            tileW = maxTileW
            tileH = tileW / photoAspect
        }

        val totalGridW = cols * tileW + (cols - 1) * gap
        val startX = (sheetType.widthPx - totalGridW) / 2f
        val startY = margin

        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 90, 95, 100)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }

        for (i in 0 until copies) {
            val r = i / cols
            val c = i % cols
            val left = startX + c * (tileW + gap)
            val top = startY + r * (tileH + gap)
            val rect = RectF(left, top, left + tileW, top + tileH)
            canvas.drawBitmap(photo, null, rect, bitmapPaint)
            if (addCutGuides) {
                canvas.drawRect(rect, guidePaint)
            }
        }
        return sheet
    }
}
