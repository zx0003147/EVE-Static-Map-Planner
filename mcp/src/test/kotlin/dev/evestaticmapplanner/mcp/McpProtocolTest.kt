package dev.evestaticmapplanner.mcp

import dev.evestaticmapplanner.control.transport.LocalControlClientResult
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalMcpApi::class)
class McpProtocolTest {
    @Test
    fun `official client initializes lists and calls fixed tools`() = runBlocking {
        val mapClient = ProtocolMapClient()
        val server = createMcpServer(mapClient)
        val transports = ChannelTransport.createLinkedPair()
        server.createSession(transports.serverTransport)
        val client = Client(Implementation("step-3a-test", "1.0"))
        try {
            client.connect(transports.clientTransport)
            val capabilities = assertNotNull(client.serverCapabilities)
            assertTrue(capabilities.tools != null)
            assertTrue(capabilities.resources == null)
            assertTrue(capabilities.prompts == null)
            assertTrue(capabilities.logging == null)
            assertTrue(capabilities.completions == null)
            assertTrue(capabilities.tasks == null)
            assertTrue(capabilities.experimental == null)
            assertTrue(capabilities.extensions.isNullOrEmpty())
            assertEquals(20, client.listTools().tools.size)

            val search = client.callTool("search_system", mapOf("query" to "Jita"))
            assertFalse(search.isError == true)
            assertEquals("Jita", search.structuredContent?.get("systems")
                ?.let { it as JsonArray }?.single()?.jsonObject?.get("canonicalName")?.jsonPrimitive?.content)

            val begin = client.callTool("begin_mission", mapOf("title" to "Protocol Mission"))
            assertFalse(begin.isError == true)
            assertEquals("mission-1", begin.structuredContent?.get("missionId")?.jsonPrimitive?.content)

            val mission = client.callTool("get_mission", mapOf("missionId" to "mission-1"))
            assertFalse(mission.isError == true)
            assertEquals("mission-1", mission.structuredContent?.get("missionId")?.jsonPrimitive?.content)

            val range = client.callTool(
                "show_jump_range",
                mapOf("missionId" to "mission-1", "originSystemId" to 30000142, "effectiveRangeLy" to 5.0),
            )
            assertFalse(range.isError == true)
            assertEquals("range-1", range.structuredContent?.get("overlayId")?.jsonPrimitive?.content)
            assertFalse("jumpRangeId" in range.structuredContent.orEmpty())

            val invalid = client.callTool("search_system", mapOf("query" to "Jita", "requestId" to "forbidden"))
            assertTrue(invalid.isError == true)
            assertEquals("INVALID_ARGUMENT", invalid.structuredContent?.get("error")?.jsonObject
                ?.get("code")?.jsonPrimitive?.content)

            val unknown = client.callTool("unknown_tool", emptyMap())
            assertTrue(unknown.isError == true)
            assertEquals(listOf("search_system", "begin_mission", "get_mission", "show_jump_range"), mapClient.calls)
        } finally {
            runCatching { client.close() }
            runCatching { server.close() }
        }
    }
}

private class ProtocolMapClient : RecordingProtocolClient() {
    val calls = mutableListOf<String>()

    override suspend fun searchSystem(query: String): LocalControlClientResult {
        calls += "search_system"
        return LocalControlClientResult.Success(
            JsonArray(listOf(buildJsonObject {
                put("systemId", 30000142)
                put("name", "Jita")
                put("regionId", 10000002)
                put("constellationId", 20000020)
                put("securityStatus", 0.9)
            })),
            null,
        )
    }

    override suspend fun beginMission(title: String): LocalControlClientResult {
        calls += "begin_mission"
        return LocalControlClientResult.Success(buildJsonObject {
            put("missionId", "mission-1")
            put("title", title)
            put("revision", 1)
        }, 1)
    }

    override suspend fun getMission(missionId: String): LocalControlClientResult {
        calls += "get_mission"
        return LocalControlClientResult.Success(buildJsonObject {
            put("missionId", missionId)
            put("title", "Protocol Mission")
            put("revision", 1)
            put("routes", JsonArray(emptyList()))
            put("jumpRanges", JsonArray(emptyList()))
            put("markers", JsonArray(emptyList()))
            put("referencedSystemIds", JsonArray(emptyList()))
        }, null)
    }

    override suspend fun showJumpRange(
        missionId: String,
        originSystemId: Int,
        effectiveRangeLy: Double,
        label: String?,
    ): LocalControlClientResult {
        calls += "show_jump_range"
        return LocalControlClientResult.Success(buildJsonObject {
            put("missionId", missionId)
            put("jumpRangeId", "range-1")
            put("originSystemId", originSystemId)
            put("effectiveRangeLy", effectiveRangeLy)
            put("reachableSystemCount", 42)
        }, 2)
    }
}

private open class RecordingProtocolClient : McpMapClient {
    private fun unused(): LocalControlClientResult =
        LocalControlClientResult.Success(JsonObject(mapOf("unused" to JsonPrimitive(true))), null)

    override suspend fun searchSystem(query: String) = unused()
    override suspend fun getSystemInfo(systemId: Int) = unused()
    override suspend fun calculateNormalRoute(startSystemId: Int, destinationSystemId: Int, useAnsiblex: Boolean) = unused()
    override suspend fun calculateCapitalRoute(startSystemId: Int, destinationSystemId: Int, effectiveRangeLy: Double) = unused()
    override suspend fun getActiveMissions() = unused()
    override suspend fun getMission(missionId: String) = unused()
    override suspend fun beginMission(title: String) = unused()
    override suspend fun focusSystem(systemId: Int) = unused()
    override suspend fun showNormalRoute(missionId: String, startSystemId: Int, destinationSystemId: Int, useAnsiblex: Boolean) = unused()
    override suspend fun showCapitalRoute(
        missionId: String,
        startSystemId: Int,
        destinationSystemId: Int,
        effectiveRangeLy: Double,
    ) = unused()
    override suspend fun removeMissionRoute(missionId: String, routeId: String) = unused()
    override suspend fun clearMissionRoutes(missionId: String) = unused()
    override suspend fun showJumpRange(missionId: String, originSystemId: Int, effectiveRangeLy: Double, label: String?) = unused()
    override suspend fun removeJumpRange(missionId: String, jumpRangeId: String) = unused()
    override suspend fun clearMissionJumpRanges(missionId: String) = unused()
    override suspend fun addMissionMarker(
        missionId: String,
        systemId: Int,
        role: String,
        label: String?,
        notes: String?,
        colorOverride: String?,
    ) = unused()
    override suspend fun removeMissionMarker(missionId: String, markerId: String) = unused()
    override suspend fun clearMissionMarkers(missionId: String) = unused()
    override suspend fun fitMission(missionId: String) = unused()
    override suspend fun clearMission(missionId: String) = unused()
    override fun close() = Unit
}
