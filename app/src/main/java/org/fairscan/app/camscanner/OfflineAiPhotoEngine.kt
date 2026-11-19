package org.fairscan.app.camscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class AiPhotoToolMode(val label: String) {
    PROMPT("AI Prompt"),
    AUTO_ENHANCE("Auto Fix"),
    BACKGROUND("Background"),
    HEAL_UPSCALE("Heal & 2x"),
}

enum class AiEnhancePreset(val label: String) {
    BALANCED("Balanced Light"),
    SHADOW_RESCUE("Shadow Rescue"),
    COLOR_POP("Color Pop"),
    DEHAZE("De-Haze"),
}

enum class AiBackgroundEffect(val label: String) {
    NONE("Original"),
    PORTRAIT_BOKEH("Portrait Bokeh"),
    TRANSPARENT_CUTOUT("Cutout PNG"),
    STUDIO_WHITE("Studio White"),
    GRAYSCALE_BG("B&W Backdrop"),
}

enum class AiArtisticStyle(val label: String) {
    NONE("None"),
    OIL_PAINT("Oil Paint"),
    PENCIL_SKETCH("Pencil Sketch"),
    CARTOON("Cartoon Ink"),
    VINTAGE_FILM("Vintage Film"),
    NOIR_DRAMATIC("Noir B&W"),
    VIVID_HDR("HDR Pop"),
    WARM_GOLDEN("Golden Hour"),
    COOL_CYBER("Cool Studio"),
}

data class PromptEditPlan(
    val rawPrompt: String,
    val autoEnhancePreset: AiEnhancePreset? = null,
    val autoEnhanceStrength: Float = 0f,
    val artisticStyle: AiArtisticStyle = AiArtisticStyle.NONE,
    val backgroundEffect: AiBackgroundEffect? = null,
    val blurIntensity: Float = 0.68f,
    val brightnessDelta: Float = 0f,
    val contrastMultiplier: Float = 1f,
    val saturationMultiplier: Float = 1f,
    val warmthDelta: Float = 0f,
    val exposureDelta: Float = 0f,
    val fadeAmount: Float = 0f,
    val sharpenStrength: Float = 0f,
    val upscale2x: Boolean = false,
    val appliedBadges: List<String> = emptyList(),
    val unrecognizedHint: String? = null,
) {
    val hasChanges: Boolean get() = appliedBadges.isNotEmpty()
}

object OfflinePromptEditEngine {

    fun parse(prompt: String): PromptEditPlan {
        val clean = prompt.trim()
        if (clean.isEmpty()) {
            return PromptEditPlan(
                rawPrompt = prompt,
                unrecognizedHint = "Enter an edit prompt or pick a quick preset chip."
            )
        }

        val lower = clean.lowercase()
        val intensity = when {
            containsAny(lower, "strong", "heavy", "dramatic", "intense", "max", "high", "ultra", "deep") -> 1.35f
            containsAny(lower, "subtle", "slight", "soft", "gentle", "light touch", "mild") -> 0.65f
            else -> 1.0f
        }

        var autoPreset: AiEnhancePreset? = null
        var autoStrength = 0f
        var artisticStyle = AiArtisticStyle.NONE
        var bgEffect: AiBackgroundEffect? = null
        var blurIntensity = (0.68f * intensity).coerceIn(0.35f, 1.0f)
        var brightness = 0f
        var contrast = 1f
        var saturation = 1f
        var warmth = 0f
        var exposure = 0f
        var fade = 0f
        var sharpen = 0f
        var upscale2x = false
        val badges = mutableListOf<String>()

        // 1. Background transformations
        when {
            containsAny(lower, "transparent", "cutout", "cut out", "remove bg", "remove background", "erase background", "isolate subject", "png") -> {
                bgEffect = AiBackgroundEffect.TRANSPARENT_CUTOUT
                badges.add("Subject Cutout")
            }
            containsAny(lower, "white background", "white backdrop", "studio white", "passport background", "clean background") -> {
                bgEffect = AiBackgroundEffect.STUDIO_WHITE
                badges.add("Studio White Backdrop")
            }
            containsAny(lower, "color splash", "bw background", "b&w background", "grayscale background", "monochrome background") -> {
                bgEffect = AiBackgroundEffect.GRAYSCALE_BG
                badges.add("B&W Backdrop")
            }
            containsAny(lower, "bokeh", "blur background", "blur bg", "portrait mode", "dslr", "shallow depth", "depth blur") -> {
                bgEffect = AiBackgroundEffect.PORTRAIT_BOKEH
                badges.add("Portrait Bokeh")
            }
        }

        // 2. Artistic & Mood styles
        when {
            containsAny(lower, "oil paint", "oil painting", "painterly", "watercolor", "brush stroke") -> {
                artisticStyle = AiArtisticStyle.OIL_PAINT
                badges.add("Oil Paint Style")
            }
            containsAny(lower, "pencil", "sketch", "charcoal", "drawing", "line art") -> {
                artisticStyle = AiArtisticStyle.PENCIL_SKETCH
                badges.add("Pencil Sketch")
            }
            containsAny(lower, "cartoon", "comic", "anime", "illustration", "cel") -> {
                artisticStyle = AiArtisticStyle.CARTOON
                badges.add("Cartoon Ink")
            }
            containsAny(lower, "noir", "dramatic b&w", "black and white", "black & white", "monochrome", "b&w") &&
                bgEffect != AiBackgroundEffect.GRAYSCALE_BG -> {
                artisticStyle = AiArtisticStyle.NOIR_DRAMATIC
                badges.add("Noir B&W")
            }
            containsAny(lower, "vintage", "retro", "film", "sepia", "analog", "polaroid") -> {
                artisticStyle = AiArtisticStyle.VINTAGE_FILM
                badges.add("Vintage Film")
            }
            containsAny(lower, "golden hour", "sunset", "golden") -> {
                artisticStyle = AiArtisticStyle.WARM_GOLDEN
                badges.add("Golden Hour")
            }
            containsAny(lower, "cyber", "teal blue", "cool studio", "arctic", "moonlight") -> {
                artisticStyle = AiArtisticStyle.COOL_CYBER
                badges.add("Cool Studio")
            }
            containsAny(lower, "hdr", "vivid hdr", "hyper") -> {
                artisticStyle = AiArtisticStyle.VIVID_HDR
                badges.add("HDR Pop")
            }
        }

        // 3. One-tap auto enhance & lighting fixes
        when {
            containsAny(lower, "dehaze", "de-haze", "haze", "fog") -> {
                autoPreset = AiEnhancePreset.DEHAZE
                autoStrength = (0.85f * intensity).coerceIn(0.4f, 1f)
                badges.add("De-Haze")
            }
            containsAny(lower, "shadow", "dark areas", "underexposed", "rescue shadow", "fix dark") -> {
                autoPreset = AiEnhancePreset.SHADOW_RESCUE
                autoStrength = (0.88f * intensity).coerceIn(0.4f, 1f)
                badges.add("Shadow Rescue")
            }
            containsAny(lower, "auto enhance", "auto fix", "fix light", "balance light", "clean light", "enhance photo", "improve") -> {
                autoPreset = AiEnhancePreset.BALANCED
                autoStrength = (0.85f * intensity).coerceIn(0.4f, 1f)
                badges.add("AI Auto-Enhance")
            }
        }

        // 4. Fine lighting & color deltas
        if (containsAny(lower, "brighter", "brighten", "lighter", "lighten", "exposure up")) {
            brightness += 18f * intensity
            exposure += 0.22f * intensity
            badges.add("Brighten")
        } else if (containsAny(lower, "darker", "darken", "moody", "low key")) {
            brightness -= 16f * intensity
            exposure -= 0.18f * intensity
            badges.add("Moody Dark")
        }

        if (containsAny(lower, "contrast", "punchy", "punch", "bold") && artisticStyle != AiArtisticStyle.NOIR_DRAMATIC) {
            contrast = (1f + 0.22f * intensity).coerceIn(1.05f, 1.55f)
            badges.add("Contrast Boost")
        }

        if (containsAny(lower, "warm", "warmth", "sunny", "cozy") && artisticStyle != AiArtisticStyle.WARM_GOLDEN) {
            warmth += 22f * intensity
            badges.add("Warm Tone")
        } else if (containsAny(lower, "cool", "cooler", "cold", "ice", "blue tone") && artisticStyle != AiArtisticStyle.COOL_CYBER) {
            warmth -= 22f * intensity
            badges.add("Cool Tone")
        }

        if (containsAny(lower, "vibrant", "vivid", "colorful", "color pop", "saturate", "rich color")) {
            saturation = (1f + 0.35f * intensity).coerceIn(1.1f, 1.85f)
            badges.add("Color Pop")
        } else if (containsAny(lower, "muted", "desaturate", "pastel", "matte", "fade")) {
            saturation = (1f - 0.28f * intensity).coerceIn(0.35f, 0.9f)
            fade = (0.28f * intensity).coerceIn(0.1f, 0.7f)
            badges.add("Soft Matte")
        }

        // 5. Sharpness & 2x Super-Resolution Upscale
        if (containsAny(lower, "upscale", "2x", "super resolution", "hi res", "hires", "high resolution", "double size")) {
            upscale2x = true
            sharpen = max(sharpen, (0.55f * intensity).coerceIn(0.3f, 0.95f))
            badges.add("2x Upscale")
        } else if (containsAny(lower, "sharp", "sharpen", "crisp", "clarity", "detail", "hd", "clear")) {
            sharpen = (0.58f * intensity).coerceIn(0.25f, 0.95f)
            badges.add("Sharpness +${(sharpen * 100).roundToInt()}%")
        }

        val hint = if (badges.isEmpty()) {
            "Try keywords like \"golden hour bokeh\", \"white background\", \"auto enhance crisp\", \"oil painting\", or \"2x upscale\"."
        } else null

        return PromptEditPlan(
            rawPrompt = prompt,
            autoEnhancePreset = autoPreset,
            autoEnhanceStrength = autoStrength,
            artisticStyle = artisticStyle,
            backgroundEffect = bgEffect,
            blurIntensity = blurIntensity,
            brightnessDelta = brightness.coerceIn(-45f, 45f),
            contrastMultiplier = contrast,
            saturationMultiplier = saturation,
            warmthDelta = warmth.coerceIn(-45f, 45f),
            exposureDelta = exposure.coerceIn(-0.8f, 0.8f),
            fadeAmount = fade,
            sharpenStrength = sharpen,
            upscale2x = upscale2x,
            appliedBadges = badges.distinct(),
            unrecognizedHint = hint,
        )
    }

    private fun containsAny(haystack: String, vararg needles: String): Boolean =
        needles.any { haystack.contains(it) }
}

object OfflineAiPhotoEngine {

    private const val MAX_WORK_SIDE_PX = 1600
    private const val MAX_UPSCALE_SIDE_PX = 3000

    fun createWorkingBitmap(source: Bitmap, maxSidePx: Int = MAX_WORK_SIDE_PX): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxSidePx) {
            return if (source.config == Bitmap.Config.ARGB_8888) {
                source.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                source.copy(Bitmap.Config.ARGB_8888, true) ?: source
            }
        }
        val scale = maxSidePx.toFloat() / longest.toFloat()
        val w = (source.width * scale).roundToInt().coerceAtLeast(1)
        val h = (source.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    /**
     * One-tap AI Auto-Enhance & Lighting Fix using OpenCV Gray-World White Balance,
     * LAB CLAHE adaptive contrast/lighting normalization, and tone balance.
     */
    fun autoEnhanceAndFixLight(
        source: Bitmap,
        strength: Float = 0.85f,
        preset: AiEnhancePreset = AiEnhancePreset.BALANCED,
    ): Bitmap {
        val clampedStrength = strength.coerceIn(0f, 1f)
        if (clampedStrength <= 0.01f) return source.copy(Bitmap.Config.ARGB_8888, false)

        return try {
            val rgba = Mat()
            Utils.bitmapToMat(source, rgba)
            val bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            rgba.release()

            val wb = applyGrayWorldWhiteBalance(bgr)
            val lab = Mat()
            Imgproc.cvtColor(wb, lab, Imgproc.COLOR_BGR2Lab)
            wb.release()

            val labChannels = ArrayList<Mat>(3)
            Core.split(lab, labChannels)
            lab.release()

            val clipLimit = when (preset) {
                AiEnhancePreset.BALANCED -> 2.3
                AiEnhancePreset.SHADOW_RESCUE -> 3.0
                AiEnhancePreset.COLOR_POP -> 2.0
                AiEnhancePreset.DEHAZE -> 3.4
            }
            val clahe = Imgproc.createCLAHE(clipLimit, Size(8.0, 8.0))
            val enhancedL = Mat()
            clahe.apply(labChannels[0], enhancedL)

            if (preset == AiEnhancePreset.SHADOW_RESCUE) {
                // Lift crushed shadows gently in L channel
                val lFloat = Mat()
                enhancedL.convertTo(lFloat, CvType.CV_32F, 1.0 / 255.0)
                val boosted = Mat()
                Core.pow(lFloat, 0.84, boosted)
                boosted.convertTo(enhancedL, CvType.CV_8U, 255.0)
                lFloat.release()
                boosted.release()
            } else if (preset == AiEnhancePreset.DEHAZE) {
                Core.normalize(enhancedL, enhancedL, 8.0, 250.0, Core.NORM_MINMAX)
            }

            labChannels[0].release()
            labChannels[0] = enhancedL
            val mergedLab = Mat()
            Core.merge(labChannels, mergedLab)
            labChannels.forEach { it.release() }

            var enhancedBgr = Mat()
            Imgproc.cvtColor(mergedLab, enhancedBgr, Imgproc.COLOR_Lab2BGR)
            mergedLab.release()

            if (preset == AiEnhancePreset.COLOR_POP || preset == AiEnhancePreset.BALANCED) {
                val hsv = Mat()
                Imgproc.cvtColor(enhancedBgr, hsv, Imgproc.COLOR_BGR2HSV)
                val hsvCh = ArrayList<Mat>(3)
                Core.split(hsv, hsvCh)
                hsv.release()
                val satMult = if (preset == AiEnhancePreset.COLOR_POP) 1.28 else 1.12
                Core.multiply(hsvCh[1], Scalar(satMult), hsvCh[1])
                Core.merge(hsvCh, hsv)
                hsvCh.forEach { it.release() }
                enhancedBgr.release()
                enhancedBgr = Mat()
                Imgproc.cvtColor(hsv, enhancedBgr, Imgproc.COLOR_HSV2BGR)
                hsv.release()
            }

            val blended = Mat()
            Core.addWeighted(enhancedBgr, clampedStrength.toDouble(), bgr, (1f - clampedStrength).toDouble(), 0.0, blended)
            enhancedBgr.release()
            bgr.release()

            val outRgba = Mat()
            Imgproc.cvtColor(blended, outRgba, Imgproc.COLOR_BGR2RGBA)
            blended.release()

            val result = Bitmap.createBitmap(outRgba.cols(), outRgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, result)
            outRgba.release()
            result
        } catch (_: UnsatisfiedLinkError) {
            val adj = when (preset) {
                AiEnhancePreset.BALANCED -> ImageAdjustments(
                    brightness = 8f * clampedStrength,
                    contrast = 1f + 0.16f * clampedStrength,
                    saturation = 1f + 0.14f * clampedStrength,
                )
                AiEnhancePreset.SHADOW_RESCUE -> ImageAdjustments(
                    brightness = 18f * clampedStrength,
                    contrast = 1f + 0.08f * clampedStrength,
                    exposure = 0.28f * clampedStrength,
                )
                AiEnhancePreset.COLOR_POP -> ImageAdjustments(
                    contrast = 1f + 0.18f * clampedStrength,
                    saturation = 1f + 0.36f * clampedStrength,
                )
                AiEnhancePreset.DEHAZE -> ImageAdjustments(
                    contrast = 1f + 0.26f * clampedStrength,
                    brightness = -4f * clampedStrength,
                    saturation = 1f + 0.18f * clampedStrength,
                )
            }
            ProFilterEngine.applyFilterAndAdjustments(source, ProFilterPreset.ORIGINAL, adj)
        }
    }

    private fun applyGrayWorldWhiteBalance(bgr: Mat): Mat {
        val channels = ArrayList<Mat>(3)
        Core.split(bgr, channels)
        val means = channels.map { Core.mean(it).`val`[0].coerceAtLeast(1.0) }
        val avgGray = (means[0] + means[1] + means[2]) / 3.0
        for (i in 0 until 3) {
            val gain = (avgGray / means[i]).coerceIn(0.80, 1.25)
            // Soften gain to prevent extreme color casts on monochrome photos
            val smoothGain = 1.0 + (gain - 1.0) * 0.65
            Core.multiply(channels[i], Scalar(smoothGain), channels[i])
        }
        val out = Mat()
        Core.merge(channels, out)
        channels.forEach { it.release() }
        return out
    }

    /**
     * Generates a soft-feathered foreground subject alpha mask using OpenCV GrabCut
     * initialized with a center-prior trimap, falling back cleanly in JVM unit tests.
     */
    fun extractForegroundMask(source: Bitmap, centerBias: Float = 0.58f): Bitmap {
        return try {
            val maxMaskDim = 340
            val scale = min(maxMaskDim.toFloat() / max(source.width, source.height), 1f)
            val mw = (source.width * scale).roundToInt().coerceAtLeast(32)
            val mh = (source.height * scale).roundToInt().coerceAtLeast(32)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(source, mw, mh, true) else source

            val rgba = Mat()
            Utils.bitmapToMat(small, rgba)
            if (small !== source) small.recycle()
            val bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            rgba.release()

            val mask = Mat(mh, mw, CvType.CV_8UC1, Scalar(Imgproc.GC_PR_BGD.toDouble()))

            // Sure background outer rim (border 6%)
            val bx = (mw * 0.06f).roundToInt().coerceAtLeast(2)
            val by = (mh * 0.06f).roundToInt().coerceAtLeast(2)
            Imgproc.rectangle(mask, org.opencv.core.Point(0.0, 0.0), org.opencv.core.Point((mw - 1).toDouble(), by.toDouble()), Scalar(Imgproc.GC_BGD.toDouble()), -1)
            Imgproc.rectangle(mask, org.opencv.core.Point(0.0, (mh - by).toDouble()), org.opencv.core.Point((mw - 1).toDouble(), (mh - 1).toDouble()), Scalar(Imgproc.GC_BGD.toDouble()), -1)
            Imgproc.rectangle(mask, org.opencv.core.Point(0.0, 0.0), org.opencv.core.Point(bx.toDouble(), (mh - 1).toDouble()), Scalar(Imgproc.GC_BGD.toDouble()), -1)
            Imgproc.rectangle(mask, org.opencv.core.Point((mw - bx).toDouble(), 0.0), org.opencv.core.Point((mw - 1).toDouble(), (mh - 1).toDouble()), Scalar(Imgproc.GC_BGD.toDouble()), -1)

            // Probable foreground central box
            val fgW = (mw * centerBias.coerceIn(0.4f, 0.78f)).roundToInt()
            val fgH = (mh * (centerBias + 0.14f).coerceIn(0.5f, 0.84f)).roundToInt()
            val fgLeft = (mw - fgW) / 2
            val fgTop = (mh - fgH) / 2
            Imgproc.rectangle(
                mask,
                org.opencv.core.Point(fgLeft.toDouble(), fgTop.toDouble()),
                org.opencv.core.Point((fgLeft + fgW).toDouble(), (fgTop + fgH).toDouble()),
                Scalar(Imgproc.GC_PR_FGD.toDouble()),
                -1
            )

            // Core sure-foreground seed ellipse
            Imgproc.ellipse(
                mask,
                org.opencv.core.Point(mw / 2.0, mh / 2.0),
                Size(mw * 0.16, mh * 0.22),
                0.0, 0.0, 360.0,
                Scalar(Imgproc.GC_FGD.toDouble()),
                -1
            )

            val bgdModel = Mat()
            val fgdModel = Mat()
            Imgproc.grabCut(bgr, mask, Rect(), bgdModel, fgdModel, 3, Imgproc.GC_INIT_WITH_MASK)
            bgr.release()
            bgdModel.release()
            fgdModel.release()

            val prFgd = Mat()
            val fgd = Mat()
            Core.compare(mask, Scalar(Imgproc.GC_PR_FGD.toDouble()), prFgd, Core.CMP_EQ)
            Core.compare(mask, Scalar(Imgproc.GC_FGD.toDouble()), fgd, Core.CMP_EQ)
            val binary = Mat()
            Core.bitwise_or(prFgd, fgd, binary)
            prFgd.release()
            fgd.release()
            mask.release()

            // Smooth edges and remove tiny islands
            val morphKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            val cleaned = Mat()
            Imgproc.morphologyEx(binary, cleaned, Imgproc.MORPH_CLOSE, morphKernel)
            Imgproc.morphologyEx(cleaned, cleaned, Imgproc.MORPH_OPEN, morphKernel)
            morphKernel.release()
            binary.release()

            val feathered = Mat()
            Imgproc.GaussianBlur(cleaned, feathered, Size(9.0, 9.0), 3.0)
            cleaned.release()

            val fullMask = Mat()
            Imgproc.resize(feathered, fullMask, Size(source.width.toDouble(), source.height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            feathered.release()

            // Build ARGB_8888 mask bitmap (grayscale intensity in alpha & RGB for easy compositing)
            val maskRgba = Mat()
            Imgproc.cvtColor(fullMask, maskRgba, Imgproc.COLOR_GRAY2RGBA)
            val alphaChannels = ArrayList<Mat>(4)
            Core.split(maskRgba, alphaChannels)
            alphaChannels[3] = fullMask.clone()
            Core.merge(alphaChannels, maskRgba)
            alphaChannels.forEach { it.release() }
            fullMask.release()

            val outMask = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(maskRgba, outMask)
            maskRgba.release()
            outMask
        } catch (_: UnsatisfiedLinkError) {
            createFallbackCenterMask(source, centerBias)
        }
    }

    private fun createFallbackCenterMask(source: Bitmap, centerBias: Float): Bitmap {
        val w = source.width
        val h = source.height
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cx = w / 2f
        val cy = h / 2f
        val rx = (w * centerBias * 0.55f).coerceAtLeast(1f)
        val ry = (h * (centerBias + 0.1f) * 0.55f).coerceAtLeast(1f)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val dy = (y - cy) / ry
            for (x in 0 until w) {
                val dx = (x - cx) / rx
                val dist = hypot(dx, dy)
                val alpha = when {
                    dist <= 0.85f -> 255
                    dist >= 1.25f -> 0
                    else -> ((1f - (dist - 0.85f) / 0.40f) * 255f).roundToInt().coerceIn(0, 255)
                }
                pixels[y * w + x] = Color.argb(alpha, alpha, alpha, alpha)
            }
        }
        mask.setPixels(pixels, 0, w, 0, 0, w, h)
        return mask
    }

    fun applyBackgroundEffect(
        source: Bitmap,
        foregroundMask: Bitmap,
        effect: AiBackgroundEffect,
        blurIntensity: Float = 0.68f,
        studioColor: Int = Color.WHITE,
    ): Bitmap {
        if (effect == AiBackgroundEffect.NONE) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val w = source.width
        val h = source.height
        val scaledMask = if (foregroundMask.width != w || foregroundMask.height != h) {
            Bitmap.createScaledBitmap(foregroundMask, w, h, true)
        } else foregroundMask

        // Extract foreground cut with feathered alpha mask
        val fgLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fgCanvas = Canvas(fgLayer)
        fgCanvas.drawBitmap(source, 0f, 0f, null)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        fgCanvas.drawBitmap(scaledMask, 0f, 0f, maskPaint)
        if (scaledMask !== foregroundMask) scaledMask.recycle()

        if (effect == AiBackgroundEffect.TRANSPARENT_CUTOUT) {
            return fgLayer
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        when (effect) {
            AiBackgroundEffect.STUDIO_WHITE -> {
                canvas.drawColor(studioColor)
            }
            AiBackgroundEffect.GRAYSCALE_BG -> {
                val bwBg = try {
                    ProFilterEngine.applyFilterAndAdjustments(
                        source,
                        ProFilterPreset.GRAYSCALE,
                        ImageAdjustments(brightness = -10f, contrast = 1.05f)
                    )
                } catch (_: UnsatisfiedLinkError) {
                    ProFilterEngine.applyFilterAndAdjustments(
                        source,
                        ProFilterPreset.SILVER_GRAY,
                        ImageAdjustments(brightness = -10f, contrast = 1.05f)
                    )
                }
                canvas.drawBitmap(bwBg, 0f, 0f, null)
                if (bwBg !== source) bwBg.recycle()
            }
            AiBackgroundEffect.PORTRAIT_BOKEH -> {
                val blurredBg = blurBitmapOffline(source, blurIntensity)
                canvas.drawBitmap(blurredBg, 0f, 0f, null)
                if (blurredBg !== source) blurredBg.recycle()
            }
            AiBackgroundEffect.NONE, AiBackgroundEffect.TRANSPARENT_CUTOUT -> Unit
        }
        canvas.drawBitmap(fgLayer, 0f, 0f, null)
        fgLayer.recycle()
        return result
    }

    private fun blurBitmapOffline(source: Bitmap, intensity: Float): Bitmap {
        val factor = intensity.coerceIn(0.1f, 1.0f)
        return try {
            val rgba = Mat()
            Utils.bitmapToMat(source, rgba)
            val downScale = 0.35f
            val dw = (rgba.cols() * downScale).roundToInt().coerceAtLeast(16)
            val dh = (rgba.rows() * downScale).roundToInt().coerceAtLeast(16)
            val small = Mat()
            Imgproc.resize(rgba, small, Size(dw.toDouble(), dh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val kSize = (3 + (factor * 24f).roundToInt()).let { if (it % 2 == 0) it + 1 else it }.toDouble()
            val blurredSmall = Mat()
            Imgproc.GaussianBlur(small, blurredSmall, Size(kSize, kSize), kSize * 0.45)
            small.release()
            val blurredFull = Mat()
            Imgproc.resize(blurredSmall, blurredFull, Size(rgba.cols().toDouble(), rgba.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            blurredSmall.release()
            rgba.release()
            val out = Bitmap.createBitmap(blurredFull.cols(), blurredFull.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(blurredFull, out)
            blurredFull.release()
            out
        } catch (_: UnsatisfiedLinkError) {
            val downScale = (0.28f - 0.18f * factor).coerceIn(0.06f, 0.35f)
            val dw = (source.width * downScale).roundToInt().coerceAtLeast(8)
            val dh = (source.height * downScale).roundToInt().coerceAtLeast(8)
            val tiny = Bitmap.createScaledBitmap(source, dw, dh, true)
            val upscaled = Bitmap.createScaledBitmap(tiny, source.width, source.height, true)
            if (tiny !== upscaled) tiny.recycle()
            upscaled
        }
    }

    /**
     * Removes marked objects/blemishes cleanly using OpenCV Telea inpainting.
     */
    fun removeObjectsWithInpaint(source: Bitmap, strokes: List<EraserStroke>): Bitmap {
        return ScanCleanupEngine.applyMagicEraserBrush(source, strokes)
    }

    /**
     * Enhances micro-contrast and crispness and optionally performs 2x Lanczos4 super-resolution upscale.
     */
    fun sharpenAndUpscale(
        source: Bitmap,
        sharpenStrength: Float = 0.6f,
        upscale2x: Boolean = true,
    ): Bitmap {
        val sStrength = sharpenStrength.coerceIn(0f, 1.0f)
        val scale = if (upscale2x) {
            val longest = max(source.width, source.height)
            min(2.0f, MAX_UPSCALE_SIDE_PX.toFloat() / longest.coerceAtLeast(1))
        } else 1.0f

        val targetW = (source.width * scale).roundToInt().coerceAtLeast(source.width)
        val targetH = (source.height * scale).roundToInt().coerceAtLeast(source.height)

        return try {
            val rgba = Mat()
            Utils.bitmapToMat(source, rgba)
            val working = if (targetW > source.width || targetH > source.height) {
                val up = Mat()
                Imgproc.resize(rgba, up, Size(targetW.toDouble(), targetH.toDouble()), 0.0, 0.0, Imgproc.INTER_LANCZOS4)
                rgba.release()
                up
            } else {
                rgba
            }

            if (sStrength > 0.02f) {
                val blurred = Mat()
                Imgproc.GaussianBlur(working, blurred, Size(0.0, 0.0), 1.5)
                val sharp = Mat()
                val alpha = 1.0 + 0.85 * sStrength
                val beta = -0.85 * sStrength
                Core.addWeighted(working, alpha, blurred, beta, 0.0, sharp)
                working.release()
                blurred.release()
                val out = Bitmap.createBitmap(sharp.cols(), sharp.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(sharp, out)
                sharp.release()
                out
            } else {
                val out = Bitmap.createBitmap(working.cols(), working.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(working, out)
                working.release()
                out
            }
        } catch (_: UnsatisfiedLinkError) {
            val resized = if (targetW > source.width || targetH > source.height) {
                Bitmap.createScaledBitmap(source, targetW, targetH, true)
            } else {
                source.copy(Bitmap.Config.ARGB_8888, false)
            }
            if (sStrength > 0.02f) {
                val sharpened = ProFilterEngine.applyFilterAndAdjustments(
                    resized,
                    ProFilterPreset.ORIGINAL,
                    ImageAdjustments(contrast = 1f + 0.10f * sStrength)
                )
                if (resized !== sharpened) resized.recycle()
                sharpened
            } else {
                resized
            }
        }
    }

    fun applyArtisticStyle(source: Bitmap, style: AiArtisticStyle): Bitmap {
        return when (style) {
            AiArtisticStyle.NONE -> source.copy(Bitmap.Config.ARGB_8888, false)
            AiArtisticStyle.OIL_PAINT -> applyOilPaintStyle(source)
            AiArtisticStyle.PENCIL_SKETCH -> try {
                ProFilterEngine.applyPreset(source, ProFilterPreset.PENCIL_SKETCH)
            } catch (_: UnsatisfiedLinkError) {
                ProFilterEngine.applyFilterAndAdjustments(
                    source,
                    ProFilterPreset.SILVER_GRAY,
                    ImageAdjustments(brightness = 18f, contrast = 1.35f)
                )
            }
            AiArtisticStyle.CARTOON -> applyCartoonInkStyle(source)
            AiArtisticStyle.VINTAGE_FILM -> ProFilterEngine.applyPreset(source, ProFilterPreset.VINTAGE)
            AiArtisticStyle.NOIR_DRAMATIC -> ProFilterEngine.applyPreset(source, ProFilterPreset.NOIR)
            AiArtisticStyle.VIVID_HDR -> {
                val enhanced = autoEnhanceAndFixLight(source, strength = 0.85f, preset = AiEnhancePreset.COLOR_POP)
                val finalHdr = ProFilterEngine.applyFilterAndAdjustments(
                    enhanced,
                    ProFilterPreset.VIVID,
                    ImageAdjustments(contrast = 1.12f, sharpness = 0.45f)
                )
                if (enhanced !== source) enhanced.recycle()
                finalHdr
            }
            AiArtisticStyle.WARM_GOLDEN -> ProFilterEngine.applyFilterAndAdjustments(
                source,
                ProFilterPreset.WARM_TONE,
                ImageAdjustments(brightness = 8f, contrast = 1.10f, saturation = 1.18f, warmth = 24f)
            )
            AiArtisticStyle.COOL_CYBER -> ProFilterEngine.applyFilterAndAdjustments(
                source,
                ProFilterPreset.COOL_BLUE,
                ImageAdjustments(contrast = 1.16f, saturation = 1.15f, warmth = -24f)
            )
        }
    }

    private fun applyOilPaintStyle(source: Bitmap): Bitmap {
        return try {
            val rgba = Mat()
            Utils.bitmapToMat(source, rgba)
            val bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            rgba.release()

            var smooth1 = Mat()
            var smooth2 = Mat()
            Imgproc.bilateralFilter(bgr, smooth1, 9, 65.0, 65.0)
            Imgproc.bilateralFilter(smooth1, smooth2, 9, 65.0, 65.0)
            bgr.release()
            smooth1.release()

            val outRgba = Mat()
            Imgproc.cvtColor(smooth2, outRgba, Imgproc.COLOR_BGR2RGBA)
            smooth2.release()
            val bmp = Bitmap.createBitmap(outRgba.cols(), outRgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, bmp)
            outRgba.release()
            val warmPop = ProFilterEngine.applyFilterAndAdjustments(
                bmp,
                ProFilterPreset.ORIGINAL,
                ImageAdjustments(contrast = 1.14f, saturation = 1.24f, sharpness = 0.15f)
            )
            bmp.recycle()
            warmPop
        } catch (_: UnsatisfiedLinkError) {
            ProFilterEngine.applyFilterAndAdjustments(
                source,
                ProFilterPreset.SUNLIGHT,
                ImageAdjustments(contrast = 1.16f, saturation = 1.28f, fade = 0.12f)
            )
        }
    }

    private fun applyCartoonInkStyle(source: Bitmap): Bitmap {
        return try {
            val rgba = Mat()
            Utils.bitmapToMat(source, rgba)
            val bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            rgba.release()

            val gray = Mat()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.medianBlur(gray, gray, 5)
            val edges = Mat()
            Imgproc.adaptiveThreshold(gray, edges, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, 9, 7.0)
            gray.release()

            val colorSmooth = Mat()
            Imgproc.bilateralFilter(bgr, colorSmooth, 9, 75.0, 75.0)
            bgr.release()

            val edgesBgr = Mat()
            Imgproc.cvtColor(edges, edgesBgr, Imgproc.COLOR_GRAY2BGR)
            edges.release()

            val cartoon = Mat()
            Core.bitwise_and(colorSmooth, edgesBgr, cartoon)
            colorSmooth.release()
            edgesBgr.release()

            val outRgba = Mat()
            Imgproc.cvtColor(cartoon, outRgba, Imgproc.COLOR_BGR2RGBA)
            cartoon.release()
            val bmp = Bitmap.createBitmap(outRgba.cols(), outRgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, bmp)
            outRgba.release()
            bmp
        } catch (_: UnsatisfiedLinkError) {
            ProFilterEngine.applyFilterAndAdjustments(
                source,
                ProFilterPreset.VIVID,
                ImageAdjustments(contrast = 1.32f, saturation = 1.40f)
            )
        }
    }

    fun executePromptPlan(
        source: Bitmap,
        plan: PromptEditPlan,
        cachedMask: Bitmap? = null,
    ): Pair<Bitmap, Bitmap?> {
        var current = source.copy(Bitmap.Config.ARGB_8888, false)
        var outMask = cachedMask

        if (plan.autoEnhancePreset != null && plan.autoEnhanceStrength > 0.01f) {
            val next = autoEnhanceAndFixLight(current, plan.autoEnhanceStrength, plan.autoEnhancePreset)
            if (next !== current) current.recycle()
            current = next
        }

        if (plan.artisticStyle != AiArtisticStyle.NONE) {
            val next = applyArtisticStyle(current, plan.artisticStyle)
            if (next !== current) current.recycle()
            current = next
        }

        val fineAdj = ImageAdjustments(
            brightness = plan.brightnessDelta,
            contrast = plan.contrastMultiplier,
            sharpness = 0f,
            saturation = plan.saturationMultiplier,
            warmth = plan.warmthDelta,
            exposure = plan.exposureDelta,
            fade = plan.fadeAmount,
        )
        if (!fineAdj.isDefault) {
            val next = ProFilterEngine.applyFilterAndAdjustments(current, ProFilterPreset.ORIGINAL, fineAdj)
            if (next !== current) current.recycle()
            current = next
        }

        if (plan.backgroundEffect != null && plan.backgroundEffect != AiBackgroundEffect.NONE) {
            val mask = outMask ?: extractForegroundMask(source).also { outMask = it }
            val next = applyBackgroundEffect(current, mask, plan.backgroundEffect, plan.blurIntensity)
            if (next !== current) current.recycle()
            current = next
        }

        if (plan.sharpenStrength > 0.02f || plan.upscale2x) {
            val next = sharpenAndUpscale(current, plan.sharpenStrength.coerceAtLeast(0.35f), plan.upscale2x)
            if (next !== current) current.recycle()
            current = next
        }

        return current to outMask
    }
}
