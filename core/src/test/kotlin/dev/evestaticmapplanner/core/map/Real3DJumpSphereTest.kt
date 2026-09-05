package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.JumpRangeOverlay
import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Real3DJumpSphereTest {
    @Test
    fun `sphere radius uses the canonical EVE light year conversion`() {
        val geometry = geometryAt(UniversePosition(2e15, 3e15, 4e15))
        val overlay = JumpRangeOverlay(
            id = "range",
            originSystemId = 1,
            profile = JumpProfile("custom", "Custom", 7.5),
            reachableSystemIds = setOf(1),
        )

        val sphere = Real3DJumpSphereBuilder.build(listOf(overlay), geometry).single()

        assertEquals(
            7.5 * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR / REAL_MAP_COORDINATE_UNIT_METERS,
            sphere.radius,
            1e-10,
        )
        assertEquals(MapPoint3(2.0, 3.0, 4.0), sphere.center)
        assertTrue(sphere.shellSegments.isNotEmpty())
        assertTrue(sphere.fillTriangles.isNotEmpty())
        assertTrue(sphere.fillTriangles.flatMap { listOf(it.first, it.second, it.third) }.any { it.y != sphere.center.y })
    }

    @Test
    fun `sphere shell remains projectable while camera is inside`() {
        val geometry = geometryAt(UniversePosition(0.0, 0.0, 0.0))
        val overlay = JumpRangeOverlay(
            id = "inside",
            originSystemId = 1,
            profile = JumpProfile("custom", "Custom", 2.0),
            reachableSystemIds = setOf(1),
        )
        val sphere = Real3DJumpSphereBuilder.build(listOf(overlay), geometry).single()
        val camera = Real3DCamera(target = MapPoint3(0.0, sphere.radius, 0.0), distance = sphere.radius)

        val projected = Real3DJumpSphereProjector.project(listOf(sphere), camera, MapSize(800.0, 600.0)).single()

        assertTrue(projected.shellSegments.isNotEmpty())
        assertTrue(projected.fillTrianglesFarToNear.isNotEmpty())
    }

    private fun geometryAt(position: UniversePosition): Real3DStaticGeometry {
        val data = StaticMapData(
            systems = listOf(testSystem(1, x = position.x, y = position.y, z = position.z)),
            connections = emptyList(),
            regions = listOf(Region(1, "Region", position, null)),
            constellations = listOf(Constellation(10, 1, "Constellation", position, null)),
        )
        return Real3DStaticGeometry.from(MapSceneBuilder().build(data, Real3DCanonicalProjection))
    }
}
