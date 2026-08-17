package dev.evestaticmapplanner.core.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SystemSpatialIndexTest {
    private val index = SystemSpatialIndex.build(
        mapOf(
            1 to MapPoint(0.0, 0.0),
            2 to MapPoint(10.0, 0.0),
            3 to MapPoint(30.0, 30.0),
        ),
    )

    @Test
    fun `range query returns only points inside bounds`() {
        assertEquals(listOf(1, 2), index.query(MapBounds(-1.0, -1.0, 11.0, 1.0)).sorted())
    }

    @Test
    fun `nearest returns closest point inside radius`() {
        assertEquals(2, index.nearest(MapPoint(8.0, 1.0), maxDistance = 3.0))
    }

    @Test
    fun `nearest returns null outside radius`() {
        assertNull(index.nearest(MapPoint(20.0, 0.0), maxDistance = 2.0))
    }

    @Test
    fun `nearest resolves equal distance using stable system id`() {
        assertEquals(1, index.nearest(MapPoint(5.0, 0.0), maxDistance = 6.0))
    }

    @Test
    fun `index handles a full-universe sized data set`() {
        val positions = (1..8_490).associateWith { id ->
            MapPoint((id % 100).toDouble(), (id / 100).toDouble())
        }
        val fullIndex = SystemSpatialIndex.build(positions)

        assertEquals(8_490, fullIndex.query(MapBounds(-1.0, -1.0, 101.0, 86.0)).size)
        assertEquals(8_490, fullIndex.nearest(positions.getValue(8_490), 0.01))
    }
}
