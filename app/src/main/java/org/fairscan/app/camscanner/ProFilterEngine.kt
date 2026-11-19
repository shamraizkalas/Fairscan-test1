package org.fairscan.app.camscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import org.fairscan.app.R
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.pow

enum class FilterCategory(val labelRes: Int) {
    ALL(R.string.filter_cat_all),
    DOCUMENT(R.string.filter_cat_document),
    BLACK_AND_WHITE(R.string.filter_cat_bw),
    COLOR(R.string.filter_cat_color),
    ARTISTIC(R.string.filter_cat_artistic),
}

enum class ProFilterPreset(
    val labelRes: Int,
    val category: FilterCategory,
) {
    // Document enhancement presets (8)
    ORIGINAL(R.string.filter_original, FilterCategory.DOCUMENT),
    MAGIC_COLOR(R.string.filter_magic_color, FilterCategory.DOCUMENT),
    SUPER_CRISP(R.string.filter_super_crisp, FilterCategory.DOCUMENT),
    AUTO_ENHANCE(R.string.filter_auto_enhance, FilterCategory.DOCUMENT),
    LIGHTEN(R.string.filter_lighten, FilterCategory.DOCUMENT),
    WHITEBOARD(R.string.filter_whiteboard, FilterCategory.DOCUMENT),
    CLEAR_TEXT(R.string.filter_clear_text, FilterCategory.DOCUMENT),
    WARM_PAPER(R.string.filter_warm_paper, FilterCategory.DOCUMENT),

    // B&W & Ink presets (8)
    GRAYSCALE(R.string.filter_grayscale, FilterCategory.BLACK_AND_WHITE),
    SILVER_GRAY(R.string.filter_silver_gray, FilterCategory.BLACK_AND_WHITE),
    BLACK_AND_WHITE(R.string.filter_bw, FilterCategory.BLACK_AND_WHITE),
    BW_ECO(R.string.filter_bw_eco, FilterCategory.BLACK_AND_WHITE),
    HIGH_CONTRAST(R.string.filter_high_contrast, FilterCategory.BLACK_AND_WHITE),
    FINE_PRINT(R.string.filter_fine_print, FilterCategory.BLACK_AND_WHITE),
    NEWSPAPER(R.string.filter_newspaper, FilterCategory.BLACK_AND_WHITE),
    BLUEPRINT(R.string.filter_blueprint, FilterCategory.BLACK_AND_WHITE),

    // Color & Studio Light presets (7)
    VIVID(R.string.filter_vivid, FilterCategory.COLOR),
    WARM_TONE(R.string.filter_warm_tone, FilterCategory.COLOR),
    COOL_BLUE(R.string.filter_cool_blue, FilterCategory.COLOR),
    EMERALD(R.string.filter_emerald, FilterCategory.COLOR),
    ROSE_PRINT(R.string.filter_rose_print, FilterCategory.COLOR),
    SUNLIGHT(R.string.filter_sunlight, FilterCategory.COLOR),
    DEEP_CONTRAST(R.string.filter_deep_contrast, FilterCategory.COLOR),

    // Artistic & Archive presets (6)
    SEPIA(R.string.filter_sepia, FilterCategory.ARTISTIC),
    VINTAGE(R.string.filter_vintage, FilterCategory.ARTISTIC),
    NOIR(R.string.filter_noir, FilterCategory.ARTISTIC),
    DUOTONE_TEAL(R.string.filter_duotone_teal, FilterCategory.ARTISTIC),
    PENCIL_SKETCH(R.string.filter_pencil_sketch, FilterCategory.ARTISTIC),
    INVERT(R.string.filter_invert, FilterCategory.ARTISTIC);

    companion object {
        fun forCategory(category: FilterCategory): List<ProFilterPreset> =
            if (category == FilterCategory.ALL) entries else entries.filter { it.category == category }
    }
}

data class ImageAdjustments(
    val brightness: Float = 0f,   // -50f .. +50f
    val contrast: Float = 1f,     // 0.60f .. 1.60f
    val sharpness: Float = 0f,    // 0f .. 1.0f
    val saturation: Float = 1f,   // 0f .. 2.0f
    val warmth: Float = 0f,       // -50f .. +50f
    val exposure: Float = 0f,     // -1.0f .. +1.0f
    val fade: Float = 0f,         // 0f .. 1.0f
) {
    val hasColorMatrixChanges: Boolean
        get() = abs(brightness) >= 0.5f ||
            abs(contrast - 1f) >= 0.02f ||
            abs(saturation - 1f) >= 0.02f ||
            abs(warmth) >= 0.5f ||
            abs(exposure) >= 0.02f ||
            fade >= 0.02f

    val isDefault: Boolean
        get() = !hasColorMatrixChanges && sharpness < 0.02f
}

object ProFilterEngine {

    fun generateFilterThumbnails(source: Bitmap, maxDim: Int = 112): Map<ProFilterPreset, Bitmap> {
        val scale = minOf(maxDim.toFloat() / source.width, maxDim.toFloat() / source.height, 1f)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(16),
                (source.height * scale).toInt().coerceAtLeast(16),
                true
            )
        } else {
            source
        }
        return ProFilterPreset.entries.associateWith { preset ->
            runCatching { applyPreset(small, preset) }
                .getOrElse { small.copy(Bitmap.Config.ARGB_8888, false) }
        }
    }

    fun applyFilterAndAdjustments(
        source: Bitmap,
        preset: ProFilterPreset,
        adjustments: ImageAdjustments,
    ): Bitmap {
        val base = applyPreset(source, preset)
        if (adjustments.isDefault) return base

        var current = base
        if (adjustments.hasColorMatrixChanges) {
            val adjusted = applyColorAdjustments(current, adjustments)
            if (current !== source && current !== base) current.recycle()
            current = adjusted
        }
        if (adjustments.sharpness >= 0.02f) {
            val sharpened = applySharpness(current, adjustments.sharpness)
            if (current !== source && current !== base) current.recycle()
            current = sharpened
        }
        return current
    }

    fun applyPreset(source: Bitmap, preset: ProFilterPreset): Bitmap {
        return when (preset) {
            ProFilterPreset.ORIGINAL -> source.copy(Bitmap.Config.ARGB_8888, false)

            // Fast pure-Android ColorMatrix presets (also JVM/Robolectric safe)
            ProFilterPreset.VIVID -> applyColorMatrix(source, vividMatrix())
            ProFilterPreset.WARM_TONE -> applyColorMatrix(source, warmthAndLightMatrix(warmth = 28f, contrast = 1.06f, brightness = 6f, saturation = 1.08f))
            ProFilterPreset.COOL_BLUE -> applyColorMatrix(source, warmthAndLightMatrix(warmth = -30f, contrast = 1.06f, brightness = 4f, saturation = 1.04f))
            ProFilterPreset.EMERALD -> applyColorMatrix(source, channelTintMatrix(rScale = 0.90f, gScale = 1.12f, bScale = 1.02f, sat = 1.15f, contrast = 1.08f))
            ProFilterPreset.ROSE_PRINT -> applyColorMatrix(source, channelTintMatrix(rScale = 1.12f, gScale = 0.94f, bScale = 1.04f, sat = 1.10f, contrast = 1.05f, bias = 4f))
            ProFilterPreset.SUNLIGHT -> applyColorMatrix(source, warmthAndLightMatrix(warmth = 24f, contrast = 1.10f, brightness = 16f, saturation = 1.18f))
            ProFilterPreset.DEEP_CONTRAST -> applyColorMatrix(source, contrastSaturationMatrix(contrast = 1.38f, brightness = -10f, saturation = 1.15f))

            ProFilterPreset.SEPIA -> applyColorMatrix(source, sepiaMatrix())
            ProFilterPreset.VINTAGE -> applyColorMatrix(source, vintageMatrix())
            ProFilterPreset.NOIR -> applyColorMatrix(source, noirMatrix())
            ProFilterPreset.DUOTONE_TEAL -> applyColorMatrix(source, duotoneTealMatrix())
            ProFilterPreset.INVERT -> applyColorMatrix(source, invertMatrix())
            ProFilterPreset.SILVER_GRAY -> applyColorMatrix(source, silverGrayMatrix())
            ProFilterPreset.NEWSPAPER -> applyColorMatrix(source, newspaperMatrix())
            ProFilterPreset.WARM_PAPER -> applyColorMatrix(source, warmPaperMatrix())

            // OpenCV-accelerated document enhancement, thresholding & sketch presets
            ProFilterPreset.MAGIC_COLOR,
            ProFilterPreset.SUPER_CRISP,
            ProFilterPreset.AUTO_ENHANCE,
            ProFilterPreset.LIGHTEN,
            ProFilterPreset.WHITEBOARD,
            ProFilterPreset.CLEAR_TEXT,
            ProFilterPreset.GRAYSCALE,
            ProFilterPreset.BLACK_AND_WHITE,
            ProFilterPreset.BW_ECO,
            ProFilterPreset.HIGH_CONTRAST,
            ProFilterPreset.FINE_PRINT,
            ProFilterPreset.BLUEPRINT,
            ProFilterPreset.PENCIL_SKETCH -> applyOpenCvPreset(source, preset)
        }
    }

    private fun applyOpenCvPreset(source: Bitmap, preset: ProFilterPreset): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(source, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()

        val outBgr: Mat = when (preset) {
            ProFilterPreset.MAGIC_COLOR -> magicColor(bgr)
            ProFilterPreset.SUPER_CRISP -> superCrisp(bgr)
            ProFilterPreset.AUTO_ENHANCE -> autoEnhance(bgr)
            ProFilterPreset.LIGHTEN -> lightenDocument(bgr)
            ProFilterPreset.WHITEBOARD -> whiteboardBoost(bgr)
            ProFilterPreset.CLEAR_TEXT -> clearTextIllumination(bgr)
            ProFilterPreset.GRAYSCALE -> claheGrayscale(bgr, clipLimit = 2.0)
            ProFilterPreset.BLACK_AND_WHITE -> adaptiveBw(bgr, blockSize = 31, c = 12.0)
            ProFilterPreset.BW_ECO -> adaptiveBw(bgr, blockSize = 21, c = 18.0)
            ProFilterPreset.HIGH_CONTRAST -> otsuHighContrastBw(bgr)
            ProFilterPreset.FINE_PRINT -> adaptiveBw(bgr, blockSize = 13, c = 7.0)
            ProFilterPreset.BLUEPRINT -> blueprintEffect(bgr)
            ProFilterPreset.PENCIL_SKETCH -> pencilSketchEffect(bgr)
            else -> bgr.clone()
        }
        bgr.release()

        val outRgba = Mat()
        Imgproc.cvtColor(outBgr, outRgba, Imgproc.COLOR_BGR2RGBA)
        outBgr.release()
        val result = Bitmap.createBitmap(outRgba.cols(), outRgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outRgba, result)
        outRgba.release()
        return result
    }

    private fun magicColor(bgr: Mat): Mat {
        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val channels = ArrayList<Mat>(3)
        Core.split(lab, channels)
        val clahe = Imgproc.createCLAHE(2.4, Size(8.0, 8.0))
        val lEnhanced = Mat()
        clahe.apply(channels[0], lEnhanced)
        channels[0].release()
        channels[0] = lEnhanced

        val merged = Mat()
        Core.merge(channels, merged)
        channels.forEach { it.release() }
        lab.release()

        val enhancedBgr = Mat()
        Imgproc.cvtColor(merged, enhancedBgr, Imgproc.COLOR_Lab2BGR)
        merged.release()

        val blurred = Mat()
        Imgproc.GaussianBlur(enhancedBgr, blurred, Size(0.0, 0.0), 1.8)
        val pop = Mat()
        Core.addWeighted(enhancedBgr, 1.35, blurred, -0.35, 6.0, pop)
        enhancedBgr.release()
        blurred.release()
        return pop
    }

    private fun superCrisp(bgr: Mat): Mat {
        val norm = clearTextIllumination(bgr)
        val lab = Mat()
        Imgproc.cvtColor(norm, lab, Imgproc.COLOR_BGR2Lab)
        norm.release()

        val channels = ArrayList<Mat>(3)
        Core.split(lab, channels)
        val clahe = Imgproc.createCLAHE(3.2, Size(8.0, 8.0))
        val lCrisp = Mat()
        clahe.apply(channels[0], lCrisp)
        channels[0].release()
        channels[0] = lCrisp

        val merged = Mat()
        Core.merge(channels, merged)
        channels.forEach { it.release() }
        lab.release()

        val sharpBgr = Mat()
        Imgproc.cvtColor(merged, sharpBgr, Imgproc.COLOR_Lab2BGR)
        merged.release()

        val blur = Mat()
        Imgproc.GaussianBlur(sharpBgr, blur, Size(0.0, 0.0), 1.4)
        val crisp = Mat()
        Core.addWeighted(sharpBgr, 1.55, blur, -0.55, 4.0, crisp)
        sharpBgr.release()
        blur.release()
        return crisp
    }

    private fun autoEnhance(bgr: Mat): Mat {
        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val channels = ArrayList<Mat>(3)
        Core.split(lab, channels)
        val normL = Mat()
        Core.normalize(channels[0], normL, 12.0, 250.0, Core.NORM_MINMAX)
        channels[0].release()
        channels[0] = normL
        val merged = Mat()
        Core.merge(channels, merged)
        channels.forEach { it.release() }
        lab.release()

        val out = Mat()
        Imgproc.cvtColor(merged, out, Imgproc.COLOR_Lab2BGR)
        merged.release()
        val boosted = Mat()
        out.convertTo(boosted, -1, 1.08, 4.0)
        out.release()
        return boosted
    }

    private fun lightenDocument(bgr: Mat): Mat {
        val out = Mat()
        bgr.convertTo(out, -1, 1.12, 24.0)
        return out
    }

    private fun whiteboardBoost(bgr: Mat): Mat {
        val cleared = clearTextIllumination(bgr)
        val out = Mat()
        cleared.convertTo(out, -1, 1.24, -14.0)
        cleared.release()
        return out
    }

    private fun clearTextIllumination(bgr: Mat): Mat {
        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val channels = ArrayList<Mat>(3)
        Core.split(lab, channels)

        val lFloat = Mat()
        channels[0].convertTo(lFloat, CvType.CV_32F)

        val kDim = (minOf(bgr.cols(), bgr.rows()) / 12).coerceIn(15, 75) or 1
        val bgEst = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(kDim.toDouble(), kDim.toDouble()))
        Imgproc.morphologyEx(lFloat, bgEst, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()
        Imgproc.GaussianBlur(bgEst, bgEst, Size(kDim.toDouble(), kDim.toDouble()), 0.0)

        val divided = Mat()
        Core.divide(lFloat, bgEst, divided, 244.0)
        lFloat.release()
        bgEst.release()

        val lClean = Mat()
        divided.convertTo(lClean, CvType.CV_8UC1)
        divided.release()
        channels[0].release()
        channels[0] = lClean

        val merged = Mat()
        Core.merge(channels, merged)
        channels.forEach { it.release() }
        lab.release()

        val out = Mat()
        Imgproc.cvtColor(merged, out, Imgproc.COLOR_Lab2BGR)
        merged.release()
        return out
    }

    private fun claheGrayscale(bgr: Mat, clipLimit: Double): Mat {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val clahe = Imgproc.createCLAHE(clipLimit, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(gray, enhanced)
        gray.release()
        val res = Mat()
        Imgproc.cvtColor(enhanced, res, Imgproc.COLOR_GRAY2BGR)
        enhanced.release()
        return res
    }

    private fun adaptiveBw(bgr: Mat, blockSize: Int, c: Double): Mat {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)
        gray.release()
        val bw = Mat()
        Imgproc.adaptiveThreshold(
            blurred,
            bw,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            blockSize,
            c
        )
        blurred.release()
        val out = Mat()
        Imgproc.cvtColor(bw, out, Imgproc.COLOR_GRAY2BGR)
        bw.release()
        return out
    }

    private fun otsuHighContrastBw(bgr: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(3.0, 3.0), 0.0)
        gray.release()
        val bw = Mat()
        Imgproc.threshold(blur, bw, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        blur.release()
        val out = Mat()
        Imgproc.cvtColor(bw, out, Imgproc.COLOR_GRAY2BGR)
        bw.release()
        return out
    }

    private fun blueprintEffect(bgr: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val bw = Mat()
        Imgproc.adaptiveThreshold(
            gray,
            bw,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            25,
            10.0
        )
        gray.release()

        // Deep cyan-blue background (#103C78 in BGR: B=120, G=60, R=16) with crisp white lines
        val out = Mat(bw.size(), CvType.CV_8UC3, Scalar(120.0, 60.0, 16.0))
        val white = Mat(bw.size(), CvType.CV_8UC3, Scalar(250.0, 248.0, 242.0))
        white.copyTo(out, bw)
        white.release()
        bw.release()
        return out
    }

    private fun pencilSketchEffect(bgr: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val inv = Mat()
        Core.bitwise_not(gray, inv)
        val blurInv = Mat()
        Imgproc.GaussianBlur(inv, blurInv, Size(21.0, 21.0), 0.0)
        inv.release()

        val denom = Mat()
        Core.bitwise_not(blurInv, denom)
        blurInv.release()

        val sketch = Mat()
        Core.divide(gray, denom, sketch, 256.0)
        gray.release()
        denom.release()

        val out = Mat()
        Imgproc.cvtColor(sketch, out, Imgproc.COLOR_GRAY2BGR)
        sketch.release()
        return out
    }

    // --- Pure Android ColorMatrix Helpers ---

    private fun applyColorMatrix(source: Bitmap, matrix: ColorMatrix): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    private fun vividMatrix(): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation(1.42f)
        cm.postConcat(contrastSaturationMatrix(contrast = 1.15f, brightness = 4f, saturation = 1f))
        return cm
    }

    private fun silverGrayMatrix(): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    1.08f, 0f, 0f, 0f, 8f,
                    0f, 1.08f, 0f, 0f, 8f,
                    0f, 0f, 1.08f, 0f, 10f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        return cm
    }

    private fun newspaperMatrix(): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        cm.postConcat(contrastSaturationMatrix(contrast = 1.48f, brightness = -8f, saturation = 1f))
        return cm
    }

    private fun warmPaperMatrix(): ColorMatrix = ColorMatrix(
        floatArrayOf(
            1.06f, 0f, 0f, 0f, 10f,
            0f, 1.03f, 0f, 0f, 6f,
            0f, 0f, 0.90f, 0f, -4f,
            0f, 0f, 0f, 1f, 0f,
        )
    )

    private fun sepiaMatrix(): ColorMatrix = ColorMatrix(
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    )

    private fun vintageMatrix(): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation(0.68f)
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    0.96f, 0.08f, 0f, 0f, 18f,
                    0.04f, 0.92f, 0.04f, 0f, 14f,
                    0f, 0.06f, 0.78f, 0f, 22f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        return cm
    }

    private fun noirMatrix(): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        cm.postConcat(contrastSaturationMatrix(contrast = 1.65f, brightness = -18f, saturation = 1f))
        return cm
    }

    private fun duotoneTealMatrix(): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    0.68f, 0f, 0f, 0f, 10f,
                    0f, 1.02f, 0f, 0f, 22f,
                    0f, 0f, 1.00f, 0f, 24f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        return cm
    }

    private fun invertMatrix(): ColorMatrix = ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )

    private fun channelTintMatrix(
        rScale: Float,
        gScale: Float,
        bScale: Float,
        sat: Float = 1f,
        contrast: Float = 1f,
        bias: Float = 0f,
    ): ColorMatrix {
        val cm = ColorMatrix()
        if (abs(sat - 1f) > 0.01f) cm.setSaturation(sat)
        val cShift = (1f - contrast) * 128f + bias
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    rScale * contrast, 0f, 0f, 0f, cShift,
                    0f, gScale * contrast, 0f, 0f, cShift,
                    0f, 0f, bScale * contrast, 0f, cShift,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        return cm
    }

    private fun warmthAndLightMatrix(
        warmth: Float,
        contrast: Float,
        brightness: Float,
        saturation: Float,
    ): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation(saturation)
        val rShift = warmth * 0.7f
        val bShift = -warmth * 0.7f
        val baseShift = brightness + (1f - contrast) * 128f
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, baseShift + rShift,
                    0f, contrast, 0f, 0f, baseShift,
                    0f, 0f, contrast, 0f, baseShift + bShift,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        return cm
    }

    private fun contrastSaturationMatrix(
        contrast: Float,
        brightness: Float,
        saturation: Float,
    ): ColorMatrix {
        val cm = ColorMatrix()
        if (abs(saturation - 1f) > 0.01f) cm.setSaturation(saturation)
        val translate = brightness + (1f - contrast) * 128f
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        return cm
    }

    private fun applyColorAdjustments(source: Bitmap, adjustments: ImageAdjustments): Bitmap {
        val cm = ColorMatrix()

        // 1. Saturation
        if (abs(adjustments.saturation - 1f) >= 0.02f) {
            cm.setSaturation(adjustments.saturation.coerceIn(0f, 2f))
        }

        // 2. Exposure multiplier (2^exposure) combined with Contrast, Brightness, Warmth, and Matte Fade
        val expGain = if (abs(adjustments.exposure) >= 0.02f) 2.0.pow(adjustments.exposure.toDouble()).toFloat() else 1f
        val fadeAmount = adjustments.fade.coerceIn(0f, 1f)
        val fadeCompress = 1f - fadeAmount * 0.24f
        val fadeLift = fadeAmount * 42f

        val totalGain = adjustments.contrast * expGain * fadeCompress
        val baseTranslate = adjustments.brightness + (1f - adjustments.contrast) * 128f + fadeLift
        val rShift = adjustments.warmth * 0.65f
        val gShift = adjustments.warmth * 0.10f
        val bShift = -adjustments.warmth * 0.65f

        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    totalGain, 0f, 0f, 0f, baseTranslate + rShift,
                    0f, totalGain, 0f, 0f, baseTranslate + gShift,
                    0f, 0f, totalGain, 0f, baseTranslate + bShift,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        return applyColorMatrix(source, cm)
    }

    private fun applySharpness(source: Bitmap, amount: Float): Bitmap {
        return try {
            val rgba = Mat()
            Utils.bitmapToMat(source, rgba)
            val blurred = Mat()
            Imgproc.GaussianBlur(rgba, blurred, Size(0.0, 0.0), 2.0)
            val sharpened = Mat()
            val alpha = 1.0 + (amount * 1.4)
            val beta = -(amount * 1.4)
            Core.addWeighted(rgba, alpha, blurred, beta, 0.0, sharpened)
            rgba.release()
            blurred.release()
            val result = Bitmap.createBitmap(sharpened.cols(), sharpened.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(sharpened, result)
            sharpened.release()
            result
        } catch (_: UnsatisfiedLinkError) {
            applyColorAdjustments(source, ImageAdjustments(contrast = 1f + 0.12f * amount))
        }
    }

    fun splitBookSpread(spread: Bitmap): Pair<Bitmap, Bitmap> {
        val halfW = (spread.width / 2).coerceAtLeast(1)
        val rightStart = (spread.width / 2).coerceAtMost(spread.width - 1)
        val rightW = (spread.width - rightStart).coerceAtLeast(1)
        val left = Bitmap.createBitmap(spread, 0, 0, halfW, spread.height)
        val right = Bitmap.createBitmap(spread, rightStart, 0, rightW, spread.height)
        return left to right
    }
}
