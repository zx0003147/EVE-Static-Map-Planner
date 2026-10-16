package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NavigationRouteRenderingTest {
    @Test
    fun `crawling route parameters match Spai source`() {
        assertEquals(6f, NAVIGATION_ROUTE_FLOW_DASH_PX)
        assertEquals(12f, NAVIGATION_ROUTE_FLOW_PERIOD_PX)
        assertEquals(2f, NAVIGATION_ROUTE_FLOW_STROKE_WIDTH_PX)
        assertEquals(28f, NAVIGATION_ROUTE_FLOW_SPEED_PX_PER_SECOND)
        assertEquals(14, NAVIGATION_ROUTE_CURVE_SEGMENTS)
    }

    @Test
    fun `increasing phase moves dashes from route start toward route end`() {
        val atStart = navigationRouteDashSpans(lengthPx = 30f, phasePx = 0f)
        val afterThreePixels = navigationRouteDashSpans(lengthPx = 30f, phasePx = 3f)

        assertEquals(NavigationRouteDashSpan(0f, 6f), atStart.first())
        assertEquals(NavigationRouteDashSpan(3f, 9f), afterThreePixels.first())
        assertTrue(afterThreePixels.first().startPx > atStart.first().startPx)
    }

    @Test
    fun `animation clock advances at source speed and loops by one period`() {
        val start = 9_000_000_000L

        assertEquals(0f, navigationRouteAnimationPhasePx(start, start))
        assertEquals(2f, navigationRouteAnimationPhasePx(start, start + 500_000_000L))
        assertEquals(4f, navigationRouteAnimationPhasePx(start, start + 1_000_000_000L))
        assertEquals(0f, navigationRouteAnimationPhasePx(start, start + 3_000_000_000L))
    }

    @Test
    fun `phase cursor continues across route legs instead of resetting`() {
        val cursor = NavigationRoutePhaseCursor(routePhasePx = 5f)

        assertEquals(5f, cursor.currentPhasePx)
        cursor.advance(17f)
        assertEquals(-12f, cursor.currentPhasePx)
        assertEquals(
            listOf(NavigationRouteDashSpan(0f, 6f), NavigationRouteDashSpan(12f, 18f)),
            navigationRouteDashSpans(lengthPx = 20f, phasePx = cursor.currentPhasePx),
        )
        cursor.advance(20f)
        assertEquals(-32f, cursor.currentPhasePx)
        assertEquals(37f, cursor.walkedDistancePx)
    }

    @Test
    fun `quadratic route uses sampled arc length when carrying phase`() {
        val curve = QuadraticMapConnectionGeometry(
            start = MapPoint(0.0, 0.0),
            control = MapPoint(50.0, 80.0),
            end = MapPoint(100.0, 0.0),
            controlOffset = 80.0,
        )
        val arcLength = navigationRouteGeometryLength(curve)
        val cursor = NavigationRoutePhaseCursor(routePhasePx = 7f)
        cursor.advance(arcLength)

        assertTrue(arcLength > 100f, "phase must use sampled arc length rather than the straight chord")
        assertEquals(7f - arcLength, cursor.currentPhasePx)
    }

    @Test
    fun `quadratic sampling subtracts walked arc length at every segment`() {
        val curve = QuadraticMapConnectionGeometry(
            start = MapPoint(0.0, 0.0),
            control = MapPoint(40.0, 70.0),
            end = MapPoint(90.0, 10.0),
            controlOffset = 70.0,
        )
        val phases = mutableListOf<Float>()
        var walkedPx = 0f

        forEachNavigationRouteLinearSegment(curve) { start, end ->
            phases += 9f - walkedPx
            walkedPx += hypot((end.x - start.x).toFloat(), (end.y - start.y).toFloat())
        }

        assertEquals(NAVIGATION_ROUTE_CURVE_SEGMENTS, phases.size)
        assertEquals(9f, phases.first())
        assertTrue(phases.zipWithNext().all { (first, second) -> second < first })
        assertEquals(navigationRouteGeometryLength(curve), walkedPx, absoluteTolerance = 0.0001f)
    }

    @Test
    fun `straight and curved geometries share the same whole-route phase cursor`() {
        val straight = StraightMapConnectionGeometry(MapPoint(0.0, 0.0), MapPoint(30.0, 0.0))
        val curve = QuadraticMapConnectionGeometry(
            start = MapPoint(30.0, 0.0),
            control = MapPoint(45.0, 20.0),
            end = MapPoint(60.0, 0.0),
            controlOffset = 20.0,
        )
        val cursor = NavigationRoutePhaseCursor(routePhasePx = 4f)
        cursor.advance(navigationRouteGeometryLength(straight))
        val curveStartPhase = cursor.currentPhasePx
        cursor.advance(navigationRouteGeometryLength(curve))

        assertEquals(-26f, curveStartPhase)
        assertEquals(
            4f - navigationRouteGeometryLength(straight) - navigationRouteGeometryLength(curve),
            cursor.currentPhasePx,
            absoluteTolerance = 0.0001f,
        )
    }

    @Test
    fun `invalid animation inputs fail fast`() {
        assertFailsWith<IllegalArgumentException> { NavigationRoutePhaseCursor(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { NavigationRoutePhaseCursor(0f).advance(-1f) }
        assertFailsWith<IllegalArgumentException> { navigationRouteDashSpans(10f, Float.NaN) }
    }
}
