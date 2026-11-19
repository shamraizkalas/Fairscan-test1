package org.fairscan.app.camscanner

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class DocxOutputMode(val label: String) {
    EDITABLE_TEXT("Editable Text"),
    HYBRID_WITH_PAGES("Text + Page Previews"),
    LEGACY_DOC_HTML("Legacy .doc"),
}

data class DocxPageContent(
    val pageNumber: Int,
    val text: String,
    val previewJpegBytes: ByteArray? = null,
    val previewWidthPx: Int = 0,
    val previewHeightPx: Int = 0,
)

data class DocxRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

sealed interface ParsedDocBlock {
    data class Heading(val level: Int, val text: String) : ParsedDocBlock
    data class Paragraph(val runs: List<DocxRun>, val isBullet: Boolean = false) : ParsedDocBlock {
        val plainText: String get() = runs.joinToString("") { it.text }
    }
    data class Table(val rows: List<List<String>>) : ParsedDocBlock
    data class EmbeddedImage(val bytes: ByteArray, val caption: String = "") : ParsedDocBlock
    data object PageBreak : ParsedDocBlock
}

data class ParsedDocumentResult(
    val title: String,
    val blocks: List<ParsedDocBlock>,
)

enum class DocxParagraphStyle(val styleId: String) {
    TITLE("Title"),
    HEADING_1("Heading1"),
    HEADING_2("Heading2"),
    BULLET("Normal"),
    NORMAL("Normal"),
}

sealed interface DocxBlock {
    data class Paragraph(
        val text: String,
        val style: DocxParagraphStyle = DocxParagraphStyle.NORMAL,
    ) : DocxBlock

    data class Table(val rows: List<List<String>>) : DocxBlock
}

/**
 * Pure-Kotlin ECMA-376 OOXML (.docx) reader and writer plus legacy .doc/.html support.
 * Runs 100% offline on Android with zero Apache POI or network dependencies.
 */
object DocxOoxmlCodec {

    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val PIC_NS = "http://schemas.openxmlformats.org/drawingml/2006/picture"

    fun createDocxFromPages(
        title: String,
        pages: List<DocxPageContent>,
        outFile: File,
        includePagePreviews: Boolean = false,
    ): File {
        outFile.parentFile?.mkdirs()
        val mediaItems = mutableListOf<Triple<String, String, ByteArray>>() // rId, entryName, jpegBytes
        if (includePagePreviews) {
            pages.forEachIndexed { idx, page ->
                val bytes = page.previewJpegBytes
                if (bytes != null && bytes.isNotEmpty()) {
                    val rId = "rIdImg${idx + 1}"
                    val entryName = "media/page_${page.pageNumber}.jpg"
                    mediaItems.add(Triple(rId, entryName, bytes))
                }
            }
        }

        val pageMediaMap = pages.mapIndexedNotNull { idx, page ->
            val bytes = page.previewJpegBytes
            if (includePagePreviews && bytes != null && bytes.isNotEmpty()) {
                page.pageNumber to Triple("rIdImg${idx + 1}", page.previewWidthPx, page.previewHeightPx)
            } else null
        }.toMap()

        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            writeZipEntry(zos, "[Content_Types].xml", buildContentTypesXml(hasImages = mediaItems.isNotEmpty()))
            writeZipEntry(zos, "_rels/.rels", buildRootRelsXml())
            writeZipEntry(zos, "word/_rels/document.xml.rels", buildDocumentRelsXml(mediaItems))
            writeZipEntry(zos, "word/styles.xml", buildStylesXml())
            writeZipEntry(
                zos,
                "word/document.xml",
                buildDocumentXml(title = title, pages = pages, pageMediaMap = pageMediaMap)
            )
            mediaItems.forEach { (_, entryName, bytes) ->
                zos.putNextEntry(ZipEntry("word/$entryName"))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return outFile
    }

    fun createDocxFromBlocks(
        title: String,
        blocks: List<DocxBlock>,
        outFile: File,
        previewPages: List<DocxPageContent> = emptyList(),
    ): File {
        outFile.parentFile?.mkdirs()
        val mediaItems = mutableListOf<Triple<String, String, ByteArray>>()
        previewPages.forEachIndexed { idx, page ->
            val bytes = page.previewJpegBytes
            if (bytes != null && bytes.isNotEmpty()) {
                mediaItems.add(Triple("rIdImg${idx + 1}", "media/page_${page.pageNumber}.jpg", bytes))
            }
        }
        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            writeZipEntry(zos, "[Content_Types].xml", buildContentTypesXml(hasImages = mediaItems.isNotEmpty()))
            writeZipEntry(zos, "_rels/.rels", buildRootRelsXml())
            writeZipEntry(zos, "word/_rels/document.xml.rels", buildDocumentRelsXml(mediaItems))
            writeZipEntry(zos, "word/styles.xml", buildStylesXml())
            writeZipEntry(
                zos,
                "word/document.xml",
                buildDocumentXmlFromBlocks(title, blocks, mediaItems, previewPages)
            )
            mediaItems.forEach { (_, entryName, bytes) ->
                zos.putNextEntry(ZipEntry("word/$entryName"))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return outFile
    }

    fun renderDocxBlocksToPlainText(blocks: List<DocxBlock>): String = buildString {
        blocks.forEach { block ->
            when (block) {
                is DocxBlock.Paragraph -> when (block.style) {
                    DocxParagraphStyle.TITLE, DocxParagraphStyle.HEADING_1 -> {
                        if (isNotEmpty()) appendLine()
                        appendLine(block.text.uppercase())
                    }
                    DocxParagraphStyle.HEADING_2 -> {
                        if (isNotEmpty()) appendLine()
                        appendLine("## ${block.text}")
                    }
                    DocxParagraphStyle.BULLET -> appendLine("• ${block.text}")
                    DocxParagraphStyle.NORMAL -> appendLine(block.text)
                }
                is DocxBlock.Table -> {
                    appendLine()
                    block.rows.forEach { row ->
                        appendLine(row.joinToString(" | "))
                    }
                    appendLine()
                }
            }
        }
    }.trim()

    fun renderDocxBlocksToHtml(title: String, blocks: List<DocxBlock>): String = buildString {
        append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"/>")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>")
        append("<title>${escapeXml(title)}</title>")
        append("<style>body{font-family:system-ui,-apple-system,sans-serif;max-width:860px;margin:24px auto;padding:0 16px;line-height:1.6;color:#1b1f23;background:#f8fafc;}")
        append(".doc{background:#fff;border-radius:12px;padding:24px;box-shadow:0 2px 10px rgba(0,0,0,.06);}")
        append("h1{color:#0f172a;margin-top:0;}h2{color:#0f62fe;margin-top:20px;}table{width:100%;border-collapse:collapse;margin:14px 0;}th,td{border:1px solid #cbd5e1;padding:8px;text-align:left;}th{background:#f1f5f9;font-weight:600;}</style></head><body><div class=\"doc\">")
        append("<h1>${escapeXml(title)}</h1>")
        var inList = false
        blocks.forEach { block ->
            if (block !is DocxBlock.Paragraph || block.style != DocxParagraphStyle.BULLET) {
                if (inList) {
                    append("</ul>")
                    inList = false
                }
            }
            when (block) {
                is DocxBlock.Paragraph -> when (block.style) {
                    DocxParagraphStyle.TITLE -> append("<h1>${escapeXml(block.text)}</h1>")
                    DocxParagraphStyle.HEADING_1 -> append("<h1>${escapeXml(block.text)}</h1>")
                    DocxParagraphStyle.HEADING_2 -> append("<h2>${escapeXml(block.text)}</h2>")
                    DocxParagraphStyle.BULLET -> {
                        if (!inList) {
                            append("<ul>")
                            inList = true
                        }
                        append("<li>${escapeXml(block.text)}</li>")
                    }
                    DocxParagraphStyle.NORMAL -> append("<p>${escapeXml(block.text)}</p>")
                }
                is DocxBlock.Table -> if (block.rows.isNotEmpty()) {
                    append("<table>")
                    block.rows.forEachIndexed { rIdx, row ->
                        append("<tr>")
                        val cellTag = if (rIdx == 0) "th" else "td"
                        row.forEach { cell -> append("<$cellTag>${escapeXml(cell)}</$cellTag>") }
                        append("</tr>")
                    }
                    append("</table>")
                }
            }
        }
        if (inList) append("</ul>")
        append("</div></body></html>")
    }

    fun createLegacyDocHtml(
        title: String,
        pages: List<DocxPageContent>,
        outFile: File,
    ): File {
        outFile.parentFile?.mkdirs()
        val html = buildString {
            append("<html xmlns:w=\"urn:schemas-microsoft-com:office:word\">")
            append("<head><meta charset=\"utf-8\"/><title>${escapeXml(title)}</title></head>")
            append("<body style=\"font-family: Calibri, sans-serif; line-height: 1.5; margin: 24px;\">")
            append("<h1>${escapeXml(title)}</h1>")
            pages.forEachIndexed { idx, p ->
                append("<h3>Page ${p.pageNumber}</h3>")
                val safeText = escapeXml(p.text.ifBlank { "(No selectable text detected on this page)" })
                    .replace("\n", "<br/>")
                append("<p>$safeText</p>")
                if (idx < pages.lastIndex) {
                    append("<hr style=\"page-break-after: always;\"/>")
                }
            }
            append("</body></html>")
        }
        outFile.writeText(html, Charsets.UTF_8)
        return outFile
    }

    fun parseWordFile(file: File, fallbackTitle: String = file.nameWithoutExtension): ParsedDocumentResult {
        val ext = file.extension.lowercase()
        if (ext == "docx" || looksLikeZip(file)) {
            return parseDocxZip(file, fallbackTitle)
        }
        return parseLegacyDocOrHtml(file, fallbackTitle)
    }

    private fun looksLikeZip(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return FileInputStream(file).use { fis ->
            val header = ByteArray(4)
            fis.read(header) == 4 &&
                header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        }
    }

    private fun parseDocxZip(file: File, fallbackTitle: String): ParsedDocumentResult {
        var documentXmlBytes: ByteArray? = null
        var relsXmlBytes: ByteArray? = null
        val mediaMap = mutableMapOf<String, ByteArray>()

        ZipInputStream(FileInputStream(file)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zis.readBytes()
                    when (entry.name) {
                        "word/document.xml" -> documentXmlBytes = bytes
                        "word/_rels/document.xml.rels" -> relsXmlBytes = bytes
                        else -> if (entry.name.startsWith("word/")) {
                            mediaMap[entry.name.removePrefix("word/")] = bytes
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val docBytes = documentXmlBytes
            ?: return ParsedDocumentResult(
                fallbackTitle,
                listOf(ParsedDocBlock.Paragraph(listOf(DocxRun("Unable to read word/document.xml from DOCX archive."))))
            )

        val relTargets = relsXmlBytes?.let(::parseRelsXml) ?: emptyMap()
        val blocks = parseDocumentXml(docBytes, relTargets, mediaMap)
        return ParsedDocumentResult(title = fallbackTitle, blocks = blocks)
    }

    private fun parseRelsXml(bytes: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.endsWith("Relationship")) {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")?.removePrefix("/")
                if (!id.isNullOrBlank() && !target.isNullOrBlank()) {
                    map[id] = target.removePrefix("word/")
                }
            }
            event = parser.next()
        }
        return map
    }

    private fun parseDocumentXml(
        bytes: ByteArray,
        relTargets: Map<String, String>,
        mediaMap: Map<String, ByteArray>,
    ): List<ParsedDocBlock> {
        val blocks = mutableListOf<ParsedDocBlock>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

        var inParagraph = false
        var inRun = false
        var inText = false
        var inTable = false
        var inTableRow = false
        var inTableCell = false

        var pStyle = ""
        var isBullet = false
        var runBold = false
        var runItalic = false
        val runText = StringBuilder()
        var currentRuns = mutableListOf<DocxRun>()

        val currentTableRows = mutableListOf<List<String>>()
        var currentRowCells = mutableListOf<String>()
        val currentCellText = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = localName(parser.name)
                    when (tag) {
                        "tbl" -> {
                            inTable = true
                            currentTableRows.clear()
                        }
                        "tr" -> if (inTable) {
                            inTableRow = true
                            currentRowCells = mutableListOf()
                        }
                        "tc" -> if (inTableRow) {
                            inTableCell = true
                            currentCellText.clear()
                        }
                        "p" -> {
                            inParagraph = true
                            pStyle = ""
                            isBullet = false
                            currentRuns = mutableListOf()
                        }
                        "pStyle" -> if (inParagraph) {
                            pStyle = attrByLocalName(parser, "val").orEmpty()
                        }
                        "numPr" -> if (inParagraph) {
                            isBullet = true
                        }
                        "r" -> if (inParagraph) {
                            inRun = true
                            runBold = false
                            runItalic = false
                            runText.clear()
                        }
                        "b" -> if (inRun) {
                            val v = attrByLocalName(parser, "val")
                            runBold = v == null || (!v.equals("false", true) && v != "0")
                        }
                        "i" -> if (inRun) {
                            val v = attrByLocalName(parser, "val")
                            runItalic = v == null || (!v.equals("false", true) && v != "0")
                        }
                        "t", "delText" -> if (inRun) {
                            inText = true
                        }
                        "tab" -> if (inRun) {
                            runText.append("    ")
                        }
                        "br" -> {
                            val type = attrByLocalName(parser, "type")
                            if (type.equals("page", true) && !inTable) {
                                blocks.add(ParsedDocBlock.PageBreak)
                            } else if (inRun) {
                                runText.append("\n")
                            }
                        }
                        "blip" -> {
                            val embedId = attrByLocalName(parser, "embed")
                            val target = embedId?.let { relTargets[it] }
                            val imgBytes = target?.let { mediaMap[it] }
                            if (imgBytes != null && imgBytes.isNotEmpty() && !inTable) {
                                blocks.add(ParsedDocBlock.EmbeddedImage(imgBytes))
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inText && inRun) {
                        runText.append(parser.text.orEmpty())
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = localName(parser.name)
                    when (tag) {
                        "t", "delText" -> inText = false
                        "r" -> {
                            if (inRun && runText.isNotEmpty()) {
                                if (inTableCell) {
                                    currentCellText.append(runText)
                                } else {
                                    currentRuns.add(DocxRun(runText.toString(), runBold, runItalic))
                                }
                            }
                            inRun = false
                        }
                        "p" -> {
                            if (inTableCell) {
                                if (currentCellText.isNotEmpty() && !currentCellText.endsWith("\n")) {
                                    currentCellText.append(" ")
                                }
                            } else if (inParagraph) {
                                val combined = currentRuns.joinToString("") { it.text }
                                if (combined.isNotBlank()) {
                                    val headingLevel = when {
                                        pStyle.equals("Title", true) -> 1
                                        pStyle.startsWith("Heading1", true) -> 1
                                        pStyle.startsWith("Heading2", true) -> 2
                                        pStyle.startsWith("Heading3", true) -> 3
                                        else -> 0
                                    }
                                    if (headingLevel > 0) {
                                        blocks.add(ParsedDocBlock.Heading(headingLevel, combined.trim()))
                                    } else {
                                        blocks.add(ParsedDocBlock.Paragraph(currentRuns, isBullet))
                                    }
                                } else {
                                    blocks.add(ParsedDocBlock.Paragraph(listOf(DocxRun(""))))
                                }
                            }
                            inParagraph = false
                        }
                        "tc" -> {
                            if (inTableCell) {
                                currentRowCells.add(currentCellText.toString().trim())
                            }
                            inTableCell = false
                        }
                        "tr" -> {
                            if (inTableRow && currentRowCells.isNotEmpty()) {
                                currentTableRows.add(currentRowCells.toList())
                            }
                            inTableRow = false
                        }
                        "tbl" -> {
                            if (inTable && currentTableRows.isNotEmpty()) {
                                blocks.add(ParsedDocBlock.Table(currentTableRows.toList()))
                            }
                            inTable = false
                        }
                    }
                }
            }
            event = parser.next()
        }
        return blocks
    }

    private fun parseLegacyDocOrHtml(file: File, fallbackTitle: String): ParsedDocumentResult {
        val rawBytes = file.readBytes()
        val textPreview = rawBytes.toString(Charsets.UTF_8)
        // If it's HTML-based .doc (very common on mobile/Word exports), strip HTML cleanly
        if (textPreview.contains("<html", ignoreCase = true) ||
            textPreview.contains("<body", ignoreCase = true) ||
            textPreview.contains("<p", ignoreCase = true)
        ) {
            val cleaned = android.text.Html.fromHtml(
                textPreview,
                android.text.Html.FROM_HTML_MODE_LEGACY
            ).toString()
            val blocks = cleaned.lines().map { line ->
                ParsedDocBlock.Paragraph(listOf(DocxRun(line.trim())))
            }
            return ParsedDocumentResult(fallbackTitle, blocks)
        }
        // Fallback for binary/UTF-16/RTF legacy .doc: extract readable printable runs
        val extracted = extractReadableStringsFromBinaryDoc(rawBytes)
        val blocks = extracted.lines().map { line ->
            ParsedDocBlock.Paragraph(listOf(DocxRun(line)))
        }
        return ParsedDocumentResult(fallbackTitle, blocks)
    }

    private fun extractReadableStringsFromBinaryDoc(bytes: ByteArray): String {
        val sb = StringBuilder()
        val current = StringBuilder()
        for (b in bytes) {
            val ch = b.toInt() and 0xFF
            if (ch == 10 || ch == 13 || (ch in 32..126)) {
                current.append(ch.toChar())
            } else {
                if (current.length >= 4 && current.any { it.isLetterOrDigit() }) {
                    sb.append(current).append('\n')
                }
                current.clear()
            }
        }
        if (current.length >= 4 && current.any { it.isLetterOrDigit() }) {
            sb.append(current)
        }
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun localName(tag: String?): String {
        if (tag == null) return ""
        val idx = tag.indexOf(':')
        return if (idx >= 0) tag.substring(idx + 1) else tag
    }

    private fun attrByLocalName(parser: XmlPullParser, local: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (localName(parser.getAttributeName(i)) == local) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun writeZipEntry(zos: ZipOutputStream, entryName: String, xmlText: String) {
        zos.putNextEntry(ZipEntry(entryName))
        zos.write(xmlText.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun buildContentTypesXml(hasImages: Boolean): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        if (hasImages) {
            append("<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>")
            append("<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>")
            append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
        }
        append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>")
        append("<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>")
        append("</Types>")
    }

    private fun buildRootRelsXml(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>")
        append("</Relationships>")
    }

    private fun buildDocumentRelsXml(mediaItems: List<Triple<String, String, ByteArray>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        mediaItems.forEach { (rId, entryName, _) ->
            append("<Relationship Id=\"$rId\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"$entryName\"/>")
        }
        append("</Relationships>")
    }

    private fun buildStylesXml(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<w:styles xmlns:w=\"$W_NS\">")
        append("<w:docDefaults><w:rPrDefault><w:rPr>")
        append("<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/>")
        append("<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/>")
        append("</w:rPr></w:rPrDefault></w:docDefaults>")
        append("<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>")
        append("<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:rPr><w:b/><w:sz w:val=\"36\"/></w:rPr></w:style>")
        append("<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:rPr><w:b/><w:color w:val=\"0F172A\"/><w:sz w:val=\"32\"/></w:rPr></w:style>")
        append("<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:rPr><w:b/><w:color w:val=\"0F62FE\"/><w:sz w:val=\"26\"/></w:rPr></w:style>")
        append("</w:styles>")
    }

    private fun buildDocumentXml(
        title: String,
        pages: List<DocxPageContent>,
        pageMediaMap: Map<Int, Triple<String, Int, Int>>,
    ): String {
        val baos = ByteArrayOutputStream()
        val xs = Xml.newSerializer()
        xs.setOutput(baos, "UTF-8")
        xs.startDocument("UTF-8", true)
        xs.setPrefix("w", W_NS)
        xs.setPrefix("r", R_NS)
        xs.setPrefix("wp", WP_NS)
        xs.setPrefix("a", A_NS)
        xs.setPrefix("pic", PIC_NS)

        xs.startTag(W_NS, "document")
        xs.startTag(W_NS, "body")

        // Document title paragraph
        writeStyledParagraph(xs, "Title", title)

        pages.forEachIndexed { idx, page ->
            writeStyledParagraph(xs, "Heading2", "Page ${page.pageNumber}")
            val cleanLines = page.text.replace("\r\n", "\n").split("\n")
            if (cleanLines.all { it.isBlank() }) {
                writeStyledParagraph(xs, "Normal", "(No selectable text extracted for this page)")
            } else {
                cleanLines.forEach { line ->
                    writeStyledParagraph(xs, "Normal", line)
                }
            }

            pageMediaMap[page.pageNumber]?.let { (rId, pxW, pxH) ->
                writeEmbeddedImageParagraph(xs, idx + 1, rId, pxW, pxH)
            }

            if (idx < pages.lastIndex) {
                xs.startTag(W_NS, "p")
                xs.startTag(W_NS, "r")
                xs.startTag(W_NS, "br")
                xs.attribute(W_NS, "type", "page")
                xs.endTag(W_NS, "br")
                xs.endTag(W_NS, "r")
                xs.endTag(W_NS, "p")
            }
        }

        // Standard A4 page section properties
        xs.startTag(W_NS, "sectPr")
        xs.startTag(W_NS, "pgSz")
        xs.attribute(W_NS, "w", "11906")
        xs.attribute(W_NS, "h", "16838")
        xs.endTag(W_NS, "pgSz")
        xs.startTag(W_NS, "pgMar")
        xs.attribute(W_NS, "top", "1134")
        xs.attribute(W_NS, "right", "1134")
        xs.attribute(W_NS, "bottom", "1134")
        xs.attribute(W_NS, "left", "1134")
        xs.endTag(W_NS, "pgMar")
        xs.endTag(W_NS, "sectPr")

        xs.endTag(W_NS, "body")
        xs.endTag(W_NS, "document")
        xs.endDocument()
        return baos.toString("UTF-8")
    }

    private fun buildDocumentXmlFromBlocks(
        title: String,
        blocks: List<DocxBlock>,
        mediaItems: List<Triple<String, String, ByteArray>>,
        previewPages: List<DocxPageContent>,
    ): String {
        val baos = ByteArrayOutputStream()
        val xs = Xml.newSerializer()
        xs.setOutput(baos, "UTF-8")
        xs.startDocument("UTF-8", true)
        xs.setPrefix("w", W_NS)
        xs.setPrefix("r", R_NS)
        xs.setPrefix("wp", WP_NS)
        xs.setPrefix("a", A_NS)
        xs.setPrefix("pic", PIC_NS)

        xs.startTag(W_NS, "document")
        xs.startTag(W_NS, "body")

        writeStyledParagraph(xs, "Title", title)
        blocks.forEach { block ->
            when (block) {
                is DocxBlock.Paragraph -> {
                    val outText = if (block.style == DocxParagraphStyle.BULLET) "• ${block.text}" else block.text
                    writeStyledParagraph(xs, block.style.styleId, outText)
                }
                is DocxBlock.Table -> writeTableXml(xs, block.rows)
            }
        }

        mediaItems.forEachIndexed { idx, (rId, _, _) ->
            val p = previewPages.getOrNull(idx)
            writeEmbeddedImageParagraph(
                xs = xs,
                docPrId = idx + 1,
                rId = rId,
                pxW = p?.previewWidthPx ?: 1200,
                pxH = p?.previewHeightPx ?: 1600,
            )
        }

        xs.startTag(W_NS, "sectPr")
        xs.startTag(W_NS, "pgSz")
        xs.attribute(W_NS, "w", "11906")
        xs.attribute(W_NS, "h", "16838")
        xs.endTag(W_NS, "pgSz")
        xs.startTag(W_NS, "pgMar")
        xs.attribute(W_NS, "top", "1134")
        xs.attribute(W_NS, "right", "1134")
        xs.attribute(W_NS, "bottom", "1134")
        xs.attribute(W_NS, "left", "1134")
        xs.endTag(W_NS, "pgMar")
        xs.endTag(W_NS, "sectPr")

        xs.endTag(W_NS, "body")
        xs.endTag(W_NS, "document")
        xs.endDocument()
        return baos.toString("UTF-8")
    }

    private fun writeTableXml(xs: org.xmlpull.v1.XmlSerializer, rows: List<List<String>>) {
        if (rows.isEmpty()) return
        val maxCols = rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: return
        xs.startTag(W_NS, "tbl")
        xs.startTag(W_NS, "tblPr")
        xs.startTag(W_NS, "tblBorders")
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { edge ->
            xs.startTag(W_NS, edge)
            xs.attribute(W_NS, "val", "single")
            xs.attribute(W_NS, "sz", "4")
            xs.attribute(W_NS, "space", "0")
            xs.attribute(W_NS, "color", "CBD5E1")
            xs.endTag(W_NS, edge)
        }
        xs.endTag(W_NS, "tblBorders")
        xs.endTag(W_NS, "tblPr")

        rows.forEachIndexed { rIdx, row ->
            xs.startTag(W_NS, "tr")
            for (c in 0 until maxCols) {
                val cellText = stripInvalidXmlChars(row.getOrElse(c) { "" })
                xs.startTag(W_NS, "tc")
                xs.startTag(W_NS, "p")
                xs.startTag(W_NS, "r")
                if (rIdx == 0) {
                    xs.startTag(W_NS, "rPr")
                    xs.startTag(W_NS, "b")
                    xs.endTag(W_NS, "b")
                    xs.endTag(W_NS, "rPr")
                }
                xs.startTag(W_NS, "t")
                xs.attribute("http://www.w3.org/XML/1998/namespace", "space", "preserve")
                xs.text(cellText)
                xs.endTag(W_NS, "t")
                xs.endTag(W_NS, "r")
                xs.endTag(W_NS, "p")
                xs.endTag(W_NS, "tc")
            }
            xs.endTag(W_NS, "tr")
        }
        xs.endTag(W_NS, "tbl")
    }

    private fun writeStyledParagraph(xs: org.xmlpull.v1.XmlSerializer, styleId: String, text: String) {
        val safeText = stripInvalidXmlChars(text)
        xs.startTag(W_NS, "p")
        xs.startTag(W_NS, "pPr")
        xs.startTag(W_NS, "pStyle")
        xs.attribute(W_NS, "val", styleId)
        xs.endTag(W_NS, "pStyle")
        xs.endTag(W_NS, "pPr")
        xs.startTag(W_NS, "r")
        xs.startTag(W_NS, "t")
        xs.attribute("http://www.w3.org/XML/1998/namespace", "space", "preserve")
        xs.text(safeText)
        xs.endTag(W_NS, "t")
        xs.endTag(W_NS, "r")
        xs.endTag(W_NS, "p")
    }

    private fun writeEmbeddedImageParagraph(
        xs: org.xmlpull.v1.XmlSerializer,
        docPrId: Int,
        rId: String,
        pxW: Int,
        pxH: Int,
    ) {
        val maxWidthEmu = 5_400_000L // ~5.9 inches inside A4 margins
        val w = pxW.coerceAtLeast(100).toLong()
        val h = pxH.coerceAtLeast(100).toLong()
        val cx = maxWidthEmu
        val cy = (maxWidthEmu * h / w).coerceIn(900_000L, 7_200_000L)

        xs.startTag(W_NS, "p")
        xs.startTag(W_NS, "r")
        xs.startTag(W_NS, "drawing")
        xs.startTag(WP_NS, "inline")
        xs.attribute(null, "distT", "0")
        xs.attribute(null, "distB", "0")
        xs.attribute(null, "distL", "0")
        xs.attribute(null, "distR", "0")

        xs.startTag(WP_NS, "extent")
        xs.attribute(null, "cx", cx.toString())
        xs.attribute(null, "cy", cy.toString())
        xs.endTag(WP_NS, "extent")

        xs.startTag(WP_NS, "docPr")
        xs.attribute(null, "id", docPrId.toString())
        xs.attribute(null, "name", "PagePreview$docPrId")
        xs.endTag(WP_NS, "docPr")

        xs.startTag(A_NS, "graphic")
        xs.startTag(A_NS, "graphicData")
        xs.attribute(null, "uri", "http://schemas.openxmlformats.org/drawingml/2006/picture")
        xs.startTag(PIC_NS, "pic")

        xs.startTag(PIC_NS, "nvPicPr")
        xs.startTag(PIC_NS, "cNvPr")
        xs.attribute(null, "id", docPrId.toString())
        xs.attribute(null, "name", "page_$docPrId.jpg")
        xs.endTag(PIC_NS, "cNvPr")
        xs.startTag(PIC_NS, "cNvPicPr")
        xs.endTag(PIC_NS, "cNvPicPr")
        xs.endTag(PIC_NS, "nvPicPr")

        xs.startTag(PIC_NS, "blipFill")
        xs.startTag(A_NS, "blip")
        xs.attribute(R_NS, "embed", rId)
        xs.endTag(A_NS, "blip")
        xs.startTag(A_NS, "stretch")
        xs.startTag(A_NS, "fillRect")
        xs.endTag(A_NS, "fillRect")
        xs.endTag(A_NS, "stretch")
        xs.endTag(PIC_NS, "blipFill")

        xs.startTag(PIC_NS, "spPr")
        xs.startTag(A_NS, "xfrm")
        xs.startTag(A_NS, "off")
        xs.attribute(null, "x", "0")
        xs.attribute(null, "y", "0")
        xs.endTag(A_NS, "off")
        xs.startTag(A_NS, "ext")
        xs.attribute(null, "cx", cx.toString())
        xs.attribute(null, "cy", cy.toString())
        xs.endTag(A_NS, "ext")
        xs.endTag(A_NS, "xfrm")
        xs.startTag(A_NS, "prstGeom")
        xs.attribute(null, "prst", "rect")
        xs.endTag(A_NS, "prstGeom")
        xs.endTag(PIC_NS, "spPr")

        xs.endTag(PIC_NS, "pic")
        xs.endTag(A_NS, "graphicData")
        xs.endTag(A_NS, "graphic")
        xs.endTag(WP_NS, "inline")
        xs.endTag(W_NS, "drawing")
        xs.endTag(W_NS, "r")
        xs.endTag(W_NS, "p")
    }

    private fun stripInvalidXmlChars(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val code = ch.code
            val valid = code == 0x9 || code == 0xA || code == 0xD ||
                (code in 0x20..0xD7FF) || (code in 0xE000..0xFFFD)
            if (valid) sb.append(ch)
        }
        return sb.toString()
    }

    private fun escapeXml(input: String): String = stripInvalidXmlChars(input)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
