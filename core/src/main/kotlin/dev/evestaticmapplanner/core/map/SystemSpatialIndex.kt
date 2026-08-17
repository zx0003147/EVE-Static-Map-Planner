package dev.evestaticmapplanner.core.map

import kotlin.math.floor
import kotlin.math.max

class SystemSpatialIndex private constructor(
    private val positions: Map<Int, MapPoint>,
    private val cells: Map<Cell, IntArray>,
    private val cellSize: Double,
) {
    fun query(bounds: MapBounds): List<Int> {
        val minCell = cell(bounds.minX, bounds.minY)
        val maxCell = cell(bounds.maxX, bounds.maxY)
        val result = ArrayList<Int>()
        for (x in minCell.x..maxCell.x) {
            for (y in minCell.y..maxCell.y) {
                cells[Cell(x, y)]?.forEach { systemId ->
                    val position = positions.getValue(systemId)
                    if (bounds.contains(position)) result += systemId
                }
            }
        }
        return result
    }

    fun nearest(point: MapPoint, maxDistance: Double): Int? {
        require(maxDistance.isFinite() && maxDistance >= 0.0)
        val candidates = query(
            MapBounds(
                point.x - maxDistance,
                point.y - maxDistance,
                point.x + maxDistance,
                point.y + maxDistance,
            ),
        )
        val maximumSquared = maxDistance * maxDistance
        return candidates.asSequence()
            .map { it to positions.getValue(it).distanceSquaredTo(point) }
            .filter { (_, distance) -> distance <= maximumSquared }
            .minWithOrNull(compareBy<Pair<Int, Double>> { it.second }.thenBy { it.first })
            ?.first
    }

    private fun cell(x: Double, y: Double): Cell = Cell(
        floor(x / cellSize).toInt(),
        floor(y / cellSize).toInt(),
    )

    private data class Cell(val x: Int, val y: Int)

    companion object {
        fun build(positions: Map<Int, MapPoint>): SystemSpatialIndex {
            require(positions.isNotEmpty()) { "A spatial index requires at least one position" }
            val bounds = MapBounds.fromPoints(positions.values)
            val cellSize = max(max(bounds.width, bounds.height) / TARGET_CELLS_PER_AXIS, MIN_CELL_SIZE)
            val mutableCells = mutableMapOf<Cell, MutableList<Int>>()
            positions.forEach { (systemId, position) ->
                val cell = Cell(
                    floor(position.x / cellSize).toInt(),
                    floor(position.y / cellSize).toInt(),
                )
                mutableCells.getOrPut(cell, ::mutableListOf) += systemId
            }
            return SystemSpatialIndex(
                positions = positions.toMap(),
                cells = mutableCells.mapValues { (_, ids) -> ids.sorted().toIntArray() },
                cellSize = cellSize,
            )
        }
    }
}

private const val TARGET_CELLS_PER_AXIS = 128.0
private const val MIN_CELL_SIZE = 1e-6
