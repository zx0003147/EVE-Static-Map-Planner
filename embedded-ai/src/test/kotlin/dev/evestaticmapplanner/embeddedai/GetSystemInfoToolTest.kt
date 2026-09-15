package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.testing.tools.getMockExecutor
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.GetSystemInfoRequest
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.SystemInfoDto
import dev.evestaticmapplanner.control.SystemSummaryDto
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetSystemInfoToolTest {
    @Test
    fun `tool calls MapControlService with the requested ID and returns Planner data`() = runBlocking {
        val requestedIds = mutableListOf<Int>()
        val tool = GetSystemInfoTool(recordingService(requestedIds))

        val result = tool.execute(GetSystemInfoTool.Args(30_000_142))

        assertEquals(listOf(30_000_142), requestedIds)
        assertTrue(result.contains("\"systemId\":30000142"))
        assertTrue(result.contains("\"name\":\"Jita\""))
        assertTrue(result.contains("\"regionName\":\"The Forge\""))
    }

    @Test
    fun `Koog agent executes the native tool before answering`() = runBlocking {
        val requestedIds = mutableListOf<Int>()
        val tool = GetSystemInfoTool(recordingService(requestedIds))
        val question = "Tell me about system 30000142"
        val answer = "Jita is in The Forge and has 7 stargates."
        val executor = getMockExecutor {
            mockLLMToolCall(tool, GetSystemInfoTool.Args(30_000_142)) onRequestEquals question
            mockLLMAnswer(answer) onRequestContains "\"name\":\"Jita\""
        }
        try {
            val agent = createKoogAgent(tool, executor)

            assertEquals(answer, agent.run(question))
            assertEquals(listOf(30_000_142), requestedIds)
        } finally {
            executor.close()
        }
    }
}

private fun recordingService(requestedIds: MutableList<Int>): MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, arguments ->
    when (method.name) {
        "getSystemInfo" -> {
            val request = arguments?.first() as GetSystemInfoRequest
            requestedIds += request.systemId
            ControlResult.Success(request.requestId, JITA_INFO)
        }
        "toString" -> "RecordingMapControlService"
        else -> error("Unexpected MapControlService call: ${method.name}")
    }
} as MapControlService

private val JITA_INFO = SystemInfoDto(
    system = SystemSummaryDto(
        systemId = 30_000_142,
        name = "Jita",
        regionId = 10_000_002,
        constellationId = 20_000_020,
        securityStatus = 0.945913116664154,
    ),
    regionName = "The Forge",
    constellationName = "Kimotoro",
    x = -1.2906486217319209E17,
    y = 6.07553069223847E16,
    z = 1.1746922706072243E17,
    stargateCount = 7,
)
