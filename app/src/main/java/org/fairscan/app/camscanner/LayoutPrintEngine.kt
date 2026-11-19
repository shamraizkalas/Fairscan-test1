package org.fairscan.app.camscanner

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

enum class CollageLayout(val label: String, val cols: Int, val rows: Int) {
    ONE_BY_TWO("1 × 2 Vertical", 1, 2),
    TWO_BY_ONE("2 × 1 Side-by-Side", 2, 1),
    TWO_BY_TWO("2 × 2 Grid", 2, 2),
    TWO_BY_THREE("2 × 3 Grid", 2, 3),
}

object LayoutPrintEngine {

    /**
     * Stitches multiple scanned pages vertically into one seamless high-res long scroll image.
     */
    fun stitchLongImage(
        bitmaps: List<Bitmap>,
        addDividers: Boolean = true,
        targetWidthPx: Int = 1240,
        maxTotalHeightPx: Int = 12000,
    ): Bitmap {
        require(bitmaps.isNotEmpty()) { "At least 1 bitmap is required for long stitch" }
        val width = targetWidthPx.coerceAtLeast(320)
        val dividerH = if (addDividers && bitmaps.size > 1) 12 else 0

        val rawHeights = bitmaps.map { bmp ->
            val ratio = bmp.height.toFloat() / bmp.width.coerceAtLeast(1)
            (width * ratio).roundToInt().coerceAtLeast(1)
        }
        val rawTotalH = rawHeights.sum() + dividerH * (bitmaps.size - 1)
        val heightScale = if (rawTotalH > maxTotalHeightPx) {
            maxTotalHeightPx.toFloat() / rawTotalH.toFloat()
        } else 1f

        val finalWidth = (width * heightScale).roundToInt().coerceAtLeast(240)
        val finalHeights = rawHeights.map { (it * heightScale).roundToInt().coerceAtLeast(1) }
        val finalDividerH = (dividerH * heightScale).roundToInt()
        val totalHeight = (finalHeights.sum() + finalDividerH * (bitmaps.size - 1)).coerceAtLeast(1)

        val result = Bitmap.createBitmap(finalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)

        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val dividerPaint = Paint().apply {
            color = Color.argb(45, 0, 0, 0)
            strokeWidth = 2f
        }

        var y = 0
        bitmaps.forEachIndexed { index, bmp ->
            val h = finalHeights[index]
            canvas.drawBitmap(bmp, null, RectF(0f, y.toFloat(), finalWidth.toFloat(), (y + h).toFloat()), bitmapPaint)
            y += h
            if (addDividers && index < bitmaps.lastIndex && finalDividerH > 0) {
                val midY = y + finalDividerH / 2f
                canvas.drawLine(24f, midY, finalWidth - 24f, midY, dividerPaint)
                y += finalDividerH
            }
        }
        return result
    }

    /**
     * Composes up to cols*rows images onto a crisp printable A4 aspect collage sheet.
     */
    fun createCollageBitmap(
        bitmaps: List<Bitmap>,
        layout: CollageLayout,
        addCutGuides: Boolean = true,
        outputWidthPx: Int = 1654,
        outputHeightPx: Int = 2339,
    ): Bitmap {
        val result = Bitmap.createBitmap(outputWidthPx, outputHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)

        val margin = 48f
        val gap = 28f
        val cols = layout.cols
        val rows = layout.rows
        val cellW = (outputWidthPx - margin * 2 - gap * (cols - 1)) / cols
        val cellH = (outputHeightPx - margin * 2 - gap * (rows - 1)) / rows

        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(85, 110, 120, 130)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        }

        val maxSlots = cols * rows
        for (slot in 0 until min(bitmaps.size, maxSlots)) {
            val r = slot / cols
            val c = slot % cols
            val cellLeft = margin + c * (cellW + gap)
            val cellTop = margin + r * (cellH + gap)
            val cellRect = RectF(cellLeft, cellTop, cellLeft + cellW, cellTop + cellH)

            val bmp = bitmaps[slot]
            val innerPad = 10f
            val availW = (cellW - innerPad * 2).coerceAtLeast(10f)
            val availH = (cellH - innerPad * 2).coerceAtLeast(10f)
            val scale = min(availW / bmp.width.coerceAtLeast(1), availH / bmp.height.coerceAtLeast(1))
            val drawW = bmp.width * scale
            val drawH = bmp.height * scale
            val drawLeft = cellLeft + (cellW - drawW) / 2f
            val drawTop = cellTop + (cellH - drawH) / 2f

            canvas.drawBitmap(bmp, null, RectF(drawLeft, drawTop, drawLeft + drawW, drawTop + drawH), bitmapPaint)
            if (addCutGuides) {
                canvas.drawRect(cellRect, guidePaint)
            }
        }
        return result
    }

    /**
     * Launches Android's native Wireless Print / System Print spooler for any list of bitmaps.
     */
    fun printBitmaps(
        activity: Activity,
        jobName: String,
        bitmaps: List<Bitmap>,
    ) {
        if (bitmaps.isEmpty()) return
        val copies = bitmaps.map { it.copy(Bitmap.Config.ARGB_8888, false) }
        val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return

        val adapter = object : PrintDocumentAdapter() {
            private var attributes: PrintAttributes? = null

            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?,
            ) {
                attributes = newAttributes
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder(jobName.ifBlank { "ProScan Document" })
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(copies.size)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal,
                callback: WriteResultCallback,
            ) {
                val attrs = attributes ?: PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .build()
                val pdfDoc = PrintedPdfDocument(activity, attrs)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

                try {
                    for (i in copies.indices) {
                        if (cancellationSignal.isCanceled) {
                            callback.onWriteCancelled()
                            pdfDoc.close()
                            return
                        }
                        if (!isPageInRange(i, pages)) continue
                        val page: PdfDocument.Page = pdfDoc.startPage(i)
                        val canvas = page.canvas
                        val bmp = copies[i]
                        val pad = 18f
                        val availW = (canvas.width - pad * 2).coerceAtLeast(10f)
                        val availH = (canvas.height - pad * 2).coerceAtLeast(10f)
                        val scale = min(availW / bmp.width.coerceAtLeast(1), availH / bmp.height.coerceAtLeast(1))
                        val drawW = bmp.width * scale
                        val drawH = bmp.height * scale
                        val left = (canvas.width - drawW) / 2f
                        val top = (canvas.height - drawH) / 2f
                        canvas.drawBitmap(bmp, null, RectF(left, top, left + drawW, top + drawH), paint)
                        pdfDoc.finishPage(page)
                    }
                    FileOutputStream(destination.fileDescriptor).use { out ->
                        pdfDoc.writeTo(out)
                    }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                } finally {
                    pdfDoc.close()
                }
            }

            override fun onFinish() {
                super.onFinish()
                copies.forEach { if (!it.isRecycled) it.recycle() }
            }

            private fun isPageInRange(pageIndex: Int, ranges: Array<out PageRange>): Boolean {
                for (r in ranges) {
                    if (pageIndex >= r.start && pageIndex <= r.end) return true
                }
                return false
            }
        }

        printManager.print(jobName.ifBlank { "ProScan Document" }, adapter, null)
    }
}
