package dev.evestaticmapplanner.core.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Real3DMathTest {
    private val viewport = MapSize(800.0, 600.0)

    @Test
    fun `default camera faces universe Y with X horizontal and Z vertical`() {
        val camera = Real3DCamera(MapPoint3.Zero, distance = 10.0)
        val basis = camera.basis()

        assertPoint(MapPoint3(1.0, 0.0, 0.0), basis.right)
        assertPoint(MapPoint3(0.0, 0.0, 1.0), basis.up)
        assertPoint(MapPoint3(0.0, 1.0, 0.0), basis.forward)

        val projector = Real3DProjector(camera, viewport)
        val center = assertNotNull(projector.project(MapPoint3.Zero)).screen
        val right = assertNotNull(projector.project(MapPoint3(1.0, 0.0, 0.0))).screen
        val up = assertNotNull(projector.project(MapPoint3(0.0, 0.0, 1.0))).screen
        assertTrue(right.x > center.x)
        assertTrue(up.y < center.y)
    }

    @Test
    fun `yaw rotates around universe Z without roll`() {
        val camera = Real3DCamera(MapPoint3.Zero, distance = 10.0, yawDegrees = 90.0)
        assertPoint(MapPoint3(1.0, 0.0, 0.0), camera.basis().forward)
        assertPoint(MapPoint3(0.0, 0.0, 1.0), camera.basis().up)
    }

    @Test
    fun `pitch is clamped and yaw wraps`() {
        val rotated = Real3DCamera(MapPoint3.Zero, distance = 10.0).rotated(-450.0, 100.0)

        assertEquals(270.0, rotated.yawDegrees, 1e-10)
        assertEquals(MAX_REAL_3D_PITCH_DEGREES, rotated.pitchDegrees, 1e-10)
        assertTrue(rotated.basis().up.z > 0.0)
    }

    @Test
    fun `projection maps target to viewport center`() {
        val projected = assertNotNull(
            Real3DProjector(
                Real3DCamera(MapPoint3(5.0, 8.0, -2.0), distance = 20.0),
                viewport,
            ).project(MapPoint3(5.0, 8.0, -2.0)),
        )

        assertEquals(MapPoint(400.0, 300.0), projected.screen)
        assertEquals(20.0, projected.depth, 1e-10)
    }

    @Test
    fun `near plane and behind camera are rejected`() {
        val camera = Real3DCamera(MapPoint3.Zero, distance = 10.0, nearPlane = 0.5, farPlane = 20.0)
        val projector = Real3DProjector(camera, viewport)

        assertNull(projector.project(MapPoint3(0.0, -10.1, 0.0), cullToViewport = false))
        assertNull(projector.project(MapPoint3(0.0, -9.75, 0.0), cullToViewport = false))
        assertNotNull(projector.project(MapPoint3(0.0, -9.5, 0.0), cullToViewport = false))
        assertNull(projector.project(MapPoint3(0.0, 11.0, 0.0), cullToViewport = false))
    }

    @Test
    fun `segment crossing near plane is clipped instead of discarded`() {
        val camera = Real3DCamera(MapPoint3.Zero, distance = 10.0, nearPlane = 1.0)
        val segment = assertNotNull(
            Real3DProjector(camera, viewport).projectSegment(
                MapPoint3(0.0, -9.5, 0.0),
                MapPoint3(1.0, 0.0, 0.0),
            ),
        )

        assertEquals(1.0, segment.first.depth, 1e-10)
        assertTrue(segment.second.screen.x > segment.first.screen.x)
    }

    @Test
    fun `pan follows camera screen axes`() {
        val camera = Real3DCamera(MapPoint3.Zero, distance = 10.0)

        val draggedRight = camera.panned(MapPoint(100.0, 0.0), viewport)
        val draggedDown = camera.panned(MapPoint(0.0, 100.0), viewport)

        assertTrue(draggedRight.target.x < 0.0)
        assertEquals(0.0, draggedRight.target.z, 1e-10)
        assertTrue(draggedDown.target.z > 0.0)
        assertEquals(0.0, draggedDown.target.x, 1e-10)
    }

    @Test
    fun `fit preserves requested orientation and places every point inside viewport`() {
        val points = listOf(
            MapPoint3(-20.0, -8.0, -10.0),
            MapPoint3(20.0, 9.0, 10.0),
            MapPoint3(5.0, 15.0, -3.0),
        )
        val camera = Real3DCameraFitter.fit(points, viewport, yawDegrees = 32.0, pitchDegrees = -21.0)
        val projector = Real3DProjector(camera, viewport)

        assertEquals(32.0, camera.yawDegrees, 1e-10)
        assertEquals(-21.0, camera.pitchDegrees, 1e-10)
        points.forEach { assertNotNull(projector.project(it)) }
    }

    private fun assertPoint(expected: MapPoint3, actual: MapPoint3, tolerance: Double = 1e-10) {
        assertTrue(abs(expected.x - actual.x) <= tolerance, "x: expected=${expected.x}, actual=${actual.x}")
        assertTrue(abs(expected.y - actual.y) <= tolerance, "y: expected=${expected.y}, actual=${actual.y}")
        assertTrue(abs(expected.z - actual.z) <= tolerance, "z: expected=${expected.z}, actual=${actual.z}")
    }
}
