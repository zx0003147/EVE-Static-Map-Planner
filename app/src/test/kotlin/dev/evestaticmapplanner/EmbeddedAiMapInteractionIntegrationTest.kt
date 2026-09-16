package dev.evestaticmapplanner

import dev.evestaticmapplanner.control.DefaultMapControlService
import dev.evestaticmapplanner.control.JumpPlanningPort
import dev.evestaticmapplanner.control.MissionMapStateStore
import dev.evestaticmapplanner.control.RoutePlanningPort
import dev.evestaticmapplanner.control.SystemInfoDto
import dev.evestaticmapplanner.control.SystemReadPort
import dev.evestaticmapplanner.control.SystemSummaryDto
import dev.evestaticmapplanner.control.ViewportControlPort
import dev.evestaticmapplanner.control.ViewportOperationOutcome
import dev.evestaticmapplanner.core.jump.EligibilityVerdict
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.JumpRangeResult
import dev.evestaticmapplanner.core.jump.PositionQueryStrategy
import dev.evestaticmapplanner.core.route.CapitalRouteLeg
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteEdgeId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.embeddedai.AddMissionMarkerTool
import dev.evestaticmapplanner.embeddedai.BeginMissionTool
import dev.evestaticmapplanner.embeddedai.FitMissionTool
import dev.evestaticmapplanner.embeddedai.FocusSystemTool
import dev.evestaticmapplanner.embeddedai.GetMissionTool
import dev.evestaticmapplanner.embeddedai.ShowCapitalRouteTool
import dev.evestaticmapplanner.embeddedai.ShowJumpRangeTool
import dev.evestaticmapplanner.embeddedai.ShowNormalRouteTool
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EmbeddedAiMapInteractionIntegrationTest {
    @Test
    fun `native tools update the shared Mission map state and viewport through DefaultMapControlService`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val mapState = MissionMapStateStore(dispatcher)
        val viewport = RecordingViewport()
        val service = DefaultMapControlService(
            systemReadPort = IntegrationSystems,
            routePlanningPort = IntegrationRoutes,
            jumpPlanningPort = JumpPlanningPort { originSystemId, effectiveRangeLy ->
                JumpRangeResult(
                    originSystemId = originSystemId,
                    profile = JumpProfile.manual(effectiveRangeLy),
                    reachableSystemIds = setOf(1, 2, 3),
                    originVerdict = EligibilityVerdict.Eligible,
                    queryStrategy = PositionQueryStrategy.LINEAR_SCAN,
                )
            },
            viewportControlPort = viewport,
            missionRenderStatePort = mapState,
            scope = this,
        )
        try {
            FocusSystemTool(service).execute(FocusSystemTool.Args(1))
            val missionId = Json.parseToJsonElement(
                BeginMissionTool(service).execute(BeginMissionTool.Args("Integration Mission")),
            ).jsonObject.getValue("missionId").jsonPrimitive.content

            ShowNormalRouteTool(service).execute(
                ShowNormalRouteTool.Args(missionId, 1, 2),
            )
            ShowCapitalRouteTool(service).execute(
                ShowCapitalRouteTool.Args(missionId, 1, 3, effectiveRangeLy = 6.0),
            )
            val range = ShowJumpRangeTool(service).execute(
                ShowJumpRangeTool.Args(missionId, 1, 6.0, "6 LY"),
            )
            AddMissionMarkerTool(service).execute(
                AddMissionMarkerTool.Args(missionId, 2, label = "Rally"),
            )
            val mission = GetMissionTool(service).execute(GetMissionTool.Args(missionId))
            FitMissionTool(service).execute(FitMissionTool.Args(missionId))

            val state = mapState.state.value
            assertEquals(1, state.missions.size)
            assertEquals(1, state.normalRoutes.size)
            assertEquals(1, state.capitalRoutes.size)
            assertEquals(1, state.jumpRanges.size)
            assertEquals(1, state.markers.size)
            assertEquals(listOf(1), viewport.focused)
            assertEquals(listOf(setOf(1, 2, 3)), viewport.fitted)
            assertTrue(range.contains("\"reachableSystemCount\":3"))
            assertTrue(mission.contains("\"routeOverlays\""))
            assertTrue(mission.contains("\"missionMarkers\""))
        } finally {
            service.close()
        }
    }
}

private object IntegrationSystems : SystemReadPort {
    private val systems = (1..3).associateWith { id ->
        SystemSummaryDto(id, "System $id", 10, 20, 0.0)
    }

    override suspend fun searchSystems(query: String, limit: Int) =
        systems.values.filter { it.name.contains(query, ignoreCase = true) }.take(limit)

    override suspend fun getSystemInfo(systemId: Int) = systems[systemId]?.let { system ->
        SystemInfoDto(system, "Region", "Constellation", 0.0, 0.0, 0.0, 1)
    }
}

private object IntegrationRoutes : RoutePlanningPort {
    override suspend fun calculateNormalRoute(
        startSystemId: Int,
        destinationSystemId: Int,
        useAnsiblex: Boolean,
    ): RouteCalculationOutcome = RouteCalculationOutcome.Found(
        RouteResult(
            startSystemId,
            destinationSystemId,
            listOf(startSystemId, destinationSystemId),
            listOf(
                RouteEdge(
                    RouteEdgeId("stargate:$startSystemId:$destinationSystemId"),
                    RouteConnectionId("fixture:$startSystemId:$destinationSystemId"),
                    startSystemId,
                    destinationSystemId,
                    RouteEdgeType.STARGATE,
                ),
            ),
        ),
    )

    override suspend fun calculateCapitalRoute(
        startSystemId: Int,
        destinationSystemId: Int,
        effectiveRangeLy: Double,
    ): CapitalRouteOutcome = CapitalRouteOutcome.Found(
        CapitalRouteResult(
            startSystemId,
            destinationSystemId,
            JumpProfile.manual(effectiveRangeLy),
            listOf(startSystemId, destinationSystemId),
            listOf(CapitalRouteLeg(startSystemId, destinationSystemId, 1.0)),
        ),
    )
}

private class RecordingViewport : ViewportControlPort {
    val focused = mutableListOf<Int>()
    val fitted = mutableListOf<Set<Int>>()

    override suspend fun focusSystem(systemId: Int): ViewportOperationOutcome {
        focused += systemId
        return ViewportOperationOutcome.COMPLETED
    }

    override suspend fun fitSystems(systemIds: Set<Int>): ViewportOperationOutcome {
        fitted += systemIds
        return ViewportOperationOutcome.COMPLETED
    }
}
