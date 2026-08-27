package dev.evestaticmapplanner.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StableEmblemAnchorSelectionTest {
    @Test
    fun `rectangle anchor is its visual center`() {
        val component = rectangle(0..10, 0..8)

        val selected = select(component)

        assertEquals(GridCell(5, 4), selected?.cell)
    }

    @Test
    fun `concave component remains interior and reasonably central`() {
        val component = rectangle(0..12, 0..10).filterTo(linkedSetOf()) { cell ->
            !(cell.x >= 8 && cell.y >= 5)
        }

        val selected = assertNotNull(select(component))
        val centroid = centroid(component)

        assertTrue(selected.cell in component)
        assertTrue(selected.clearanceCells >= MIN_EMBLEM_BOUNDARY_CLEARANCE_CELLS)
        assertTrue(distanceSquared(selected.cell, centroid) < 14.0)
    }

    @Test
    fun `donut anchor never lands in its hole`() {
        val hole = rectangle(5..7, 4..6)
        val component = rectangle(0..12, 0..10).filterTo(linkedSetOf()) { it !in hole }

        val selected = assertNotNull(select(component))

        assertTrue(selected.cell in component)
        assertTrue(selected.cell !in hole)
        assertTrue(selected.clearanceCells >= MIN_EMBLEM_BOUNDARY_CLEARANCE_CELLS)
    }

    @Test
    fun `L-shaped component favors a central valid interior instead of an extreme pocket`() {
        val component = linkedSetOf<GridCell>().apply {
            addAll(rectangle(0..5, 0..13))
            addAll(rectangle(0..13, 0..5))
        }

        val selected = assertNotNull(select(component))
        val centroid = centroid(component)

        assertTrue(selected.cell in component)
        assertTrue(selected.clearanceCells >= MIN_EMBLEM_BOUNDARY_CLEARANCE_CELLS)
        assertTrue(distanceSquared(selected.cell, centroid) < 12.0)
    }

    @Test
    fun `narrow component is suppressed when no safe clearance exists`() {
        val component = rectangle(0..14, 0..2)

        assertNull(select(component))
    }

    @Test
    fun `centroid beneath a region label chooses another stable interior cell`() {
        val component = rectangle(0..14, 0..10)
        val exclusion = StableRegionLabelExclusion(7.5, 5.5, 3.0, 2.0)

        val first = assertNotNull(select(component, listOf(exclusion)))
        val second = assertNotNull(select(component, listOf(exclusion)))

        assertEquals(first, second)
        assertTrue(!exclusion.contains(first.cell))
    }

    @Test
    fun `region label near edge leaves normal central anchor unchanged`() {
        val component = rectangle(0..14, 0..10)
        val normal = assertNotNull(select(component))
        val edgeLabel = StableRegionLabelExclusion(0.5, 0.5, 1.0, 1.0)

        assertEquals(normal, select(component, listOf(edgeLabel)))
    }

    @Test
    fun `component is suppressed when region label covers every safe alternative`() {
        val component = rectangle(0..6, 0..6)
        val exclusion = StableRegionLabelExclusion(3.5, 3.5, 4.0, 4.0)

        assertNull(select(component, listOf(exclusion)))
    }

    private fun select(
        component: Set<GridCell>,
        exclusions: List<StableRegionLabelExclusion> = emptyList(),
    ): InteriorCell? = selectStableEmblemAnchor(
        component = component,
        distanceByCell = boundaryDistanceByCell(component),
        labelExclusions = exclusions,
    )

    private fun rectangle(xs: IntRange, ys: IntRange): Set<GridCell> = buildSet {
        xs.forEach { x -> ys.forEach { y -> add(GridCell(x, y)) } }
    }

    private fun centroid(component: Set<GridCell>): Pair<Double, Double> =
        component.sumOf { it.x + 0.5 } / component.size to component.sumOf { it.y + 0.5 } / component.size

    private fun distanceSquared(cell: GridCell, centroid: Pair<Double, Double>): Double {
        val dx = cell.x + 0.5 - centroid.first
        val dy = cell.y + 0.5 - centroid.second
        return dx * dx + dy * dy
    }
}
