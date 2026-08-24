package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.MapViewport
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.map.RealXzProjection
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapLabelPresentationTest {
    @Test
    fun `semantic modes expose only their primary hierarchy labels`() {
        val scene = sceneOf(
            SystemSpec(1, 0.0, 0.0, regionId = 1, constellationId = 10),
            SystemSpec(2, 2.0, 0.0, regionId = 1, constellationId = 10),
        )
        val transform = transform(centerX = 1.0)

        val region = present(scene, transform, SemanticLabelMode.REGION_ONLY)
        val constellation = present(scene, transform, SemanticLabelMode.CONSTELLATION)
        val system = present(scene, transform, SemanticLabelMode.SYSTEM)

        assertEquals(RegionLabelRole.PRIMARY, region.regionLabelRole)
        assertEquals(listOf(MapLabelType.REGION_PRIMARY), region.regionLabels.map { it.type })
        assertTrue(region.constellationLabels.isEmpty())
        assertTrue(region.systemLabelSystemIds.isEmpty())

        assertEquals(RegionLabelRole.BACKGROUND, constellation.regionLabelRole)
        assertEquals(listOf(MapLabelType.REGION_BACKGROUND), constellation.regionLabels.map { it.type })
        assertEquals(listOf(10), constellation.constellationLabels.map { it.groupId })
        assertTrue(constellation.systemLabelSystemIds.isEmpty())

        assertEquals(RegionLabelRole.BACKGROUND, system.regionLabelRole)
        assertTrue(system.constellationLabels.isEmpty())
        assertEquals(listOf(1, 2), system.systemLabelSystemIds)
    }

    @Test
    fun `background region uses canonical anchor when it is inside viewport`() {
        val scene = sceneOf(
            SystemSpec(1, 98.0, 0.0, regionId = 1, constellationId = 10),
            SystemSpec(2, 102.0, 0.0, regionId = 1, constellationId = 10),
        )
        val presentation = present(scene, transform(centerX = 100.0), SemanticLabelMode.SYSTEM)
        val label = presentation.regionLabels.single()

        assertEquals(RegionAnchorSource.CANONICAL, label.regionAnchorSource)
        assertEquals(scene.regions.single().canonicalAnchor, label.worldAnchor)
    }

    @Test
    fun `background region falls back to closest visible member with system id tie break`() {
        val scene = sceneOf(
            SystemSpec(1, 5.0, 0.0, regionId = 1, constellationId = 10),
            SystemSpec(2, -5.0, 0.0, regionId = 1, constellationId = 10),
            SystemSpec(3, 100.0, 0.0, regionId = 1, constellationId = 10),
            SystemSpec(4, 101.0, 0.0, regionId = 1, constellationId = 10),
            SystemSpec(5, 102.0, 0.0, regionId = 1, constellationId = 10),
        )
        val presentation = present(
            scene,
            transform(centerX = 0.0, zoom = 10.0),
            SemanticLabelMode.SYSTEM,
        )
        val label = presentation.regionLabels.single()

        assertEquals(RegionAnchorSource.VIEWPORT_MEMBER_FALLBACK, label.regionAnchorSource)
        assertEquals(scene.nodesById.getValue(1).position, label.worldAnchor)
        assertEquals(1, presentation.regionLabels.size)
    }

    @Test
    fun `background region is absent when canonical and all members are outside viewport`() {
        val scene = sceneOf(
            SystemSpec(1, 100.0, 0.0, regionId = 1, constellationId = 10),
            SystemSpec(2, 102.0, 0.0, regionId = 1, constellationId = 10),
        )
        val presentation = present(
            scene,
            transform(centerX = 0.0),
            SemanticLabelMode.SYSTEM,
        )

        assertTrue(presentation.regionLabels.isEmpty())
    }

    @Test
    fun `constellation collision uses member count after equal center distance`() {
        val scene = sceneOf(
            SystemSpec(1, -1.0, 0.0, regionId = 1, constellationId = 10),
            SystemSpec(2, 1.0, 0.0, regionId = 1, constellationId = 20),
            SystemSpec(3, 1.0, 0.0, regionId = 1, constellationId = 20),
        )
        val transform = transform(centerX = 0.0, zoom = 10.0)

        val first = present(scene, transform, SemanticLabelMode.CONSTELLATION)
        val second = present(scene, transform, SemanticLabelMode.CONSTELLATION)

        assertEquals(listOf(20), first.constellationLabels.map { it.groupId })
        assertEquals(first.constellationLabels, second.constellationLabels)
    }

    @Test
    fun `constellation collision uses id as deterministic final tie break`() {
        val scene = sceneOf(
            SystemSpec(1, -1.0, 0.0, regionId = 1, constellationId = 10),
            SystemSpec(2, 1.0, 0.0, regionId = 1, constellationId = 20),
            SystemSpec(3, 100.0, 0.0, regionId = 2, constellationId = 30),
        )

        val presentation = present(
            scene,
            transform(centerX = 0.0, zoom = 10.0),
            SemanticLabelMode.CONSTELLATION,
        )

        assertEquals(listOf(10), presentation.constellationLabels.map { it.groupId })
    }

    @Test
    fun `constellation outside viewport is culled before collision`() {
        val scene = sceneOf(
            SystemSpec(1, 0.0, 0.0, regionId = 1, constellationId = 10),
            SystemSpec(2, 100.0, 0.0, regionId = 2, constellationId = 20),
        )

        val presentation = present(
            scene,
            transform(centerX = 0.0, zoom = 10.0),
            SemanticLabelMode.CONSTELLATION,
        )

        assertEquals(listOf(10), presentation.constellationLabels.map { it.groupId })
    }

    @Test
    fun `official projection never invents hierarchy labels for omitted systems`() {
        val visible = testSystem(SystemSpec(1, 0.0, 0.0, 1, 10))
        val visibleTwo = testSystem(SystemSpec(3, 100.0, 0.0, 1, 10))
        val hidden = testSystem(SystemSpec(2, 20.0, 20.0, 2, 20), hasOfficialPosition = false)
        val data = StaticMapData(
            systems = listOf(visible, visibleTwo, hidden),
            connections = emptyList(),
            regions = listOf(region(1), region(2)),
            constellations = listOf(constellation(10, 1), constellation(20, 2)),
        )
        val scene = MapSceneBuilder().build(data, OfficialPosition2DProjection)
        val presentation = present(scene, transform(centerX = 50.0, zoom = 10.0), SemanticLabelMode.CONSTELLATION)

        assertEquals(listOf(1), scene.regions.map { it.id })
        assertEquals(listOf(10), scene.constellations.map { it.id })
        assertEquals(listOf(1), presentation.regionLabels.map { it.groupId })
        assertEquals(listOf(10), presentation.constellationLabels.map { it.groupId })
    }

    @Test
    fun `system label density guard suppresses more than seven hundred visible labels`() {
        val specs = (1..701).map { id ->
            SystemSpec(id, (id % 101).toDouble(), 0.0, regionId = 1, constellationId = 10)
        }
        val scene = sceneOf(*specs.toTypedArray())
        val presentation = present(scene, transform(centerX = 50.0, zoom = 1.0), SemanticLabelMode.SYSTEM)

        assertEquals(701, presentation.visibleSystemIds.size)
        assertTrue(presentation.systemLabelSystemIds.isEmpty())
    }

    @Test
    fun `emphasized route labels remain available outside system LOD without enabling every label`() {
        val scene = sceneOf(
            SystemSpec(1, -30.0, 0.0, regionId = 1, constellationId = 10),
            SystemSpec(2, 0.0, 0.0, regionId = 1, constellationId = 10),
            SystemSpec(3, 30.0, 0.0, regionId = 1, constellationId = 10),
        )

        val presentation = present(
            scene = scene,
            transform = transform(centerX = 0.0, zoom = 1.0),
            mode = SemanticLabelMode.REGION_ONLY,
            emphasizedSystemIds = linkedSetOf(1, 3),
        )

        assertTrue(presentation.systemLabelSystemIds.isEmpty())
        assertEquals(listOf(1, 3), presentation.emphasizedSystemLabelIds)
    }

    @Test
    fun `emphasized route labels follow the shared presentation path in both projections`() {
        val visibleInBoth = testSystem(SystemSpec(1, 0.0, 0.0, 1, 10))
        val realOnly = testSystem(SystemSpec(2, 30.0, 0.0, 1, 10), hasOfficialPosition = false)
        val officialIndexExtent = testSystem(SystemSpec(3, 60.0, 0.0, 1, 10))
        val data = StaticMapData(
            systems = listOf(visibleInBoth, realOnly, officialIndexExtent),
            connections = emptyList(),
            regions = listOf(region(1)),
            constellations = listOf(constellation(10, 1)),
        )
        val official = MapSceneBuilder().build(data, OfficialPosition2DProjection)
        val real = MapSceneBuilder().build(data, RealXzProjection)

        val officialPresentation = present(
            official,
            transform(centerX = 15.0, zoom = 2.0),
            SemanticLabelMode.CONSTELLATION,
            linkedSetOf(1, 2),
        )
        val realPresentation = present(
            real,
            transform(centerX = 15.0, zoom = 2.0),
            SemanticLabelMode.CONSTELLATION,
            linkedSetOf(1, 2),
        )

        assertEquals(listOf(1), officialPresentation.emphasizedSystemLabelIds)
        assertEquals(listOf(1, 2), realPresentation.emphasizedSystemLabelIds)
    }

    private fun present(
        scene: dev.evestaticmapplanner.core.map.ProjectedMapScene,
        transform: MapTransform,
        mode: SemanticLabelMode,
        emphasizedSystemIds: Set<Int> = emptySet(),
    ) = MapLabelPresentationBuilder.build(scene, transform, mode, FIXED_METRICS, emphasizedSystemIds)

    private fun sceneOf(vararg specs: SystemSpec): dev.evestaticmapplanner.core.map.ProjectedMapScene {
        val systems = specs.map { testSystem(it) }
        val data = StaticMapData(
            systems = systems,
            connections = emptyList(),
            regions = specs.map(SystemSpec::regionId).distinct().map(::region),
            constellations = specs.distinctBy(SystemSpec::constellationId).map {
                constellation(it.constellationId, it.regionId)
            },
        )
        return MapSceneBuilder().build(data, OfficialPosition2DProjection)
    }

    private fun transform(
        centerX: Double,
        canvasWidth: Double = 200.0,
        zoom: Double = 100.0,
    ) = MapTransform(
        viewport = MapViewport(MapPoint(centerX, 0.0), zoom),
        canvasSize = MapSize(canvasWidth, 100.0),
    )

    private data class SystemSpec(
        val id: Int,
        val x: Double,
        val y: Double,
        val regionId: Int,
        val constellationId: Int,
    )

    companion object {
        private val FIXED_METRICS = MapLabelMetricsProvider { _, type ->
            when (type) {
                MapLabelType.CONSTELLATION -> MapSize(60.0, 14.0)
                else -> MapSize(20.0, 12.0)
            }
        }

        private fun testSystem(spec: SystemSpec, hasOfficialPosition: Boolean = true) = SolarSystem(
            id = spec.id,
            constellationId = spec.constellationId,
            regionId = spec.regionId,
            name = "System ${spec.id}",
            securityStatus = 0.0,
            securityClass = null,
            position = UniversePosition(spec.x * MAP_UNIT, 0.0, -spec.y * MAP_UNIT),
            schematicPosition = if (hasOfficialPosition) {
                SchematicPosition(spec.x * MAP_UNIT, -spec.y * MAP_UNIT)
            } else {
                null
            },
            radius = 1.0,
            factionId = null,
            wormholeClassId = null,
        )

        private fun region(id: Int) = Region(
            id = id,
            name = "Region $id",
            position = UniversePosition(0.0, 0.0, 0.0),
            wormholeClassId = null,
        )

        private fun constellation(id: Int, regionId: Int) = Constellation(
            id = id,
            regionId = regionId,
            name = "Constellation $id",
            position = UniversePosition(0.0, 0.0, 0.0),
            wormholeClassId = null,
        )
    }
}

private const val MAP_UNIT = 1_000_000_000_000_000.0
