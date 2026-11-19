package org.fairscan.app.camscanner

import org.fairscan.imageprocessing.OcrTextBox
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ReceiptLineItem(
    val description: String,
    val amount: String,
    val amountValue: Double,
)

data class ReceiptExtractResult(
    val merchant: String? = null,
    val date: String? = null,
    val invoiceNumber: String? = null,
    val subtotal: String? = null,
    val tax: String? = null,
    val total: String? = null,
    val totalValue: Double? = null,
    val currencySymbol: String? = null,
    val lineItems: List<ReceiptLineItem> = emptyList(),
) {
    val hasAnyField: Boolean
        get() = merchant != null || date != null || invoiceNumber != null ||
            subtotal != null || tax != null || total != null || lineItems.isNotEmpty()
}

data class CsvTableResult(
    val rows: List<List<String>>,
    val csv: String,
    val columnCount: Int,
) {
    val isEmpty: Boolean get() = rows.isEmpty() || columnCount == 0
}

object DocumentDataExtractor {

    private val datePatterns = listOf(
        Regex("""\b\d{4}[-/]\d{1,2}[-/]\d{1,2}\b"""),
        Regex("""\b\d{1,2}[-/]\d{1,2}[-/]\d{2,4}\b"""),
        Regex("""\b\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*[\s,]+\d{2,4}\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{1,2}[\s,]+\d{2,4}\b""", RegexOption.IGNORE_CASE),
    )

    private val invoicePattern = Regex(
        """(?:invoice|inv|receipt|bill|order|ref)\s*(?:no\.?|number|#|:)?\s*[:#-]?\s*([A-Z0-9][A-Z0-9/_-]{2,24})""",
        RegexOption.IGNORE_CASE
    )

    private val currencyTokenPattern = Regex("""(\$|€|£|¥|₹|PKR|Rs\.?|USD|EUR|AED|SAR)""", RegexOption.IGNORE_CASE)

    private val amountPattern = Regex(
        """(?:(\$|€|£|¥|₹|PKR|Rs\.?|USD|EUR|AED|SAR)\s*)?(-?\d{1,3}(?:,\d{3})*(?:\.\d{2})|-?\d+\.\d{2})"""
    )

    private val excludedItemKeywords = listOf(
        "subtotal", "sub total", "sub-total", "grand total", "total", "tax", "vat", "gst",
        "discount", "change", "cash", "card", "visa", "mastercard", "paid", "balance", "due",
        "tip", "service charge", "invoice", "receipt", "tel", "phone", "date", "time"
    )

    fun extractReceiptData(
        fullText: String,
        boxes: List<OcrTextBox> = emptyList(),
    ): ReceiptExtractResult {
        val lines = if (fullText.isNotBlank()) {
            fullText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        } else if (boxes.isNotEmpty()) {
            clusterBoxesIntoVisualRows(boxes).map { row ->
                row.joinToString(" ") { it.text.trim() }.trim()
            }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }

        if (lines.isEmpty()) return ReceiptExtractResult()

        val joinedText = lines.joinToString("\n")
        val detectedCurrency = currencyTokenPattern.find(joinedText)?.groupValues?.getOrNull(1)

        // Merchant: top header line containing letters and not a receipt metadata label
        val merchant = lines.take(4).firstOrNull { line ->
            val lower = line.lowercase()
            line.length >= 3 &&
                line.any { it.isLetter() } &&
                !lower.startsWith("receipt") &&
                !lower.startsWith("invoice") &&
                !lower.startsWith("tax invoice") &&
                !lower.startsWith("date") &&
                !lower.startsWith("tel") &&
                !lower.startsWith("phone") &&
                !lower.startsWith("www.") &&
                datePatterns.none { it.containsMatchIn(line) }
        }

        val date = datePatterns.firstNotNullOfOrNull { regex ->
            regex.find(joinedText)?.value?.trim()
        }

        val invoiceNumber = invoicePattern.find(joinedText)?.groupValues?.getOrNull(1)?.trim()

        var subtotal: String? = null
        var tax: String? = null
        var total: String? = null
        var totalValue: Double? = null
        var totalPriority = -1

        val candidateBottomAmounts = mutableListOf<Pair<String, Double>>()
        val lineItems = mutableListOf<ReceiptLineItem>()

        lines.forEachIndexed { idx, line ->
            val lower = line.lowercase()
            val matches = amountPattern.findAll(line).toList()
            val lastMatch = matches.lastOrNull()
            val formattedAmount = lastMatch?.let { m ->
                val cur = m.groupValues[1].ifBlank { detectedCurrency.orEmpty() }
                val numStr = m.groupValues[2]
                if (cur.isNotBlank()) "$cur${if (cur.length > 1) " " else ""}$numStr" else numStr
            }
            val numericAmount = lastMatch?.groupValues?.getOrNull(2)?.replace(",", "")?.toDoubleOrNull()

            if (formattedAmount != null && numericAmount != null) {
                when {
                    lower.contains("subtotal") || lower.contains("sub total") || lower.contains("sub-total") -> {
                        if (subtotal == null) subtotal = formattedAmount
                    }
                    lower.contains("tax") || lower.contains("vat") || lower.contains("gst") -> {
                        tax = formattedAmount
                    }
                    lower.contains("grand total") || lower.contains("total due") ||
                        lower.contains("amount due") || lower.contains("net payable") ||
                        lower.contains("balance due") -> {
                        if (totalPriority < 2) {
                            total = formattedAmount
                            totalValue = numericAmount
                            totalPriority = 2
                        }
                    }
                    Regex("""\btotal\b""").containsMatchIn(lower) &&
                        !lower.contains("sub") -> {
                        if (totalPriority < 1 || (totalPriority == 1 && numericAmount >= (totalValue ?: 0.0))) {
                            total = formattedAmount
                            totalValue = numericAmount
                            totalPriority = 1
                        }
                    }
                    else -> {
                        if (idx >= lines.size / 2) {
                            candidateBottomAmounts.add(formattedAmount to numericAmount)
                        }
                        val isExcluded = excludedItemKeywords.any { kw -> lower.contains(kw) }
                        if (!isExcluded && lastMatch != null) {
                            val rawDesc = line.substring(0, lastMatch.range.first).trim()
                                .trimEnd('-', ':', '.', ' ')
                            if (rawDesc.length >= 2 && rawDesc.any { it.isLetter() } && numericAmount > 0.0) {
                                lineItems.add(
                                    ReceiptLineItem(
                                        description = rawDesc,
                                        amount = formattedAmount,
                                        amountValue = numericAmount,
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        if (total == null && candidateBottomAmounts.isNotEmpty()) {
            val best = candidateBottomAmounts.maxByOrNull { it.second }
            if (best != null) {
                total = best.first
                totalValue = best.second
            }
        }

        return ReceiptExtractResult(
            merchant = merchant,
            date = date,
            invoiceNumber = invoiceNumber,
            subtotal = subtotal,
            tax = tax,
            total = total,
            totalValue = totalValue,
            currencySymbol = detectedCurrency,
            lineItems = lineItems,
        )
    }

    fun extractTableToCsv(
        boxes: List<OcrTextBox>,
        fallbackText: String = "",
    ): CsvTableResult {
        if (boxes.isEmpty()) {
            return extractTableFromPlainText(fallbackText)
        }

        val visualRows = clusterBoxesIntoVisualRows(boxes)
        if (visualRows.isEmpty()) return extractTableFromPlainText(fallbackText)

        // Estimate typical character width to separate intra-cell word spacing from column gaps
        val charWidths = boxes.mapNotNull { tb ->
            val len = tb.text.trim().length
            if (len > 0 && tb.box.width > 0) tb.box.width.toFloat() / len else null
        }.sorted()
        val medianCharW = if (charWidths.isNotEmpty()) charWidths[charWidths.size / 2] else 12f
        val cellGapThreshold = max(18f, medianCharW * 2.4f)

        data class CellSpan(val leftX: Int, val text: String)

        val rowSpans = visualRows.map { rowBoxes ->
            val cells = mutableListOf<CellSpan>()
            var currentLeft = rowBoxes.first().box.left
            var currentRight = rowBoxes.first().box.right
            val currentText = StringBuilder(rowBoxes.first().text.trim())

            for (i in 1 until rowBoxes.size) {
                val tb = rowBoxes[i]
                val gap = tb.box.left - currentRight
                if (gap <= cellGapThreshold) {
                    if (currentText.isNotEmpty()) currentText.append(' ')
                    currentText.append(tb.text.trim())
                    currentRight = max(currentRight, tb.box.right)
                } else {
                    cells.add(CellSpan(currentLeft, currentText.toString().trim()))
                    currentLeft = tb.box.left
                    currentRight = tb.box.right
                    currentText.clear()
                    currentText.append(tb.text.trim())
                }
            }
            if (currentText.isNotEmpty()) {
                cells.add(CellSpan(currentLeft, currentText.toString().trim()))
            }
            cells
        }.filter { it.isNotEmpty() }

        if (rowSpans.isEmpty()) return CsvTableResult(emptyList(), "", 0)

        // Align multi-column cells across rows by clustering cell leftX positions
        val multiCellRows = rowSpans.filter { it.size >= 2 }
        val columnAnchors = mutableListOf<Float>()
        val anchorTolerance = max(28f, medianCharW * 3.5f)
        val sourceForAnchors = if (multiCellRows.isNotEmpty()) multiCellRows else rowSpans
        val sortedLefts = sourceForAnchors.flatten().map { it.leftX }.sorted()

        sortedLefts.forEach { lx ->
            val existingIdx = columnAnchors.indexOfFirst { abs(it - lx) <= anchorTolerance }
            if (existingIdx >= 0) {
                columnAnchors[existingIdx] = (columnAnchors[existingIdx] + lx) / 2f
            } else {
                columnAnchors.add(lx.toFloat())
            }
        }
        columnAnchors.sort()

        val colCount = columnAnchors.size.coerceAtLeast(1)
        val tableRows = rowSpans.map { cells ->
            val rowArray = MutableList(colCount) { "" }
            cells.forEach { cell ->
                var bestCol = 0
                var bestDist = Float.MAX_VALUE
                columnAnchors.forEachIndexed { idx, anchor ->
                    val d = abs(cell.leftX - anchor)
                    if (d < bestDist) {
                        bestDist = d
                        bestCol = idx
                    }
                }
                rowArray[bestCol] = if (rowArray[bestCol].isEmpty()) {
                    cell.text
                } else {
                    "${rowArray[bestCol]} ${cell.text}".trim()
                }
            }
            rowArray
        }

        val csv = buildCsvString(tableRows, colCount)
        return CsvTableResult(rows = tableRows, csv = csv, columnCount = colCount)
    }

    private fun extractTableFromPlainText(fallbackText: String): CsvTableResult {
        val lines = fallbackText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return CsvTableResult(emptyList(), "", 0)
        val splitRegex = Regex("""\t|\s*\|\s*|\s{2,}""")
        val rawRows = lines.map { line ->
            line.split(splitRegex).map { it.trim() }.filter { it.isNotEmpty() }
        }.filter { it.isNotEmpty() }
        val colCount = rawRows.maxOfOrNull { it.size } ?: 0
        if (colCount == 0) return CsvTableResult(emptyList(), "", 0)
        val padded = rawRows.map { row ->
            if (row.size < colCount) row + List(colCount - row.size) { "" } else row
        }
        return CsvTableResult(rows = padded, csv = buildCsvString(padded, colCount), columnCount = colCount)
    }

    private fun clusterBoxesIntoVisualRows(boxes: List<OcrTextBox>): List<List<OcrTextBox>> {
        val cleaned = boxes.filter { it.text.isNotBlank() }
        if (cleaned.isEmpty()) return emptyList()

        val heights = cleaned.map {
            max(it.lineHeight, it.box.height).coerceAtLeast(10)
        }.sorted()
        val medianH = heights[heights.size / 2].toFloat()

        val sorted = cleaned.sortedWith(compareBy({ (it.box.top + it.box.bottom) / 2 }, { it.box.left }))
        val rows = mutableListOf<MutableList<OcrTextBox>>()
        val rowCenters = mutableListOf<Float>()

        for (tb in sorted) {
            val cy = (tb.box.top + tb.box.bottom) / 2f
            val matchIdx = rowCenters.indexOfLast { anchorY ->
                abs(cy - anchorY) <= medianH * 0.55f
            }
            if (matchIdx >= 0) {
                val r = rows[matchIdx]
                r.add(tb)
                rowCenters[matchIdx] = r.map { (it.box.top + it.box.bottom) / 2f }.average().toFloat()
            } else {
                rows.add(mutableListOf(tb))
                rowCenters.add(cy)
            }
        }
        return rows.map { row -> row.sortedBy { it.box.left } }
    }

    fun buildCsvString(rows: List<List<String>>, columnCount: Int): String {
        return rows.joinToString("\n") { row ->
            (0 until columnCount).joinToString(",") { c ->
                escapeCsvCell(row.getOrElse(c) { "" })
            }
        }
    }

    fun escapeCsvCell(value: String): String {
        val needsQuoting = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        if (!needsQuoting) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }
}
