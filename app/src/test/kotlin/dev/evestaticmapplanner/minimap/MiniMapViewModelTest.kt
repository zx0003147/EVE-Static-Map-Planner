package dev.evestaticmapplanner.minimap

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapViewport
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
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
    fun `Ansiblex is opt-in visual-only and never expands the Stargate neighborhood`() {
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

        viewModel.updatePreferences(viewModel.state.value.preferences.copy(includeAnsiblexEdges = true))
        assertEquals(setOf(1, 2), viewModel.state.value.slice?.includedSystemIds)
        assertEquals(listOf("ansiblex:inside"), viewModel.state.value.ansiblexVisualEdges.map { it.connectionId.value })
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
        assertEquals("Followed character is unavailable", viewModel.state.value.diagnostic)
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
}
