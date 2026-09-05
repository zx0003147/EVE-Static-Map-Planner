package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapProjection
import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.map.Real3DCanonicalProjection
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.feature.api.OverlayEntry
import dev.evestaticmapplanner.feature.api.OverlayLayer
import dev.evestaticmapplanner.feature.api.OverlayLayerState
import dev.evestaticmapplanner.feature.api.OverlayProviderDescriptor
import dev.evestaticmapplanner.feature.api.OverlayState
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureOverlayEmblemCandidateTest {
    @Test
    fun `large component qualifies while a single-system island is suppressed`() {
        val projectedScene = scene(OfficialPosition2DProjection, includeSecondLargeComponent = false, includeTinyIsland = true)
        val presentation = FeatureOverlayPresentationBuilder.build(overlay(projectedScene.nodes.map { it.system.id }), projectedScene)

        assertEquals(2, presentation.territories.size)
        assertEquals(1, presentation.emblemCandidates.size)
        assertEquals(4, presentation.emblemCandidates.single().systemCount)
        assertTrue(presentation.emblemCandidates.single().mapArea > 0.0)
    }

    @Test
    fun `two distant major components of one owner receive separate deterministic candidates`() {
        val projectedScene = scene(OfficialPosition2DProjection, includeSecondLargeComponent = true)
        val overlay = overlay(projectedScene.nodes.map { it.system.id })

        val first = FeatureOverlayPresentationBuilder.build(overlay, projectedScene).emblemCandidates
        val second = FeatureOverlayPresentationBuilder.build(overlay, projectedScene).emblemCandidates

        assertEquals(2, first.size)
        assertEquals(first, second)
        assertEquals(2, first.map(PresentedFeatureEmblemCandidate::componentKey).toSet().size)
        assertTrue(first.all { it.systemCount == 4 })
    }

    @Test
    fun `anchor is an owned interior cell with deterministic boundary clearance in both projections`() {
        listOf(OfficialPosition2DProjection, Real3DCanonicalProjection).forEach { projection ->
            val projectedScene = scene(projection, includeSecondLargeComponent = false)
            val overlay = overlay(projectedScene.nodes.map { it.system.id })
            val snapshot = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlay, projectedScene)
            val first = FeatureOverlayPresentationBuilder.build(overlay, projectedScene).emblemCandidates.single()
            val second = FeatureOverlayPresentationBuilder.build(overlay, projectedScene).emblemCandidates.single()
            val anchorCell = FeatureTerritoryCell(
                floor((first.anchor.x - snapshot.gridOrigin.x) / snapshot.cellSize).toInt(),
                floor((first.anchor.y - snapshot.gridOrigin.y) / snapshot.cellSize).toInt(),
            )

            assertEquals(first, second)
            assertEquals(OWNER_STABLE_KEY, snapshot.ownersByCell[anchorCell])
            assertTrue(first.bounds.contains(first.anchor))
            assertTrue(first.clipTerritory.bounds.contains(first.anchor))
            assertEquals(first.bounds, first.clipTerritory.bounds)
            assertTrue(
                first.boundaryClearance >= (MIN_EMBLEM_BOUNDARY_CLEARANCE_CELLS + 0.5) * snapshot.cellSize,
            )
        }
    }

    private fun overlay(systemIds: List<Int>) = OverlayState(listOf(
        OverlayLayerState(
            provider = OverlayProviderDescriptor("test.provider", "Test Provider"),
            layer = OverlayLayer("sovereignty", "Sovereignty"),
            entries = systemIds.map { systemId ->
                OverlayEntry(
                    layerId = "sovereignty",
                    systemId = systemId,
                    title = "Alliance A",
                    value = "owner-key:alliance:42;presentation-color:#CC4D9DE0;" +
                        "presentation-emblem-key:eve-alliance:42;" +
                        "presentation-emblem-url:https://images.evetech.net/alliances/42/logo?size=256",
                )
            },
        ),
    ))

    private fun scene(
        projection: MapProjection,
        includeSecondLargeComponent: Boolean,
        includeTinyIsland: Boolean = false,
    ) = MapSceneBuilder().build(
        StaticMapData(
            systems = buildList {
                addAll(cluster(1, 0.0))
                if (includeSecondLargeComponent) addAll(cluster(5, 300.0))
                if (includeTinyIsland) add(system(9, 600.0, 0.0))
            },
            connections = buildList {
                addAll(clusterConnections(1))
                if (includeSecondLargeComponent) addAll(clusterConnections(5))
            },
        ),
        projection,
    )

    private fun cluster(firstId: Int, xOffset: Double) = listOf(
        system(firstId, xOffset, 0.0),
        system(firstId + 1, xOffset + 10.0, 0.0),
        system(firstId + 2, xOffset, 10.0),
        system(firstId + 3, xOffset + 10.0, 10.0),
    )

    private fun clusterConnections(firstId: Int) = listOf(
        StargateConnection.between(firstId, firstId + 1),
        StargateConnection.between(firstId, firstId + 2),
        StargateConnection.between(firstId + 1, firstId + 3),
        StargateConnection.between(firstId + 2, firstId + 3),
    )

    private fun system(id: Int, x: Double, y: Double) = SolarSystem(
        id = id,
        constellationId = 20_000_001,
        regionId = 10_000_001,
        name = "System $id",
        securityStatus = 0.0,
        securityClass = null,
        position = UniversePosition(x * COORDINATE_UNIT, 0.0, -y * COORDINATE_UNIT),
        schematicPosition = SchematicPosition(x * COORDINATE_UNIT, y * COORDINATE_UNIT),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )

    private companion object {
        const val OWNER_STABLE_KEY = "test.provider|sovereignty|alliance:42"
        const val COORDINATE_UNIT = 1_000_000_000_000_000.0
    }
}
