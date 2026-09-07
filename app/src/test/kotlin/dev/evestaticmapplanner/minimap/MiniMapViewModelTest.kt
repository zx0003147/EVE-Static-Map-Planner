package dev.evestaticmapplanner.minimap

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapViewport
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.CapitalRouteLeg
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteEdgeId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionRoute
import dev.evestaticmapplanner.control.mission.MissionRouteId
import dev.evestaticmapplanner.feature.api.TrackedCharacterAuthorizationState
import dev.evestaticmapplanner.feature.api.TrackedCharacterLocationStatus
import dev.evestaticmapplanner.feature.api.TrackedCharacterOnlineState
import dev.evestaticmapplanner.feature.api.TrackedCharacterSnapshot
import dev.evestaticmapplanner.preferences.MiniMapFollowMode
import dev.evestaticmapplanner.preferences.MiniMapPreferences
import dev.evestaticmapplanner.preferences.MiniMapInteractionMode
import dev.evestaticmapplanner.preferences.MiniMapWindowStyle
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MiniMapViewModelTest {
    @Test
    fun `HUD presentation preferences publish and persist immediately`() {
        val persisted = mutableListOf<MiniMapPreferences>()
        val viewModel = MiniMapViewModel(persistPreferences = persisted::add)
        val updated = viewModel.state.value.preferences.copy(
            windowStyle = MiniMapWindowStyle.HUD,
            interactionMode = MiniMapInteractionMode.HUD_LOCKED,
            hudOpacity = 0.7f,
            snapToScreenEdges = false,
        )

        viewModel.updatePreferences(updated)

        assertEquals(updated, viewModel.state.value.preferences)
        assertEquals(listOf(updated), persisted)
    }

    @Test
    fun `pinned character builds shared-scene slice and aggregates same-system characters`() {
        val persisted = mutableListOf<MiniMapPreferences>()
        val viewModel = MiniMapViewModel(persistPreferences = persisted::add)
        viewModel.updateScene(scene())
        viewModel.updateCanvasSize(MapSize(400.0, 300.0))
        viewModel.updateCharacters(listOf(
            character(1, "Alpha", 1, TrackedCharacterLocationStatus.CURRENT),
            character(2, "Bravo", 1, TrackedCharacterLocationStatus.STALE),
            character(3, "Unknown", null, TrackedCharacterLocationStatus.UNKNOWN),
            character(4, "Delta", 3, TrackedCharacterLocationStatus.CURRENT),
        ))
        viewModel.setPinnedCharacter(1)

        val state = viewModel.state.value
        assertEquals(MiniMapFollowMode.PINNED, state.preferences.followMode)
        assertEquals("S1", state.followedSystemName)
        assertEquals(setOf(1, 2, 3), state.slice?.includedSystemIds)
        assertEquals(2, state.characterGroups.size)
        assertEquals(
            listOf("Alpha", "Bravo"),
            state.characterGroups.single { it.systemId == 1 }.characters.map { it.characterName },
        )
        assertTrue(state.characterGroups.single { it.systemId == 1 }.containsFollowedCharacter)
        assertEquals(listOf("Delta"), state.characterGroups.single { it.systemId == 3 }.characters.map { it.characterName })
        assertEquals(1L, persisted.last().pinnedCharacterId)
    }

    @Test
    fun `view model has no repository or database dependency`() {
        val dependencyNames = MiniMapViewModel::class.java.declaredConstructors
            .flatMap { it.parameterTypes.asList() }
            .map(Class<*>::getName)

        assertTrue(dependencyNames.none { "Repository" in it || "Database" in it || "DataSource" in it })
    }

    @Test
    fun `pinned mode ignores automatic changes until AUTO is restored`() {
        val viewModel = MiniMapViewModel()
        viewModel.updateScene(scene())
        viewModel.updateCharacters(listOf(
            character(1, "Alpha", 1, TrackedCharacterLocationStatus.CURRENT),
            character(2, "Bravo", 2, TrackedCharacterLocationStatus.CURRENT),
        ))
        viewModel.setAutomaticCharacter(2)
        viewModel.setPinnedCharacter(1)
        viewModel.setAutomaticCharacter(2)
        assertEquals(1, viewModel.state.value.followedCharacterId)

        viewModel.setFollowMode(MiniMapFollowMode.AUTO)
        assertEquals(2, viewModel.state.value.followedCharacterId)
    }

    @Test
    fun `Ansiblex toggle updates traversal and presentation immediately`() {
        val viewModel = MiniMapViewModel()
        viewModel.updateScene(scene())
        viewModel.updateCanvasSize(MapSize(400.0, 300.0))
        viewModel.updateCharacters(listOf(character(1, "Alpha", 1, TrackedCharacterLocationStatus.CURRENT)))
        viewModel.updateAnsiblexConnections(listOf(
            ansiblex("inside", 1, 2),
            ansiblex("outside", 1, 7),
            ansiblex("disabled", 2, 3, enabled = false),
        ))
        viewModel.setPinnedCharacter(1)
        viewModel.setRange(1)

        assertEquals(setOf(1, 2), viewModel.state.value.slice?.includedSystemIds)
        assertTrue(viewModel.state.value.ansiblexVisualEdges.isEmpty())
        val stargateOnlyViewport = viewModel.state.value.viewport

        viewModel.updatePreferences(viewModel.state.value.preferences.copy(includeAnsiblexEdges = true))
        assertEquals(setOf(1, 2, 7), viewModel.state.value.slice?.includedSystemIds)
        assertNotEquals(stargateOnlyViewport, viewModel.state.value.viewport)
        assertEquals(
            listOf("ansiblex:inside", "ansiblex:outside"),
            viewModel.state.value.ansiblexVisualEdges.map { it.connectionId.value },
        )
        assertEquals(1, viewModel.state.value.slice?.distanceBySystem?.get(7))

        viewModel.updatePreferences(viewModel.state.value.preferences.copy(includeAnsiblexEdges = false))
        assertEquals(setOf(1, 2), viewModel.state.value.slice?.includedSystemIds)
        assertTrue(viewModel.state.value.ansiblexVisualEdges.isEmpty())

        viewModel.updatePreferences(viewModel.state.value.preferences.copy(includeAnsiblexEdges = true))
        assertEquals(setOf(1, 2, 7), viewModel.state.value.slice?.includedSystemIds)
        assertTrue(viewModel.state.value.ansiblexVisualEdges.none { it.connectionId.value == "ansiblex:disabled" })
    }

    @Test
    fun `active route is shared updated cleared and clipped without expanding range`() {
        val viewModel = MiniMapViewModel()
        viewModel.updateScene(scene())
        viewModel.updateCanvasSize(MapSize(400.0, 300.0))
        viewModel.updateCharacters(listOf(character(1, "Alpha", 1, TrackedCharacterLocationStatus.CURRENT)))
        viewModel.setPinnedCharacter(1)
        viewModel.setRange(2)

        assertNull(viewModel.state.value.activeRoute)
        assertTrue(viewModel.state.value.visibleRouteLegs.isEmpty())

        val mixedRoute = route(
            systems = listOf(1, 2, 3, 4),
            types = listOf(RouteEdgeType.STARGATE, RouteEdgeType.ANSIBLEX, RouteEdgeType.STARGATE),
        )
        viewModel.updateActiveRoute(mixedRoute)

        assertSame(mixedRoute, viewModel.state.value.activeRoute)
        assertEquals(
            listOf(RouteEdgeType.STARGATE, RouteEdgeType.ANSIBLEX),
            viewModel.state.value.visibleRouteLegs.map { it.edge.type },
        )
        assertEquals(setOf(1, 2, 3), viewModel.state.value.slice?.includedSystemIds)

        val updatedRoute = route(
            systems = listOf(2, 3),
            types = listOf(RouteEdgeType.STARGATE),
        )
        viewModel.updateActiveRoute(updatedRoute)
        assertSame(updatedRoute, viewModel.state.value.activeRoute)
        assertEquals(listOf("route:0:2:3"), viewModel.state.value.visibleRouteLegs.map { it.edge.id.value })

        viewModel.updateActiveRoute(null)
        assertNull(viewModel.state.value.activeRoute)
        assertTrue(viewModel.state.value.visibleRouteLegs.isEmpty())
        assertEquals(setOf(1, 2, 3), viewModel.state.value.slice?.includedSystemIds)
    }

    @Test
    fun `route clipping keeps only the ordered visible middle leg`() {
        val viewModel = MiniMapViewModel()
        viewModel.updateScene(scene())
        viewModel.updateCanvasSize(MapSize(400.0, 300.0))
        viewModel.updateCharacters(listOf(character(1, "Alpha", 1, TrackedCharacterLocationStatus.CURRENT)))
        viewModel.setPinnedCharacter(1)
        viewModel.setRange(2)
        viewModel.updateActiveRoute(
            route(
                systems = listOf(7, 2, 3, 6),
                types = listOf(RouteEdgeType.STARGATE, RouteEdgeType.ANSIBLEX, RouteEdgeType.STARGATE),
            ),
        )

        val visibleLeg = viewModel.state.value.visibleRouteLegs.single()
        assertEquals(2, visibleLeg.edge.fromSystemId)
        assertEquals(3, visibleLeg.edge.toSystemId)
        assertEquals(RouteEdgeType.ANSIBLEX, visibleLeg.edge.type)
        assertEquals(setOf(1, 2, 3), viewModel.state.value.slice?.includedSystemIds)
    }

    @Test
    fun `Mission Normal and Capital routes clip ordered legs without expanding the slice`() {
        val viewModel = routeReadyViewModel()
        val hiddenNormal = missionNormal(
            "hidden-normal",
            route(listOf(6, 7), listOf(RouteEdgeType.STARGATE)),
        )
        val normalBToC = missionNormal(
            "normal-b-c",
            route(
                systems = listOf(7, 2, 3, 6),
                types = listOf(RouteEdgeType.STARGATE, RouteEdgeType.ANSIBLEX, RouteEdgeType.STARGATE),
            ),
        )
        val secondNormal = missionNormal(
            "normal-a-b",
            route(listOf(1, 2), listOf(RouteEdgeType.STARGATE)),
        )
        val hiddenCapital = missionCapital("hidden-capital", capitalRoute(listOf(6, 7)))
        val capitalAToB = missionCapital("capital-a-b", capitalRoute(listOf(7, 1, 2, 6)))

        viewModel.updateMissionRoutes(
            listOf(hiddenNormal, normalBToC, secondNormal),
            listOf(hiddenCapital, capitalAToB),
        )

        val overlays = viewModel.state.value.visibleRouteOverlays
        assertEquals(
            listOf(
                MiniMapRouteKind.MISSION_NORMAL,
                MiniMapRouteKind.MISSION_NORMAL,
                MiniMapRouteKind.MISSION_CAPITAL,
            ),
            overlays.map(MiniMapRouteOverlay::kind),
        )
        assertEquals(listOf(1, 2, 1), overlays.map(MiniMapRouteOverlay::styleIndex))
        assertEquals(listOf(2 to 3), overlays[0].segments.map { it.fromSystemId to it.toSystemId })
        assertEquals(listOf(1 to 2), overlays[1].segments.map { it.fromSystemId to it.toSystemId })
        assertEquals(listOf(1 to 2), overlays[2].segments.map { it.fromSystemId to it.toSystemId })
        assertEquals(RouteEdgeType.ANSIBLEX, overlays[0].segments.single().edgeType)
        assertEquals(setOf(1, 2, 3), viewModel.state.value.slice?.includedSystemIds)
    }

    @Test
    fun `Mission route update and clear replace overlays while the user route remains`() {
        val viewModel = routeReadyViewModel()
        viewModel.updateActiveRoute(route(listOf(1, 2), listOf(RouteEdgeType.STARGATE)))
        viewModel.updateMissionRoutes(
            normalRoutes = listOf(
                missionNormal("old-normal", route(listOf(2, 3), listOf(RouteEdgeType.STARGATE))),
            ),
            capitalRoutes = listOf(missionCapital("old-capital", capitalRoute(listOf(1, 2)))),
        )

        assertEquals(
            listOf(
                MiniMapRouteKind.USER_NORMAL,
                MiniMapRouteKind.MISSION_NORMAL,
                MiniMapRouteKind.MISSION_CAPITAL,
            ),
            viewModel.state.value.visibleRouteOverlays.map(MiniMapRouteOverlay::kind),
        )

        viewModel.updateMissionRoutes(
            normalRoutes = listOf(
                missionNormal("updated-normal", route(listOf(3, 2), listOf(RouteEdgeType.WORMHOLE))),
            ),
            capitalRoutes = emptyList(),
        )

        val updated = viewModel.state.value.visibleRouteOverlays
        assertEquals(listOf(MiniMapRouteKind.USER_NORMAL, MiniMapRouteKind.MISSION_NORMAL), updated.map { it.kind })
        assertEquals("mission:mission-updated-normal:updated-normal", updated[1].id)
        assertEquals(listOf(3 to 2), updated[1].segments.map { it.fromSystemId to it.toSystemId })
        assertTrue(updated.none { "old-" in it.id })

        viewModel.updateMissionRoutes(emptyList(), emptyList())
        assertEquals(
            listOf(MiniMapRouteKind.USER_NORMAL),
            viewModel.state.value.visibleRouteOverlays.map(MiniMapRouteOverlay::kind),
        )
        assertEquals(setOf(1, 2, 3), viewModel.state.value.slice?.includedSystemIds)

        viewModel.updateActiveRoute(null)
        assertTrue(viewModel.state.value.visibleRouteOverlays.isEmpty())
    }

    @Test
    fun `range resize show hide and viewport remain independent from main map`() {
        val viewModel = MiniMapViewModel()
        viewModel.updateScene(scene())
        viewModel.updateCharacters(listOf(character(1, "Alpha", 1, TrackedCharacterLocationStatus.CURRENT)))
        viewModel.setPinnedCharacter(1)
        viewModel.updateCanvasSize(MapSize(300.0, 250.0))
        val mainViewport = MapViewport(MapPoint(999.0, 999.0), 7.0)
        val firstMiniViewport = viewModel.state.value.viewport

        viewModel.setRange(5)
        viewModel.updateCanvasSize(MapSize(600.0, 500.0))
        viewModel.panBy(MapPoint(20.0, 10.0))
        viewModel.setEnabled(true)

        assertEquals(5, viewModel.state.value.preferences.stargateHops)
        assertNotEquals(firstMiniViewport, viewModel.state.value.viewport)
        assertEquals(MapViewport(MapPoint(999.0, 999.0), 7.0), mainViewport)
        assertTrue(viewModel.state.value.preferences.enabled)
        viewModel.setEnabled(false)
        assertTrue(!viewModel.state.value.preferences.enabled)
    }

    @Test
    fun `restored Preferences update an open Mini-map range and Ansiblex presentation immediately`() {
        val viewModel = MiniMapViewModel()
        viewModel.updateScene(scene())
        viewModel.updateCanvasSize(MapSize(400.0, 300.0))
        viewModel.updateCharacters(listOf(character(1, "Alpha", 1, TrackedCharacterLocationStatus.CURRENT)))
        viewModel.updateAnsiblexConnections(listOf(ansiblex("inside", 1, 2)))
        viewModel.setAutomaticCharacter(1)

        viewModel.restorePreferences(
            MiniMapPreferences(
                enabled = true,
                stargateHops = 1,
                followMode = MiniMapFollowMode.AUTO,
                includeAnsiblexEdges = true,
            ),
        )

        assertEquals(1, viewModel.state.value.slice?.maxHops)
        assertEquals(setOf(1, 2), viewModel.state.value.slice?.includedSystemIds)
        assertEquals(listOf("ansiblex:inside"), viewModel.state.value.ansiblexVisualEdges.map { it.connectionId.value })
    }

    @Test
    fun `unknown and removed followed character never receives a guessed location`() {
        val viewModel = MiniMapViewModel()
        viewModel.updateScene(scene())
        viewModel.updateCanvasSize(MapSize(400.0, 300.0))
        viewModel.updateCharacters(listOf(character(1, "Alpha", null, TrackedCharacterLocationStatus.UNKNOWN)))
        viewModel.setPinnedCharacter(1)
        assertNull(viewModel.state.value.slice)
        assertTrue(viewModel.state.value.diagnostic.orEmpty().contains("Location unavailable"))

        viewModel.updateCharacters(emptyList())
        assertNull(viewModel.state.value.slice)
        assertEquals(
            "No tracked characters\nConnect a character in ESI Pack first.",
            viewModel.state.value.diagnostic,
        )
    }

    private fun scene() = MapSceneBuilder().build(
        StaticMapData(
            systems = (1..7).map(::system),
            connections = (1..6).map { StargateConnection.between(it, it + 1) },
            regions = listOf(Region(1, "Region", UniversePosition(0.0, 0.0, 0.0), null)),
            constellations = listOf(Constellation(1, 1, "Constellation", UniversePosition(0.0, 0.0, 0.0), null)),
        ),
        OfficialPosition2DProjection,
    )

    private fun system(id: Int) = SolarSystem(
        id, 1, 1, "S$id", 0.1, null,
        UniversePosition(id.toDouble(), 0.0, 0.0),
        SchematicPosition(id.toDouble(), 0.0),
        1.0, null, null,
    )

    private fun character(
        id: Long,
        name: String,
        systemId: Int?,
        status: TrackedCharacterLocationStatus,
    ) = TrackedCharacterSnapshot(
        id,
        name,
        TrackedCharacterAuthorizationState.CONNECTED,
        true,
        systemId,
        status,
        systemId?.let { Instant.EPOCH },
        systemId?.let { Instant.EPOCH },
        systemId?.let { Instant.EPOCH },
        TrackedCharacterOnlineState.UNKNOWN,
        null,
        null,
    )

    private fun ansiblex(id: String, first: Int, second: Int, enabled: Boolean = true) = AnsiblexConnection(
        id,
        first,
        second,
        AnsiblexDirection.BIDIRECTIONAL,
        null,
        null,
        AnsiblexSource.MANUAL,
        null,
        enabled,
        Instant.EPOCH,
        Instant.EPOCH,
    )

    private fun route(systems: List<Int>, types: List<RouteEdgeType>): RouteResult {
        val edges = types.mapIndexed { index, type ->
            val from = systems[index]
            val to = systems[index + 1]
            RouteEdge(
                RouteEdgeId("route:$index:$from:$to"),
                RouteConnectionId("route:$index"),
                from,
                to,
                type,
            )
        }
        return RouteResult(systems.first(), systems.last(), systems, edges)
    }

    private fun routeReadyViewModel() = MiniMapViewModel().also { viewModel ->
        viewModel.updateScene(scene())
        viewModel.updateCanvasSize(MapSize(400.0, 300.0))
        viewModel.updateCharacters(listOf(character(1, "Alpha", 1, TrackedCharacterLocationStatus.CURRENT)))
        viewModel.setPinnedCharacter(1)
        viewModel.setRange(2)
    }

    private fun missionNormal(id: String, route: RouteResult) = MissionRoute.Normal(
        missionId = MissionId("mission-$id"),
        routeId = MissionRouteId(id),
        route = route,
    )

    private fun missionCapital(id: String, route: CapitalRouteResult) = MissionRoute.Capital(
        missionId = MissionId("mission-$id"),
        routeId = MissionRouteId(id),
        route = route,
    )

    private fun capitalRoute(systems: List<Int>): CapitalRouteResult = CapitalRouteResult(
        startSystemId = systems.first(),
        destinationSystemId = systems.last(),
        profile = JumpProfile("test", "Test", 10.0),
        systems = systems,
        legs = systems.zipWithNext { from, to -> CapitalRouteLeg(from, to, 0.0) },
    )
}
