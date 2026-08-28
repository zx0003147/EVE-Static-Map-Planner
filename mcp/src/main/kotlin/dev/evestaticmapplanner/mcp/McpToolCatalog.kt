package dev.evestaticmapplanner.mcp

import dev.evestaticmapplanner.control.transport.LocalControlClientError
import dev.evestaticmapplanner.control.transport.LocalControlClientErrorCode
import dev.evestaticmapplanner.control.transport.LocalControlClientResult
import dev.evestaticmapplanner.control.transport.LocalControlProtocol
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal data class McpToolDefinition(
    val tool: Tool,
    val invoke: suspend (JsonObject) -> LocalControlClientResult,
    val listResultKey: String? = null,
)

internal object McpToolCatalog {
    val names = listOf(
        "search_system", "get_system_info", "get_system_markers", "calculate_normal_route", "calculate_capital_route",
        "get_active_missions", "get_mission", "begin_mission", "focus_system", "show_normal_route",
        "show_capital_route", "remove_mission_route", "clear_mission_routes", "show_jump_range",
        "remove_jump_range", "clear_mission_jump_ranges", "add_mission_marker", "remove_mission_marker",
        "clear_mission_markers", "fit_mission", "clear_mission", "create_saved_marker",
    )

    fun definitions(client: McpMapClient): List<McpToolDefinition> = listOf(
        queryTool(
            "search_system",
            "Search for a solar system by name or ID. Use this to resolve a canonical systemId before calling map-changing tools.",
            schema(listOf("query"), "query" to stringProperty(64)),
            schema(listOf("systems"), "systems" to arrayProperty()),
            "systems",
        ) { arguments ->
            val input = StrictArguments(arguments, setOf("query"), setOf("query"))
            client.searchSystem(input.string("query", 64))
        },
        queryTool(
            "get_system_info",
            "Return safe static information for one canonical solar system ID. This does not change the map.",
            schema(listOf("systemId"), "systemId" to positiveIntegerProperty()),
            objectOutput("system", "regionName", "constellationName", "x", "y", "z", "stargateCount"),
        ) { arguments ->
            val input = StrictArguments(arguments, setOf("systemId"), setOf("systemId"))
            client.getSystemInfo(input.positiveInt("systemId"))
        },
        queryTool(
            "get_system_markers",
            "Query the Saved Marker and current temporary AI Mission Markers for one canonical solar system ID. " +
                "Reading the Saved Marker requires the user to enable Saved Marker access. This tool never modifies markers.",
            schema(listOf("systemId"), "systemId" to positiveIntegerProperty()),
            objectOutput("systemId", "savedMarker", "missionMarkers"),
        ) { arguments ->
            val input = StrictArguments(arguments, setOf("systemId"), setOf("systemId"))
            client.getSystemMarkers(input.positiveInt("systemId"))
        },
        queryTool(
            "calculate_normal_route",
            "Calculate a normal route with optional Ansiblex edges. This does not display the route or change the map.",
            routeInput(false, false),
            objectOutput("startSystemId", "destinationSystemId", "systemIds", "totalJumps", "stargateJumps", "ansiblexJumps"),
        ) { arguments ->
            val fields = setOf("startSystemId", "destinationSystemId", "useAnsiblex")
            val input = StrictArguments(arguments, fields, fields)
            client.calculateNormalRoute(
                input.positiveInt("startSystemId"), input.positiveInt("destinationSystemId"), input.boolean("useAnsiblex"),
            )
        },
        queryTool(
            "calculate_capital_route",
            "Calculate a capital route using an effective jump range in light-years. This does not display the route or change the map.",
            routeInput(false, true),
            objectOutput(
                "startSystemId", "destinationSystemId", "effectiveRangeLy", "systemIds", "legs", "totalJumps", "totalDistanceLy",
            ),
        ) { arguments ->
            val fields = setOf("startSystemId", "destinationSystemId", "effectiveRangeLy")
            val input = StrictArguments(arguments, fields, fields)
            client.calculateCapitalRoute(
                input.positiveInt("startSystemId"), input.positiveInt("destinationSystemId"), input.range("effectiveRangeLy"),
            )
        },
        queryTool(
            "get_active_missions",
            "List temporary AI Missions in the current AI Map Control session. This does not change the map.",
            schema(emptyList()),
            schema(listOf("missions"), "missions" to arrayProperty()),
            "missions",
        ) { arguments ->
            StrictArguments(arguments, emptySet(), emptySet())
            client.getActiveMissions()
        },
        queryTool(
            "get_mission",
            "Get one temporary AI Mission and its Mission-owned routes, jump ranges, and markers. This does not change the map.",
            missionOnlyInput(),
            objectOutput("missionId", "title", "revision", "routes", "jumpRanges", "markers", "referencedSystemIds"),
        ) { arguments ->
            val input = StrictArguments(arguments, setOf("missionId"), setOf("missionId"))
            client.getMission(input.opaqueId("missionId"))
        },
        commandTool(
            "begin_mission",
            "Begin a temporary AI Mission. The Mission disappears when AI Map Control or the app session ends.",
            schema(listOf("title"), "title" to stringProperty(120)),
            objectOutput("missionId", "title", "revision"),
            false,
        ) { arguments ->
            val input = StrictArguments(arguments, setOf("title"), setOf("title"))
            client.beginMission(input.string("title", 120))
        },
        commandTool(
            "focus_system",
            "Explicitly focus the shared map viewport on one canonical solar system ID.",
            schema(listOf("systemId"), "systemId" to positiveIntegerProperty()),
            objectOutput("systemId", "name", "regionId", "constellationId", "securityStatus"),
            false,
        ) { arguments ->
            val input = StrictArguments(arguments, setOf("systemId"), setOf("systemId"))
            client.focusSystem(input.positiveInt("systemId"))
        },
        commandTool(
            "show_normal_route",
            "Calculate and display a Mission-owned normal route. Requires an active temporary Mission.",
            routeInput(true, false),
            objectOutput("missionId", "routeId", "type", "route"),
            false,
        ) { arguments ->
            val fields = setOf("missionId", "startSystemId", "destinationSystemId", "useAnsiblex")
            val input = StrictArguments(arguments, fields, fields)
            client.showNormalRoute(
                input.opaqueId("missionId"), input.positiveInt("startSystemId"),
                input.positiveInt("destinationSystemId"), input.boolean("useAnsiblex"),
            )
        },
        commandTool(
            "show_capital_route",
            "Calculate and display a Mission-owned capital route. Requires an active temporary Mission.",
            routeInput(true, true),
            objectOutput("missionId", "routeId", "type", "route"),
            false,
        ) { arguments ->
            val fields = setOf("missionId", "startSystemId", "destinationSystemId", "effectiveRangeLy")
            val input = StrictArguments(arguments, fields, fields)
            client.showCapitalRoute(
                input.opaqueId("missionId"), input.positiveInt("startSystemId"),
                input.positiveInt("destinationSystemId"), input.range("effectiveRangeLy"),
            )
        },
        commandTool(
            "remove_mission_route",
            "Remove one opaque route ID owned by the specified temporary Mission. User routes are never affected.",
            schema(listOf("missionId", "routeId"), "missionId" to opaqueIdProperty(), "routeId" to opaqueIdProperty()),
            objectOutput("missionId"),
        ) { arguments ->
            val fields = setOf("missionId", "routeId")
            val input = StrictArguments(arguments, fields, fields)
            client.removeMissionRoute(input.opaqueId("missionId"), input.opaqueId("routeId"))
        },
        missionCommand("clear_mission_routes", "Clear only routes owned by the specified temporary Mission. User routes are never affected.") {
            client.clearMissionRoutes(it)
        },
        commandTool(
            "show_jump_range",
            "Display a Mission-owned jump-range overlay using an effective range in light-years.",
            schema(
                listOf("missionId", "originSystemId", "effectiveRangeLy"),
                "missionId" to opaqueIdProperty(), "originSystemId" to positiveIntegerProperty(),
                "effectiveRangeLy" to rangeProperty(), "label" to stringProperty(120),
            ),
            objectOutput("missionId", "overlayId", "originSystemId", "effectiveRangeLy", "reachableSystemCount"),
            false,
        ) { arguments ->
            val allowed = setOf("missionId", "originSystemId", "effectiveRangeLy", "label")
            val required = setOf("missionId", "originSystemId", "effectiveRangeLy")
            val input = StrictArguments(arguments, allowed, required)
            client.showJumpRange(
                input.opaqueId("missionId"), input.positiveInt("originSystemId"), input.range("effectiveRangeLy"),
                input.optionalString("label", 120),
            )
        },
        commandTool(
            "remove_jump_range",
            "Remove one opaque jump-range overlay ID owned by the specified temporary Mission.",
            schema(listOf("missionId", "overlayId"), "missionId" to opaqueIdProperty(), "overlayId" to opaqueIdProperty()),
            objectOutput("missionId"),
        ) { arguments ->
            val fields = setOf("missionId", "overlayId")
            val input = StrictArguments(arguments, fields, fields)
            client.removeJumpRange(input.opaqueId("missionId"), input.opaqueId("overlayId"))
        },
        missionCommand(
            "clear_mission_jump_ranges",
            "Clear only jump-range overlays owned by the specified temporary Mission. User overlays are never affected.",
        ) { client.clearMissionJumpRanges(it) },
        commandTool(
            "add_mission_marker",
            "Add a temporary Mission-only marker to a canonical solar system ID.",
            schema(
                listOf("missionId", "systemId", "role"),
                "missionId" to opaqueIdProperty(), "systemId" to positiveIntegerProperty(),
                "role" to enumProperty(MARKER_ROLES), "label" to stringProperty(120),
                "notes" to stringProperty(1024), "colorOverride" to enumProperty(MARKER_COLORS),
            ),
            objectOutput("missionId", "markerId", "systemId", "role"),
            false,
        ) { arguments ->
            val input = StrictArguments(
                arguments,
                setOf("missionId", "systemId", "role", "label", "notes", "colorOverride"),
                setOf("missionId", "systemId", "role"),
            )
            client.addMissionMarker(
                input.opaqueId("missionId"), input.positiveInt("systemId"), input.enum("role", MARKER_ROLES),
                input.optionalString("label", 120), input.optionalString("notes", 1024),
                input.optionalEnum("colorOverride", MARKER_COLORS),
            )
        },
        commandTool(
            "remove_mission_marker",
            "Remove one opaque marker ID owned by the specified temporary Mission. Saved and user markers are never affected.",
            schema(listOf("missionId", "markerId"), "missionId" to opaqueIdProperty(), "markerId" to opaqueIdProperty()),
            objectOutput("missionId"),
        ) { arguments ->
            val fields = setOf("missionId", "markerId")
            val input = StrictArguments(arguments, fields, fields)
            client.removeMissionMarker(input.opaqueId("missionId"), input.opaqueId("markerId"))
        },
        missionCommand(
            "clear_mission_markers",
            "Clear only markers owned by the specified temporary Mission. Saved and user markers are never affected.",
        ) { client.clearMissionMarkers(it) },
        commandTool(
            "fit_mission",
            "Fit the shared map viewport to the visual bounds of the specified temporary Mission.",
            missionOnlyInput(),
            objectOutput("missionId"),
            false,
        ) { arguments ->
            val input = StrictArguments(arguments, setOf("missionId"), setOf("missionId"))
            client.fitMission(input.opaqueId("missionId"))
        },
        missionCommand(
            "clear_mission",
            "Clear only the specified temporary Mission. This does not clear user routes, user jump ranges, temporary or saved user markers, preferences, or Ansiblex data.",
        ) { client.clearMission(it) },
        commandTool(
            "create_saved_marker",
            "Create one persistent Saved Marker after the user has explicitly requested permanent storage. " +
                "Optional tags set supported initial tags in the same atomic create. Requires Saved Marker access in " +
                "Preferences. This create-only tool never overwrites, updates, deletes, or clears an existing marker, " +
                "and cannot add or remove tags on an existing marker.",
            schema(
                listOf("systemId", "color"),
                "systemId" to positiveIntegerProperty(),
                "color" to enumProperty(MARKER_COLORS),
                "name" to stringProperty(120),
                "notes" to stringProperty(1024),
                "tags" to enumArrayProperty(SAVED_MARKER_TAGS),
            ),
            objectOutput("marker"),
            false,
        ) { arguments ->
            val input = StrictArguments(
                arguments,
                setOf("systemId", "color", "name", "notes", "tags"),
                setOf("systemId", "color"),
            )
            client.createSavedMarker(
                systemId = input.positiveInt("systemId"),
                color = input.enum("color", MARKER_COLORS),
                name = input.optionalString("name", 120),
                notes = input.optionalString("notes", 1024),
                tags = input.optionalEnumArray("tags", SAVED_MARKER_TAGS),
            )
        },
    ).also { check(it.map { definition -> definition.tool.name } == names) }

    fun register(server: Server, client: McpMapClient) {
        definitions(client).forEach { definition ->
            server.addTool(definition.tool) { request ->
                val result = try {
                    definition.invoke(request.arguments ?: JsonObject(emptyMap()))
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw failure
                } catch (_: IllegalArgumentException) {
                    invalidArgumentsFailure()
                } catch (_: Exception) {
                    internalToolFailure()
                }
                result.toMcpResult(definition.tool.name, definition.listResultKey)
            }
        }
    }

    private fun missionCommand(
        name: String,
        description: String,
        call: suspend (String) -> LocalControlClientResult,
    ) = commandTool(name, description, missionOnlyInput(), objectOutput("missionId")) { arguments ->
        val input = StrictArguments(arguments, setOf("missionId"), setOf("missionId"))
        call(input.opaqueId("missionId"))
    }

    private fun queryTool(
        name: String,
        description: String,
        input: ToolSchema,
        output: ToolSchema,
        listResultKey: String? = null,
        invoke: suspend (JsonObject) -> LocalControlClientResult,
    ) = McpToolDefinition(
        Tool(
            name = name, inputSchema = input, description = description, outputSchema = output,
            annotations = ToolAnnotations(
                readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false,
            ),
        ),
        invoke,
        listResultKey,
    )

    private fun commandTool(
        name: String,
        description: String,
        input: ToolSchema,
        output: ToolSchema,
        destructive: Boolean = true,
        invoke: suspend (JsonObject) -> LocalControlClientResult,
    ) = McpToolDefinition(
        Tool(
            name = name, inputSchema = input, description = description, outputSchema = output,
            annotations = ToolAnnotations(
                readOnlyHint = false, destructiveHint = destructive, idempotentHint = false, openWorldHint = false,
            ),
        ),
        invoke,
    )
}

private class StrictArguments(
    private val values: JsonObject,
    allowed: Set<String>,
    required: Set<String>,
) {
    init {
        if (values.keys.any { it !in allowed } || required.any { it !in values }) invalid()
    }

    fun string(name: String, maxLength: Int): String = primitiveString(name)
        .takeIf { it.codePointCount(0, it.length) <= maxLength && it.isNotBlank() } ?: invalid()
    fun optionalString(name: String, maxLength: Int): String? = if (name in values) string(name, maxLength) else null
    fun opaqueId(name: String): String = primitiveString(name).takeIf { OPAQUE_ID.matches(it) } ?: invalid()
    fun positiveInt(name: String): Int = (values[name] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)?.intOrNull?.takeIf { it > 0 } ?: invalid()
    fun range(name: String): Double = (values[name] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)?.doubleOrNull?.takeIf { it.isFinite() && it > 0.0 && it <= 20.0 } ?: invalid()
    fun boolean(name: String): Boolean = (values[name] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)?.booleanOrNull ?: invalid()
    fun enum(name: String, allowed: Set<String>): String = primitiveString(name).takeIf { it in allowed } ?: invalid()
    fun optionalEnum(name: String, allowed: Set<String>): String? = if (name in values) enum(name, allowed) else null
    fun optionalEnumArray(name: String, allowed: Set<String>): List<String> {
        if (name !in values) return emptyList()
        return (values[name] as? JsonArray)?.map { item ->
            (item as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.takeIf { it in allowed } ?: invalid()
        } ?: invalid()
    }

    private fun primitiveString(name: String): String = (values[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)?.content ?: invalid()

    private companion object {
        val OPAQUE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$")
    }
}

private fun LocalControlClientResult.toMcpResult(toolName: String, listResultKey: String?): CallToolResult = when (this) {
    is LocalControlClientResult.Success -> {
        val resultValue = value.toPublicToolValue(toolName)
        val structured = when {
            listResultKey != null && resultValue is JsonArray -> buildJsonObject { put(listResultKey, resultValue) }
            resultValue is JsonObject -> JsonObject(buildMap {
                putAll(resultValue)
                if (missionRevision != null && "revision" !in resultValue) put("revision", JsonPrimitive(missionRevision))
            })
            else -> buildJsonObject { put("result", resultValue) }
        }
        CallToolResult(
            content = listOf(TextContent(McpTextFallbackFormatter.format(toolName, structured))),
            isError = false,
            structuredContent = structured,
        )
    }
    is LocalControlClientResult.Failure -> {
        val structured = buildJsonObject {
            put("error", buildJsonObject {
                put("code", error.code.name)
                put("message", error.message)
            })
        }
        CallToolResult(
            content = listOf(TextContent(McpTextFallbackFormatter.formatError(toolName, error))),
            isError = true,
            structuredContent = structured,
        )
    }
}

private fun JsonElement.toPublicToolValue(toolName: String): JsonElement =
    when {
        toolName == "search_system" && this is JsonArray -> JsonArray(map { item ->
            if (item is JsonObject) item.renameField("name", "canonicalName") else item
        })
        toolName == "show_jump_range" && this is JsonObject -> renameField("jumpRangeId", "overlayId")
        else -> this
    }

private fun JsonObject.renameField(source: String, destination: String): JsonObject = JsonObject(buildMap {
    for ((name, value) in this@renameField) put(if (name == source) destination else name, value)
})

private fun invalidArgumentsFailure() = LocalControlClientResult.Failure(
    LocalControlClientError(LocalControlClientErrorCode.INVALID_ARGUMENT, "The request is invalid."),
)

private fun internalToolFailure() = LocalControlClientResult.Failure(
    LocalControlClientError(LocalControlClientErrorCode.INTERNAL_ERROR, "The AI Map Control operation failed."),
)

private fun schema(required: List<String>, vararg properties: Pair<String, JsonObject>) = ToolSchema(
    properties = buildJsonObject { properties.forEach { (name, value) -> put(name, value) } },
    required = required,
)

private fun objectOutput(vararg required: String) = schema(
    required.toList(),
    *required.map { it to JsonObject(emptyMap()) }.toTypedArray(),
)

private fun routeInput(includeMission: Boolean, capital: Boolean): ToolSchema {
    val properties = mutableListOf<Pair<String, JsonObject>>()
    val required = mutableListOf<String>()
    if (includeMission) {
        properties += "missionId" to opaqueIdProperty()
        required += "missionId"
    }
    properties += "startSystemId" to positiveIntegerProperty()
    properties += "destinationSystemId" to positiveIntegerProperty()
    required += listOf("startSystemId", "destinationSystemId")
    if (capital) {
        properties += "effectiveRangeLy" to rangeProperty()
        required += "effectiveRangeLy"
    } else {
        properties += "useAnsiblex" to booleanProperty()
        required += "useAnsiblex"
    }
    return schema(required, *properties.toTypedArray())
}

private fun missionOnlyInput() = schema(listOf("missionId"), "missionId" to opaqueIdProperty())
private fun stringProperty(maxLength: Int) = buildJsonObject { put("type", "string"); put("maxLength", maxLength) }
private fun opaqueIdProperty() = buildJsonObject {
    put("type", "string"); put("pattern", "^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$")
}
private fun positiveIntegerProperty() = buildJsonObject { put("type", "integer"); put("minimum", 1) }
private fun rangeProperty() = buildJsonObject { put("type", "number"); put("exclusiveMinimum", 0); put("maximum", 20) }
private fun booleanProperty() = buildJsonObject { put("type", "boolean") }
private fun arrayProperty() = buildJsonObject { put("type", "array") }
private fun enumArrayProperty(values: Set<String>) = buildJsonObject {
    put("type", "array")
    put("items", enumProperty(values))
}
private fun enumProperty(values: Set<String>) = buildJsonObject {
    put("type", "string")
    put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}
private fun invalid(): Nothing = throw IllegalArgumentException("Invalid MCP tool arguments")

private val MARKER_ROLES = linkedSetOf("RALLY", "DESTINATION", "DANGER", "BACKUP", "WAYPOINT", "INFO")
private val MARKER_COLORS = linkedSetOf("RED", "ORANGE", "YELLOW", "GREEN", "BLUE", "PURPLE", "WHITE")
private val SAVED_MARKER_TAGS = LocalControlProtocol.SAVED_MARKER_TAGS.toCollection(linkedSetOf())
