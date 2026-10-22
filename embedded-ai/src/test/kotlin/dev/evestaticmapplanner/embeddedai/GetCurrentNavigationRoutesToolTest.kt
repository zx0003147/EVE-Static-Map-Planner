package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.testing.tools.getMockExecutor
import dev.evestaticmapplanner.control.ControlError
import dev.evestaticmapplanner.control.ControlErrorCode
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.CurrentNavigationConnectionDto
import dev.evestaticmapplanner.control.CurrentNavigationConnectionType
import dev.evestaticmapplanner.control.CurrentNavigationRouteDto
import dev.evestaticmapplanner.control.CurrentNavigationRouteStatus
import dev.evestaticmapplanner.control.CurrentNavigationRouteType
import dev.evestaticmapplanner.control.CurrentNavigationRoutesDto
import dev.evestaticmapplanner.control.CurrentNavigationSecurityBand
import dev.evestaticmapplanner.control.CurrentNavigationSystemDto
import dev.evestaticmapplanner.control.GetCurrentNavigationRoutesRequest
import dev.evestaticmapplanner.control.MapControlService
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GetCurrentNavigationRoutesToolTest {
    @Test
    fun `tool returns both current route types with ordered systems and distinct connections`() = runBlocking {
        val requests = mutableListOf<String>()
        val tool = GetCurrentNavigationRoutesTool(currentRoutesService(requests) { ROUTES })

        val result = tool.execute(GetCurrentNavigationRoutesTool.Args())

        assertEquals(1, requests.size)
        assertTrue(result.contains("\"snapshotSemantics\":\"CURRENT_AT_TOOL_EXECUTION\""))
        assertTrue(result.contains("\"routeType\":\"NORMAL\""))
        assertTrue(result.contains("\"type\":\"ANSIBLEX\""))
        assertTrue(result.contains("\"routeType\":\"CAPITAL\""))
        assertTrue(result.contains("\"type\":\"CAPITAL_JUMP\""))
        assertTrue(result.contains("\"securityBand\":\"LOW_SECURITY\""))
        assertTrue(result.indexOf("\"systemId\":30000001") < result.indexOf("\"systemId\":30000002"))
        assertEquals(
            PlannerToolRisk.READ_ONLY,
            PlannerToolPermissions.riskOf(GetCurrentNavigationRoutesTool.NAME),
        )
        assertTrue(OpenRouterKoogAgentFactory.SYSTEM_PROMPT.contains("call it again for every request"))
        assertTrue(OpenRouterKoogAgentFactory.SYSTEM_PROMPT.contains("Treat STALE and UNAVAILABLE routes as invalid"))
    }

    @Test
    fun `tool reads service again after route changes and clear`() = runBlocking {
        var current = ROUTES
        var reads = 0
        val tool = GetCurrentNavigationRoutesTool(currentRoutesService { reads++; current })

        val first = tool.execute(GetCurrentNavigationRoutesTool.Args(routeType = GetCurrentNavigationRoutesTool.RouteType.NORMAL))
        current = CurrentNavigationRoutesDto(notPlanned(CurrentNavigationRouteType.NORMAL), ROUTES.capital)
        val second = tool.execute(GetCurrentNavigationRoutesTool.Args(routeType = GetCurrentNavigationRoutesTool.RouteType.NORMAL))

        assertEquals(2, reads)
        assertTrue(first.contains("\"exists\":true"))
        assertTrue(second.contains("\"status\":\"NOT_PLANNED\""))
        assertTrue(second.contains("\"exists\":false"))
        assertTrue(second.contains("\"systems\":[]"))
    }

    @Test
    fun `segment query preserves order and reports missing or repeated endpoints without guessing`() = runBlocking {
        val tool = GetCurrentNavigationRoutesTool(currentRoutesService { ROUTES })

        val unique = tool.execute(
            GetCurrentNavigationRoutesTool.Args(
                routeType = GetCurrentNavigationRoutesTool.RouteType.NORMAL,
                segmentStartSystemId = 30_000_002,
                segmentEndSystemId = 30_000_003,
            ),
        )
        val missing = findCurrentRouteSegments(listOf(1, 2, 3), 2, 4)
        val reversed = findCurrentRouteSegments(listOf(1, 2, 3), 3, 1)
        val repeated = findCurrentRouteSegments(listOf(1, 2, 1, 2), 1, 2)

        assertTrue(unique.contains("\"status\":\"UNIQUE\""))
        assertTrue(unique.contains("\"startRouteIndex\":1"))
        assertTrue(unique.contains("\"endRouteIndex\":2"))
        assertTrue(unique.indexOf("\"systemId\":30000002", unique.indexOf("segmentQuery")) <
            unique.indexOf("\"systemId\":30000003", unique.indexOf("segmentQuery")))
        assertEquals(CurrentRouteSegmentMatch.NotFound(startPresent = true, endPresent = false), missing)
        assertEquals(CurrentRouteSegmentMatch.NotFound(startPresent = true, endPresent = true), reversed)
        assertIs<CurrentRouteSegmentMatch.Ambiguous>(repeated)
        assertEquals(listOf(0..1, 0..3, 2..3), repeated.candidates)
    }

    @Test
    fun `invalid segment arguments and service failures use existing safe failure boundaries`() = runBlocking {
        var calls = 0
        val invalidTool = GetCurrentNavigationRoutesTool(currentRoutesService { calls++; ROUTES })

        val invalid = invalidTool.execute(
            GetCurrentNavigationRoutesTool.Args(segmentStartSystemId = 30_000_001),
        )

        assertEquals(0, calls)
        assertTrue(invalid.contains("\"error\":\"INVALID_SEGMENT_ENDPOINTS\""))

        val diagnostics = mutableListOf<String>()
        val failingTool = GetCurrentNavigationRoutesTool(failingCurrentRoutesService(), diagnostics::add)
        runCatching { failingTool.execute(GetCurrentNavigationRoutesTool.Args()) }
        assertEquals(
            listOf(toolCallDiagnostic(GetCurrentNavigationRoutesTool.NAME), toolFailureDiagnostic()),
            diagnostics,
        )

        val cancellationDiagnostics = mutableListOf<String>()
        val cancelledTool = GetCurrentNavigationRoutesTool(
            currentRoutesService { throw CancellationException("cancelled") },
            cancellationDiagnostics::add,
        )
        assertIs<CancellationException>(
            runCatching { cancelledTool.execute(GetCurrentNavigationRoutesTool.Args()) }.exceptionOrNull(),
        )
        assertEquals(
            listOf(toolCallDiagnostic(GetCurrentNavigationRoutesTool.NAME), toolCancellationDiagnostic()),
            cancellationDiagnostics,
        )
    }

    @Test
    fun `Koog agent obtains the current route through a real native tool call`() = runBlocking {
        var reads = 0
        val tools = PlannerToolSet(currentRoutesService { reads++; ROUTES })
        val question = "What is my current normal route?"
        val answer = "Your current normal route is A, B, then C, with one Stargate and one Ansiblex connection."
        val executor = getMockExecutor {
            mockLLMToolCall(tools.getCurrentNavigationRoutes, GetCurrentNavigationRoutesTool.Args()) onRequestEquals question
            mockLLMAnswer(answer) onRequestContains "CURRENT_AT_TOOL_EXECUTION"
        }
        try {
            assertEquals(answer, createKoogAgent(tools, executor).run(question))
            assertEquals(1, reads)
        } finally {
            executor.close()
        }
    }
}

private fun currentRoutesService(
    requests: MutableList<String>? = null,
    routes: () -> CurrentNavigationRoutesDto,
): MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, arguments ->
    when (method.name) {
        "getCurrentNavigationRoutes" -> {
            val request = arguments?.first() as GetCurrentNavigationRoutesRequest
            requests?.add(request.requestId)
            ControlResult.Success(request.requestId, routes())
        }
        "toString" -> "CurrentRoutesMapControlService"
        else -> error("Unexpected MapControlService call: ${method.name}")
    }
} as MapControlService

private fun failingCurrentRoutesService(): MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, arguments ->
    when (method.name) {
        "getCurrentNavigationRoutes" -> {
            val request = arguments?.first() as GetCurrentNavigationRoutesRequest
            ControlResult.Failure(
                request.requestId,
                ControlError(ControlErrorCode.APP_NOT_READY, "Current routes are unavailable"),
            )
        }
        "toString" -> "FailingCurrentRoutesMapControlService"
        else -> error("Unexpected MapControlService call: ${method.name}")
    }
} as MapControlService

private fun notPlanned(type: CurrentNavigationRouteType) = CurrentNavigationRouteDto(
    routeType = type,
    status = CurrentNavigationRouteStatus.NOT_PLANNED,
    valid = false,
    stale = false,
    unavailableReason = "NO_CALCULATED_ROUTE",
)

private fun system(index: Int, id: Int, name: String, band: CurrentNavigationSecurityBand) =
    CurrentNavigationSystemDto(
        routeIndex = index,
        systemId = id,
        name = name,
        securityStatus = when (band) {
            CurrentNavigationSecurityBand.HIGH_SECURITY -> 0.9
            CurrentNavigationSecurityBand.LOW_SECURITY -> 0.2
            CurrentNavigationSecurityBand.NULL_SECURITY -> -0.1
        },
        spaceClassification = "NEW_EDEN",
        securityBand = band,
    )

private val ROUTES = CurrentNavigationRoutesDto(
    normal = CurrentNavigationRouteDto(
        routeType = CurrentNavigationRouteType.NORMAL,
        status = CurrentNavigationRouteStatus.CALCULATED,
        valid = true,
        stale = false,
        startSystemId = 30_000_001,
        destinationSystemId = 30_000_003,
        systems = listOf(
            system(0, 30_000_001, "A", CurrentNavigationSecurityBand.HIGH_SECURITY),
            system(1, 30_000_002, "B", CurrentNavigationSecurityBand.LOW_SECURITY),
            system(2, 30_000_003, "C", CurrentNavigationSecurityBand.NULL_SECURITY),
        ),
        connections = listOf(
            CurrentNavigationConnectionDto(0, 30_000_001, 30_000_002, CurrentNavigationConnectionType.STARGATE),
            CurrentNavigationConnectionDto(1, 30_000_002, 30_000_003, CurrentNavigationConnectionType.ANSIBLEX),
        ),
        waypointSystemIds = listOf(30_000_002),
        explicitDestinationSystemId = 30_000_003,
    ),
    capital = CurrentNavigationRouteDto(
        routeType = CurrentNavigationRouteType.CAPITAL,
        status = CurrentNavigationRouteStatus.CALCULATED,
        valid = true,
        stale = false,
        startSystemId = 30_000_001,
        destinationSystemId = 30_000_003,
        systems = listOf(
            system(0, 30_000_001, "A", CurrentNavigationSecurityBand.HIGH_SECURITY),
            system(1, 30_000_003, "C", CurrentNavigationSecurityBand.NULL_SECURITY),
        ),
        connections = listOf(
            CurrentNavigationConnectionDto(
                0,
                30_000_001,
                30_000_003,
                CurrentNavigationConnectionType.CAPITAL_JUMP,
                distanceLy = 4.5,
            ),
        ),
        explicitDestinationSystemId = 30_000_003,
        effectiveRangeLy = 5.0,
    ),
)
