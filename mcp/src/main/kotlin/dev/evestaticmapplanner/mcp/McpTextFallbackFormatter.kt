package dev.evestaticmapplanner.mcp

import dev.evestaticmapplanner.control.transport.LocalControlClientError
import dev.evestaticmapplanner.control.transport.LocalControlClientErrorCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Produces a bounded, human-readable summary for MCP clients that ignore structuredContent. */
internal object McpTextFallbackFormatter {
    private const val MAX_TEXT_LENGTH = 4_000
    private const val MAX_SEARCH_RESULTS = 10
    private const val MAX_MISSION_ITEMS = 8
    private const val MAX_PATH_SYSTEMS = 16

    fun format(toolName: String, structuredContent: JsonObject): String = limit(
        when (toolName) {
            "search_system" -> formatSystemSearch(structuredContent)
            "get_system_info" -> formatSystemInfo(structuredContent)
            "get_system_markers" -> formatSystemMarkers(structuredContent)
            "list_wormholes" -> formatWormholes(structuredContent)
            "calculate_normal_route" -> formatRoute(structuredContent, "NORMAL", "Route calculated.")
            "calculate_capital_route" -> formatRoute(structuredContent, "CAPITAL", "Route calculated.")
            "get_active_missions" -> formatActiveMissions(structuredContent)
            "get_mission" -> formatMission(structuredContent)
            "list_eve_navigation_targets" -> formatEveNavigationTargets(structuredContent)
            "begin_mission" -> formatMutation("Mission created successfully.", structuredContent)
            "focus_system" -> formatMutation("Map focused successfully.", structuredContent)
            "create_wormhole" -> formatCreatedWormhole(structuredContent)
            "show_normal_route", "show_capital_route" -> formatDisplayedRoute(structuredContent)
            "remove_mission_route" -> formatMutation("Mission route removed successfully.", structuredContent)
            "clear_mission_routes" -> formatMutation("Mission routes cleared successfully.", structuredContent)
            "show_jump_range" -> formatMutation("Jump range displayed successfully.", structuredContent)
            "remove_jump_range" -> formatMutation("Jump range removed successfully.", structuredContent)
            "clear_mission_jump_ranges" -> formatMutation("Mission jump ranges cleared successfully.", structuredContent)
            "add_mission_marker" -> formatMutation("Mission marker added successfully.", structuredContent)
            "remove_mission_marker" -> formatMutation("Mission marker removed successfully.", structuredContent)
            "clear_mission_markers" -> formatMutation("Mission markers cleared successfully.", structuredContent)
            "fit_mission" -> formatMutation("Map fitted to mission successfully.", structuredContent)
            "clear_mission" -> formatMutation("Mission cleared successfully.", structuredContent)
            "create_saved_marker" -> formatCreatedSavedMarker(structuredContent)
            "send_mission_navigation_to_eve" -> formatSentMissionNavigation(structuredContent)
            else -> formatGeneric(toolName, structuredContent)
        },
    )

    fun formatError(toolName: String, error: LocalControlClientError): String {
        if (toolName !in SAVED_MARKER_TOOLS) return "${error.code.name}: ${error.message}"
        return when (error.code) {
            LocalControlClientErrorCode.CAPABILITY_DENIED ->
                "CAPABILITY_DENIED: Saved Marker access is disabled in Preferences. Permission was denied and no marker was changed."
            LocalControlClientErrorCode.MARKER_ALREADY_EXISTS ->
                "MARKER_ALREADY_EXISTS: This solar system already has a Saved Marker. The existing marker was not overwritten."
            LocalControlClientErrorCode.SYSTEM_NOT_FOUND ->
                "SYSTEM_NOT_FOUND: The requested solar system does not exist. No marker was changed."
            LocalControlClientErrorCode.INVALID_ARGUMENT ->
                "INVALID_ARGUMENT: The marker request is invalid. Check the required systemId and supported field types."
            LocalControlClientErrorCode.INVALID_MARKER_DATA ->
                "INVALID_MARKER_DATA: The Saved Marker data is invalid. Check the color and text limits."
            LocalControlClientErrorCode.DATABASE_UNAVAILABLE ->
                "DATABASE_UNAVAILABLE: Saved Marker storage is unavailable. The operation was not completed."
            LocalControlClientErrorCode.IDEMPOTENCY_CONFLICT ->
                "IDEMPOTENCY_CONFLICT: A retried mutation did not match its original request. No different mutation was applied."
            LocalControlClientErrorCode.INTERNAL_ERROR ->
                "INTERNAL_ERROR: The Saved Marker operation failed internally. Do not claim that it succeeded."
            else -> "${error.code.name}: ${error.message}"
        }
    }

    private fun formatSystemSearch(content: JsonObject): String {
        val systems = content.array("systems")
        val shown = systems.take(MAX_SEARCH_RESULTS)
        return buildString {
            appendLine("Found ${systems.size} ${if (systems.size == 1) "system" else "systems"}.")
            shown.forEachIndexed { index, element ->
                val system = element as? JsonObject ?: return@forEachIndexed
                appendLine()
                if (shown.size > 1) appendLine("Result ${index + 1}:")
                appendField("Name", system.text("canonicalName") ?: system.text("name"))
                appendField("System ID", system.text("systemId"))
                appendField("Region ID", system.text("regionId"))
                appendField("Constellation ID", system.text("constellationId"))
                appendField("Security", system.text("securityStatus"))
            }
            appendOmitted(systems.size - shown.size, "search results")
        }.trimEnd()
    }

    private fun formatSystemInfo(content: JsonObject): String {
        val system = content.obj("system")
        return buildString {
            appendLine("System information.")
            appendLine()
            appendField("Name", system?.text("name") ?: system?.text("canonicalName"))
            appendField("System ID", system?.text("systemId"))
            appendField("Region", content.text("regionName"))
            appendField("Region ID", system?.text("regionId"))
            appendField("Constellation", content.text("constellationName"))
            appendField("Constellation ID", system?.text("constellationId"))
            appendField("Security", system?.text("securityStatus"))
            appendField("Stargates", content.text("stargateCount"))
        }.trimEnd()
    }

    private fun formatSystemMarkers(content: JsonObject): String {
        val systemId = content.text("systemId") ?: "unknown"
        val savedMarker = content["savedMarker"] as? JsonObject
        val missionMarkers = content.array("missionMarkers")
        if (savedMarker == null && missionMarkers.isEmpty()) {
            return "No markers found for system $systemId."
        }
        return buildString {
            appendLine("Markers for system $systemId.")
            if (savedMarker != null) {
                appendLine()
                appendLine("Saved Marker (persistent):")
                appendMarkerFields(savedMarker)
                val children = savedMarker.array("children")
                if (children.isNotEmpty()) {
                    appendField(
                        "Tags",
                        children.mapNotNull { (it as? JsonObject)?.text("type") }.joinToString(", "),
                    )
                }
                appendField("Created by", savedMarker.text("createdBy"))
            } else {
                appendLine()
                appendLine("Saved Marker: none")
            }
            appendLine()
            appendLine("Temporary Mission Markers: ${missionMarkers.size}")
            missionMarkers.take(MAX_MISSION_ITEMS).forEach { element ->
                val marker = element as? JsonObject ?: return@forEach
                append("- ${marker.text("markerId") ?: "unknown ID"}")
                marker.text("missionId")?.let { append(" in Mission $it") }
                marker.text("role")?.let { append(", role $it") }
                marker.text("label")?.let { append(", label: $it") }
                marker.text("color")?.let { append(", color $it") }
                marker.text("notes")?.let { append(", notes: $it") }
                appendLine()
            }
            appendOmitted(missionMarkers.size - MAX_MISSION_ITEMS, "Mission markers")
        }.trimEnd()
    }

    private fun formatCreatedSavedMarker(content: JsonObject): String {
        val marker = content.obj("marker") ?: JsonObject(emptyMap())
        return buildString {
            appendLine("Saved Marker created successfully.")
            appendLine()
            appendMarkerFields(marker)
            val children = marker.array("children")
            if (children.isNotEmpty()) {
                appendField(
                    "Tags",
                    children.mapNotNull { (it as? JsonObject)?.text("type") }.joinToString(", "),
                )
            }
            appendField("Created by", marker.text("createdBy"))
        }.trimEnd()
    }

    private fun formatEveNavigationTargets(content: JsonObject): String {
        val targets = content.array("targets")
        if (targets.isEmpty()) return "No connected EVE navigation targets are available."
        return buildString {
            appendLine("Found ${targets.size} EVE navigation targets.")
            targets.forEach { element ->
                val target = element as? JsonObject ?: return@forEach
                appendLine()
                appendField("Character", target.text("label"))
                appendField("Character ID", target.text("characterId"))
                appendField("Available", target.text("available"))
                appendField("Details", target.text("description"))
            }
        }.trimEnd()
    }

    private fun formatSentMissionNavigation(content: JsonObject): String = buildString {
        appendLine(if (content.text("status") == "SUCCEEDED") "EVE navigation replaced successfully." else "EVE navigation was not confirmed.")
        appendLine()
        appendField("Status", content.text("status"))
        appendField("Message", content.text("message"))
        appendField("Mission ID", content.text("missionId"))
        appendField("Route ID", content.text("routeId"))
        appendField("Character ID", content.text("characterId"))
        appendField("Explicit target count", content.array("targetSystemIds").size.toString())
    }.trimEnd()

    private fun formatWormholes(content: JsonObject): String {
        val wormholes = content.array("wormholes")
        if (wormholes.isEmpty()) return "No temporary Wormhole connections are active."
        return buildString {
            appendLine("Found ${wormholes.size} temporary Wormhole ${if (wormholes.size == 1) "connection" else "connections"}.")
            wormholes.take(MAX_MISSION_ITEMS).forEach { element ->
                val connection = element as? JsonObject ?: return@forEach
                appendLine()
                appendField("Connection ID", connection.text("connectionId"))
                appendField("First system", endpoint(connection, "first"))
                appendField("Second system", endpoint(connection, "second"))
            }
            appendOmitted(wormholes.size - MAX_MISSION_ITEMS, "Wormhole connections")
        }.trimEnd()
    }

    private fun formatCreatedWormhole(content: JsonObject): String {
        val connection = content.obj("connection") ?: JsonObject(emptyMap())
        val heading = if (content.text("created") == "true") {
            "Wormhole connection created successfully."
        } else {
            "Wormhole connection already exists."
        }
        return buildString {
            appendLine(heading)
            appendLine()
            appendField("Status", content.text("status"))
            appendField("Connection ID", connection.text("connectionId"))
            appendField("First system", endpoint(connection, "first"))
            appendField("Second system", endpoint(connection, "second"))
        }.trimEnd()
    }

    private fun endpoint(connection: JsonObject, prefix: String): String? {
        val name = connection.text("${prefix}SystemName")
        val id = connection.text("${prefix}SystemId") ?: return name
        return if (name == null) id else "$name ($id)"
    }

    private fun StringBuilder.appendMarkerFields(marker: JsonObject) {
        appendField("System ID", marker.text("systemId"))
        appendField("Name", marker.text("name"))
        appendField("Color", marker.text("color"))
        appendField("Notes", marker.text("notes"))
    }

    private fun formatRoute(content: JsonObject, routeType: String, heading: String): String = buildString {
        appendLine(heading)
        appendLine()
        appendField("From (system ID)", content.text("startSystemId"))
        appendField("To (system ID)", content.text("destinationSystemId"))
        val waypointIds = content.array("waypointSystemIds")
        if (waypointIds.isNotEmpty()) {
            appendField("Required Waypoints (system IDs)", waypointIds.joinToString(" -> ") { it.toString() })
        }
        if ("explicitDestinationSystemId" in content) {
            appendField(
                "Explicit Destination (system ID)",
                content.text("explicitDestinationSystemId") ?: "None (last Waypoint is the endpoint)",
            )
        }
        appendField("Jumps", content.text("totalJumps"))
        appendField("Route type", routeType)
        if (routeType == "NORMAL") {
            appendField("Stargate jumps", content.text("stargateJumps"))
            appendField("Ansiblex jumps", content.text("ansiblexJumps"))
            appendField("Wormhole jumps", content.text("wormholeJumps"))
        } else {
            appendField("Effective range", content.text("effectiveRangeLy")?.let { "$it LY" })
            appendField("Total distance", content.text("totalDistanceLy")?.let { "$it LY" })
        }
        appendField("Path (system IDs)", formatPath(content.array("systemIds")))
    }.trimEnd()

    private fun formatDisplayedRoute(content: JsonObject): String {
        val route = content.obj("route") ?: JsonObject(emptyMap())
        val routeType = content.text("type") ?: "UNKNOWN"
        return buildString {
            append(formatRoute(route, routeType, "Route calculated and displayed."))
            appendLine()
            appendLine()
            appendField("Mission ID", content.text("missionId"))
            appendField("Route ID", content.text("routeId"))
            appendField("Revision", content.text("revision"))
        }.trimEnd()
    }

    private fun formatActiveMissions(content: JsonObject): String {
        val missions = content.array("missions")
        val shown = missions.take(MAX_MISSION_ITEMS)
        return buildString {
            appendLine("Found ${missions.size} active ${if (missions.size == 1) "mission" else "missions"}.")
            shown.forEach { element ->
                val mission = element as? JsonObject ?: return@forEach
                appendLine()
                appendLine("${mission.text("title") ?: "Untitled mission"} (${mission.text("missionId") ?: "unknown ID"})")
                appendLine(
                    "Revision ${mission.text("revision") ?: "unknown"}; " +
                        "routes ${mission.text("routeCount") ?: "unknown"}; " +
                        "jump ranges ${mission.text("jumpRangeCount") ?: "unknown"}; " +
                        "markers ${mission.text("markerCount") ?: "unknown"}.",
                )
            }
            appendOmitted(missions.size - shown.size, "missions")
        }.trimEnd()
    }

    private fun formatMission(content: JsonObject): String {
        val routes = content.array("routes")
        val jumpRanges = content.array("jumpRanges")
        val markers = content.array("markers")
        val referencedSystems = content.array("referencedSystemIds")
        return buildString {
            appendLine("Mission details.")
            appendLine()
            appendField("Title", content.text("title"))
            appendField("Mission ID", content.text("missionId"))
            appendField("Revision", content.text("revision"))
            appendField("Routes", routes.size.toString())
            appendField("Jump ranges", jumpRanges.size.toString())
            appendField("Markers", markers.size.toString())
            appendField("Referenced systems", referencedSystems.size.toString())

            appendMissionRoutes(routes)
            appendMissionJumpRanges(jumpRanges)
            appendMissionMarkers(markers)
        }.trimEnd()
    }

    private fun StringBuilder.appendMissionRoutes(routes: JsonArray) {
        val shown = routes.take(MAX_MISSION_ITEMS)
        if (shown.isEmpty()) return
        appendLine()
        appendLine("Route summaries:")
        shown.forEach { element ->
            val ownedRoute = element as? JsonObject ?: return@forEach
            val route = ownedRoute.obj("route")
            append("- ${ownedRoute.text("routeId") ?: "unknown ID"}: ${ownedRoute.text("type") ?: "UNKNOWN"}")
            route?.let {
                append(", ${it.text("startSystemId") ?: "?"} -> ${it.text("destinationSystemId") ?: "?"}")
                append(", ${it.text("totalJumps") ?: "?"} jumps")
            }
            appendLine()
        }
        appendOmitted(routes.size - shown.size, "routes")
    }

    private fun StringBuilder.appendMissionJumpRanges(jumpRanges: JsonArray) {
        val shown = jumpRanges.take(MAX_MISSION_ITEMS)
        if (shown.isEmpty()) return
        appendLine()
        appendLine("Jump range summaries:")
        shown.forEach { element ->
            val range = element as? JsonObject ?: return@forEach
            val reachableCount = range.array("reachableSystemIds").size
            val profile = range.obj("profile")
            append("- ${range.text("jumpRangeId") ?: range.text("overlayId") ?: "unknown ID"}")
            append(": origin ${range.text("originSystemId") ?: "?"}")
            profile?.text("displayName")?.let { append(", $it") }
            profile?.text("maxRangeLy")?.let { append(", $it LY") }
            append(", $reachableCount reachable systems")
            range.text("label")?.let { append(", label: $it") }
            appendLine()
        }
        appendOmitted(jumpRanges.size - shown.size, "jump ranges")
    }

    private fun StringBuilder.appendMissionMarkers(markers: JsonArray) {
        val shown = markers.take(MAX_MISSION_ITEMS)
        if (shown.isEmpty()) return
        appendLine()
        appendLine("Marker summaries:")
        shown.forEach { element ->
            val marker = element as? JsonObject ?: return@forEach
            append("- ${marker.text("markerId") ?: "unknown ID"}: system ${marker.text("systemId") ?: "?"}")
            marker.text("role")?.let { append(", role $it") }
            marker.text("label")?.let { append(", label: $it") }
            appendLine()
        }
        appendOmitted(markers.size - shown.size, "markers")
    }

    private fun formatMutation(heading: String, content: JsonObject): String = buildString {
        appendLine(heading)
        appendLine()
        MUTATION_FIELDS.forEach { (name, label) -> appendField(label, content.text(name)) }
    }.trimEnd()

    private fun formatGeneric(toolName: String, content: JsonObject): String = buildString {
        appendLine("$toolName completed successfully.")
        content.entries.asSequence()
            .filter { (_, value) -> value is JsonPrimitive && value !== JsonNull }
            .take(12)
            .forEach { (name, value) -> appendField(name, (value as JsonPrimitive).content) }
    }.trimEnd()

    private fun formatPath(systemIds: JsonArray): String? {
        if (systemIds.isEmpty()) return null
        val values = systemIds.mapNotNull { it.displayText() }
        if (values.size <= MAX_PATH_SYSTEMS) return values.joinToString(" -> ")
        val first = values.take(MAX_PATH_SYSTEMS / 2)
        val last = values.takeLast(MAX_PATH_SYSTEMS / 2)
        return first.joinToString(" -> ") +
            " -> ... (${values.size - MAX_PATH_SYSTEMS} systems omitted) ... -> " +
            last.joinToString(" -> ")
    }

    private fun limit(value: String): String {
        if (value.length <= MAX_TEXT_LENGTH) return value
        return value.take(MAX_TEXT_LENGTH - TRUNCATION_MARKER.length).trimEnd() + TRUNCATION_MARKER
    }

    private fun StringBuilder.appendField(label: String, value: String?) {
        if (value == null) return
        appendLine("$label:")
        appendLine(value)
    }

    private fun StringBuilder.appendOmitted(count: Int, description: String) {
        if (count <= 0) return
        appendLine()
        appendLine("[$count more $description omitted.]")
    }

    private fun JsonObject.text(name: String): String? = this[name]?.displayText()
    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
    private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonElement.displayText(): String? = (this as? JsonPrimitive)
        ?.takeUnless { it === JsonNull }
        ?.content

    private val MUTATION_FIELDS = listOf(
        "missionId" to "Mission ID",
        "routeId" to "Route ID",
        "overlayId" to "Overlay ID",
        "markerId" to "Marker ID",
        "systemId" to "System ID",
        "name" to "System name",
        "originSystemId" to "Origin system ID",
        "effectiveRangeLy" to "Effective range (LY)",
        "reachableSystemCount" to "Reachable systems",
        "role" to "Marker role",
        "revision" to "Revision",
    )

    private val SAVED_MARKER_TOOLS = setOf("get_system_markers", "create_saved_marker")

    private const val TRUNCATION_MARKER = "\n[Additional result details truncated.]"
}
