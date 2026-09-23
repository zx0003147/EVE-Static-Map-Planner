package dev.evestaticmapplanner.data.ansiblex

internal object WebwayAnsiblexParser {
    private val expectedHeaders = listOf(
        "Region",
        "System / POS",
        "System / POS",
        "Status",
        "Owner",
        "Password",
        "Dist (ly)",
        "Route",
        "Friendly",
    )

    fun parse(text: String): ParsedImport {
        val physicalLines = text.lineSequence().mapIndexed { index, value -> IndexedLine(index + 1L, value.trim()) }
            .filter { it.value.isNotEmpty() }
            .toList()
        if (physicalLines.isEmpty()) return ParsedImport(emptyList(), listOf(error("EMPTY_WEBWAY", "Pasted Webway text is empty")))

        val tabular = physicalLines.map { it to splitColumns(it.value) }
        val headerRowIndex = tabular.indexOfFirst { (_, columns) -> headersMatch(columns) }
        if (headerRowIndex >= 0) return parseTabularRows(tabular.drop(headerRowIndex + 1))

        val headerStart = physicalLines.indices.firstOrNull { start ->
            physicalLines.drop(start).take(expectedHeaders.size).map(IndexedLine::value) == expectedHeaders
        } ?: return ParsedImport(
            emptyList(),
            listOf(error("WEBWAY_HEADER_NOT_FOUND", "Expected Webway table header was not found")),
        )
        return parseCellStream(physicalLines.drop(headerStart + expectedHeaders.size))
    }

    private fun parseTabularRows(rows: List<Pair<IndexedLine, List<String>>>): ParsedImport {
        val parsed = mutableListOf<RawImportRow>()
        val diagnostics = mutableListOf<ImportDiagnostic>()
        rows.forEach { (line, columns) ->
            if (columns.size != expectedHeaders.size) {
                diagnostics += error(
                    "WEBWAY_COLUMN_COUNT",
                    "Expected ${expectedHeaders.size} columns but found ${columns.size}",
                    line.number,
                )
            } else {
                parsed += row(columns, line.number, diagnostics)
            }
        }
        return ParsedImport(parsed, diagnostics)
    }

    private fun parseCellStream(cells: List<IndexedLine>): ParsedImport {
        val parsed = mutableListOf<RawImportRow>()
        val diagnostics = mutableListOf<ImportDiagnostic>()
        cells.chunked(expectedHeaders.size).forEach { chunk ->
            if (chunk.size != expectedHeaders.size) {
                diagnostics += error(
                    "WEBWAY_INCOMPLETE_ROW",
                    "Incomplete Webway row: expected ${expectedHeaders.size} cells but found ${chunk.size}",
                    chunk.firstOrNull()?.number,
                )
            } else {
                parsed += row(chunk.map(IndexedLine::value), chunk.first().number, diagnostics)
            }
        }
        return ParsedImport(parsed, diagnostics)
    }

    private fun row(columns: List<String>, rowNumber: Long, diagnostics: MutableList<ImportDiagnostic>): RawImportRow {
        val from = systemName(columns[1])
        val to = systemName(columns[2])
        if (from == null) diagnostics += error("WEBWAY_FROM_MISSING", "From System / POS is missing", rowNumber, "from")
        if (to == null) diagnostics += error("WEBWAY_TO_MISSING", "To System / POS is missing", rowNumber, "to")
        val owner = columns[4].trim().takeIf { it.isNotEmpty() && it != "-" }
        if (owner == null) diagnostics += error("WEBWAY_OWNER_MISSING", "Webway Owner is missing", rowNumber, "owner")
        return RawImportRow(
            rowNumber = rowNumber,
            from = RawImportEndpoint(null, from),
            to = RawImportEndpoint(null, to),
            displayName = null,
            notes = listOf(columns[0], columns[7]).filter { it.isNotBlank() && it != "-" }.joinToString(" · ").takeIf(String::isNotEmpty),
            ownerAllianceId = null,
            ownerAllianceName = null,
            ownerAllianceTicker = null,
            ownerRawText = owner,
            enabled = columns[3].equals("Online", ignoreCase = true),
            direction = "BIDIRECTIONAL",
        )
    }

    private fun systemName(value: String): String? = value.substringBefore(" @ ")
        .trim()
        .takeIf(String::isNotEmpty)

    private fun splitColumns(value: String): List<String> = when {
        '\t' in value -> value.split('\t').map(String::trim)
        else -> value.split(MULTI_SPACE).map(String::trim)
    }

    private fun headersMatch(columns: List<String>): Boolean = columns == expectedHeaders

    private fun error(code: String, message: String, rowNumber: Long? = null, field: String? = null) =
        ImportDiagnostic(ImportDiagnosticSeverity.ERROR, code, message, rowNumber, field)

    private data class IndexedLine(val number: Long, val value: String)
    private val MULTI_SPACE = Regex("\\s{2,}")
}
