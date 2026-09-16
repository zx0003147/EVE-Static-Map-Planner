package dev.evestaticmapplanner.route

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProjectionToggleGeometryTest {
    @Test
    fun `toggle arrows are separate non-intersecting arcs on opposite canvas halves`() {
        val arrows = projectionToggleArrowGeometry(CANVAS_WIDTH, CANVAS_HEIGHT)
        val left = arrows[0]
        val right = arrows[1]
        val leftPath = sample(left)
        val rightPath = sample(right)

        assertNotEquals(PROJECTION_TOGGLE_LEFT_ARC, PROJECTION_TOGGLE_RIGHT_ARC)
        assertFalse(polylinesIntersect(leftPath, rightPath))
        assertTrue(
            minimumDistance(leftPath, rightPath) >= MINIMUM_ARC_GAP,
            "The two paths must preserve at least a 6dp visual gap.",
        )

        val leftBounds = bounds(leftPath + left.headSideA + left.headSideB)
        val rightBounds = bounds(rightPath + right.headSideA + right.headSideB)
        assertTrue(leftBounds.center.x < CANVAS_WIDTH / 2f)
        assertTrue(rightBounds.center.x > CANVAS_WIDTH / 2f)
        assertTrue(leftPath.count { it.x < CANVAS_WIDTH / 2f } >= leftPath.size * 0.8f)
        assertTrue(rightPath.count { it.x > CANVAS_WIDTH / 2f } >= rightPath.size * 0.8f)

        val leftHeadBounds = bounds(listOf(left.end, left.headSideA, left.headSideB))
        val rightHeadBounds = bounds(listOf(right.end, right.headSideA, right.headSideB))
        assertFalse(leftHeadBounds.overlaps(rightHeadBounds))
        assertTrue(leftHeadBounds.center.x < CANVAS_WIDTH / 2f)
        assertTrue(rightHeadBounds.center.x > CANVAS_WIDTH / 2f)
    }

    private fun sample(arrow: ProjectionToggleArrowGeometry): List<Offset> =
        (0..SAMPLE_COUNT).map { index ->
            val t = index.toFloat() / SAMPLE_COUNT
            val inverse = 1f - t
            arrow.start * (inverse * inverse * inverse) +
                arrow.control1 * (3f * inverse * inverse * t) +
                arrow.control2 * (3f * inverse * t * t) +
                arrow.end * (t * t * t)
        }

    private fun minimumDistance(first: List<Offset>, second: List<Offset>): Float =
        first.minOf { a -> second.minOf { b -> hypot(a.x - b.x, a.y - b.y) } }

    private fun polylinesIntersect(first: List<Offset>, second: List<Offset>): Boolean =
        first.zipWithNext().any { (a, b) ->
            second.zipWithNext().any { (c, d) -> segmentsIntersect(a, b, c, d) }
        }

    private fun segmentsIntersect(a: Offset, b: Offset, c: Offset, d: Offset): Boolean {
        fun cross(origin: Offset, first: Offset, second: Offset): Float =
            (first.x - origin.x) * (second.y - origin.y) -
                (first.y - origin.y) * (second.x - origin.x)
        val abC = cross(a, b, c)
        val abD = cross(a, b, d)
        val cdA = cross(c, d, a)
        val cdB = cross(c, d, b)
        return abC * abD < -INTERSECTION_EPSILON && cdA * cdB < -INTERSECTION_EPSILON
    }

    private fun bounds(points: List<Offset>): Rect = Rect(
        left = points.minOf(Offset::x),
        top = points.minOf(Offset::y),
        right = points.maxOf(Offset::x),
        bottom = points.maxOf(Offset::y),
    )

    private companion object {
        const val CANVAS_WIDTH = 46f
        const val CANVAS_HEIGHT = 38f
        const val SAMPLE_COUNT = 100
        const val MINIMUM_ARC_GAP = 6f
        const val INTERSECTION_EPSILON = 0.001f
    }
}
