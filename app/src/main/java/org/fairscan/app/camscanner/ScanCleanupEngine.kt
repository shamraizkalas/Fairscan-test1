package org.fairscan.app.camscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.min
import kotlin.math.roundToInt

data class EraserStroke(
    val pointsNorm: List<Pair<Float, Float>>,
    val brushRadiusNorm: Float = 0.032f,
)

object ScanCleanupEngine {

    /**
     * Erases marked strokes cleanly using OpenCV Telea inpainting.
     * Falls back to smooth neighboring paper fill if OpenCV JNI is unavailable.
     */
    fun applyMagicEraserBrush(source: Bitmap, strokes: List<EraserStroke>): Bitmap {
        if (strokes.isEmpty()) return source.copy(Bitmap.Config.ARGB_8888, false)
        return try {
            applyOpenCvInpaint(source, strokes)
        } catch (_: UnsatisfiedLinkError) {
            applyCanvasPaperFillFallback(source, strokes)
        }
    }

    private fun applyOpenCvInpaint(source: Bitmap, strokes: List<EraserStroke>): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(source, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()

        val mask = Mat.zeros(bgr.rows(), bgr.cols(), CvType.CV_8UC1)
        val baseDim = min(bgr.cols(), bgr.rows()).toFloat().coerceAtLeast(1f)
        val white = Scalar(255.0)
        var maxRadiusPx = 6.0

        for (stroke in strokes) {
            if (stroke.pointsNorm.isEmpty()) continue
            val thickness = (stroke.brushRadiusNorm * baseDim * 2f).roundToInt().coerceAtLeast(6)
            maxRadiusPx = maxOf(maxRadiusPx, thickness / 2.0)
            if (stroke.pointsNorm.size == 1) {
                val pt = Point(
                    (stroke.pointsNorm[0].first * bgr.cols()).toDouble(),
                    (stroke.pointsNorm[0].second * bgr.rows()).toDouble()
                )
                Imgproc.circle(mask, pt, (thickness / 2).coerceAtLeast(3), white, -1)
            } else {
                for (i in 0 until stroke.pointsNorm.size - 1) {
                    val p1 = Point(
                        (stroke.pointsNorm[i].first * bgr.cols()).toDouble(),
                        (stroke.pointsNorm[i].second * bgr.rows()).toDouble()
                    )
                    val p2 = Point(
                        (stroke.pointsNorm[i + 1].first * bgr.cols()).toDouble(),
                        (stroke.pointsNorm[i + 1].second * bgr.rows()).toDouble()
                    )
                    Imgproc.line(mask, p1, p2, white, thickness, Imgproc.LINE_AA)
                }
            }
        }

        val inpainted = Mat()
        Photo.inpaint(bgr, mask, inpainted, maxRadiusPx.coerceIn(4.0, 24.0), Photo.INPAINT_TELEA)
        bgr.release()
        mask.release()

        val outRgba = Mat()
        Imgproc.cvtColor(inpainted, outRgba, Imgproc.COLOR_BGR2RGBA)
        inpainted.release()
        val result = Bitmap.createBitmap(outRgba.cols(), outRgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outRgba, result)
        outRgba.release()
        return result
    }

    private fun applyCanvasPaperFillFallback(source: Bitmap, strokes: List<EraserStroke>): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val baseDim = min(source.width, source.height).toFloat().coerceAtLeast(1f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFAF9F6.toInt()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (stroke in strokes) {
            if (stroke.pointsNorm.isEmpty()) continue
            paint.strokeWidth = (stroke.brushRadiusNorm * baseDim * 2f).coerceAtLeast(8f)
            val path = Path().apply {
                val first = stroke.pointsNorm.first()
                moveTo(first.first * source.width, first.second * source.height)
                for (i in 1 until stroke.pointsNorm.size) {
                    val pt = stroke.pointsNorm[i]
                    lineTo(pt.first * source.width, pt.second * source.height)
                }
            }
            canvas.drawPath(path, paint)
        }
        return result
    }

    /**
     * Flattens uneven hand/phone shadows while preserving document text and color ink.
     */
    fun flattenShadows(source: Bitmap): Bitmap {
        return try {
            val rgba = Mat()
            Utils.bitmapToMat(source, rgba)
            val bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            rgba.release()

            val normalized = flattenBgrShadows(bgr)
            bgr.release()

            val outRgba = Mat()
            Imgproc.cvtColor(normalized, outRgba, Imgproc.COLOR_BGR2RGBA)
            normalized.release()
            val result = Bitmap.createBitmap(outRgba.cols(), outRgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, result)
            outRgba.release()
            result
        } catch (_: UnsatisfiedLinkError) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    private fun flattenBgrShadows(bgr: Mat): Mat {
        val channels = ArrayList<Mat>(3)
        Core.split(bgr, channels)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        val outChannels = ArrayList<Mat>(3)
        for (ch in channels) {
            val dilated = Mat()
            Imgproc.dilate(ch, dilated, kernel)
            val bg = Mat()
            val blurSize = (min(bgr.cols(), bgr.rows()) / 18).let { s ->
                val odd = if (s % 2 == 0) s + 1 else s
                odd.coerceIn(15, 51)
            }
            Imgproc.medianBlur(dilated, bg, blurSize)
            dilated.release()

            val diff = Mat()
            Core.absdiff(ch, bg, diff)
            val normDiff = Mat()
            Core.bitwise_not(diff, normDiff)
            diff.release()

            val chFloat = Mat()
            val bgFloat = Mat()
            ch.convertTo(chFloat, CvType.CV_32F, 1.0, 1.0)
            bg.convertTo(bgFloat, CvType.CV_32F, 1.0, 1.0)
            val divided = Mat()
            Core.divide(chFloat, bgFloat, divided, 250.0)
            val dividedU8 = Mat()
            divided.convertTo(dividedU8, CvType.CV_8U)

            chFloat.release()
            bgFloat.release()
            divided.release()
            bg.release()
            normDiff.release()
            outChannels.add(dividedU8)
        }
        kernel.release()
        channels.forEach { it.release() }

        val merged = Mat()
        Core.merge(outChannels, merged)
        outChannels.forEach { it.release() }
        return merged
    }

    /**
     * Suppresses projector/whiteboard glare, pops text contrast, and optionally fits into a crisp 16:9 slide frame.
     */
    fun enhancePptWhiteboard(source: Bitmap, cropTo16By9: Boolean = false): Bitmap {
        val enhanced = try {
            val rgba = Mat()
            Utils.bitmapToMat(source, rgba)
            val bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            rgba.release()

            val flat = flattenBgrShadows(bgr)
            bgr.release()

            val lab = Mat()
            Imgproc.cvtColor(flat, lab, Imgproc.COLOR_BGR2Lab)
            flat.release()

            val channels = ArrayList<Mat>(3)
            Core.split(lab, channels)
            lab.release()
            val clahe = Imgproc.createCLAHE(2.8, Size(8.0, 8.0))
            val lEnhanced = Mat()
            clahe.apply(channels[0], lEnhanced)
            channels[0].release()
            channels[0] = lEnhanced

            val merged = Mat()
            Core.merge(channels, merged)
            channels.forEach { it.release() }

            val outBgr = Mat()
            Imgproc.cvtColor(merged, outBgr, Imgproc.COLOR_Lab2BGR)
            merged.release()

            val sharpened = Mat()
            val blurred = Mat()
            Imgproc.GaussianBlur(outBgr, blurred, Size(0.0, 0.0), 1.6)
            Core.addWeighted(outBgr, 1.38, blurred, -0.38, 4.0, sharpened)
            outBgr.release()
            blurred.release()

            val outRgba = Mat()
            Imgproc.cvtColor(sharpened, outRgba, Imgproc.COLOR_BGR2RGBA)
            sharpened.release()
            val bmp = Bitmap.createBitmap(outRgba.cols(), outRgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, bmp)
            outRgba.release()
            bmp
        } catch (_: UnsatisfiedLinkError) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        }

        if (!cropTo16By9) return enhanced
        val targetW = enhanced.width.coerceAtLeast(16)
        val targetH = (targetW * 9f / 16f).roundToInt().coerceAtLeast(9)
        val slide = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(slide)
        canvas.drawColor(0xFFFFFFFF.toInt())
        val scale = min(targetW.toFloat() / enhanced.width, targetH.toFloat() / enhanced.height)
        val drawW = enhanced.width * scale
        val drawH = enhanced.height * scale
        val left = (targetW - drawW) / 2f
        val top = (targetH - drawH) / 2f
        canvas.drawBitmap(enhanced, null, RectF(left, top, left + drawW, top + drawH), Paint(Paint.FILTER_BITMAP_FLAG))
        if (enhanced !== source) enhanced.recycle()
        return slide
    }

    /**
     * Detects chromatic pen ink (blue, red, green, purple) and light pencil strokes,
     * removes them cleanly via OpenCV inpainting, and keeps dark printed questions crisp.
     */
    fun eraseHandwriting(source: Bitmap): Bitmap {
        return try {
            val rgba = Mat()
            Utils.bitmapToMat(source, rgba)
            val bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            rgba.release()

            val hsv = Mat()
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            val hsvChannels = ArrayList<Mat>(3)
            Core.split(hsv, hsvChannels)
            hsv.release()

            val satMask = Mat()
            val valMask = Mat()
            val colorInkMask = Mat()
            // Chromatic ink: Saturation > 38 and Value < 240
            Imgproc.threshold(hsvChannels[1], satMask, 38.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.threshold(hsvChannels[2], valMask, 240.0, 255.0, Imgproc.THRESH_BINARY_INV)
            Core.bitwise_and(satMask, valMask, colorInkMask)
            satMask.release()
            valMask.release()
            hsvChannels.forEach { it.release() }

            // Also catch light/medium gray pencil marks (118..198) while protecting bold dark printed ink (<100)
            val gray = Mat()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            val pencilMask = Mat()
            Core.inRange(gray, Scalar(118.0), Scalar(198.0), pencilMask)

            val combinedMask = Mat()
            Core.bitwise_or(colorInkMask, pencilMask, combinedMask)
            colorInkMask.release()
            pencilMask.release()

            // Protect dense black printed text (< 85) from accidental dilation bleed
            val darkPrintProtect = Mat()
            Imgproc.threshold(gray, darkPrintProtect, 85.0, 255.0, Imgproc.THRESH_BINARY)
            gray.release()

            val dilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            val expandedMask = Mat()
            Imgproc.dilate(combinedMask, expandedMask, dilateKernel)
            dilateKernel.release()
            combinedMask.release()

            val finalMask = Mat()
            Core.bitwise_and(expandedMask, darkPrintProtect, finalMask)
            expandedMask.release()
            darkPrintProtect.release()

            val cleaned = Mat()
            Photo.inpaint(bgr, finalMask, cleaned, 4.5, Photo.INPAINT_TELEA)
            bgr.release()
            finalMask.release()

            val outRgba = Mat()
            Imgproc.cvtColor(cleaned, outRgba, Imgproc.COLOR_BGR2RGBA)
            cleaned.release()
            val result = Bitmap.createBitmap(outRgba.cols(), outRgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, result)
            outRgba.release()
            result
        } catch (_: UnsatisfiedLinkError) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        }
    }
}
