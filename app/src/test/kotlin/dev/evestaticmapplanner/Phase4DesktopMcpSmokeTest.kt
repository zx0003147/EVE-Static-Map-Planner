package dev.evestaticmapplanner

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Opt-in QA driver for inspecting the real packaged Desktop UI. MCP is used only to drive the already-running
 * application during visual acceptance; the embedded Koog implementation never depends on this path.
 */
@OptIn(ExperimentalMcpApi::class)
class Phase4DesktopMcpSmokeTest {
    @Test
    fun `real Desktop renders focus route range marker and fit`() = runBlocking {
        assumeTrue(System.getenv(ENABLED) == "true", "Set $ENABLED=true for the interactive Desktop QA driver")
        val http = HttpClient(CIO) { install(SSE) }
        val client = Client(Implementation("phase4-desktop-qa", "1.0"))
        val calls = mutableListOf<String>()
        val timingsMillis = mutableListOf<Pair<String, Long>>()
        try {
            client.connect(StreamableHttpClientTransport(http, ENDPOINT))

            suspend fun call(name: String, arguments: Map<String, Any>): CallToolResult {
                calls += name
                val startedAt = System.nanoTime()
                return try {
                    requireNotNull(client.callTool(name, arguments)).also { assertFalse(it.isError == true, name) }
                } finally {
                    timingsMillis += name to (System.nanoTime() - startedAt) / 1_000_000
                }
            }

            suspend fun resolveSystem(name: String): Int {
                val result = call("search_system", mapOf("query" to name))
                val systems = result.structuredContent?.get("systems") as JsonArray
                val match = systems
                    .map { it.jsonObject }
                    .single { it.getValue("canonicalName").jsonPrimitive.content == name }
                return match.getValue("systemId").jsonPrimitive.content.toInt()
            }

            val jita = resolveSystem("Jita")
            val amarr = resolveSystem("Amarr")
            val oneDq = resolveSystem("1DQ1-A")
            assertEquals(30_000_142, jita)

            call("focus_system", mapOf("systemId" to jita))
            val mission = call("begin_mission", mapOf("title" to "Phase 4 Desktop UI Smoke"))
            val missionId = mission.structuredContent?.get("missionId")?.jsonPrimitive?.content
                ?: error("begin_mission did not return missionId")
            val normalRoute = call(
                "show_normal_route",
                mapOf(
                    "missionId" to missionId,
                    "startSystemId" to jita,
                    "destinationSystemId" to amarr,
                    "useAnsiblex" to false,
                    "useWormholes" to false,
                ),
            )
            val jumpRange = call(
                "show_jump_range",
                mapOf(
                    "missionId" to missionId,
                    "originSystemId" to oneDq,
                    "effectiveRangeLy" to 6.0,
                    "label" to "1DQ1-A 6 LY",
                ),
            )
            val marker = call(
                "add_mission_marker",
                mapOf(
                    "missionId" to missionId,
                    "systemId" to jita,
                    "role" to "RALLY",
                    "label" to "Jita rally",
                ),
            )
            call("fit_mission", mapOf("missionId" to missionId))
            val snapshot = call("get_mission", mapOf("missionId" to missionId))

            val report = System.getenv(REPORT)?.let(Path::of)
            if (report != null) {
                report.parent?.let(Files::createDirectories)
                Files.writeString(
                    report,
                    buildJsonObject {
                        put("driver", "MCP QA only; embedded AI remains Native Kotlin")
                        put("missionId", missionId)
                        put("calls", buildJsonArray { calls.forEach { add(JsonPrimitive(it)) } })
                        put(
                            "timingsMillis",
                            buildJsonArray {
                                timingsMillis.forEach { (name, elapsedMillis) ->
                                    add(buildJsonObject {
                                        put("tool", name)
                                        put("elapsed", elapsedMillis)
                                    })
                                }
                            },
                        )
                        put("normalRoute", normalRoute.structuredContent ?: JsonObject(emptyMap()))
                        put("jumpRange", jumpRange.structuredContent ?: JsonObject(emptyMap()))
                        put("marker", marker.structuredContent ?: JsonObject(emptyMap()))
                        put("mission", snapshot.structuredContent ?: JsonObject(emptyMap()))
                    }.toString(),
                )
            }
        } finally {
            runCatching { client.close() }
            http.close()
        }
    }

    private companion object {
        const val ENABLED = "PHASE4_DESKTOP_MCP_SMOKE_TEST"
        const val REPORT = "PHASE4_DESKTOP_MCP_SMOKE_REPORT"
        const val ENDPOINT = "http://127.0.0.1:27892/mcp"
    }
}
