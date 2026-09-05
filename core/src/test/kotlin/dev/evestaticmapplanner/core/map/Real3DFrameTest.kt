package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Real3DFrameTest {
    @Test
    fun `frame uses true Y as depth and sorts systems far to near`() {
        val scene = MapSceneBuilder().build(testData(), Real3DCanonicalProjection)
        val geometry = Real3DStaticGeometry.from(scene)
        val camera = Real3DCameraFitter.fit(geometry.fitPoints, MapSize(800.0, 600.0))

        val frame = Real3DFrameProjector.project(geometry, camera, MapSize(800.0, 600.0))

        assertTrue(frame.nodesFarToNear.zipWithNext().all { (first, second) -> first.depth >= second.depth })
        assertEquals(frame.nodesFarToNear.size, frame.projectedBySystemId.size)
        assertTrue(frame.edges.isNotEmpty())
    }

    @Test
    fun `camera rotation changes projected screen positions`() {
        val scene = MapSceneBuilder().build(testData(), Real3DCanonicalProjection)
        val geometry = Real3DStaticGeometry.from(scene)
        val viewport = MapSize(800.0, 600.0)
        val camera = Real3DCameraFitter.fit(geometry.fitPoints, viewport)
        val initial = Real3DFrameProjector.project(geometry, camera, viewport).projectedBySystemId
        val rotated = Real3DFrameProjector.project(geometry, camera.rotated(35.0, 20.0), viewport).projectedBySystemId

        assertTrue(initial.keys.intersect(rotated.keys).any { initial.getValue(it).screen != rotated.getValue(it).screen })
    }

    @Test
    fun `legacy projection value migrates to Real 3D`() {
        assertEquals(MapProjectionId.REAL_3D, MapProjectionId.fromPersistedValue("REAL_XZ"))
        assertEquals(MapProjectionId.REAL_3D, MapProjectionId.fromPersistedValue("REAL_3D"))
        assertEquals(MapProjectionId.OFFICIAL_2D, MapProjectionId.fromPersistedValue("unknown"))
    }

    @Test
    fun `picking chooses camera-nearest system when projections overlap`() {
        val overlapData = StaticMapData(
            systems = listOf(
                testSystem(1, x = 0.0, y = 0.0, z = 0.0),
                testSystem(2, x = 0.0, y = 5e15, z = 0.0),
            ),
            connections = listOf(StargateConnection.between(1, 2)),
            regions = listOf(Region(1, "Region", UniversePosition(0.0, 0.0, 0.0), null)),
            constellations = listOf(Constellation(10, 1, "Constellation", UniversePosition(0.0, 0.0, 0.0), null)),
        )
        val geometry = Real3DStaticGeometry.from(MapSceneBuilder().build(overlapData, Real3DCanonicalProjection))

        val picked = Real3DPicker.nearestSystem(
            geometry = geometry,
            camera = Real3DCamera(MapPoint3.Zero, distance = 10.0),
            viewportSize = MapSize(800.0, 600.0),
            screenPosition = MapPoint(400.0, 300.0),
            radiusPx = 12.0,
        )

        assertEquals(1, picked)
    }

    private fun testData() = StaticMapData(
        systems = listOf(
            testSystem(1, x = -10e15, y = -8e15, z = -3e15),
            testSystem(2, x = 0.0, y = 4e15, z = 2e15),
            testSystem(3, x = 12e15, y = 10e15, z = 7e15),
        ),
        connections = listOf(StargateConnection.between(1, 2), StargateConnection.between(2, 3)),
        regions = listOf(Region(1, "Region", UniversePosition(0.0, 0.0, 0.0), null)),
        constellations = listOf(Constellation(10, 1, "Constellation", UniversePosition(0.0, 0.0, 0.0), null)),
    )
}
