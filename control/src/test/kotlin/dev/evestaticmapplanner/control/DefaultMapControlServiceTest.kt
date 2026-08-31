package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.control.mission.Mission
import dev.evestaticmapplanner.control.mission.MissionJumpRangeId
import dev.evestaticmapplanner.control.mission.MissionMarkerId
import dev.evestaticmapplanner.control.mission.MissionMarkerRole
import dev.evestaticmapplanner.control.mission.MissionRouteId
import dev.evestaticmapplanner.core.jump.EligibilityVerdict
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.JumpRangeResult
import dev.evestaticmapplanner.core.jump.PositionQueryStrategy
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteEdgeId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DefaultMapControlServiceTest {
    @Test
    fun `queries are explicit read-only operations and calculate does not render`() = runTest {
        val fixture = Fixture(this)
        try {
            val search = fixture.service.searchSystems(SearchSystemsRequest("q1", "One", 10)).success()
            val info = fixture.service.getSystemInfo(GetSystemInfoRequest("q2", 1)).success()
            val normal = fixture.service.calculateNormalRoute(
                CalculateNormalRouteRequest("q3", 1, 2, useAnsiblex = true),
            ).success()
            val capital = fixture.service.calculateCapitalRoute(
                CalculateCapitalRouteRequest("q4", 1, 2, 5.0),
            ).success()

            assertEquals(listOf("One"), search.value.map(SystemSummaryDto::name))
            assertEquals(1, info.value.system.systemId)
            assertEquals(1, normal.value.ansiblexJumps)
            assertEquals(1, capital.value.totalJumps)
            assertTrue(fixture.rendered.isEmpty())
            assertEquals(listOf(true), fixture.routes.normalAnsiblexFlags)
            assertEquals(listOf(false), fixture.routes.normalWormholeFlags)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `show operations generate owned IDs and clear never changes user state sentinels`() = runTest {
        val fixture = Fixture(this)
        val userState = UserStateSentinel()
        try {
            val mission = fixture.begin().value.missionId
            val normal = fixture.service.showNormalRoute(
                ShowNormalRouteCommand("n", "normal", mission, 1, 2, true),
            ).success()
            val capital = fixture.service.showCapitalRoute(
                ShowCapitalRouteCommand("c", "capital", mission, 1, 2, 5.0),
            ).success()
            val jump = fixture.service.showJumpRange(
                ShowJumpRangeCommand("j", "jump", mission, 1, 5.0, "Range"),
            ).success()
            val marker = fixture.service.addMissionMarker(
                AddMissionMarkerCommand("m", "marker", mission, 1, MissionMarkerRole.RALLY, "Rally"),
            ).success()

            assertNotEquals(normal.value.routeId.value, capital.value.routeId.value)
            assertEquals(2, fixture.rendered.single().routes.size)
            assertEquals(1, fixture.rendered.single().jumpRanges.size)
            assertEquals(1, fixture.rendered.single().markers.size)

            fixture.service.clearMissionRoutes(ClearMissionRoutesCommand("cr", "clear-routes", mission)).success()
            fixture.service.clearMissionJumpRanges(ClearMissionJumpRangesCommand("cj", "clear-jumps", mission)).success()
            fixture.service.clearMissionMarkers(ClearMissionMarkersCommand("cm", "clear-markers", mission)).success()
            assertEquals(UserStateSentinel(), userState)
            assertTrue(fixture.rendered.single().routes.isEmpty())
            assertTrue(fixture.rendered.single().jumpRanges.isEmpty())
            assertTrue(fixture.rendered.single().markers.isEmpty())

            fixture.service.clearMission(ClearMissionCommand("clear", "clear", mission)).success()
            assertTrue(fixture.rendered.isEmpty())
            assertEquals(UserStateSentinel(), userState)
            assertEquals(marker.value.systemId, jump.value.originSystemId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `cross Mission removals do not reveal ownership and leave both Missions unchanged`() = runTest {
        val fixture = Fixture(this)
        try {
            val first = fixture.begin("First", "first").value.missionId
            val second = fixture.begin("Second", "second").value.missionId
            val route = fixture.service.showNormalRoute(
                ShowNormalRouteCommand("add-route", "add-route", first, 1, 2, true),
            ).success().value
            val range = fixture.service.showJumpRange(
                ShowJumpRangeCommand("add-range", "add-range", first, 1, 5.0),
            ).success().value
            val marker = fixture.service.addMissionMarker(
                AddMissionMarkerCommand("add", "add", first, 1, MissionMarkerRole.INFO),
            ).success().value
            val firstBefore = fixture.service.getMission(GetMissionRequest("first-before", first)).success().value
            val secondBefore = fixture.service.getMission(GetMissionRequest("second-before", second)).success().value

            val crossErrors = listOf(
                fixture.service.removeMissionRoute(
                    RemoveMissionRouteCommand("cross-route", "cross-route", second, route.routeId),
                ).failure().error,
                fixture.service.removeJumpRange(
                    RemoveJumpRangeCommand("cross-range", "cross-range", second, range.jumpRangeId),
                ).failure().error,
                fixture.service.removeMissionMarker(
                    RemoveMissionMarkerCommand("cross-marker", "cross-marker", second, marker.markerId),
                ).failure().error,
            )
            val unknownErrors = listOf(
                fixture.service.removeMissionRoute(
                    RemoveMissionRouteCommand("unknown-route", "unknown-route", first, MissionRouteId("unknown")),
                ).failure().error,
                fixture.service.removeJumpRange(
                    RemoveJumpRangeCommand("unknown-range", "unknown-range", first, MissionJumpRangeId("unknown")),
                ).failure().error,
                fixture.service.removeMissionMarker(
                    RemoveMissionMarkerCommand("unknown-marker", "unknown-marker", first, MissionMarkerId("unknown")),
                ).failure().error,
            )

            assertTrue((crossErrors + unknownErrors).all { it.code == ControlErrorCode.OBJECT_NOT_FOUND })
            assertEquals(unknownErrors, crossErrors)
            assertEquals(firstBefore, fixture.service.getMission(GetMissionRequest("first-after", first)).success().value)
            assertEquals(secondBefore, fixture.service.getMission(GetMissionRequest("second-after", second)).success().value)

            fixture.service.removeMissionRoute(
                RemoveMissionRouteCommand("own-route", "own-route", first, route.routeId),
            ).success()
            fixture.service.removeJumpRange(
                RemoveJumpRangeCommand("own-range", "own-range", first, range.jumpRangeId),
            ).success()
            fixture.service.removeMissionMarker(
                RemoveMissionMarkerCommand("own-marker", "own-marker", first, marker.markerId),
            ).success()
            val cleared = fixture.service.getMission(GetMissionRequest("cleared", first)).success().value
            assertTrue(cleared.routes.isEmpty() && cleared.jumpRanges.isEmpty() && cleared.markers.isEmpty())

            fixture.routes.normalOutcome = RouteCalculationOutcome.Unreachable(1, 2)
            assertEquals(
                ControlErrorCode.ROUTE_NOT_FOUND,
                fixture.service.calculateNormalRoute(CalculateNormalRouteRequest("route", 1, 2, false))
                    .failure().error.code,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `idempotency replays original result conflicts on changed input and protects retries`() = runTest {
        val fixture = Fixture(this)
        try {
            val first = fixture.service.beginMission(BeginMissionCommand("first-request", "same-key", "Title")).success()
            val replay = fixture.service.beginMission(BeginMissionCommand("retry-request", "same-key", "Title")).success()
            assertEquals(first, replay)
            assertEquals(1, fixture.rendered.size)

            val conflict = fixture.service.beginMission(BeginMissionCommand("conflict", "same-key", "Other"))
                .failure()
            assertEquals(ControlErrorCode.IDEMPOTENCY_CONFLICT, conflict.error.code)

            val mission = first.value.missionId
            val marker = fixture.service.addMissionMarker(
                AddMissionMarkerCommand("add", "marker-key", mission, 1, MissionMarkerRole.INFO),
            ).success().value
            val remove = RemoveMissionMarkerCommand("remove", "remove-key", mission, marker.markerId)
            val removed = fixture.service.removeMissionMarker(remove).success()
            val retried = fixture.service.removeMissionMarker(remove.copy(requestId = "remove-retry")).success()
            assertEquals(removed, retried)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `limits validate before planning or state mutation`() = runTest {
        val fixture = Fixture(this)
        try {
            val invalidSearch = fixture.service.searchSystems(SearchSystemsRequest("s", "x".repeat(65), 20)).failure()
            val invalidRange = fixture.service.calculateCapitalRoute(
                CalculateCapitalRouteRequest("r", 1, 2, 20.1),
            ).failure()
            val invalidTitle = fixture.service.beginMission(
                BeginMissionCommand("b", "b", "x".repeat(121)),
            ).failure()

            assertEquals(ControlErrorCode.INVALID_ARGUMENT, invalidSearch.error.code)
            assertEquals(ControlErrorCode.INVALID_ARGUMENT, invalidRange.error.code)
            assertEquals(ControlErrorCode.INVALID_ARGUMENT, invalidTitle.error.code)
            assertEquals(0, fixture.routes.capitalCalls)
            assertTrue(fixture.rendered.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `fit uses Mission jump visual coverage without changing references or including user Jump state`() = runTest {
        val fixture = Fixture(this)
        val userJumpRangeSystemIds = setOf(3)
        try {
            val mission = fixture.begin().value.missionId
            fixture.service.showJumpRange(
                ShowJumpRangeCommand("range", "range", mission, 1, 5.0),
            ).success()
            fixture.service.focusSystem(FocusSystemCommand("focus", "focus", 1)).success()
            fixture.service.fitMission(FitMissionCommand("fit", "fit", mission)).success()

            val state = fixture.service.getMission(GetMissionRequest("mission", mission)).success().value
            val summary = fixture.service.getActiveMissions(GetActiveMissionsRequest("missions"))
                .success().value.single()
            assertEquals(setOf(1), state.referencedSystemIds)
            assertEquals(setOf(1, 2), state.visualFitSystemIds)
            assertEquals(1, summary.referencedSystemCount)
            assertEquals(listOf(1), fixture.viewport.focused)
            assertEquals(listOf(setOf(1, 2)), fixture.viewport.fitted)
            assertTrue(fixture.viewport.fitted.single().intersect(userJumpRangeSystemIds).isEmpty())
            assertTrue(fixture.viewport.focusCommitted)
            assertTrue(fixture.viewport.fitCommitted)

            fixture.service.clearMission(ClearMissionCommand("clear-fit", "clear-fit", mission)).success()
            assertTrue(fixture.rendered.isEmpty())
            assertEquals(setOf(3), userJumpRangeSystemIds)
        } finally {
            fixture.close()
        }
    }
}

private data class UserStateSentinel(
    val normalRoute: String = "user-normal",
    val capitalRoute: String = "user-capital",
    val jumpOverlays: List<String> = listOf("user-jump"),
    val temporaryMarkers: List<String> = listOf("temporary"),
    val savedMarkers: List<String> = listOf("saved"),
)

private class Fixture(scope: kotlinx.coroutines.CoroutineScope) {
    val routes = FakeRoutePort()
    val viewport = FakeViewportPort()
    var rendered: List<Mission> = emptyList()
    private val systemPort = FakeSystemPort()
    val service = DefaultMapControlService(
        systemPort,
        routes,
        JumpPlanningPort { origin, range ->
            JumpRangeResult(
                origin,
                JumpProfile.manual(range),
                setOf(2),
                EligibilityVerdict.Eligible,
                PositionQueryStrategy.LINEAR_SCAN,
            )
        },
        viewport,
        MissionRenderStatePort { rendered = it },
        scope,
    )

    suspend fun begin(title: String = "Mission", key: String = "begin") =
        service.beginMission(BeginMissionCommand("begin-$key", key, title)).success()

    fun close() = service.close()
}

private class FakeSystemPort : SystemReadPort {
    private val systems = (1..3).associateWith { id ->
        SystemSummaryDto(id, if (id == 1) "One" else "System $id", 10, 20, 0.1)
    }

    override suspend fun searchSystems(query: String, limit: Int) =
        systems.values.filter { it.name.contains(query, ignoreCase = true) }.take(limit)

    override suspend fun getSystemInfo(systemId: Int) = systems[systemId]?.let {
        SystemInfoDto(it, "Region", "Constellation", 0.0, 0.0, 0.0, 1)
    }
}

private class FakeRoutePort : RoutePlanningPort {
    val normalAnsiblexFlags = mutableListOf<Boolean>()
    val normalWormholeFlags = mutableListOf<Boolean>()
    var capitalCalls = 0
    var normalOutcome: RouteCalculationOutcome = RouteCalculationOutcome.Found(normalRoute())

    override suspend fun calculateNormalRoute(startSystemId: Int, destinationSystemId: Int, useAnsiblex: Boolean) =
        normalOutcome.also { normalAnsiblexFlags += useAnsiblex }

    override suspend fun calculateNormalRoute(
        startSystemId: Int,
        destinationSystemId: Int,
        useAnsiblex: Boolean,
        useWormholes: Boolean,
    ) = normalOutcome.also {
        normalAnsiblexFlags += useAnsiblex
        normalWormholeFlags += useWormholes
    }

    override suspend fun calculateCapitalRoute(
        startSystemId: Int,
        destinationSystemId: Int,
        effectiveRangeLy: Double,
    ): CapitalRouteOutcome {
        capitalCalls++
        return CapitalRouteOutcome.Found(
            CapitalRouteResult(
                1,
                2,
                JumpProfile.manual(effectiveRangeLy),
                listOf(1, 2),
                listOf(dev.evestaticmapplanner.core.route.CapitalRouteLeg(1, 2, 1.0)),
            ),
        )
    }
}

private class FakeViewportPort : ViewportControlPort {
    val focused = mutableListOf<Int>()
    val fitted = mutableListOf<Set<Int>>()
    var focusCommitted = false
    var fitCommitted = false

    override suspend fun focusSystem(systemId: Int): ViewportOperationOutcome {
        focused += systemId
        focusCommitted = true
        return ViewportOperationOutcome.COMPLETED
    }

    override suspend fun fitSystems(systemIds: Set<Int>): ViewportOperationOutcome {
        fitted += systemIds
        fitCommitted = true
        return ViewportOperationOutcome.COMPLETED
    }
}

private fun normalRoute(): RouteResult {
    val edge = RouteEdge(
        RouteEdgeId("ansiblex:1:2"),
        RouteConnectionId("ansiblex:test"),
        1,
        2,
        RouteEdgeType.ANSIBLEX,
    )
    return RouteResult(1, 2, listOf(1, 2), listOf(edge))
}

private fun <T> ControlResult<T>.success(): ControlResult.Success<T> = assertIs(this)
private fun ControlResult<*>.failure(): ControlResult.Failure = assertIs(this)
