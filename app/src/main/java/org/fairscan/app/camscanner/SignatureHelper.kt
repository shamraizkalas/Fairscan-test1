package org.fairscan.app.camscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset

data class SignaturePath(
    val points: List<Offset>,
    val color: Int,
    val strokeWidth: Float
)

object SignatureHelper {

    fun createSignatureBitmap(
        paths: List<SignaturePath>,
        viewWidth: Int,
        viewHeight: Int,
        padding: Int = 16
    ): Bitmap? {
        if (paths.isEmpty() || viewWidth <= 0 || viewHeight <= 0) return null

        val bitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        for (sigPath in paths) {
            if (sigPath.points.size < 2) continue
            paint.color = sigPath.color
            paint.strokeWidth = sigPath.strokeWidth

            val path = Path()
            path.moveTo(sigPath.points.first().x, sigPath.points.first().y)
            for (i in 1 until sigPath.points.size) {
                val pt = sigPath.points[i]
                path.lineTo(pt.x, pt.y)
            }
            canvas.drawPath(path, paint)
        }

        val trimmed = SignatureStampExtractor.trimTransparentBounds(bitmap, padding = padding)
        if (trimmed != null && trimmed !== bitmap) {
            bitmap.recycle()
            return trimmed
        }
        return bitmap
    }

    fun mergeSignatureOnBitmap(
        pageBitmap: Bitmap,
        signatureBitmap: Bitmap,
        positionNormalizedX: Float = 0.5f,
        positionNormalizedY: Float = 0.75f,
        scaleFactor: Float = 0.35f,
        rotationDeg: Float = 0f,
        opacity: Float = 1f,
    ): Bitmap {
        val result = pageBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val targetWidth = result.width * scaleFactor
        val targetHeight = targetWidth * (signatureBitmap.height.toFloat() / signatureBitmap.width.coerceAtLeast(1).toFloat())

        val cx = result.width * positionNormalizedX
        val cy = result.height * positionNormalizedY

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (opacity.coerceIn(0.1f, 1f) * 255).toInt()
        }

        canvas.save()
        canvas.translate(cx, cy)
        if (rotationDeg != 0f) {
            canvas.rotate(rotationDeg)
        }
        val destRect = RectF(
            -targetWidth / 2f,
            -targetHeight / 2f,
            targetWidth / 2f,
            targetHeight / 2f
        )
        canvas.drawBitmap(signatureBitmap, null, destRect, paint)
        canvas.restore()

        return result
    }
}
