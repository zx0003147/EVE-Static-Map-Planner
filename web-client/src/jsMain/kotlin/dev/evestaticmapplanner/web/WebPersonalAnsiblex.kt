package dev.evestaticmapplanner.web

import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteLink
import dev.evestaticmapplanner.core.route.RouteLinkDirection
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
enum class WebAnsiblexDirection {
    BIDIRECTIONAL,
    FIRST_TO_SECOND,
    SECOND_TO_FIRST,
}

data class WebAnsiblexLink(
    val id: String,
    val firstSystemId: Int,
    val secondSystemId: Int,
    val direction: WebAnsiblexDirection,
    val enabled: Boolean,
    val personal: Boolean,
)

@Serializable
data class PersonalAnsiblexConnection(
    val id: String,
    val firstSystemId: Int,
    val secondSystemId: Int,
    val direction: WebAnsiblexDirection,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank())
        require(firstSystemId > 0 && firstSystemId < secondSystemId)
    }

    fun toVisual() = WebAnsiblexLink(id, firstSystemId, secondSystemId, direction, enabled, personal = true)

    fun toRouteLink() = RouteLink(
        RouteConnectionId("personal-ansiblex:$id"),
        firstSystemId,
        secondSystemId,
        direction.toRouteDirection(),
        RouteEdgeType.ANSIBLEX,
        enabled,
    )
}

data class PersonalAnsiblexDraft(
    val fromSystemId: Int,
    val toSystemId: Int,
    val bidirectional: Boolean,
    val enabled: Boolean = true,
) {
    val firstSystemId = minOf(fromSystemId, toSystemId)
    val secondSystemId = maxOf(fromSystemId, toSystemId)
    val direction = when {
        bidirectional -> WebAnsiblexDirection.BIDIRECTIONAL
        fromSystemId < toSystemId -> WebAnsiblexDirection.FIRST_TO_SECOND
        else -> WebAnsiblexDirection.SECOND_TO_FIRST
    }

    init {
        require(fromSystemId > 0 && toSystemId > 0 && fromSystemId != toSystemId)
    }
}

data class PersonalAnsiblexImportError(val row: Int, val message: String)

data class PersonalAnsiblexImportPreview(
    val valid: List<PersonalAnsiblexDraft>,
    val errors: List<PersonalAnsiblexImportError>,
    val duplicateRows: List<Int>,
) {
    val canApply: Boolean get() = valid.isNotEmpty() && errors.isEmpty()
}

object PersonalAnsiblexParser {
    fun parse(
        fileName: String,
        content: String,
        systems: Collection<SolarSystem>,
        packLinks: Collection<WebAnsiblexLink>,
        existing: Collection<PersonalAnsiblexConnection>,
    ): PersonalAnsiblexImportPreview {
        if (content.isBlank()) return PersonalAnsiblexImportPreview(
            emptyList(),
            listOf(PersonalAnsiblexImportError(1, "File is empty.")),
            emptyList(),
        )
        val resolver = SystemResolver(systems)
        val parsed = if (fileName.lowercase().endsWith(".json") || content.trimStart().startsWith("[")) {
            parseJson(content, resolver)
        } else {
            parseCsv(content, resolver)
        }
        val occupied = (packLinks.map { ansiblexPairKey(it.firstSystemId, it.secondSystemId) } + existing.map {
            ansiblexPairKey(it.firstSystemId, it.secondSystemId)
        }).toMutableSet()
        val valid = mutableListOf<PersonalAnsiblexDraft>()
        val duplicates = mutableListOf<Int>()
        parsed.rows.forEach { row ->
            val key = ansiblexPairKey(row.draft.firstSystemId, row.draft.secondSystemId)
            if (!occupied.add(key)) duplicates += row.row else valid += row.draft
        }
        return PersonalAnsiblexImportPreview(valid, parsed.errors, duplicates)
    }

    private fun parseCsv(content: String, resolver: SystemResolver): ParsedRows {
        val lines = content.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.isEmpty()) return ParsedRows(errors = listOf(PersonalAnsiblexImportError(1, "File is empty.")))
        val header = splitCsv(lines.first()).map { it.lowercase() }
        val fromIndex = header.indexOfFirst { it in setOf("from", "from_system_id", "from_system_name") }
        val toIndex = header.indexOfFirst { it in setOf("to", "to_system_id", "to_system_name") }
        val directionIndex = header.indexOf("direction")
        val enabledIndex = header.indexOf("enabled")
        if (fromIndex < 0 || toIndex < 0) return ParsedRows(
            errors = listOf(PersonalAnsiblexImportError(1, "CSV header must contain from/to or from_system_id/to_system_id.")),
        )
        val rows = mutableListOf<ParsedRow>()
        val errors = mutableListOf<PersonalAnsiblexImportError>()
        lines.drop(1).forEachIndexed { index, line ->
            val number = index + 2
            val cells = splitCsv(line)
            val from = cells.getOrNull(fromIndex)
            val to = cells.getOrNull(toIndex)
            val draft = resolveDraft(from, to, cells.getOrNull(directionIndex), cells.getOrNull(enabledIndex), resolver)
            draft.fold(
                onSuccess = { rows += ParsedRow(number, it) },
                onFailure = { errors += PersonalAnsiblexImportError(number, it.message ?: "Invalid connection.") },
            )
        }
        return ParsedRows(rows, errors)
    }

    private fun parseJson(content: String, resolver: SystemResolver): ParsedRows {
        val root = runCatching { JSON.parseToJsonElement(content) }.getOrElse {
            return ParsedRows(errors = listOf(PersonalAnsiblexImportError(1, "JSON is malformed.")))
        }
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                if (primitiveValue(root["format_version"]) != "1") return ParsedRows(
                    errors = listOf(PersonalAnsiblexImportError(1, "Desktop JSON format_version must be 1.")),
                )
                root["connections"] as? JsonArray ?: return ParsedRows(
                    errors = listOf(PersonalAnsiblexImportError(1, "JSON must be an array or a Desktop format document with connections.")),
                )
            }
            else -> return ParsedRows(errors = listOf(PersonalAnsiblexImportError(1, "JSON root is invalid.")))
        }
        val rows = mutableListOf<ParsedRow>()
        val errors = mutableListOf<PersonalAnsiblexImportError>()
        array.forEachIndexed { index, element ->
            val number = index + 1
            val value = element as? JsonObject
            if (value == null) {
                errors += PersonalAnsiblexImportError(number, "Entry must be an object.")
                return@forEachIndexed
            }
            val from = endpointValue(value["from"])
                ?: primitiveValue(value["from_system_id"])
                ?: primitiveValue(value["from_system_name"])
            val to = endpointValue(value["to"])
                ?: primitiveValue(value["to_system_id"])
                ?: primitiveValue(value["to_system_name"])
            val direction = primitiveValue(value["direction"])
            val enabled = primitiveValue(value["enabled"])
            resolveDraft(from, to, direction, enabled, resolver).fold(
                onSuccess = { rows += ParsedRow(number, it) },
                onFailure = { errors += PersonalAnsiblexImportError(number, it.message ?: "Invalid connection.") },
            )
        }
        return ParsedRows(rows, errors)
    }

    private fun resolveDraft(
        rawFrom: String?,
        rawTo: String?,
        rawDirection: String?,
        rawEnabled: String?,
        resolver: SystemResolver,
    ): Result<PersonalAnsiblexDraft> = runCatching {
        val from = resolver.resolve(rawFrom ?: error("Missing from system."))
        val to = resolver.resolve(rawTo ?: error("Missing to system."))
        require(from != to) { "Self-loop connections are not allowed." }
        val enabled = when (rawEnabled?.trim()?.lowercase()?.takeIf(String::isNotEmpty)) {
            null, "true" -> true
            "false" -> false
            else -> error("Enabled must be true or false.")
        }
        val direction = rawDirection?.trim()?.uppercase()?.ifEmpty { "BIDIRECTIONAL" } ?: "BIDIRECTIONAL"
        val bidirectional = when (direction) {
            "BIDIRECTIONAL", "BOTH", "B" -> true
            "FIRST_TO_SECOND", "FORWARD", "ONE_WAY", "->" -> false
            "SECOND_TO_FIRST", "REVERSE", "<-" -> return@runCatching PersonalAnsiblexDraft(to, from, false, enabled)
            else -> error("Direction must be BIDIRECTIONAL, FIRST_TO_SECOND, or SECOND_TO_FIRST.")
        }
        PersonalAnsiblexDraft(from, to, bidirectional, enabled)
    }

    private fun primitiveValue(value: kotlinx.serialization.json.JsonElement?): String? =
        (value as? JsonPrimitive)?.contentOrNull

    private fun endpointValue(value: kotlinx.serialization.json.JsonElement?): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull
        is JsonObject -> primitiveValue(value["system_id"]) ?: primitiveValue(value["system_name"])
        else -> null
    }

    private fun splitCsv(line: String): List<String> = line.split(',').map { it.trim().trim('"') }

    private data class ParsedRow(val row: Int, val draft: PersonalAnsiblexDraft)
    private data class ParsedRows(
        val rows: List<ParsedRow> = emptyList(),
        val errors: List<PersonalAnsiblexImportError> = emptyList(),
    )

    private class SystemResolver(systems: Collection<SolarSystem>) {
        private val byId = systems.associateBy(SolarSystem::id)
        private val byName = systems.groupBy { it.name.lowercase() }

        fun resolve(raw: String): Int {
            val value = raw.trim()
            value.toIntOrNull()?.let { return byId[it]?.id ?: error("Unknown system ID $it.") }
            val matches = byName[value.lowercase()].orEmpty()
            require(matches.size == 1) { if (matches.isEmpty()) "Unknown system '$value'." else "System '$value' is ambiguous." }
            return matches.single().id
        }
    }

    private val JSON = Json { ignoreUnknownKeys = true }
}

class PersonalAnsiblexStore(private val storage: BrowserStringStore = LocalStorageStringStore) {
    fun load(): List<PersonalAnsiblexConnection> = storage.get(STORAGE_KEY)?.let { raw ->
        runCatching { JSON.decodeFromString<List<PersonalAnsiblexConnection>>(raw) }.getOrNull()
    }.orEmpty().filter { it.firstSystemId > 0 && it.firstSystemId < it.secondSystemId }.take(MAX_CONNECTIONS)

    fun save(connections: List<PersonalAnsiblexConnection>) {
        require(connections.size <= MAX_CONNECTIONS) { "Personal Ansiblex is limited to $MAX_CONNECTIONS connections." }
        storage.set(STORAGE_KEY, JSON.encodeToString(connections))
    }

    fun clear() = storage.remove(STORAGE_KEY)

    private companion object {
        val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        const val STORAGE_KEY = "eve-static-map-planner.personal-ansiblex.v1"
        const val MAX_CONNECTIONS = 500
    }
}

class WebKeepstarMarkerStore(private val storage: BrowserStringStore = LocalStorageStringStore) {
    fun load(): Set<Int> = storage.get(STORAGE_KEY).orEmpty().split(',').mapNotNull(String::toIntOrNull)
        .filter { it > 0 }.toSet()

    fun save(systemIds: Set<Int>) {
        storage.set(STORAGE_KEY, systemIds.sorted().joinToString(","))
    }

    private companion object {
        const val STORAGE_KEY = "eve-static-map-planner.saved-marker.keepstar.v1"
    }
}

internal fun WebAnsiblexDirection.toRouteDirection(): RouteLinkDirection = when (this) {
    WebAnsiblexDirection.BIDIRECTIONAL -> RouteLinkDirection.BIDIRECTIONAL
    WebAnsiblexDirection.FIRST_TO_SECOND -> RouteLinkDirection.FIRST_TO_SECOND
    WebAnsiblexDirection.SECOND_TO_FIRST -> RouteLinkDirection.SECOND_TO_FIRST
}

internal fun ansiblexPairKey(first: Int, second: Int) = "${minOf(first, second)}:${maxOf(first, second)}"
