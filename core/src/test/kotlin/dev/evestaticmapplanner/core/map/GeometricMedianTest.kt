package dev.evestaticmapplanner.core.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeometricMedianTest {
    @Test
    fun `single point is its own median`() {
        val point = MapPoint(4.0, -3.0)

        assertEquals(point, GeometricMedian.calculate(listOf(point)))
    }

    @Test
    fun `two points use their midpoint`() {
        assertPointEquals(
            MapPoint(3.0, 4.0),
            GeometricMedian.calculate(listOf(MapPoint(1.0, 2.0), MapPoint(5.0, 6.0))),
        )
    }

    @Test
    fun `symmetric group converges to its center`() {
        val points = listOf(
            MapPoint(-2.0, -2.0),
            MapPoint(-2.0, 2.0),
            MapPoint(2.0, -2.0),
            MapPoint(2.0, 2.0),
        )

        assertPointEquals(MapPoint(0.0, 0.0), GeometricMedian.calculate(points))
    }

    @Test
    fun `obvious outlier does not pull the median like a centroid`() {
        val result = GeometricMedian.calculate(
            listOf(
                MapPoint(-1.0, 0.0),
                MapPoint(0.0, -1.0),
                MapPoint(0.0, 0.0),
                MapPoint(0.0, 1.0),
                MapPoint(1.0, 0.0),
                MapPoint(1_000.0, 0.0),
            ),
        )

        assertTrue(result.x in -0.01..1.01)
        assertEquals(0.0, result.y, ABSOLUTE_TOLERANCE)
    }

    @Test
    fun `coincident points are handled without division by zero`() {
        val result = GeometricMedian.calculate(
            listOf(MapPoint(0.0, 0.0), MapPoint(0.0, 0.0), MapPoint(0.0, 0.0), MapPoint(10.0, 0.0)),
        )

        assertPointEquals(MapPoint(0.0, 0.0), result)
    }

    @Test
    fun `input order and repeated runs are deterministic`() {
        val points = listOf(
            MapPoint(-4.0, 3.0),
            MapPoint(8.0, 2.0),
            MapPoint(1.0, -6.0),
            MapPoint(2.0, 1.0),
            MapPoint(2.0, 1.0),
        )
        val expected = GeometricMedian.calculate(points)

        assertEquals(expected, GeometricMedian.calculate(points.reversed()))
        repeat(10) { assertEquals(expected, GeometricMedian.calculate(points.shuffled(java.util.Random(it.toLong())))) }
    }

    @Test
    fun `large finite coordinates produce a finite result`() {
        val result = GeometricMedian.calculate(
            listOf(
                MapPoint(1e100, 1e100),
                MapPoint(1e100 + 1e90, 1e100),
                MapPoint(1e100, 1e100 + 1e90),
            ),
        )

        assertTrue(result.x.isFinite())
        assertTrue(result.y.isFinite())
    }

    @Test
    fun `empty input is rejected`() {
        assertFailsWith<IllegalArgumentException> { GeometricMedian.calculate(emptyList()) }
    }
}

private fun assertPointEquals(expected: MapPoint, actual: MapPoint) {
    assertEquals(expected.x, actual.x, ABSOLUTE_TOLERANCE)
    assertEquals(expected.y, actual.y, ABSOLUTE_TOLERANCE)
}

private const val ABSOLUTE_TOLERANCE = 1e-8
