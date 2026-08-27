package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapProjection
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.map.RealXzProjection
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.route.RouteEdgeType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MapConnectionGeometryTest {
    @Test
    fun `standalone Ansiblex geometry is a curved quadratic with exact anchors`() {
        val first = MapPoint(20.0, 30.0)
        val second = MapPoint(140.0, 90.0)

        val geometry = ansiblexConnectionGeometry(30_000_001, 30_000_002, first, second)

        assertEquals(first, geometry.start)
        assertEquals(second, geometry.end)
        assertTrue(abs(crossProduct(geometry.start, geometry.end, geometry.control)) > 1e-6)
    }

    @Test
    fun `Ansiblex curvature is deterministic and canonical reversal does not flip the curve`() {
        val first = MapPoint(-35.0, 18.0)
        val second = MapPoint(115.0, 73.0)

        val expected = ansiblexConnectionGeometry(30_000_123, 30_000_987, first, second)
        val repeated = ansiblexConnectionGeometry(30_000_123, 30_000_987, first, second)
        val reversed = ansiblexConnectionGeometry(30_000_987, 30_000_123, second, first)

        assertEquals(expected, repeated)
        assertEquals(expected.control, reversed.control)
        assertEquals(expected.controlOffset, reversed.controlOffset)
        assertEquals(expected.start, reversed.end)
        assertEquals(expected.end, reversed.start)
    }

    @Test
    fun `active route Ansiblex reuses the exact base Jump Bridge curve`() {
        val first = MapPoint(13.0, -21.0)
        val second = MapPoint(177.0, 94.0)
        val base = ansiblexConnectionGeometry(30_000_100, 30_000_200, first, second)

        val route = assertIs<QuadraticMapConnectionGeometry>(
            activeRouteConnectionGeometry(
                firstSystemId = 30_000_100,
                secondSystemId = 30_000_200,
                edgeType = RouteEdgeType.ANSIBLEX,
                start = first,
                end = second,
            ),
        )
        val reversedRoute = assertIs<QuadraticMapConnectionGeometry>(
            activeRouteConnectionGeometry(
                firstSystemId = 30_000_200,
                secondSystemId = 30_000_100,
                edgeType = RouteEdgeType.ANSIBLEX,
                start = second,
                end = first,
            ),
        )

        assertEquals(base, route)
        assertEquals(base.control, reversedRoute.control)
        assertEquals(base.start, reversedRoute.end)
        assertEquals(base.end, reversedRoute.start)
        assertTrue(abs(crossProduct(route.start, route.end, route.control)) > 1e-6)
    }

    @Test
    fun `Ansiblex curvature is proportional and bounded for short and long bridges`() {
        val short = ansiblexConnectionGeometry(1, 2, MapPoint(0.0, 0.0), MapPoint(5.0, 0.0))
        val proportional = ansiblexConnectionGeometry(1, 2, MapPoint(0.0, 0.0), MapPoint(100.0, 0.0))
        val long = ansiblexConnectionGeometry(1, 2, MapPoint(0.0, 0.0), MapPoint(2_000.0, 0.0))

        assertEquals(ANSIBLEX_CURVE_MIN_OFFSET_PX, short.controlOffset)
        assertEquals(100.0 * ANSIBLEX_CURVE_FACTOR, proportional.controlOffset)
        assertEquals(ANSIBLEX_CURVE_MAX_OFFSET_PX, long.controlOffset)
    }

    @Test
    fun `both supported projections produce finite deterministic curved geometry at projected anchors`() {
        val first = system(
            id = 30_000_001,
            realX = 8.0,
            realZ = -14.0,
            schematicX = 12.0,
            schematicY = 6.0,
        )
        val second = system(
            id = 30_000_002,
            realX = 91.0,
            realZ = -47.0,
            schematicX = 74.0,
            schematicY = 58.0,
        )

        listOf(OfficialPosition2DProjection, RealXzProjection).forEach { projection ->
            assertProjectionCurve(projection, first, second)
        }
    }

    @Test
    fun `stargates and active route legs retain straight endpoint geometry`() {
        val start = MapPoint(4.0, 7.0)
        val end = MapPoint(88.0, 53.0)

        val stargate = stargateConnectionGeometry(start, end)
        val route = activeRouteConnectionGeometry(1, 2, RouteEdgeType.STARGATE, start, end)

        assertIs<StraightMapConnectionGeometry>(stargate)
        assertEquals(start, stargate.start)
        assertEquals(end, stargate.end)
        assertIs<StraightMapConnectionGeometry>(route)
        assertEquals(start, route.start)
        assertEquals(end, route.end)
    }

    private fun assertProjectionCurve(
        projection: MapProjection,
        first: SolarSystem,
        second: SolarSystem,
    ) {
        val projectedFirst = requireNotNull(projection.project(first))
        val projectedSecond = requireNotNull(projection.project(second))
        val geometry = ansiblexConnectionGeometry(first.id, second.id, projectedFirst, projectedSecond)
        val routeGeometry = assertIs<QuadraticMapConnectionGeometry>(
            activeRouteConnectionGeometry(
                first.id,
                second.id,
                RouteEdgeType.ANSIBLEX,
                projectedFirst,
                projectedSecond,
            ),
        )

        assertEquals(projectedFirst, geometry.start)
        assertEquals(projectedSecond, geometry.end)
        assertTrue(geometry.control.x.isFinite())
        assertTrue(geometry.control.y.isFinite())
        assertTrue(abs(crossProduct(geometry.start, geometry.end, geometry.control)) > 1e-6)
        assertEquals(geometry, routeGeometry)
        assertEquals(
            geometry,
            ansiblexConnectionGeometry(first.id, second.id, projectedFirst, projectedSecond),
        )
    }

    private fun crossProduct(start: MapPoint, end: MapPoint, point: MapPoint): Double =
        (end.x - start.x) * (point.y - start.y) - (end.y - start.y) * (point.x - start.x)

    private fun system(
        id: Int,
        realX: Double,
        realZ: Double,
        schematicX: Double,
        schematicY: Double,
    ) = SolarSystem(
        id = id,
        constellationId = 20_000_001,
        regionId = 10_000_001,
        name = "System $id",
        securityStatus = 0.0,
        securityClass = null,
        position = UniversePosition(
            x = realX * MAP_COORDINATE_UNIT,
            y = 0.0,
            z = realZ * MAP_COORDINATE_UNIT,
        ),
        schematicPosition = SchematicPosition(
            x = schematicX * MAP_COORDINATE_UNIT,
            y = schematicY * MAP_COORDINATE_UNIT,
        ),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )

    private companion object {
        const val MAP_COORDINATE_UNIT = 1_000_000_000_000_000.0
    }
}
