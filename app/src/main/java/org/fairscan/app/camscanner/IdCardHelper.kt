package org.fairscan.app.camscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

object IdCardHelper {

    fun mergeFrontAndBack(
        front: Bitmap,
        back: Bitmap,
        outputWidth: Int = 2480,  // Standard A4 300dpi width
        outputHeight: Int = 3508 // Standard A4 300dpi height
    ): Bitmap {
        val result = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Clean white background
        canvas.drawColor(Color.WHITE)

        val cardWidth = (outputWidth * 0.78f).toInt()
        val cardHeight = (cardWidth / 1.586f).toInt() // Standard credit card/ID aspect ratio 85.6/53.98

        val left = (outputWidth - cardWidth) / 2f
        val marginY = (outputHeight - (cardHeight * 2)) / 3.2f

        val top1 = marginY
        val top2 = top1 + cardHeight + (marginY * 0.8f)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CCCCCC")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#555555")
            textSize = outputWidth * 0.022f
            style = Paint.Style.FILL
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        // Draw Front Side Label
        canvas.drawText("FRONT SIDE", outputWidth / 2f, top1 - 24f, textPaint)

        // Draw Front Card Bitmap
        val frontRect = RectF(left, top1, left + cardWidth, top1 + cardHeight)
        canvas.drawRoundRect(frontRect, 24f, 24f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            color = Color.WHITE
        })
        canvas.drawBitmap(front, null, frontRect, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.drawRoundRect(frontRect, 24f, 24f, borderPaint)

        // Draw Back Side Label
        canvas.drawText("BACK SIDE", outputWidth / 2f, top2 - 24f, textPaint)

        // Draw Back Card Bitmap
        val backRect = RectF(left, top2, left + cardWidth, top2 + cardHeight)
        canvas.drawRoundRect(backRect, 24f, 24f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            color = Color.WHITE
        })
        canvas.drawBitmap(back, null, backRect, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.drawRoundRect(backRect, 24f, 24f, borderPaint)

        // Draw dashed dividing line in between
        val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E0E0E0")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val dividerY = (top1 + cardHeight + top2) / 2f
        canvas.drawLine(left, dividerY, left + cardWidth, dividerY, dashPaint)

        return result
    }
}
