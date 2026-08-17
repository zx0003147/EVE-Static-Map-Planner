package dev.evestaticmapplanner.data.ansiblex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.csv.CSVFormat
import java.io.StringReader

internal object AnsiblexImportParsers {
    private val allowedCsvHeaders = setOf(
        "from_system_id",
        "from_system_name",
        "to_system_id",
        "to_system_name",
        "connection_name",
        "note",
        "enabled",
        "direction",
    )

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    }

    fun parse(fileName: String, text: String): ParsedImport = when (fileName.substringAfterLast('.', "").lowercase()) {
        "csv" -> parseCsv(text)
        "json" -> parseJson(text)
        else -> ParsedImport(
            emptyList(),
            listOf(error("UNSUPPORTED_FILE_TYPE", "Only .csv and .json Ansiblex imports are supported")),
        )
    }

    private fun parseCsv(text: String): ParsedImport = runCatching {
        val format = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(false)
            .get()
        format.parse(StringReader(text)).use { parser ->
            val headers = parser.headerNames
            val diagnostics = mutableListOf<ImportDiagnostic>()
            val unknown = headers.filter { it !in allowedCsvHeaders }
            if (unknown.isNotEmpty()) {
                diagnostics += error("UNKNOWN_CSV_COLUMN", "Unknown CSV column(s): ${unknown.joinToString()}")
            }
            if (headers.none { it == "from_system_id" || it == "from_system_name" }) {
                diagnostics += error("MISSING_FROM_COLUMN", "CSV requires from_system_id or from_system_name")
            }
            if (headers.none { it == "to_system_id" || it == "to_system_name" }) {
                diagnostics += error("MISSING_TO_COLUMN", "CSV requires to_system_id or to_system_name")
            }
            val rows = parser.map { record ->
                val rowNumber = record.recordNumber + 1
                fun value(header: String): String? = if (header in headers) {
                    record.get(header).trim().takeIf(String::isNotEmpty)
                } else {
                    null
                }
                fun id(header: String): Int? {
                    val raw = value(header) ?: return null
                    return raw.toIntOrNull()?.takeIf { it > 0 } ?: run {
                        diagnostics += error(
                            "INVALID_SYSTEM_ID",
                            "System ID must be a positive integer: $raw",
                            rowNumber,
                            header,
                        )
                        null
                    }
                }
                fun enabled(): Boolean? = when (val raw = value("enabled")?.lowercase()) {
                    null -> null
                    "true" -> true
                    "false" -> false
                    else -> {
                        diagnostics += error(
                            "INVALID_ENABLED",
                            "enabled must be true or false: $raw",
                            rowNumber,
                            "enabled",
                        )
                        null
                    }
                }
                RawImportRow(
                    rowNumber = rowNumber,
                    from = RawImportEndpoint(id("from_system_id"), value("from_system_name")),
                    to = RawImportEndpoint(id("to_system_id"), value("to_system_name")),
                    displayName = value("connection_name"),
                    notes = value("note"),
                    enabled = enabled(),
                    direction = value("direction"),
                )
            }
            ParsedImport(rows, diagnostics)
        }
    }.getOrElse { cause ->
        ParsedImport(emptyList(), listOf(error("BAD_CSV", cause.message ?: "Unable to parse CSV")))
    }

    private fun parseJson(text: String): ParsedImport = runCatching {
        val document = json.decodeFromString<JsonDocument>(text)
        if (document.formatVersion != 1) {
            return ParsedImport(
                emptyList(),
                listOf(error("UNSUPPORTED_FORMAT_VERSION", "Unsupported JSON format_version: ${document.formatVersion}")),
            )
        }
        ParsedImport(
            rows = document.connections.mapIndexed { index, row ->
                RawImportRow(
                    rowNumber = (index + 1).toLong(),
                    from = RawImportEndpoint(row.from.systemId, row.from.systemName?.trim()?.takeIf(String::isNotEmpty)),
                    to = RawImportEndpoint(row.to.systemId, row.to.systemName?.trim()?.takeIf(String::isNotEmpty)),
                    displayName = row.connectionName?.trim()?.takeIf(String::isNotEmpty),
                    notes = row.note?.trim()?.takeIf(String::isNotEmpty),
                    enabled = row.enabled,
                    direction = row.direction,
                )
            },
            diagnostics = emptyList(),
        )
    }.getOrElse { cause ->
        ParsedImport(emptyList(), listOf(error("BAD_JSON", cause.message ?: "Unable to parse JSON")))
    }

    private fun error(
        code: String,
        message: String,
        rowNumber: Long? = null,
        field: String? = null,
    ) = ImportDiagnostic(ImportDiagnosticSeverity.ERROR, code, message, rowNumber, field)
}

@Serializable
private data class JsonDocument(
    @SerialName("format_version") val formatVersion: Int,
    val connections: List<JsonConnection>,
)

@Serializable
private data class JsonConnection(
    val from: JsonEndpoint,
    val to: JsonEndpoint,
    @SerialName("connection_name") val connectionName: String? = null,
    val note: String? = null,
    val enabled: Boolean? = null,
    val direction: String? = null,
)

@Serializable
private data class JsonEndpoint(
    @SerialName("system_id") val systemId: Int? = null,
    @SerialName("system_name") val systemName: String? = null,
)
