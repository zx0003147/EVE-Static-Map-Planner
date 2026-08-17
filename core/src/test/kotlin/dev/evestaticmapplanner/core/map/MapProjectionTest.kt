package dev.evestaticmapplanner.core.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapProjectionTest {
    @Test
    fun `official projection uses schematic coordinates and flips vertical axis`() {
        val system = testSystem(1, x2d = 2e15, y2d = 3e15)

        assertEquals(MapPoint(2.0, -3.0), OfficialPosition2DProjection.project(system))
    }

    @Test
    fun `official projection omits a system without position2d`() {
        assertNull(OfficialPosition2DProjection.project(testSystem(1, x2d = null, y2d = null)))
    }

    @Test
    fun `real projection uses x and z and retains large coordinate separation`() {
        val system = testSystem(1, x = 8.239e18, z = -1.0045e19)

        assertEquals(MapPoint(8239.0, 10045.0), RealXzProjection.project(system))
    }

    @Test
    fun `projection lookup returns both supported implementations`() {
        assertEquals(MapProjectionId.OFFICIAL_2D, projectionFor(MapProjectionId.OFFICIAL_2D).id)
        assertEquals(MapProjectionId.REAL_XZ, projectionFor(MapProjectionId.REAL_XZ).id)
    }
}
