package dev.evestaticmapplanner.mcp

import dev.evestaticmapplanner.control.transport.LocalControlClientResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpToolCatalogTest {
    @Test
    fun `capability surface is exactly the fixed twenty two tools`() {
        val client = RecordingClient()
        val definitions = McpToolCatalog.definitions(client)
        val server = createMcpServer(client)

        assertEquals(22, definitions.size)
        assertEquals(McpToolCatalog.names, definitions.map { it.tool.name })
        assertEquals(McpToolCatalog.names.toSet(), server.tools.keys)
        assertTrue(server.resources.isEmpty())
        assertTrue(server.prompts.isEmpty())
        assertTrue(server.resourceTemplates.isEmpty())

        val names = definitions.joinToString(" ") { it.tool.name }.lowercase()
        listOf("shell", "file", "sql", "db", "process", "invoke", "execute", "http", "url", "ansiblex_add", "preference")
            .forEach { assertFalse(names.contains(it), it) }
        assertEquals(
            setOf("get_system_markers", "create_saved_marker"),
            definitions.map { it.tool.name }.filter {
                it == "get_system_markers" || "saved_marker" in it
            }.toSet(),
        )
        listOf("update_saved_marker", "delete_saved_marker", "clear_saved_markers", "replace_saved_marker")
            .forEach { assertFalse(it in McpToolCatalog.names, it) }
        runBlocking { server.close() }
    }

    @Test
    fun `schemas never expose transport metadata and strict handlers reject additional fields`() = runBlocking {
        val client = RecordingClient()
        McpToolCatalog.definitions(client).forEach { definition ->
            assertFalse("requestId" in definition.tool.inputSchema.properties.orEmpty(), definition.tool.name)
            assertFalse("idempotencyKey" in definition.tool.inputSchema.properties.orEmpty(), definition.tool.name)
            assertTrue(definition.tool.outputSchema != null, definition.tool.name)

            val valid = validArguments.getValue(definition.tool.name)
            assertFails(definition.tool.name) {
                definition.invoke(JsonObject(valid + ("requestId" to JsonPrimitive("not-allowed"))))
            }
        }
    }

    @Test
    fun `each tool has one explicit client operation mapping`() = runBlocking {
        val client = RecordingClient()
        val definitions = McpToolCatalog.definitions(client)
        definitions.forEach { definition ->
            client.called = null
            definition.invoke(JsonObject(validArguments.getValue(definition.tool.name)))
            assertEquals(definition.tool.name, client.called, definition.tool.name)
        }
    }

    @Test
    fun `marker enums and numeric ranges fail closed`() = runBlocking {
        val definitions = McpToolCatalog.definitions(RecordingClient()).associateBy { it.tool.name }
        assertFails {
            definitions.getValue("add_mission_marker").invoke(
                buildJsonObject {
                    put("missionId", "m1")
                    put("systemId", 1)
                    put("role", "ARBITRARY")
                },
            )
        }
        assertFails {
            definitions.getValue("show_jump_range").invoke(
                buildJsonObject {
                    put("missionId", "m1")
                    put("originSystemId", 1)
                    put("effectiveRangeLy", 20.1)
                },
            )
        }
        Unit
    }

    @Test
    fun `Saved Marker schemas descriptions and null handling are strict`() = runBlocking {
        val definitions = McpToolCatalog.definitions(RecordingClient()).associateBy { it.tool.name }
        val query = definitions.getValue("get_system_markers").tool
        assertEquals(setOf("systemId"), query.inputSchema.properties.orEmpty().keys)
        assertEquals(listOf("systemId"), query.inputSchema.required)
        assertTrue(query.description.orEmpty().contains("never modifies markers"))
        assertTrue(query.description.orEmpty().contains("requires the user to enable"))

        val create = definitions.getValue("create_saved_marker").tool
        assertEquals(setOf("systemId", "color", "name", "notes", "tags"), create.inputSchema.properties.orEmpty().keys)
        assertEquals(listOf("systemId", "color"), create.inputSchema.required)
        assertTrue(create.description.orEmpty().contains("create-only"))
        listOf("overwrites", "updates", "deletes", "initial tags", "cannot add or remove tags").forEach {
            assertTrue(create.description.orEmpty().contains(it), it)
        }
        val tags = assertIs<JsonObject>(create.inputSchema.properties.orEmpty().getValue("tags"))
        assertEquals("array", assertIs<JsonPrimitive>(tags.getValue("type")).content)
        val items = assertIs<JsonObject>(tags.getValue("items"))
        assertEquals(
            listOf("STAGING", "RALLY", "DANGER", "LOGISTICS", "HOME", "BACKUP", "INDUSTRIAL", "STRATEGIC", "KEEPSTAR"),
            assertIs<JsonArray>(items.getValue("enum")).map { assertIs<JsonPrimitive>(it).content },
        )

        val invoke = definitions.getValue("create_saved_marker").invoke
        listOf(
            buildJsonObject { put("color", "RED") },
            buildJsonObject { put("systemId", "30000142"); put("color", "RED") },
            buildJsonObject { put("systemId", 30000142) },
            buildJsonObject { put("systemId", 30000142); put("color", "BLACK") },
            buildJsonObject { put("systemId", 30000142); put("color", "RED"); put("name", JsonNull) },
            buildJsonObject { put("systemId", 30000142); put("color", "RED"); put("notes", "x".repeat(1025)) },
            buildJsonObject { put("systemId", 30000142); put("color", "RED"); put("children", JsonNull) },
            buildJsonObject { put("systemId", 30000142); put("color", "RED"); put("tags", JsonNull) },
            buildJsonObject { put("systemId", 30000142); put("color", "RED"); put("tags", "DANGER") },
            buildJsonObject { put("systemId", 30000142); put("color", "RED"); put("tags", JsonArray(listOf(JsonPrimitive("UNKNOWN")))) },
        ).forEach { arguments -> assertFails { invoke(arguments) } }

        val client = RecordingClient()
        McpToolCatalog.definitions(client).associateBy { it.tool.name }.getValue("create_saved_marker").invoke(
            buildJsonObject {
                put("systemId", 30000142)
                put("color", "BLUE")
                put("tags", JsonArray(listOf(JsonPrimitive("STAGING"), JsonPrimitive("STRATEGIC"))))
            },
        )
        assertEquals(listOf("STAGING", "STRATEGIC"), client.createdTags)
    }
}

private val validArguments: Map<String, Map<String, kotlinx.serialization.json.JsonElement>> = mapOf(
    "search_system" to mapOf("query" to JsonPrimitive("Jita")),
    "get_system_info" to mapOf("systemId" to JsonPrimitive(30000142)),
    "get_system_markers" to mapOf("systemId" to JsonPrimitive(30000142)),
    "calculate_normal_route" to mapOf(
        "startSystemId" to JsonPrimitive(1), "destinationSystemId" to JsonPrimitive(2), "useAnsiblex" to JsonPrimitive(false),
    ),
    "calculate_capital_route" to mapOf(
        "startSystemId" to JsonPrimitive(1), "destinationSystemId" to JsonPrimitive(2), "effectiveRangeLy" to JsonPrimitive(5.0),
    ),
    "get_active_missions" to emptyMap(),
    "get_mission" to mapOf("missionId" to JsonPrimitive("m1")),
    "begin_mission" to mapOf("title" to JsonPrimitive("Operation")),
    "focus_system" to mapOf("systemId" to JsonPrimitive(1)),
    "show_normal_route" to mapOf(
        "missionId" to JsonPrimitive("m1"), "startSystemId" to JsonPrimitive(1),
        "destinationSystemId" to JsonPrimitive(2), "useAnsiblex" to JsonPrimitive(false),
    ),
    "show_capital_route" to mapOf(
        "missionId" to JsonPrimitive("m1"), "startSystemId" to JsonPrimitive(1),
        "destinationSystemId" to JsonPrimitive(2), "effectiveRangeLy" to JsonPrimitive(5.0),
    ),
    "remove_mission_route" to mapOf("missionId" to JsonPrimitive("m1"), "routeId" to JsonPrimitive("r1")),
    "clear_mission_routes" to mapOf("missionId" to JsonPrimitive("m1")),
    "show_jump_range" to mapOf(
        "missionId" to JsonPrimitive("m1"), "originSystemId" to JsonPrimitive(1), "effectiveRangeLy" to JsonPrimitive(5.0),
    ),
    "remove_jump_range" to mapOf("missionId" to JsonPrimitive("m1"), "overlayId" to JsonPrimitive("j1")),
    "clear_mission_jump_ranges" to mapOf("missionId" to JsonPrimitive("m1")),
    "add_mission_marker" to mapOf(
        "missionId" to JsonPrimitive("m1"), "systemId" to JsonPrimitive(1), "role" to JsonPrimitive("RALLY"),
    ),
    "remove_mission_marker" to mapOf("missionId" to JsonPrimitive("m1"), "markerId" to JsonPrimitive("k1")),
    "clear_mission_markers" to mapOf("missionId" to JsonPrimitive("m1")),
    "fit_mission" to mapOf("missionId" to JsonPrimitive("m1")),
    "clear_mission" to mapOf("missionId" to JsonPrimitive("m1")),
    "create_saved_marker" to mapOf(
        "systemId" to JsonPrimitive(30000142), "color" to JsonPrimitive("GREEN"),
        "name" to JsonPrimitive("Staging"), "notes" to JsonPrimitive("Persistent"),
    ),
)

private class RecordingClient : McpMapClient {
    var called: String? = null
    var createdTags: List<String>? = null

    private fun result(name: String): LocalControlClientResult {
        called = name
        return LocalControlClientResult.Success(buildJsonObject { put("operation", name) }, null)
    }

    override suspend fun searchSystem(query: String) = result("search_system")
    override suspend fun getSystemInfo(systemId: Int) = result("get_system_info")
    override suspend fun getSystemMarkers(systemId: Int) = result("get_system_markers")
    override suspend fun calculateNormalRoute(startSystemId: Int, destinationSystemId: Int, useAnsiblex: Boolean) =
        result("calculate_normal_route")
    override suspend fun calculateCapitalRoute(startSystemId: Int, destinationSystemId: Int, effectiveRangeLy: Double) =
        result("calculate_capital_route")
    override suspend fun getActiveMissions() = result("get_active_missions")
    override suspend fun getMission(missionId: String) = result("get_mission")
    override suspend fun beginMission(title: String) = result("begin_mission")
    override suspend fun createSavedMarker(
        systemId: Int,
        color: String,
        name: String?,
        notes: String?,
        tags: List<String>,
    ): LocalControlClientResult {
        createdTags = tags
        return result("create_saved_marker")
    }
    override suspend fun focusSystem(systemId: Int) = result("focus_system")
    override suspend fun showNormalRoute(missionId: String, startSystemId: Int, destinationSystemId: Int, useAnsiblex: Boolean) =
        result("show_normal_route")
    override suspend fun showCapitalRoute(
        missionId: String,
        startSystemId: Int,
        destinationSystemId: Int,
        effectiveRangeLy: Double,
    ) = result("show_capital_route")
    override suspend fun removeMissionRoute(missionId: String, routeId: String) = result("remove_mission_route")
    override suspend fun clearMissionRoutes(missionId: String) = result("clear_mission_routes")
    override suspend fun showJumpRange(missionId: String, originSystemId: Int, effectiveRangeLy: Double, label: String?) =
        result("show_jump_range")
    override suspend fun removeJumpRange(missionId: String, jumpRangeId: String) = result("remove_jump_range")
    override suspend fun clearMissionJumpRanges(missionId: String) = result("clear_mission_jump_ranges")
    override suspend fun addMissionMarker(
        missionId: String,
        systemId: Int,
        role: String,
        label: String?,
        notes: String?,
        colorOverride: String?,
    ) = result("add_mission_marker")
    override suspend fun removeMissionMarker(missionId: String, markerId: String) = result("remove_mission_marker")
    override suspend fun clearMissionMarkers(missionId: String) = result("clear_mission_markers")
    override suspend fun fitMission(missionId: String) = result("fit_mission")
    override suspend fun clearMission(missionId: String) = result("clear_mission")
    override fun close() = Unit
}
