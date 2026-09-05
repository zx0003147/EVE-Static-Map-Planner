package dev.evestaticmapplanner.core.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeometricMedian3DTest {
    @Test
    fun `symmetric cluster median stays at center despite remote outlier`() {
        val result = GeometricMedian3D.calculate(
            listOf(
                MapPoint3(-1.0, 0.0, 0.0),
                MapPoint3(1.0, 0.0, 0.0),
                MapPoint3(0.0, -1.0, 0.0),
                MapPoint3(0.0, 1.0, 0.0),
                MapPoint3(0.0, 0.0, -1.0),
                MapPoint3(0.0, 0.0, 1.0),
                MapPoint3(1000.0, 1000.0, 1000.0),
            ),
        )

        assertTrue(result.length() < 0.5, "outlier pulled anchor too far: $result")
    }

    @Test
    fun `result is deterministic across input order`() {
        val points = listOf(MapPoint3(1.0, 2.0, 3.0), MapPoint3(-2.0, 4.0, 1.0), MapPoint3(6.0, -1.0, 2.0))
        assertEquals(GeometricMedian3D.calculate(points), GeometricMedian3D.calculate(points.reversed()))
    }
}
