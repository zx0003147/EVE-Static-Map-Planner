package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionRoute
import dev.evestaticmapplanner.control.mission.MissionRouteId
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.route.CapitalRouteLeg
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteEdgeId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.feature.api.RouteIdentity
import dev.evestaticmapplanner.feature.api.RouteKind
import dev.evestaticmapplanner.feature.api.RouteSegmentKind
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class RouteSnapshotAdaptersTest {
    @Test
    fun `normal adapter preserves stargate Ansiblex order endpoints and zero-hop`() {
        val mixed = normalRoute(
            listOf(
                edge(1, 2, RouteEdgeType.STARGATE),
                edge(2, 3, RouteEdgeType.ANSIBLEX),
            ),
        )
        val snapshot = RouteSnapshotAdapters.normal(mixed, RouteIdentity("normal-1"))

        assertEquals(RouteKind.NORMAL, snapshot.kind)
        assertEquals(listOf(1, 2, 3), snapshot.orderedSystemIds)
        assertEquals(
            listOf(RouteSegmentKind.STARGATE, RouteSegmentKind.ANSIBLEX),
            snapshot.orderedSegments.map { it.kind },
        )
        assertEquals(1, snapshot.sourceSystemId)
        assertEquals(3, snapshot.destinationSystemId)

        val pureStargate = RouteSnapshotAdapters.normal(
            normalRoute(listOf(edge(4, 5, RouteEdgeType.STARGATE))),
            RouteIdentity("stargate"),
        )
        val pureAnsiblex = RouteSnapshotAdapters.normal(
            normalRoute(listOf(edge(5, 6, RouteEdgeType.ANSIBLEX))),
            RouteIdentity("ansiblex"),
        )
        assertEquals(listOf(RouteSegmentKind.STARGATE), pureStargate.orderedSegments.map { it.kind })
        assertEquals(listOf(RouteSegmentKind.ANSIBLEX), pureAnsiblex.orderedSegments.map { it.kind })

        val zero = RouteSnapshotAdapters.normal(RouteResult(7, 7, listOf(7), emptyList()), RouteIdentity("zero"))
        assertEquals(listOf(7), zero.orderedSystemIds)
        assertEquals(emptyList(), zero.orderedSegments)
    }

    @Test
    fun `capital adapter exposes only ordered capital jumps and LY distances`() {
        val route = capitalRoute()
        val snapshot = RouteSnapshotAdapters.capital(route, RouteIdentity("capital-1"))

        assertEquals(RouteKind.CAPITAL, snapshot.kind)
        assertEquals(listOf(1, 2, 3), snapshot.orderedSystemIds)
        assertEquals(listOf(RouteSegmentKind.CAPITAL_JUMP, RouteSegmentKind.CAPITAL_JUMP), snapshot.orderedSegments.map { it.kind })
        assertEquals(listOf(1.25, 2.5), snapshot.orderedSegments.map { it.distanceLy })
        assertEquals(null, snapshot.javaClass.declaredFields.singleOrNull { it.type == CapitalRouteResult::class.java })
    }

    @Test
    fun `interactive adapter keeps identity for equal active route and changes it with route content`() {
        val sequence = AtomicInteger()
        val adapter = InteractiveRouteSnapshotAdapter { RouteIdentity("host-${sequence.incrementAndGet()}") }
        val firstRoute = normalRoute(listOf(edge(1, 2, RouteEdgeType.STARGATE)))

        val first = adapter.normal(firstRoute)
        val recomposed = adapter.normal(firstRoute.copy(systems = firstRoute.systems.toList(), edges = firstRoute.edges.toList()))
        val changed = adapter.normal(normalRoute(listOf(edge(1, 3, RouteEdgeType.STARGATE))))

        assertSame(first, recomposed)
        assertEquals(first?.identity, recomposed?.identity)
        assertNotEquals(first?.identity, changed?.identity)
        adapter.normal(null)
        assertNotEquals(first?.identity, adapter.normal(firstRoute)?.identity)
    }

    @Test
    fun `mission adapters map both kinds without exposing mission IDs as identity`() {
        val sequence = AtomicInteger()
        val adapter = InteractiveRouteSnapshotAdapter { RouteIdentity("opaque-${sequence.incrementAndGet()}") }
        val normal = MissionRoute.Normal(MissionId("mission-secret"), MissionRouteId("route-secret"), normalRoute(
            listOf(edge(1, 2, RouteEdgeType.ANSIBLEX)),
        ))
        val capital = MissionRoute.Capital(MissionId("mission-secret"), MissionRouteId("capital-secret"), capitalRoute())

        val normalSnapshot = adapter.mission(normal)
        val normalAgain = adapter.mission(normal.copy(route = normal.route.copy()))
        val capitalSnapshot = adapter.mission(capital)

        assertEquals(RouteKind.MISSION_NORMAL, normalSnapshot.kind)
        assertEquals(RouteSegmentKind.ANSIBLEX, normalSnapshot.orderedSegments.single().kind)
        assertSame(normalSnapshot, normalAgain)
        assertEquals(RouteKind.MISSION_CAPITAL, capitalSnapshot.kind)
        assertEquals("opaque-2", capitalSnapshot.identity.value)
    }

    @Test
    fun `Feature API snapshot retains defensive copies rather than Core collections`() {
        val systems = mutableListOf(1, 2)
        val edges = mutableListOf(edge(1, 2, RouteEdgeType.STARGATE))
        val core = RouteResult(1, 2, systems, edges)
        val snapshot = RouteSnapshotAdapters.normal(core, RouteIdentity("copy"))

        systems += 3
        edges.clear()

        assertEquals(listOf(1, 2), snapshot.orderedSystemIds)
        assertEquals(1, snapshot.orderedSegments.size)
        assertNotSame(systems, snapshot.orderedSystemIds)
    }

    @Test
    fun `normal adapter rejects Wormhole routes at the frozen Feature API boundary`() {
        val error = assertFailsWith<IllegalStateException> {
            RouteSnapshotAdapters.normal(
                normalRoute(listOf(edge(1, 2, RouteEdgeType.WORMHOLE))),
                RouteIdentity("unsupported-wormhole"),
            )
        }

        assertEquals(
            "Feature API 2.0.0 RouteSnapshot does not support Wormhole route segments",
            error.message,
        )
    }

    private fun normalRoute(edges: List<RouteEdge>): RouteResult {
        val systems = buildList {
            add(edges.first().fromSystemId)
            edges.forEach { add(it.toSystemId) }
        }
        return RouteResult(systems.first(), systems.last(), systems, edges)
    }

    private fun edge(from: Int, to: Int, type: RouteEdgeType) = RouteEdge(
        RouteEdgeId("${type.name.lowercase()}:$from:$to"),
        RouteConnectionId("${type.name.lowercase()}:$from:$to"),
        from,
        to,
        type,
    )

    private fun capitalRoute(): CapitalRouteResult {
        val profile = JumpProfile.manual(5.0)
        return CapitalRouteResult(
            1,
            3,
            profile,
            listOf(1, 2, 3),
            listOf(
                CapitalRouteLeg(1, 2, 1.25 * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR),
                CapitalRouteLeg(2, 3, 2.5 * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR),
            ),
        )
    }
}
