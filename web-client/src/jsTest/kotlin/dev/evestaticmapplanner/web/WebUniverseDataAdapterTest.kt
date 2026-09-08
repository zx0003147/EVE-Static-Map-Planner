package dev.evestaticmapplanner.web

import dev.evestaticmapplanner.core.jump.JumpCoverageCalculator
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.JumpRangeOverlay
import dev.evestaticmapplanner.core.jump.CapitalJumpCandidateProvider
import dev.evestaticmapplanner.core.jump.UniformGridSystemPositionIndex
import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.MapViewport
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.NavigationIntent
import dev.evestaticmapplanner.core.route.NormalNavigationOutcome
import dev.evestaticmapplanner.core.route.NormalNavigationPlanner
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteGraphBuilder
import dev.evestaticmapplanner.core.route.RouteLink
import dev.evestaticmapplanner.core.route.RouteLinkDirection
import dev.evestaticmapplanner.core.route.RouteOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebUniverseDataAdapterTest {
    @Test
    fun `maps schema 1 payload into domain repositories and nullable Official positions`() {
        val universe = WebUniverseDataAdapter.adapt(fixtureDocument())

        assertEquals(6, universe.staticRepository.load().systems.size)
        assertEquals("Test Region", universe.regionsById.getValue(REGION).name)
        assertEquals("Test Constellation", universe.constellationsById.getValue(CONSTELLATION).name)
        assertEquals(4, universe.staticData.connections.size)
        assertEquals(1, universe.ansiblex.size)
        assertEquals(WebPackAnsiblexDirectionDto.FIRST_TO_SECOND, universe.ansiblex.single().direction)
        assertNull(universe.systemsById.getValue(HIDDEN).schematicPosition)
        assertTrue(HIDDEN in universe.scene.omittedSystemIds)
        assertFalse(HIDDEN in universe.scene.nodesById)
        assertEquals(listOf(ALPHA), universe.searchRepository.searchSystems("alp").map { it.id })
        assertEquals(listOf(GAMMA), universe.searchRepository.searchSystems("amm").map { it.id })
        assertEquals(listOf(BETA), universe.searchRepository.searchSystems(BETA.toString()).map { it.id })
    }

    @Test
    fun `Web Pack route path matches direct shared Core path with Ansiblex off on and directional`() {
        val universe = WebUniverseDataAdapter.adapt(fixtureDocument())
        val directGraph = RouteGraphBuilder.build(
            universe.staticData,
            listOf(
                RouteLink(
                    connectionId = RouteConnectionId("ansiblex:forward"),
                    firstSystemId = ALPHA,
                    secondSystemId = DELTA,
                    direction = RouteLinkDirection.FIRST_TO_SECOND,
                    type = RouteEdgeType.ANSIBLEX,
                ),
            ),
        )
        val directPlanner = NormalNavigationPlanner()
        val webOff = universe.normalPlanner.calculate(
            universe.routeGraph,
            NavigationIntent(ALPHA, destinationSystemId = DELTA),
            RouteOptions(useAnsiblex = false),
        )
        val directOff = directPlanner.calculate(
            directGraph,
            NavigationIntent(ALPHA, destinationSystemId = DELTA),
            RouteOptions(useAnsiblex = false),
        )
        val offRoute = assertIs<NormalNavigationOutcome.Found>(webOff).route
        assertEquals(assertIs<NormalNavigationOutcome.Found>(directOff).route, offRoute)
        assertEquals(listOf(ALPHA, BETA, GAMMA, DELTA), offRoute.systems)

        val onRoute = assertIs<NormalNavigationOutcome.Found>(
            universe.normalPlanner.calculate(
                universe.routeGraph,
                NavigationIntent(ALPHA, destinationSystemId = DELTA),
                RouteOptions(useAnsiblex = true),
            ),
        ).route
        val directOnRoute = assertIs<NormalNavigationOutcome.Found>(
            directPlanner.calculate(
                directGraph,
                NavigationIntent(ALPHA, destinationSystemId = DELTA),
                RouteOptions(useAnsiblex = true),
            ),
        ).route
        assertEquals(directOnRoute, onRoute)
        assertEquals(listOf(ALPHA, DELTA), onRoute.systems)
        assertEquals(1, onRoute.ansiblexJumps)

        val reverse = assertIs<NormalNavigationOutcome.Found>(
            universe.normalPlanner.calculate(
                universe.routeGraph,
                NavigationIntent(DELTA, destinationSystemId = ALPHA),
                RouteOptions(useAnsiblex = true),
            ),
        ).route
        val directReverse = assertIs<NormalNavigationOutcome.Found>(
            directPlanner.calculate(
                directGraph,
                NavigationIntent(DELTA, destinationSystemId = ALPHA),
                RouteOptions(useAnsiblex = true),
            ),
        ).route
        assertEquals(directReverse, reverse)
        assertEquals(listOf(DELTA, GAMMA, BETA, ALPHA), reverse.systems)
        assertEquals(0, reverse.ansiblexJumps)
    }

    @Test
    fun `waypoints compose in order and unreachable segment is explicit`() {
        val universe = WebUniverseDataAdapter.adapt(fixtureDocument())
        val route = assertIs<NormalNavigationOutcome.Found>(
            universe.normalPlanner.calculate(
                universe.routeGraph,
                NavigationIntent(ALPHA, listOf(GAMMA), DELTA),
            ),
        ).route
        assertEquals(listOf(ALPHA, BETA, GAMMA, DELTA), route.systems)

        val failure = assertIs<NormalNavigationOutcome.SegmentFailed>(
            universe.normalPlanner.calculate(
                universe.routeGraph,
                NavigationIntent(ALPHA, listOf(GAMMA), ISOLATED),
            ),
        )
        assertEquals(GAMMA, failure.segment.fromSystemId)
        assertEquals(ISOLATED, failure.segment.toSystemId)
    }

    @Test
    fun `Capital distance route Jump Range and multi-source Coverage share Core semantics`() {
        val universe = WebUniverseDataAdapter.adapt(fixtureDocument())
        val directCandidates = CapitalJumpCandidateProvider(UniformGridSystemPositionIndex(universe.staticData.systems))
        val directCapitalEngine = dev.evestaticmapplanner.core.route.CapitalRouteEngine(directCandidates)
        val profile = JumpProfile.manual(0.6)
        assertEquals(0.5, universe.jumpCandidates.distanceLy(ALPHA, BETA)!!, 1e-9)
        assertEquals(directCandidates.distanceLy(ALPHA, BETA), universe.jumpCandidates.distanceLy(ALPHA, BETA))
        assertTrue(UniverseDistanceCalculator.isWithinRange(
            universe.systemsById.getValue(ALPHA).position,
            universe.systemsById.getValue(BETA).position,
            0.6,
        ))
        assertFalse(UniverseDistanceCalculator.isWithinRange(
            universe.systemsById.getValue(ALPHA).position,
            universe.systemsById.getValue(GAMMA).position,
            0.6,
        ))

        val route = assertIs<CapitalRouteOutcome.Found>(universe.capitalEngine.calculate(ALPHA, DELTA, profile)).route
        val directRoute = assertIs<CapitalRouteOutcome.Found>(directCapitalEngine.calculate(ALPHA, DELTA, profile)).route
        assertEquals(directRoute, route)
        assertEquals(listOf(ALPHA, BETA, GAMMA, DELTA), route.systems)
        route.legs.forEach { assertTrue(it.distanceLy <= profile.maxRangeLy) }

        val alphaRange = universe.jumpCandidates.reachableFrom(ALPHA, profile)
        val gammaRange = universe.jumpCandidates.reachableFrom(GAMMA, profile)
        assertEquals(directCandidates.reachableFrom(ALPHA, profile), alphaRange)
        assertEquals(directCandidates.reachableFrom(GAMMA, profile), gammaRange)
        assertEquals(setOf(BETA, HIDDEN), alphaRange.reachableSystemIds)
        assertEquals(setOf(BETA, DELTA), gammaRange.reachableSystemIds)
        val overlays = listOf(
            JumpRangeOverlay("a", ALPHA, profile, alphaRange.reachableSystemIds),
            JumpRangeOverlay("g", GAMMA, profile, gammaRange.reachableSystemIds),
        )
        assertEquals(2, JumpCoverageCalculator.coverageCounts(overlays).getValue(BETA))
        assertEquals(setOf(BETA), JumpCoverageCalculator.intersection(overlays))
    }

    @Test
    fun `map projection fit culling and indexed picking handle the scene`() {
        val universe = WebUniverseDataAdapter.adapt(fixtureDocument())
        val scene = universe.scene
        val size = MapSize(1_000.0, 600.0)
        val viewport = MapViewport.fit(scene.defaultFitBounds, size)
        val transform = MapTransform(viewport, size)
        val visible = scene.spatialIndex.query(transform.visibleWorldBounds())
        assertTrue(ALPHA in visible)
        assertTrue(DELTA in visible)
        val alphaPoint = scene.nodesById.getValue(ALPHA).position
        assertEquals(ALPHA, scene.spatialIndex.nearest(alphaPoint, 0.01))
        val roundTrip = transform.screenToWorld(transform.worldToScreen(alphaPoint))
        assertEquals(alphaPoint.x, roundTrip.x, 1e-9)
        assertEquals(alphaPoint.y, roundTrip.y, 1e-9)
        assertNull(scene.spatialIndex.nearest(MapPoint(99_999.0, 99_999.0), 0.01))
    }

    @Test
    fun `shared marker location distinguishes positioned unpositioned and unknown systems`() {
        val universe = WebUniverseDataAdapter.adapt(fixtureDocument())

        assertEquals(SharedMarkerLocationAvailability.POSITIONED, universe.sharedMarkerLocationAvailability(ALPHA))
        assertEquals(SharedMarkerLocationAvailability.UNPOSITIONED, universe.sharedMarkerLocationAvailability(HIDDEN))
        assertEquals(SharedMarkerLocationAvailability.UNKNOWN_SYSTEM, universe.sharedMarkerLocationAvailability(99_999_999))
    }
}

internal fun fixtureDocument(): WebPackDocumentDto {
    val ly = UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR
    fun system(id: Int, name: String, lyX: Double, official: Boolean = true) = WebPackSystemDto(
        id = id,
        name = name,
        regionId = REGION,
        constellationId = CONSTELLATION,
        securityStatus = 0.0,
        x = lyX * ly,
        y = 0.0,
        z = 0.0,
        officialX = if (official) lyX * 1_000_000_000_000_000.0 else null,
        officialY = if (official) (lyX % 0.3) * 1_000_000_000_000_000.0 else null,
        effectiveWormholeClassId = null,
    )
    return WebPackDocumentDto(
        schemaVersion = 1,
        packVersion = "fixture-1",
        desktopAppVersion = "1.7.0",
        sdeBuild = 1L,
        systems = listOf(
            system(ALPHA, "Alpha", 0.0),
            system(BETA, "Beta", 0.5),
            system(GAMMA, "Gamma", 1.0),
            system(DELTA, "Delta", 1.5),
            system(ISOLATED, "Isolated", 3.0),
            system(HIDDEN, "Hidden", 0.2, official = false),
        ),
        stargates = listOf(
            WebPackStargateDto(ALPHA, BETA),
            WebPackStargateDto(BETA, GAMMA),
            WebPackStargateDto(GAMMA, DELTA),
            WebPackStargateDto(DELTA, HIDDEN),
        ),
        regions = listOf(WebPackRegionDto(REGION, "Test Region")),
        constellations = listOf(WebPackConstellationDto(CONSTELLATION, REGION, "Test Constellation")),
        ansiblex = listOf(
            WebPackAnsiblexDto(
                id = "forward",
                firstSystemId = ALPHA,
                secondSystemId = DELTA,
                direction = WebPackAnsiblexDirectionDto.FIRST_TO_SECOND,
                displayName = "Alpha → Delta",
                enabled = true,
            ),
        ),
    )
}

private const val REGION = 10_000_001
private const val CONSTELLATION = 20_000_001
private const val ALPHA = 30_000_001
private const val BETA = 30_000_002
private const val GAMMA = 30_000_003
private const val DELTA = 30_000_004
private const val ISOLATED = 30_000_005
private const val HIDDEN = 30_000_006
