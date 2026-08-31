package dev.evestaticmapplanner.control.mission

import dev.evestaticmapplanner.control.ControlErrorCode
import dev.evestaticmapplanner.control.ControlLimits
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteEdgeId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MissionRegistryTest {
    @Test
    fun `begin list get clear and revision lifecycle`() {
        val registry = registry()
        val mission = registry.begin("Operation One")
        assertEquals(1, mission.revision)
        assertEquals(mission, registry.get(mission.missionId))
        assertEquals(listOf(mission), registry.active())

        val marker = registry.addMarker(mission.missionId, 1, MissionMarkerRole.RALLY, null, null, null)
        assertEquals(2, registry.get(mission.missionId).revision)
        registry.removeMarker(mission.missionId, marker.markerId)
        assertEquals(3, registry.get(mission.missionId).revision)

        registry.clearMission(mission.missionId)
        assertTrue(registry.active().isEmpty())
        assertEquals(ControlErrorCode.MISSION_NOT_FOUND, failure { registry.get(mission.missionId) }.code)
    }

    @Test
    fun `missions work during a session and recreation starts empty`() {
        val activeSession = registry()
        val mission = activeSession.begin("Session plan", viewId = "view-2")
        activeSession.addMarker(mission.missionId, 42, MissionMarkerRole.RALLY, null, null, null)
        assertEquals(1, activeSession.active("view-2").size)

        val restartedSession = registry()
        assertTrue(restartedSession.active().isEmpty())
    }

    @Test
    fun `active mission and per-resource limits are enforced`() {
        val registry = registry()
        repeat(ControlLimits.MAX_ACTIVE_MISSIONS) { registry.begin("Mission $it") }
        assertEquals(
            ControlErrorCode.MISSION_LIMIT_EXCEEDED,
            failure { registry.begin("Overflow") }.code,
        )

        val markerRegistry = registry()
        val mission = markerRegistry.begin("Markers")
        repeat(ControlLimits.MAX_MARKERS_PER_MISSION) { index ->
            markerRegistry.addMarker(mission.missionId, index + 1, MissionMarkerRole.INFO, null, null, null)
        }
        assertEquals(
            ControlErrorCode.MISSION_LIMIT_EXCEEDED,
            failure {
                markerRegistry.addMarker(mission.missionId, 1000, MissionMarkerRole.INFO, null, null, null)
            }.code,
        )
    }

    @Test
    fun `route and jump range limits are enforced independently`() {
        val registry = registry()
        val mission = registry.begin("Resources")
        repeat(ControlLimits.MAX_ROUTES_PER_MISSION) { registry.addNormalRoute(mission.missionId, sameSystemRoute(it + 1)) }
        assertEquals(
            ControlErrorCode.MISSION_LIMIT_EXCEEDED,
            failure { registry.addNormalRoute(mission.missionId, sameSystemRoute(99)) }.code,
        )

        repeat(ControlLimits.MAX_JUMP_RANGES_PER_MISSION) { index ->
            registry.addJumpRange(
                mission.missionId,
                index + 1,
                JumpProfile.manual(5.0, "range-$index"),
                setOf(100 + index),
                null,
            )
        }
        assertEquals(
            ControlErrorCode.MISSION_LIMIT_EXCEEDED,
            failure {
                registry.addJumpRange(mission.missionId, 99, JumpProfile.manual(5.0), emptySet(), null)
            }.code,
        )
    }

    @Test
    fun `cross Mission and unknown object IDs are indistinguishable and do not mutate either Mission`() {
        val registry = registry()
        val first = registry.begin("First")
        val second = registry.begin("Second")
        val route = registry.addNormalRoute(first.missionId, sameSystemRoute(1))
        val marker = registry.addMarker(first.missionId, 1, MissionMarkerRole.DANGER, null, null, null)
        val range = registry.addJumpRange(
            first.missionId,
            1,
            JumpProfile.manual(5.0),
            setOf(2),
            null,
        )
        val firstBefore = registry.get(first.missionId)
        val secondBefore = registry.get(second.missionId)

        val crossFailures = listOf(
            failure { registry.removeRoute(second.missionId, route.routeId) },
            failure { registry.removeJumpRange(second.missionId, range.jumpRangeId) },
            failure { registry.removeMarker(second.missionId, marker.markerId) },
        )
        val unknownFailures = listOf(
            failure { registry.removeRoute(first.missionId, MissionRouteId("unknown-route")) },
            failure { registry.removeJumpRange(first.missionId, MissionJumpRangeId("unknown-range")) },
            failure { registry.removeMarker(first.missionId, MissionMarkerId("unknown-marker")) },
        )

        assertTrue((crossFailures + unknownFailures).all { it.code == ControlErrorCode.OBJECT_NOT_FOUND })
        assertEquals(unknownFailures.map { it.message }, crossFailures.map { it.message })
        assertEquals(firstBefore, registry.get(first.missionId))
        assertEquals(secondBefore, registry.get(second.missionId))
    }

    @Test
    fun `own route jump range and marker objects remove successfully`() {
        val registry = registry()
        val first = registry.begin("First")
        val route = registry.addNormalRoute(first.missionId, sameSystemRoute(1))
        val range = registry.addJumpRange(first.missionId, 1, JumpProfile.manual(5.0), setOf(2), null)
        val marker = registry.addMarker(first.missionId, 1, MissionMarkerRole.INFO, null, null, null)

        registry.removeRoute(first.missionId, route.routeId)
        registry.removeJumpRange(first.missionId, range.jumpRangeId)
        registry.removeMarker(first.missionId, marker.markerId)

        val state = registry.get(first.missionId)
        assertTrue(state.routes.isEmpty())
        assertTrue(state.jumpRanges.isEmpty())
        assertTrue(state.markers.isEmpty())
    }

    @Test
    fun `removed Wormhole invalidates only dependent Normal routes across every View`() {
        val registry = registry()
        val view1 = registry.begin("View 1 Mission", viewId = "view-1")
        val view2 = registry.begin("View 2 Mission", viewId = "view-2")
        val view3 = registry.begin("View 3 Mission", viewId = "view-3")

        registry.addNormalRoute(view1.missionId, route(RouteEdgeType.WORMHOLE, "wormhole:1:2", 1, 2))
        registry.addNormalRoute(view1.missionId, route(RouteEdgeType.STARGATE, "stargate:2:3", 2, 3))
        registry.addNormalRoute(view1.missionId, route(RouteEdgeType.WORMHOLE, "wormhole:3:4", 3, 4))
        registry.addMarker(view1.missionId, 1, MissionMarkerRole.RALLY, null, null, null)
        registry.addJumpRange(view1.missionId, 1, JumpProfile.manual(5.0), setOf(2), null)
        registry.addCapitalRoute(
            view1.missionId,
            dev.evestaticmapplanner.core.route.CapitalRouteResult(
                1,
                2,
                JumpProfile.manual(5.0),
                listOf(1, 2),
                listOf(dev.evestaticmapplanner.core.route.CapitalRouteLeg(1, 2, 1.0)),
            ),
        )
        registry.addNormalRoute(view2.missionId, route(RouteEdgeType.WORMHOLE, "wormhole:1:2", 2, 1))
        registry.addNormalRoute(view3.missionId, route(RouteEdgeType.STARGATE, "stargate:4:5", 4, 5))

        assertEquals(2, registry.invalidateNormalRoutesUsingWormholes(setOf("wormhole:1:2")))

        val first = registry.get(view1.missionId)
        val second = registry.get(view2.missionId)
        val third = registry.get(view3.missionId)
        assertEquals(
            setOf("stargate:2:3", "wormhole:3:4"),
            first.routes.filterIsInstance<MissionRoute.Normal>()
                .map { it.route.edges.single().connectionId.value }
                .toSet(),
        )
        assertTrue(second.routes.isEmpty())
        assertEquals(1, third.routes.size)
        assertEquals(1, first.routes.filterIsInstance<MissionRoute.Capital>().size)
        assertEquals(1, first.markers.size)
        assertEquals(1, first.jumpRanges.size)
        assertEquals(3, registry.active().size)

        assertEquals(0, registry.invalidateNormalRoutesUsingWormholes(setOf("wormhole:8:9")))
        assertEquals(1, registry.invalidateNormalRoutesUsingWormholes(setOf("wormhole:3:4")))
        val afterClear = registry.get(view1.missionId)
        assertEquals(
            listOf("stargate:2:3"),
            afterClear.routes.filterIsInstance<MissionRoute.Normal>()
                .map { it.route.edges.single().connectionId.value },
        )
        assertEquals(1, afterClear.routes.filterIsInstance<MissionRoute.Capital>().size)
        assertEquals(1, afterClear.markers.size)
        assertEquals(1, afterClear.jumpRanges.size)
    }

    @Test
    fun `same system supports different marker roles without user marker uniqueness`() {
        val registry = registry()
        val mission = registry.begin("Roles")

        val rally = registry.addMarker(mission.missionId, 42, MissionMarkerRole.RALLY, "Rally", null, null)
        val danger = registry.addMarker(
            mission.missionId,
            42,
            MissionMarkerRole.DANGER,
            "Danger",
            null,
            MarkerColor.WHITE,
        )

        assertNotEquals(rally.markerId, danger.markerId)
        assertEquals(MarkerColor.GREEN, rally.color)
        assertEquals(MarkerColor.WHITE, danger.color)
        assertEquals(setOf(42), registry.get(mission.missionId).referencedSystemIds)
    }

    @Test
    fun `referenced system limit is checked before route commit`() {
        val registry = registry()
        val mission = registry.begin("References")
        val overLimit = chainRoute(ControlLimits.MAX_REFERENCED_SYSTEMS_PER_MISSION + 1)

        assertEquals(
            ControlErrorCode.MISSION_LIMIT_EXCEEDED,
            failure { registry.addNormalRoute(mission.missionId, overLimit) }.code,
        )
        assertTrue(registry.get(mission.missionId).routes.isEmpty())
    }

    @Test
    fun `generated jump coverage affects visual fit but not references or their limit`() {
        val registry = registry()
        val mission = registry.begin("Visual range")
        val generatedCoverage = (2..300).toSet()

        registry.addJumpRange(
            mission.missionId,
            originSystemId = 1,
            profile = JumpProfile.manual(5.0),
            reachableSystemIds = generatedCoverage,
            label = null,
        )

        val state = registry.get(mission.missionId)
        assertEquals(setOf(1), state.referencedSystemIds)
        assertEquals(generatedCoverage + 1, state.visualFitSystemIds)
        assertEquals(1, state.referencedSystemIds.size)
        assertTrue(state.visualFitSystemIds.size > ControlLimits.MAX_REFERENCED_SYSTEMS_PER_MISSION)
    }

    private fun registry(): MissionRegistry {
        var next = 0
        return MissionRegistry(now = { Instant.EPOCH }, newId = { "id-${++next}" })
    }
}

private fun failure(block: () -> Unit): MissionRegistryFailure = assertFailsWith(block = block)

private fun sameSystemRoute(systemId: Int) = RouteResult(systemId, systemId, listOf(systemId), emptyList())

private fun chainRoute(systemCount: Int): RouteResult {
    val systems = (1..systemCount).toList()
    val edges = systems.zipWithNext { from, to ->
        RouteEdge(
            RouteEdgeId("edge-$from-$to"),
            RouteConnectionId("connection-$from-$to"),
            from,
            to,
            RouteEdgeType.STARGATE,
        )
    }
    return RouteResult(systems.first(), systems.last(), systems, edges)
}

private fun route(type: RouteEdgeType, connectionId: String, from: Int, to: Int): RouteResult = RouteResult(
    from,
    to,
    listOf(from, to),
    listOf(RouteEdge(RouteEdgeId("$connectionId:$from:$to"), RouteConnectionId(connectionId), from, to, type)),
)
