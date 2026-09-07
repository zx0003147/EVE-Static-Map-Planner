package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectionalRouteArrowsTest {
    @Test
    fun `horizontal route arrow follows from to direction and reverses with the route`() {
        val forward = arrows(MapPoint(0.0, 20.0), MapPoint(120.0, 20.0)).single()
        val reverse = arrows(MapPoint(120.0, 20.0), MapPoint(0.0, 20.0)).single()

        assertPointEquals(MapPoint(1.0, 0.0), forward.unitTangent)
        assertPointEquals(MapPoint(-1.0, 0.0), reverse.unitTangent)
        assertTrue(forward.firstTail.x < forward.tip.x)
        assertTrue(reverse.firstTail.x > reverse.tip.x)
    }

    @Test
    fun `open Chevron wings stay behind the tip and reverse without forming a filled triangle`() {
        val forward = arrows(MapPoint(0.0, 20.0), MapPoint(120.0, 20.0)).single()
        val reverse = arrows(MapPoint(120.0, 20.0), MapPoint(0.0, 20.0)).single()

        assertTrue(forward.firstTail.x < forward.tip.x)
        assertTrue(forward.secondTail.x < forward.tip.x)
        assertTrue(forward.firstTail.y > forward.tip.y)
        assertTrue(forward.secondTail.y < forward.tip.y)
        assertTrue(reverse.firstTail.x > reverse.tip.x)
        assertTrue(reverse.secondTail.x > reverse.tip.x)
        assertTrue(reverse.firstTail != reverse.secondTail)
    }

    @Test
    fun `vertical and diagonal arrows use normalized segment vectors`() {
        val vertical = arrows(MapPoint(30.0, 10.0), MapPoint(30.0, 130.0)).single()
        val diagonal = arrows(MapPoint(0.0, 0.0), MapPoint(80.0, 80.0)).single()

        assertPointEquals(MapPoint(0.0, 1.0), vertical.unitTangent)
        val component = 1.0 / sqrt(2.0)
        assertPointEquals(MapPoint(component, component), diagonal.unitTangent)
    }

    @Test
    fun `short and zero length segments produce no arrows while ordinary segments do`() {
        val short = StraightMapConnectionGeometry(
            MapPoint(0.0, 0.0),
            MapPoint(ROUTE_ARROW_MIN_SEGMENT_LENGTH_PX - 0.1, 0.0),
        )
        val zero = StraightMapConnectionGeometry(MapPoint(4.0, 9.0), MapPoint(4.0, 9.0))
        val ordinary = StraightMapConnectionGeometry(MapPoint(0.0, 0.0), MapPoint(100.0, 0.0))

        assertTrue(buildDirectionalArrowheads(short).isEmpty())
        assertTrue(buildDirectionalArrowheads(zero).isEmpty())
        assertTrue(buildDirectionalArrowheads(ordinary).isNotEmpty())
        assertTrue(buildDirectionalArrowheads(ordinary).all { arrow ->
            listOf(arrow.tip, arrow.firstTail, arrow.secondTail, arrow.unitTangent).all { point ->
                point.x.isFinite() && point.y.isFinite()
            } && arrow.lengthPx.isFinite() && arrow.halfWidthPx.isFinite()
        })
    }

    @Test
    fun `Chevron size grows with route stroke width and remains clamped`() {
        val belowMinimum = resolveDirectionalChevronSize(0.5f)
        val thin = resolveDirectionalChevronSize(3f)
        val medium = resolveDirectionalChevronSize(4f)
        val thick = resolveDirectionalChevronSize(5f)
        val aboveMaximum = resolveDirectionalChevronSize(100f)

        assertEquals(ROUTE_ARROW_MIN_LENGTH_PX, belowMinimum.lengthPx)
        assertEquals(ROUTE_ARROW_MIN_LENGTH_PX, thin.lengthPx)
        assertTrue(thin.lengthPx < medium.lengthPx)
        assertTrue(medium.lengthPx < thick.lengthPx)
        assertEquals(ROUTE_ARROW_MAX_LENGTH_PX, thick.lengthPx)
        assertEquals(ROUTE_ARROW_MAX_LENGTH_PX, aboveMaximum.lengthPx)
        listOf(thin, medium, thick).forEach { size ->
            assertTrue(size.halfWidthPx in 5.0..7.0)
        }
    }

    @Test
    fun `Mini-map scaling preserves its readable Chevron floor`() {
        val geometry = StraightMapConnectionGeometry(MapPoint(0.0, 0.0), MapPoint(100.0, 0.0))
        val normal = buildDirectionalArrowheads(
            geometry = geometry,
            routeStrokeWidth = 3f,
            scale = MINI_MAP_ROUTE_ARROW_SCALE,
            minimumReadableLengthPx = MINI_MAP_ROUTE_ARROW_MIN_LENGTH_PX,
        ).single()
        val thicker = buildDirectionalArrowheads(
            geometry = geometry,
            routeStrokeWidth = 4f,
            scale = MINI_MAP_ROUTE_ARROW_SCALE,
            minimumReadableLengthPx = MINI_MAP_ROUTE_ARROW_MIN_LENGTH_PX,
        ).single()

        assertEquals(MINI_MAP_ROUTE_ARROW_MIN_LENGTH_PX, normal.lengthPx)
        assertTrue(thicker.lengthPx > normal.lengthPx)
        assertTrue(directionalChevronHaloStrokeWidth(2.4f, MINI_MAP_ROUTE_ARROW_SCALE) > 2.4f)
    }

    @Test
    fun `very long segments cap arrow count`() {
        val geometry = StraightMapConnectionGeometry(MapPoint(0.0, 0.0), MapPoint(10_000.0, 0.0))

        assertEquals(ROUTE_ARROW_MAX_COUNT, buildDirectionalArrowheads(geometry).size)
    }

    @Test
    fun `quadratic arrows follow local tangent and reverse along the same curve`() {
        val forwardGeometry = QuadraticMapConnectionGeometry(
            start = MapPoint(0.0, 0.0),
            control = MapPoint(50.0, 70.0),
            end = MapPoint(120.0, 0.0),
            controlOffset = 70.0,
        )
        val reverseGeometry = QuadraticMapConnectionGeometry(
            start = forwardGeometry.end,
            control = forwardGeometry.control,
            end = forwardGeometry.start,
            controlOffset = forwardGeometry.controlOffset,
        )

        val forward = buildDirectionalArrowheads(forwardGeometry)
        val reverse = buildDirectionalArrowheads(reverseGeometry)

        assertEquals(2, forward.size)
        assertEquals(forward.size, reverse.size)
        forward.forEachIndexed { index, arrow ->
            val amount = (index + 1.0) / (forward.size + 1.0)
            assertPointEquals(normalizedQuadraticTangent(forwardGeometry, amount), arrow.unitTangent)
        }
        forward.indices.forEach { index ->
            val mirrored = reverse.lastIndex - index
            assertPointEquals(forward[index].tip, reverse[mirrored].tip)
            assertPointEquals(
                MapPoint(-forward[index].unitTangent.x, -forward[index].unitTangent.y),
                reverse[mirrored].unitTangent,
            )
        }
    }

    @Test
    fun `each ordered route segment points toward its own destination`() {
        val routeSegments = listOf(
            StraightMapConnectionGeometry(MapPoint(0.0, 0.0), MapPoint(120.0, 0.0)),
            StraightMapConnectionGeometry(MapPoint(120.0, 0.0), MapPoint(120.0, 120.0)),
        )

        val arrows = routeSegments.map { buildDirectionalArrowheads(it).single() }

        assertPointEquals(MapPoint(1.0, 0.0), arrows[0].unitTangent)
        assertPointEquals(MapPoint(0.0, 1.0), arrows[1].unitTangent)
    }

    private fun arrows(from: MapPoint, to: MapPoint) =
        buildDirectionalArrowheads(StraightMapConnectionGeometry(from, to))

    private fun normalizedQuadraticTangent(geometry: QuadraticMapConnectionGeometry, amount: Double): MapPoint {
        val inverse = 1.0 - amount
        val x = 2.0 * inverse * (geometry.control.x - geometry.start.x) +
            2.0 * amount * (geometry.end.x - geometry.control.x)
        val y = 2.0 * inverse * (geometry.control.y - geometry.start.y) +
            2.0 * amount * (geometry.end.y - geometry.control.y)
        val length = kotlin.math.hypot(x, y)
        return MapPoint(x / length, y / length)
    }

    private fun assertPointEquals(expected: MapPoint, actual: MapPoint) {
        assertTrue(abs(expected.x - actual.x) <= EPSILON, "Expected x=${expected.x}, actual=${actual.x}")
        assertTrue(abs(expected.y - actual.y) <= EPSILON, "Expected y=${expected.y}, actual=${actual.y}")
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}
