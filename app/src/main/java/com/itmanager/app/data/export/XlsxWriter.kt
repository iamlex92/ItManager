package com.itmanager.app.data.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A minimal Office Open XML workbook: one sheet per table, bold frozen header with a filter,
 * text cells as inline strings. Opens in Excel, LibreOffice and Google Sheets; no library needed.
 */
object XlsxWriter {
    fun write(tables: List<ReportTable>, out: OutputStream, info: List<Pair<String, String>> = emptyList()) {
        val sheets = buildList {
            if (info.isNotEmpty()) add(ReportTable("Сводка", listOf("", ""), info.map { listOf(it.first, it.second) }, listOf(28, 40)))
            addAll(tables)
        }
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", contentTypes(sheets.size))
            entry("_rels/.rels", ROOT_RELS)
            entry("xl/workbook.xml", workbook(sheets))
            entry("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            entry("xl/styles.xml", STYLES)
            sheets.forEachIndexed { i, t -> entry("xl/worksheets/sheet${i + 1}.xml", sheet(t, header = t.header.any { it.isNotEmpty() })) }
        }
    }

    /** Excel sheet names: at most 31 characters, none of []:*?/\. */
    internal fun sheetName(raw: String) = raw.replace(Regex("""[\[\]:*?/\\]"""), " ").take(31)

    internal fun column(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.insert(0, 'A' + r)
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    internal fun escape(s: String): String = buildString(s.length) {
        for (c in s) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                // Control characters are not allowed in XML 1.0.
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> append(' ')
                else -> append(c)
            }
        }
    }

    private fun sheet(t: ReportTable, header: Boolean): String = buildString {
        val lastCol = column(maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0) - 1)
        val lastRow = t.rows.size + if (header) 1 else 0
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        if (header) {
            append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        }
        append("<cols>")
        t.widths.forEachIndexed { i, w -> append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        append("</cols><sheetData>")
        var r = 0
        fun row(cells: List<String>, style: Int) {
            r++
            append("""<row r="$r">""")
            cells.forEachIndexed { c, v ->
                if (v.isNotEmpty()) {
                    append("""<c r="${column(c)}$r" t="inlineStr"${if (style > 0) " s=\"$style\"" else ""}><is><t xml:space="preserve">${escape(v)}</t></is></c>""")
                }
            }
            append("</row>")
        }
        if (header) row(t.header, style = 1)
        t.rows.forEach { row(it, style = 0) }
        append("</sheetData>")
        if (header && t.rows.isNotEmpty()) append("""<autoFilter ref="A1:$lastCol$lastRow"/>""")
        append("</worksheet>")
    }

    private fun workbook(sheets: List<ReportTable>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        sheets.forEachIndexed { i, t -> append("""<sheet name="${escape(sheetName(t.title))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
        append("</sheets>")
        // Filters need a hidden defined name per sheet in Excel.
        val filtered = sheets.withIndex().filter { (_, t) -> t.header.any { it.isNotEmpty() } && t.rows.isNotEmpty() }
        if (filtered.isNotEmpty()) {
            append("<definedNames>")
            filtered.forEach { (i, t) ->
                val lastCol = column(t.header.size - 1)
                append("""<definedName name="_xlnm._FilterDatabase" localSheetId="$i" hidden="1">'${escape(sheetName(t.title)).replace("'", "''")}'!${'$'}A${'$'}1:${'$'}$lastCol${'$'}${t.rows.size + 1}</definedName>""")
            }
            append("</definedNames>")
        }
        append("</workbook>")
    }

    private fun workbookRels(count: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..count) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        append("""<Relationship Id="rId${count + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private fun contentTypes(count: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..count) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private const val ROOT_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    // Style 0: default; style 1: bold header on a light fill.
    private const val STYLES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE8EEF5"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
}
