package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapPoint3
import dev.evestaticmapplanner.core.map.REAL_MAP_COORDINATE_UNIT_METERS
import dev.evestaticmapplanner.core.map.Real3DCanonicalProjection
import dev.evestaticmapplanner.core.map.Real3DCameraFitter
import dev.evestaticmapplanner.core.map.Real3DFrameProjectionWorkspace
import dev.evestaticmapplanner.core.map.Real3DStaticGeometry
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Real3DVisibilityTest {
    private val scene = MapSceneBuilder().build(
        StaticMapData(
            systems = listOf(
                system(1, regionId = 10, constellationId = 101),
                system(2, regionId = 10, constellationId = 101),
                system(3, regionId = 20, constellationId = 201),
                system(4, regionId = 20, constellationId = 201),
                system(5, regionId = 30, constellationId = 301),
                system(6, regionId = 40, constellationId = 401),
            ),
            connections = listOf(
                StargateConnection(1, 2), // A -> A
                StargateConnection(2, 3), // A -> B
                StargateConnection(2, 5), // A -> C
                StargateConnection(3, 4), // B -> B
                StargateConnection(4, 5), // B -> C
                StargateConnection(4, 6), // B -> D
            ),
            regions = listOf(region(10), region(20), region(30), region(40)),
            constellations = listOf(
                constellation(101, 10),
                constellation(201, 20),
                constellation(301, 30),
                constellation(401, 40),
            ),
        ),
        Real3DCanonicalProjection,
    )
    private val geometry = Real3DStaticGeometry.from(scene)

    @Test
    fun `no selection still hides every normal stargate`() {
        val result = Real3DStargateVisibility.resolve(geometry, focusedSystemId = null, filteringEnabled = true)

        assertNull(result.detailRegionIds)
        assertEquals(emptySet(), result.visibleConnectionKeys)
        assertTrue(isReal3DOrdinaryLabelRegionVisible(regionId = 40, detailRegionIds = result.detailRegionIds))
    }

    @Test
    fun `no selection uses camera target and configured ranges without a region scope`() {
        val target = geometry.nodesById.getValue(1).position
        val scope = Real3DLabelDetailScopeResolver.resolve(
            selectedSystemId = null,
            selectedDetailRegionIds = setOf(10),
            center = target,
            constellationDisplayRangeLy = 50.0,
            systemDisplayRangeLy = 10.0,
        )

        assertNull(scope.detailRegionIds)
        assertTrue(scope.isOrdinaryLabelVisible(SemanticLabelMode.CONSTELLATION, 40, offsetLy(target, 49.9)))
        assertFalse(scope.isOrdinaryLabelVisible(SemanticLabelMode.CONSTELLATION, 10, offsetLy(target, 50.1)))
        assertTrue(scope.isOrdinaryLabelVisible(SemanticLabelMode.SYSTEM, 40, offsetLy(target, 9.9)))
        assertFalse(scope.isOrdinaryLabelVisible(SemanticLabelMode.SYSTEM, 10, offsetLy(target, 10.1)))
    }

    @Test
    fun `increasing configured system range expands ordinary label visibility`() {
        val target = geometry.nodesById.getValue(1).position
        val tenLyScope = Real3DLabelDetailScopeResolver.resolve(
            selectedSystemId = null,
            selectedDetailRegionIds = null,
            center = target,
            constellationDisplayRangeLy = 50.0,
            systemDisplayRangeLy = 10.0,
        )
        val twentyLyScope = Real3DLabelDetailScopeResolver.resolve(
            selectedSystemId = null,
            selectedDetailRegionIds = null,
            center = target,
            constellationDisplayRangeLy = 50.0,
            systemDisplayRangeLy = 20.0,
        )
        val fifteenLyAway = offsetLy(target, 15.0)

        assertFalse(tenLyScope.isOrdinaryLabelVisible(SemanticLabelMode.SYSTEM, 10, fifteenLyAway))
        assertTrue(twentyLyScope.isOrdinaryLabelVisible(SemanticLabelMode.SYSTEM, 10, fifteenLyAway))
    }

    @Test
    fun `selection keeps current and adjacent region scope and also applies configured ranges`() {
        val target = geometry.nodesById.getValue(1).position
        val detailRegionIds = Real3DRegionVisibility.detailRegionIds(geometry, anchorSystemId = 1)
        val scope = Real3DLabelDetailScopeResolver.resolve(
            selectedSystemId = 1,
            selectedDetailRegionIds = detailRegionIds,
            center = target,
            constellationDisplayRangeLy = 50.0,
            systemDisplayRangeLy = 10.0,
        )

        assertEquals(setOf(10, 20, 30), scope.detailRegionIds)
        assertTrue(scope.isOrdinaryLabelVisible(SemanticLabelMode.SYSTEM, 20, offsetLy(target, 9.9)))
        assertFalse(scope.isOrdinaryLabelVisible(SemanticLabelMode.SYSTEM, 20, offsetLy(target, 10.1)))
        assertFalse(scope.isOrdinaryLabelVisible(SemanticLabelMode.SYSTEM, 40, target))
        assertTrue(scope.isOrdinaryLabelVisible(SemanticLabelMode.CONSTELLATION, 30, offsetLy(target, 49.9)))
        assertFalse(scope.isOrdinaryLabelVisible(SemanticLabelMode.CONSTELLATION, 30, offsetLy(target, 50.1)))
    }

    @Test
    fun `region labels ignore region scope and configured ranges`() {
        val scope = Real3DLabelDetailScopeResolver.resolve(
            selectedSystemId = 1,
            selectedDetailRegionIds = setOf(10),
            center = MapPoint3.Zero,
            constellationDisplayRangeLy = 0.1,
            systemDisplayRangeLy = 0.1,
        )

        assertTrue(scope.isOrdinaryLabelVisible(SemanticLabelMode.REGION_ONLY, 40, farPosition(500.0)))
    }

    @Test
    fun `detail regions include current and direct neighbors without expanding a second hop`() {
        val result = Real3DStargateVisibility.resolve(geometry, focusedSystemId = 1, filteringEnabled = true)

        assertEquals(setOf(10, 20, 30), result.detailRegionIds)
        assertFalse(40 in result.detailRegionIds.orEmpty())
    }

    @Test
    fun `stargate subgraph keeps edges whose endpoint regions are both detailed`() {
        assertEquals(
            setOf(pair(1, 2), pair(2, 3), pair(2, 5), pair(3, 4), pair(4, 5)),
            Real3DStargateVisibility.visibleConnectionKeys(geometry, focusedSystemId = 1, filteringEnabled = true),
        )
    }

    @Test
    fun `disabled filter leaves stargates and semantic labels unrestricted`() {
        val result = Real3DStargateVisibility.resolve(geometry, focusedSystemId = 1, filteringEnabled = false)

        assertNull(result.detailRegionIds)
        assertNull(result.visibleConnectionKeys)
    }

    @Test
    fun `constellation and system labels share the detail region scope`() {
        val detailRegionIds = Real3DStargateVisibility.resolve(
            geometry,
            focusedSystemId = 1,
            filteringEnabled = true,
        ).detailRegionIds

        listOf(101, 201, 301).forEach { constellationId ->
            val regionId = scene.constellationsById.getValue(constellationId).regionId
            assertTrue(isReal3DOrdinaryLabelRegionVisible(regionId, detailRegionIds))
        }
        assertFalse(
            isReal3DOrdinaryLabelRegionVisible(
                scene.constellationsById.getValue(401).regionId,
                detailRegionIds,
            ),
        )
        assertTrue(isReal3DOrdinaryLabelRegionVisible(scene.nodesById.getValue(5).system.regionId, detailRegionIds))
        assertFalse(isReal3DOrdinaryLabelRegionVisible(scene.nodesById.getValue(6).system.regionId, detailRegionIds))
    }

    @Test
    fun `edge filtering leaves systems outside detail regions projected as background nodes`() {
        val result = Real3DStargateVisibility.resolve(geometry, focusedSystemId = 1, filteringEnabled = true)
        val camera = Real3DCameraFitter.fit(geometry.fitPoints, MapSize(800.0, 600.0))
        val frame = Real3DFrameProjectionWorkspace().project(geometry, camera, MapSize(800.0, 600.0)) { edge ->
            real3DSystemPairKey(edge.firstSystemId, edge.secondSystemId) in result.visibleConnectionKeys.orEmpty()
        }

        assertEquals(setOf(1, 2, 3, 4, 5, 6), frame.projectedBySystemId.keys)
        assertFalse(isReal3DOrdinaryLabelRegionVisible(regionId = 40, detailRegionIds = result.detailRegionIds))
    }

    @Test
    fun `configured range hides a far system label without removing either system node`() {
        val target = geometry.nodesById.getValue(1).position
        val detailScope = Real3DLabelDetailScopeResolver.resolve(
            selectedSystemId = null,
            selectedDetailRegionIds = null,
            center = target,
            constellationDisplayRangeLy = 50.0,
            systemDisplayRangeLy = 10.0,
        )
        val camera = Real3DCameraFitter.fit(geometry.fitPoints, MapSize(800.0, 600.0))
        val frame = Real3DFrameProjectionWorkspace().project(geometry, camera, MapSize(800.0, 600.0))
        val near = geometry.nodesById.getValue(1)
        val far = geometry.nodesById.getValue(3)

        assertTrue(detailScope.isOrdinaryLabelVisible(SemanticLabelMode.SYSTEM, 10, near.position))
        assertFalse(detailScope.isOrdinaryLabelVisible(SemanticLabelMode.SYSTEM, 20, far.position))
        assertTrue(near.system.id in frame.projectedBySystemId)
        assertTrue(far.system.id in frame.projectedBySystemId)
    }

    private fun pair(first: Int, second: Int) = real3DSystemPairKey(first, second)

    private fun offsetLy(origin: MapPoint3, distanceLy: Double) = origin + MapPoint3(
        distanceLy * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR / REAL_MAP_COORDINATE_UNIT_METERS,
        0.0,
        0.0,
    )

    private fun farPosition(distanceLy: Double) = offsetLy(MapPoint3.Zero, distanceLy)

    private fun system(id: Int, regionId: Int, constellationId: Int) = SolarSystem(
        id = id,
        constellationId = constellationId,
        regionId = regionId,
        name = "S$id",
        securityStatus = 0.0,
        securityClass = null,
        position = UniversePosition(
            id * 10.0 * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR,
            0.0,
            0.0,
        ),
        schematicPosition = SchematicPosition(id.toDouble(), 0.0),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )

    private fun region(id: Int) = Region(id, "R$id", UniversePosition(0.0, 0.0, 0.0), null)

    private fun constellation(id: Int, regionId: Int) =
        Constellation(id, regionId, "C$id", UniversePosition(0.0, 0.0, 0.0), null)
}
