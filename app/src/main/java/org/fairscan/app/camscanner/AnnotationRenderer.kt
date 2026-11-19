package org.fairscan.app.camscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

object AnnotationRenderer {

    fun bakeAnnotations(baseBitmap: Bitmap, elements: List<AnnotationElement>): Bitmap {
        if (elements.isEmpty()) return baseBitmap
        val out = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val baseDim = min(w, h)

        for (element in elements) {
            when (element) {
                is AnnotationElement.PenStroke -> {
                    if (element.pointsNorm.size < 2) continue
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        color = element.colorArgb
                        if (element.isHighlighter) {
                            alpha = 110
                            strokeWidth = (element.strokeWidthNorm * baseDim * 2.8f).coerceAtLeast(12f)
                        } else {
                            alpha = 255
                            strokeWidth = (element.strokeWidthNorm * baseDim).coerceAtLeast(3f)
                        }
                    }
                    val path = Path()
                    val first = element.pointsNorm.first()
                    path.moveTo(first.x * w, first.y * h)
                    for (i in 1 until element.pointsNorm.size) {
                        val pt = element.pointsNorm[i]
                        path.lineTo(pt.x * w, pt.y * h)
                    }
                    canvas.drawPath(path, paint)
                }

                is AnnotationElement.RedactBox -> {
                    val left = (min(element.leftNorm, element.rightNorm) * w).toInt().coerceIn(0, out.width - 1)
                    val top = (min(element.topNorm, element.bottomNorm) * h).toInt().coerceIn(0, out.height - 1)
                    val right = (max(element.leftNorm, element.rightNorm) * w).toInt().coerceIn(left + 1, out.width)
                    val bottom = (max(element.topNorm, element.bottomNorm) * h).toInt().coerceIn(top + 1, out.height)
                    val boxW = right - left
                    val boxH = bottom - top
                    if (boxW <= 1 || boxH <= 1) continue

                    if (element.isBlur) {
                        // Pixelated privacy mosaic blur
                        val patch = Bitmap.createBitmap(out, left, top, boxW, boxH)
                        val pixelScaleW = (boxW / 18).coerceIn(2, 32)
                        val pixelScaleH = (boxH / 18).coerceIn(2, 32)
                        val tiny = Bitmap.createScaledBitmap(patch, pixelScaleW, pixelScaleH, true)
                        val pixelated = Bitmap.createScaledBitmap(tiny, boxW, boxH, false)
                        canvas.drawBitmap(pixelated, null, Rect(left, top, right, bottom), null)
                        patch.recycle()
                        tiny.recycle()
                        pixelated.recycle()
                    } else {
                        val blackPaint = Paint().apply {
                            color = Color.BLACK
                            style = Paint.Style.FILL
                        }
                        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), blackPaint)
                    }
                }

                is AnnotationElement.TextAnnotation -> {
                    if (element.text.isBlank()) continue
                    val fontSize = (baseDim * 0.042f * element.sizeScale).coerceIn(20f, 140f)
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = element.colorArgb
                        textSize = fontSize
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    val bounds = Rect()
                    textPaint.getTextBounds(element.text, 0, element.text.length, bounds)
                    val cx = element.xNorm * w
                    val cy = element.yNorm * h
                    val pad = fontSize * 0.28f
                    val bgRect = RectF(
                        cx - bounds.width() / 2f - pad,
                        cy - bounds.height() / 2f - pad,
                        cx + bounds.width() / 2f + pad,
                        cy + bounds.height() / 2f + pad
                    )
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(215, 255, 255, 255)
                        style = Paint.Style.FILL
                    }
                    canvas.drawRoundRect(bgRect, pad, pad, bgPaint)
                    canvas.drawText(
                        element.text,
                        cx - bounds.width() / 2f,
                        cy + bounds.height() / 2f,
                        textPaint
                    )
                }

                is AnnotationElement.BadgeStamp -> {
                    val fontSize = (baseDim * 0.052f).coerceIn(24f, 150f)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = element.colorArgb
                        textSize = fontSize
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        alpha = 220
                    }
                    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = element.colorArgb
                        style = Paint.Style.STROKE
                        strokeWidth = (fontSize * 0.14f).coerceAtLeast(4f)
                        alpha = 220
                    }
                    val bounds = Rect()
                    paint.getTextBounds(element.label, 0, element.label.length, bounds)
                    val cx = element.xNorm * w
                    val cy = element.yNorm * h
                    val padX = fontSize * 0.5f
                    val padY = fontSize * 0.35f
                    canvas.save()
                    canvas.rotate(element.rotationDeg, cx, cy)
                    val rect = RectF(
                        cx - bounds.width() / 2f - padX,
                        cy - bounds.height() / 2f - padY,
                        cx + bounds.width() / 2f + padX,
                        cy + bounds.height() / 2f + padY
                    )
                    canvas.drawRoundRect(rect, fontSize * 0.22f, fontSize * 0.22f, borderPaint)
                    canvas.drawText(
                        element.label,
                        cx - bounds.width() / 2f,
                        cy + bounds.height() / 2f,
                        paint
                    )
                    canvas.restore()
                }
            }
        }
        return out
    }
}
