package org.fairscan.app.camscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.hypot
import kotlin.math.max

object WatermarkHelper {

    fun applyWatermark(
        source: Bitmap,
        text: String,
        opacity: Float = 0.35f,
        rotationDegrees: Float = -30f,
        tileRepeated: Boolean = true
    ): Bitmap {
        if (text.isBlank()) return source

        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            alpha = (opacity.coerceIn(0.05f, 0.95f) * 255).toInt()
            textSize = max(output.width, output.height) / 22f
            style = Paint.Style.FILL
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        if (tileRepeated) {
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            val stepX = bounds.width() * 2.2f
            val stepY = bounds.height() * 4.5f

            val diagonal = hypot(output.width.toDouble(), output.height.toDouble()).toFloat()

            canvas.save()
            canvas.translate(output.width / 2f, output.height / 2f)
            canvas.rotate(rotationDegrees)

            var y = -diagonal
            while (y <= diagonal) {
                var x = -diagonal
                while (x <= diagonal) {
                    canvas.drawText(text, x, y, paint)
                    x += stepX
                }
                y += stepY
            }
            canvas.restore()
        } else {
            // Single large centered stamp
            paint.textSize = max(output.width, output.height) / 12f
            canvas.save()
            canvas.translate(output.width / 2f, output.height / 2f)
            canvas.rotate(rotationDegrees)
            canvas.drawText(text, 0f, 0f, paint)
            canvas.restore()
        }

        return output
    }
}
