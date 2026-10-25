package com.itmanager.app.data.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/** The report as an A4 landscape PDF: summary, then each table with its header repeated on every page. */
object InventoryPdf {
    private const val PAGE_W = 842
    private const val PAGE_H = 595
    private const val MARGIN = 36f
    private const val ROW_H = 15f

    fun write(report: InventoryReport, out: OutputStream) {
        val doc = PdfDocument()
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = Color.DKGRAY }
        val cell = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.BLACK }
        val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val band = Paint().apply { color = Color.rgb(0xE8, 0xEE, 0xF5) }
        val rule = Paint().apply { color = Color.rgb(0xD0, 0xD5, 0xDC); strokeWidth = 0.5f }

        var pageNo = 0
        var page: PdfDocument.Page? = null
        lateinit var canvas: Canvas
        var y = 0f

        fun newPage() {
            page?.let(doc::finishPage)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page!!.canvas
            canvas.drawText("${report.title} · ${report.subtitle}", MARGIN, PAGE_H - 18f, small)
            canvas.drawText("стр. $pageNo", PAGE_W - MARGIN - small.measureText("стр. $pageNo"), PAGE_H - 18f, small)
            y = MARGIN
        }

        fun fits(h: Float) = y + h <= PAGE_H - MARGIN - 10f

        newPage()
        canvas.drawText(report.title, MARGIN, y + 14f, title)
        y += 22f
        canvas.drawText(report.subtitle, MARGIN, y + 8f, small)
        y += 20f
        report.summary.forEach { (k, v) ->
            canvas.drawText(k, MARGIN, y + 9f, cell)
            canvas.drawText(v, MARGIN + 160f, y + 9f, head)
            y += 13f
        }

        for (table in report.tables) {
            val width = PAGE_W - 2 * MARGIN
            val total = table.widths.sum().toFloat()
            val cols = table.widths.map { it / total * width }

            fun header() {
                canvas.drawRect(MARGIN, y, MARGIN + width, y + ROW_H, band)
                var x = MARGIN
                table.header.forEachIndexed { i, h ->
                    canvas.drawText(fit(h, cols[i] - 6f, head), x + 3f, y + 10.5f, head)
                    x += cols[i]
                }
                y += ROW_H
            }

            y += 14f
            if (!fits(40f + 2 * ROW_H)) newPage()
            canvas.drawText(table.title, MARGIN, y + 12f, title.apply { textSize = 12f })
            y += 20f
            header()
            table.rows.forEach { row ->
                if (!fits(ROW_H)) {
                    newPage()
                    header()
                }
                var x = MARGIN
                row.forEachIndexed { i, v ->
                    if (i < cols.size) canvas.drawText(fit(v, cols[i] - 6f, cell), x + 3f, y + 10.5f, cell)
                    x += cols.getOrElse(i) { 0f }
                }
                y += ROW_H
                canvas.drawLine(MARGIN, y, MARGIN + width, y, rule)
            }
            title.textSize = 16f
        }
        page?.let(doc::finishPage)
        doc.writeTo(out)
        doc.close()
    }

    /** Cuts text to the column with an ellipsis instead of running into the next one. */
    private fun fit(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        val n = paint.breakText(text, true, maxWidth - paint.measureText("…"), null)
        return text.take(n.coerceAtLeast(0)) + "…"
    }
}
