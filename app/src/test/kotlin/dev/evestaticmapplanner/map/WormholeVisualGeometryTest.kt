package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WormholeVisualGeometryTest {
    @Test
    fun `canonical endpoint reversal produces identical deterministic geometry`() {
        val first = MapPoint(10.0, 20.0)
        val second = MapPoint(190.0, 80.0)

        val expected = wormholeConnectionVisualGeometry(1, 2, first, second)
        val repeated = wormholeConnectionVisualGeometry(1, 2, first, second)
        val reversed = wormholeConnectionVisualGeometry(2, 1, second, first)

        assertEquals(expected, repeated)
        assertEquals(expected, reversed)
        assertEquals(WormholeVisualDetail.FULL, expected.detail)
    }

    @Test
    fun `endpoints are inset center is exact and chevrons are center symmetric`() {
        val first = MapPoint(0.0, 0.0)
        val second = MapPoint(160.0, 0.0)
        val geometry = wormholeConnectionVisualGeometry(1, 2, first, second)

        assertEquals(WORMHOLE_LINE_ENDPOINT_INSET_PX, geometry.lineStart.x)
        assertEquals(160.0 - WORMHOLE_LINE_ENDPOINT_INSET_PX, geometry.lineEnd.x)
        assertEquals(MapPoint(80.0, 0.0), geometry.center)
        assertEquals(geometry.firstHalfChevrons.size, geometry.secondHalfChevrons.size)
        geometry.firstHalfChevrons.zip(geometry.secondHalfChevrons.asReversed()).forEach { (left, right) ->
            assertEquals(geometry.center.x * 2.0, left.tip.x + right.tip.x)
            assertEquals(geometry.center.y * 2.0, left.tip.y + right.tip.y)
            assertEquals(
                distance(left.tip, left.firstTail),
                distance(right.tip, right.firstTail),
                absoluteTolerance = 1e-9,
            )
        }
    }

    @Test
    fun `short near-zero and zero distance degrade without invalid geometry`() {
        val short = wormholeConnectionVisualGeometry(1, 2, MapPoint(0.0, 0.0), MapPoint(18.0, 0.0))
        val nearZero = wormholeConnectionVisualGeometry(1, 2, MapPoint(4.0, 7.0), MapPoint(4.0 + 1e-8, 7.0))
        val zero = wormholeConnectionVisualGeometry(1, 2, MapPoint(4.0, 7.0), MapPoint(4.0, 7.0))

        listOf(short, nearZero, zero).forEach { geometry ->
            assertEquals(WormholeVisualDetail.SIMPLE, geometry.detail)
            assertTrue(geometry.firstHalfChevrons.isEmpty())
            assertTrue(geometry.secondHalfChevrons.isEmpty())
            listOf(geometry.lineStart, geometry.lineEnd, geometry.center).forEach { point ->
                assertTrue(point.x.isFinite() && point.y.isFinite())
            }
        }
        assertTrue(short.lineStart.x <= short.lineEnd.x)
        assertEquals(zero.center, zero.lineStart)
        assertEquals(zero.center, zero.lineEnd)
    }

    @Test
    fun `endpoint and center marker budgets remain deliberately small`() {
        assertTrue(WORMHOLE_ENDPOINT_OUTER_RADIUS_PX in 4f..5f)
        assertTrue(WORMHOLE_ENDPOINT_INNER_RADIUS_PX in 2f..2.5f)
        assertTrue(WORMHOLE_ENDPOINT_STROKE_WIDTH_PX in 1f..1.25f)
        assertTrue(WORMHOLE_CENTER_MARKER_RADIUS_PX in 1f..2f)
    }

    private fun distance(first: MapPoint, second: MapPoint): Double =
        hypot(first.x - second.x, first.y - second.y)
}
