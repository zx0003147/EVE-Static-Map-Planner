package dev.evestaticmapplanner.core.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapTransformTest {
    @Test
    fun `world and screen conversion round trips`() {
        val transform = MapTransform(MapViewport(MapPoint(10.0, 20.0), 2.5), MapSize(800.0, 600.0))
        val point = MapPoint(42.5, -7.0)

        val roundTrip = transform.screenToWorld(transform.worldToScreen(point))

        assertEquals(point.x, roundTrip.x, 1e-10)
        assertEquals(point.y, roundTrip.y, 1e-10)
    }

    @Test
    fun `zoom keeps the world point under the cursor fixed`() {
        val transform = MapTransform(MapViewport(MapPoint(0.0, 0.0), 2.0), MapSize(800.0, 600.0))
        val cursor = MapPoint(125.0, 440.0)
        val before = transform.screenToWorld(cursor)

        val zoomed = transform.zoomAt(cursor, factor = 1.5, minZoom = 0.1, maxZoom = 100.0)
        val after = MapTransform(zoomed, transform.canvasSize).screenToWorld(cursor)

        assertEquals(before.x, after.x, 1e-10)
        assertEquals(before.y, after.y, 1e-10)
    }

    @Test
    fun `pan converts screen delta through zoom`() {
        val viewport = MapViewport(MapPoint(10.0, 20.0), 4.0)

        assertEquals(MapPoint(5.0, 22.0), viewport.panBy(MapPoint(20.0, -8.0)).center)
    }

    @Test
    fun `fit includes bounds with padding`() {
        val bounds = MapBounds(-100.0, -50.0, 100.0, 50.0)
        val size = MapSize(1000.0, 600.0)
        val viewport = MapViewport.fit(bounds, size, paddingPx = 50.0)
        val transform = MapTransform(viewport, size)

        assertTrue(transform.visibleWorldBounds().contains(MapPoint(bounds.minX, bounds.minY)))
        assertTrue(transform.visibleWorldBounds().contains(MapPoint(bounds.maxX, bounds.maxY)))
    }

    @Test
    fun `visible bounds include requested pixel margin`() {
        val transform = MapTransform(MapViewport(MapPoint(0.0, 0.0), 2.0), MapSize(100.0, 80.0))

        assertEquals(MapBounds(-30.0, -25.0, 30.0, 25.0), transform.visibleWorldBounds(10.0))
    }
}
