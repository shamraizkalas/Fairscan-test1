/*
 * Copyright 2025-2026 The FairScan authors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.fairscan.app.platform

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.filter.FilterFactory
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFontDescriptor
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.fairscan.app.BuildConfig
import org.fairscan.app.data.PdfWriter
import org.fairscan.app.domain.Bitonal
import org.fairscan.app.domain.EncodedImage
import org.fairscan.app.domain.OcrService
import org.fairscan.app.domain.PageToExport
import org.fairscan.app.domain.PdfCompression
import org.fairscan.app.domain.PdfExportOptions
import org.fairscan.app.domain.PdfPageNumberStyle
import org.fairscan.app.domain.PdfPageSize
import org.fairscan.app.domain.packBitonal
import org.fairscan.app.domain.recompressForPdf
import org.fairscan.imageprocessing.ColorMode
import org.fairscan.imageprocessing.EstimatedDimensions
import org.fairscan.imageprocessing.OcrTextBox
import org.fairscan.imageprocessing.PaperFormats
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Calendar
import java.util.Locale

class AndroidPdfWriter(val ocrService: OcrService, val assets: AssetManager) : PdfWriter {

    override suspend fun writePdfFromJpegs(
        pages: List<PageToExport<EncodedImage>>,
        outputStream: OutputStream,
        disableOcr: Boolean,
        options: PdfExportOptions,
        onPageCompleted: (Int) -> Unit,
    ) {
        val doc = PDDocument()
        doc.documentInformation.creationDate = Calendar.getInstance()
        doc.documentInformation.creator = "ProScan Studio ${BuildConfig.VERSION_NAME}"
        doc.use { document ->
            val ocrDocument = OcrDocument(document, assets)
            val totalPages = pages.size
            val cleanHeader = options.headerText.trim()
            val cleanFooter = options.footerText.trim()
            val hasHeader = cleanHeader.isNotEmpty()
            val hasBottomBand = cleanFooter.isNotEmpty() || options.pageNumberStyle != PdfPageNumberStyle.NONE

            for ((index, page) in pages.withIndex()) {
                val jpegOrPng = page.image.get()
                val blackAndWhite = page.page.colorMode == ColorMode.BLACK_AND_WHITE
                val image = if (blackAndWhite) {
                    createCcittG4Image(document, packBitonal(jpegOrPng))
                } else {
                    val compressedBytes = if (options.compression != PdfCompression.HIGH) {
                        recompressForPdf(jpegOrPng.bytes, options.compression)
                    } else {
                        jpegOrPng.bytes
                    }
                    JPEGFactory.createFromByteArray(document, compressedBytes)
                }

                // PDF has 72 points (units) per inch, 1 inch = 25.4 mm
                val pointsPerMm = 72f / 25.4f

                val widthPx = image.width.toFloat()
                val heightPx = image.height.toFloat()

                val (pageWidthMm, pageHeightMm) = when (options.pageSize) {
                    PdfPageSize.AUTO_FIT -> {
                        val dimensions = page.estimatedDimensions()
                        when (dimensions) {
                            is EstimatedDimensions.Physical ->
                                constrainToMaxFormat(dimensions.widthMm, dimensions.heightMm)
                            else -> {
                                val maxDimMm = PaperFormats.A4.heightMm
                                val scalePxToMm = maxDimMm / maxOf(widthPx, heightPx)
                                constrainToMaxFormat(widthPx * scalePxToMm, heightPx * scalePxToMm)
                            }
                        }
                    }
                    else -> {
                        val stdW = options.pageSize.widthMm ?: 210.0
                        val stdH = options.pageSize.heightMm ?: 297.0
                        if (widthPx > heightPx) stdH to stdW else stdW to stdH
                    }
                }
                val widthPoints = pageWidthMm.toFloat() * pointsPerMm
                val heightPoints = pageHeightMm.toFloat() * pointsPerMm

                val pdfPage = PDPage(PDRectangle(widthPoints, heightPoints))
                document.addPage(pdfPage)

                val contentStream = PDPageContentStream(document, pdfPage, AppendMode.OVERWRITE, false)
                val topReserve = if (hasHeader) 26f else if (options.pageSize == PdfPageSize.AUTO_FIT) 0f else 18f
                val bottomReserve = if (hasBottomBand) 26f else if (options.pageSize == PdfPageSize.AUTO_FIT) 0f else 18f
                val sideReserve = if (options.pageSize == PdfPageSize.AUTO_FIT && !hasHeader && !hasBottomBand) 0f else 18f

                val (drawX, drawY, drawW, drawH) = if (sideReserve == 0f && topReserve == 0f && bottomReserve == 0f) {
                    listOf(0f, 0f, widthPoints, heightPoints)
                } else {
                    val availW = (widthPoints - sideReserve * 2).coerceAtLeast(10f)
                    val availH = (heightPoints - topReserve - bottomReserve).coerceAtLeast(10f)
                    val fitScale = minOf(availW / widthPx, availH / heightPx)
                    val targetW = widthPx * fitScale
                    val targetH = heightPx * fitScale
                    listOf(
                        (widthPoints - targetW) / 2f,
                        bottomReserve + (availH - targetH) / 2f,
                        targetW,
                        targetH
                    )
                }
                contentStream.drawImage(image, drawX, drawY, drawW, drawH)

                if (hasHeader || hasBottomBand) {
                    drawHeaderFooterAndPageNumber(
                        document = document,
                        contentStream = contentStream,
                        pageWidthPt = widthPoints,
                        pageHeightPt = heightPoints,
                        pageIndex = index,
                        totalPages = totalPages,
                        headerText = cleanHeader,
                        footerText = cleanFooter,
                        pageNumberStyle = options.pageNumberStyle,
                    )
                }

                if (!disableOcr) {
                    try {
                        val bitmap = if (blackAndWhite) halfSize(jpegOrPng.toBitmap())
                        else jpegOrPng.toBitmap()
                        val ocrTextBoxes = ocrService.runOcr(bitmap)
                        val pdfPageDimensions = PageDimensions(
                            bitmap.width,
                            bitmap.height,
                            widthPoints,
                            heightPoints
                        )
                        ocrDocument.addPage(pdfPage, ocrTextBoxes, pdfPageDimensions)
                    } catch (e: Exception) {
                        Log.e("AndroidPdfWriter", "Failed to run OCR on page $index", e)
                    }
                }
                contentStream.close()

                onPageCompleted(index + 1)
            }

            if (options.password.isNotBlank()) {
                val accessPermission = AccessPermission()
                val spp = StandardProtectionPolicy(options.password, options.password, accessPermission)
                spp.encryptionKeyLength = 128
                document.protect(spp)
            }
            document.save(outputStream)
        }
    }

    private fun drawHeaderFooterAndPageNumber(
        document: PDDocument,
        contentStream: PDPageContentStream,
        pageWidthPt: Float,
        pageHeightPt: Float,
        pageIndex: Int,
        totalPages: Int,
        headerText: String,
        footerText: String,
        pageNumberStyle: PdfPageNumberStyle,
    ) {
        val marginX = 20f
        val fontSize = 9.5f

        if (headerText.isNotEmpty()) {
            drawSafeMarginLabel(
                document = document,
                contentStream = contentStream,
                text = headerText,
                fontSizePt = fontSize,
                yBaselinePt = pageHeightPt - 17f,
                pageWidthPt = pageWidthPt,
                marginXPt = marginX,
                align = MarginLabelAlign.CENTER,
            )
        }

        val pageNumText = if (pageNumberStyle != PdfPageNumberStyle.NONE) {
            "Page ${pageIndex + 1} of $totalPages"
        } else ""

        if (footerText.isNotEmpty()) {
            val align = if (pageNumberStyle == PdfPageNumberStyle.BOTTOM_RIGHT) {
                MarginLabelAlign.LEFT
            } else if (pageNumberStyle == PdfPageNumberStyle.BOTTOM_CENTER) {
                MarginLabelAlign.LEFT
            } else {
                MarginLabelAlign.CENTER
            }
            drawSafeMarginLabel(
                document = document,
                contentStream = contentStream,
                text = footerText,
                fontSizePt = fontSize,
                yBaselinePt = 9f,
                pageWidthPt = pageWidthPt,
                marginXPt = marginX,
                align = align,
            )
        }

        if (pageNumText.isNotEmpty()) {
            val align = when (pageNumberStyle) {
                PdfPageNumberStyle.BOTTOM_CENTER -> MarginLabelAlign.CENTER
                PdfPageNumberStyle.BOTTOM_RIGHT -> MarginLabelAlign.RIGHT
                PdfPageNumberStyle.NONE -> MarginLabelAlign.CENTER
            }
            drawSafeMarginLabel(
                document = document,
                contentStream = contentStream,
                text = pageNumText,
                fontSizePt = fontSize,
                yBaselinePt = 9f,
                pageWidthPt = pageWidthPt,
                marginXPt = marginX,
                align = align,
            )
        }
    }

    private enum class MarginLabelAlign { LEFT, CENTER, RIGHT }

    private fun drawSafeMarginLabel(
        document: PDDocument,
        contentStream: PDPageContentStream,
        text: String,
        fontSizePt: Float,
        yBaselinePt: Float,
        pageWidthPt: Float,
        marginXPt: Float,
        align: MarginLabelAlign,
    ) {
        val isAsciiPrintable = text.all { ch -> ch.code in 0x20..0x7E }
        if (isAsciiPrintable) {
            val font = PDType1Font.HELVETICA
            val textWidthPt = (font.getStringWidth(text) / 1000f) * fontSizePt
            val xPt = when (align) {
                MarginLabelAlign.LEFT -> marginXPt
                MarginLabelAlign.CENTER -> ((pageWidthPt - textWidthPt) / 2f).coerceAtLeast(marginXPt)
                MarginLabelAlign.RIGHT -> (pageWidthPt - marginXPt - textWidthPt).coerceAtLeast(marginXPt)
            }
            contentStream.beginText()
            contentStream.setFont(font, fontSizePt)
            contentStream.setNonStrokingColor(85, 95, 105)
            contentStream.newLineAtOffset(xPt, yBaselinePt)
            contentStream.showText(text)
            contentStream.endText()
        } else {
            // Render non-Latin (e.g. Urdu / Arabic / Emoji) safely as crisp transparent bitmap
            val stripScale = 3f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.rgb(75, 85, 95)
                textSize = fontSizePt * stripScale
                typeface = Typeface.DEFAULT
            }
            val measuredW = paint.measureText(text).coerceAtLeast(16f)
            val fm = paint.fontMetrics
            val bmpW = (measuredW + 16f).toInt().coerceIn(32, ((pageWidthPt - marginXPt * 2) * stripScale).toInt().coerceAtLeast(64))
            val bmpH = (fm.descent - fm.ascent + 8f).toInt().coerceAtLeast(24)
            val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawText(text, 8f, -fm.ascent + 4f, paint)
            val pdImg = LosslessFactory.createFromImage(document, bmp)
            bmp.recycle()

            val drawW = pdImg.width / stripScale
            val drawH = pdImg.height / stripScale
            val xPt = when (align) {
                MarginLabelAlign.LEFT -> marginXPt
                MarginLabelAlign.CENTER -> ((pageWidthPt - drawW) / 2f).coerceAtLeast(marginXPt)
                MarginLabelAlign.RIGHT -> (pageWidthPt - marginXPt - drawW).coerceAtLeast(marginXPt)
            }
            contentStream.drawImage(pdImg, xPt, yBaselinePt - 2f, drawW, drawH)
        }
    }
}

// Same sequence of calls as PDFBox's own CCITTFactory, which is not usable here because it
// insists on an ALPHA_8 bitmap. The filter reads a set bit as black, so /BlackIs1 is left out.
private fun createCcittG4Image(document: PDDocument, bitonal: Bitonal): PDImageXObject {
    val encoded = ByteArrayOutputStream()
    val decodeParms = COSDictionary().apply {
        setInt(COSName.COLUMNS, bitonal.width)
        setInt(COSName.ROWS, bitonal.height)
    }
    FilterFactory.INSTANCE.getFilter(COSName.CCITTFAX_DECODE)
        .encode(ByteArrayInputStream(bitonal.bits), encoded, decodeParms, 0)

    val image = PDImageXObject(
        document,
        ByteArrayInputStream(encoded.toByteArray()),
        COSName.CCITTFAX_DECODE,
        bitonal.width,
        bitonal.height,
        1,
        PDDeviceGray.INSTANCE,
    )
    decodeParms.setInt(COSName.K, -1)
    image.cosObject.setItem(COSName.DECODE_PARMS, decodeParms)
    return image
}

private fun halfSize(bitmap: Bitmap): Bitmap {
    val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
    bitmap.recycle()
    return scaled
}

fun constrainToMaxFormat(widthMm: Double, heightMm: Double): Pair<Double, Double> {
    val maxDim = 297.0   // A4 height
    val minDim = 215.9   // Letter width

    val scale = minOf(
        maxDim / maxOf(widthMm, heightMm),
        minDim / minOf(widthMm, heightMm),
        1.0
    )
    return widthMm * scale to heightMm * scale
}

data class PageDimensions(
    val imageWidth: Int,
    val imageHeight: Int,
    val pageWidth: Float,
    val pageHeight: Float,
)

// Adapted from https://github.com/tesseract-ocr/tesseract/blob/main/src/api/pdfrenderer.cpp
class OcrDocument(
    private val document: PDDocument,
    private val assets: AssetManager,
) {
    private val fontBytes: ByteArray by lazy {
        assets.open("fonts/TesseractGlyphLessFont.ttf").readBytes()
    }

    private val cidToGidMap: ByteArray by lazy {
        ByteArray(65536 * 2) { i -> if (i % 2 == 0) 0x00.toByte() else 0x01.toByte() }
    }

    private var fontDict: COSDictionary? = null

    fun ensureResourcesAreCreated() {
        if (fontDict != null)
            return

        // -- Object 1 : embedded TTF --
        val fontFileStream = PDStream(document)
        fontFileStream.createOutputStream().use { it.write(fontBytes) }
        fontFileStream.cosObject.setInt(COSName.LENGTH1, fontBytes.size)

        // -- Object 2 : CIDToGIDMap stream --
        val cidToGidStream = PDStream(document)
        cidToGidStream.createOutputStream(COSName.FLATE_DECODE).use {
            it.write(cidToGidMap)
        }

        // -- Object 3 : FontDescriptor --
        val fontDescriptor = PDFontDescriptor(COSDictionary())
        fontDescriptor.fontName = "GlyphLessFont"
        fontDescriptor.flags = 5
        fontDescriptor.fontBoundingBox = PDRectangle(0f, 0f, 500f, 750f)
        fontDescriptor.italicAngle = 0f
        fontDescriptor.ascent = 750f
        fontDescriptor.descent = 0f
        fontDescriptor.capHeight = 750f
        fontDescriptor.stemV = 80f
        fontDescriptor.cosObject.setItem(COSName.FONT_FILE2, fontFileStream)

        // -- Object 4 : CIDFont descendant --
        val cidFont = COSDictionary()
        cidFont.setName(COSName.TYPE, "Font")
        cidFont.setName(COSName.SUBTYPE, "CIDFontType2")
        cidFont.setName(COSName.BASE_FONT, "GlyphLessFont")
        val cidSystemInfo = COSDictionary()
        cidSystemInfo.setString(COSName.getPDFName("Registry"), "Adobe")
        cidSystemInfo.setString(COSName.getPDFName("Ordering"), "Identity")
        cidSystemInfo.setInt(COSName.getPDFName("Supplement"), 0)
        cidFont.setItem(COSName.getPDFName("CIDSystemInfo"), cidSystemInfo)
        cidFont.setItem(COSName.FONT_DESC, fontDescriptor)
        cidFont.setInt(COSName.getPDFName("DW"), 500)
        cidFont.setItem(COSName.getPDFName("CIDToGIDMap"), cidToGidStream)

        // -- Object 5 : ToUnicode CMap --
        val toUnicode = buildToUnicodeCMap()
        val toUnicodeStream = PDStream(document)
        toUnicodeStream.createOutputStream().use {
            it.write(toUnicode.toByteArray(Charsets.US_ASCII))
        }

        // -- Object 6 : Font Type0 --
        val fontDict = COSDictionary()
        fontDict.setName(COSName.TYPE, "Font")
        fontDict.setName(COSName.SUBTYPE, "Type0")
        fontDict.setName(COSName.BASE_FONT, "GlyphLessFont")
        fontDict.setName(COSName.ENCODING, "Identity-H")
        val descendants = COSArray()
        descendants.add(cidFont)
        fontDict.setItem(COSName.DESCENDANT_FONTS, descendants)
        fontDict.setItem(COSName.TO_UNICODE, toUnicodeStream)
        this.fontDict = fontDict
    }

    private fun buildToUnicodeCMap(): String = buildString {
        append("/CIDInit /ProcSet findresource begin\n")
        append("12 dict begin\n")
        append("begincmap\n")
        append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        append("/CMapName /Adobe-Identify-UCS def\n")
        append("/CMapType 2 def\n")
        append("1 begincodespacerange\n")
        append("<0000> <FFFF>\n")
        append("endcodespacerange\n")
        append("1 beginbfrange\n")
        append("<0000> <FFFF> <0000>\n")
        append("endbfrange\n")
        append("endcmap\n")
        append("CMapName currentdict /CMap defineresource pop\n")
        append("end\nend\n")
    }

    fun addPage(
        page: PDPage,
        ocrTextBoxes: List<OcrTextBox>,
        dimensions: PageDimensions,
    ) {
        if (ocrTextBoxes.isEmpty()) return

        ensureResourcesAreCreated()
        val resources = page.resources ?: PDResources().also { page.resources = it }
        val fontResources = resources.cosObject
            .getDictionaryObject(COSName.FONT) as? COSDictionary
            ?: COSDictionary().also {
                resources.cosObject.setItem(COSName.FONT, it)
            }
        fontResources.setItem(COSName.getPDFName("F1"), fontDict)

        val textStream = buildTextStream(ocrTextBoxes, dimensions)
        val pdTextStream = PDStream(document)
        pdTextStream.createOutputStream(COSName.FLATE_DECODE).use {
            it.write(textStream.toByteArray(Charsets.US_ASCII))
        }

        val contentsArray = COSArray()
        val iter = page.contentStreams
        while (iter.hasNext()) contentsArray.add(iter.next().cosObject)
        contentsArray.add(pdTextStream.cosObject)
        page.cosObject.setItem(COSName.CONTENTS, contentsArray)
    }

    private fun buildTextStream(
        ocrTextBoxes: List<OcrTextBox>,
        dimensions: PageDimensions,
    ): String {
        val scaleX = dimensions.pageWidth / dimensions.imageWidth
        val scaleY = dimensions.pageHeight / dimensions.imageHeight
        val sb = StringBuilder()

        for (textBox in ocrTextBoxes) {
            val x = textBox.box.left * scaleX
            val wordWidth = textBox.box.width * scaleX
            val fontSize = textBox.lineHeight * scaleY * 0.8f
            // nominal width: fontSize / kCharWidth (Tesseract convention)
            val nominalWidth = textBox.text.length * fontSize / 2f
            val hScale = if (nominalWidth > 0f) (wordWidth / nominalWidth) * 100f else 100f
            val baselineY = dimensions.pageHeight - (textBox.lineBottom * scaleY) + fontSize * 0.2f

            val utf16hex = buildString {
                textBox.text.codePoints().forEach { cp ->
                    append(codepointToUtf16beHex(cp))
                }
            }

            sb.append("BT\n")
            sb.append(String.format(Locale.US, "/F1 %.3f Tf\n", fontSize))
            sb.append(String.format(Locale.US,"%.3f Tz\n", hScale))
            sb.append("3 Tr\n")
            sb.append(String.format(Locale.US,"%.3f %.3f Td\n", x, baselineY))
            sb.append("<${utf16hex}0020> Tj\n")
            sb.append("ET\n")
        }
        return sb.toString()
    }

    private fun codepointToUtf16beHex(cp: Int): String {
        return if (cp < 0x10000) {
            "%04X".format(cp)
        } else {
            val a = cp - 0x10000
            val high = (a shr 10) + 0xD800
            val low = (a and 0x3FF) + 0xDC00
            "%04X%04X".format(high, low)
        }
    }
}
