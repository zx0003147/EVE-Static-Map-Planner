package dev.evestaticmapplanner.core.jump

import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.UniversePosition
import kotlin.math.ceil
import kotlin.math.floor

enum class PositionQueryStrategy {
    UNIFORM_GRID,
    LINEAR_SCAN,
}

data class PositionQueryCandidates(
    val systemIds: Set<Int>,
    val strategy: PositionQueryStrategy,
)

interface SystemPositionIndex {
    val systemsById: Map<Int, SolarSystem>

    fun candidates(origin: UniversePosition, maxRangeLy: Double): PositionQueryCandidates
}

class LinearSystemPositionIndex(
    systems: Collection<SolarSystem>,
) : SystemPositionIndex {
    override val systemsById: Map<Int, SolarSystem> = systems.associateBy(SolarSystem::id)

    override fun candidates(origin: UniversePosition, maxRangeLy: Double): PositionQueryCandidates =
        PositionQueryCandidates(systemsById.keys, PositionQueryStrategy.LINEAR_SCAN)
}

class UniformGridSystemPositionIndex(
    systems: Collection<SolarSystem>,
    val cellSizeLy: Double = DEFAULT_CELL_SIZE_LY,
) : SystemPositionIndex {
    override val systemsById: Map<Int, SolarSystem> = systems.associateBy(SolarSystem::id)
    private val cellSizeMeters: Double
    private val cells: Map<GridCell, List<Int>>

    init {
        require(systemsById.size == systems.size) { "Solar system IDs must be unique" }
        require(cellSizeLy.isFinite() && cellSizeLy > 0.0) { "Grid cell size must be finite and positive" }
        cellSizeMeters = cellSizeLy * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR
        cells = systemsById.values.groupBy(
            keySelector = { cellFor(it.position) },
            valueTransform = SolarSystem::id,
        )
    }

    override fun candidates(origin: UniversePosition, maxRangeLy: Double): PositionQueryCandidates {
        require(maxRangeLy.isFinite() && maxRangeLy >= 0.0) { "Jump range must be finite and non-negative" }
        val cellRadius = ceil(maxRangeLy / cellSizeLy).toInt()
        val cellsPerAxis = cellRadius.toLong() * 2L + 1L
        val cellsToVisit = safeCube(cellsPerAxis)
        if (cellsToVisit >= systemsById.size.toLong()) {
            return PositionQueryCandidates(systemsById.keys, PositionQueryStrategy.LINEAR_SCAN)
        }

        val originCell = cellFor(origin)
        val candidates = linkedSetOf<Int>()
        for (x in originCell.x - cellRadius..originCell.x + cellRadius) {
            for (y in originCell.y - cellRadius..originCell.y + cellRadius) {
                for (z in originCell.z - cellRadius..originCell.z + cellRadius) {
                    cells[GridCell(x, y, z)]?.let(candidates::addAll)
                }
            }
        }
        return PositionQueryCandidates(candidates, PositionQueryStrategy.UNIFORM_GRID)
    }

    private fun cellFor(position: UniversePosition): GridCell = GridCell(
        x = floor(position.x / cellSizeMeters).toLong(),
        y = floor(position.y / cellSizeMeters).toLong(),
        z = floor(position.z / cellSizeMeters).toLong(),
    )

    private fun safeCube(value: Long): Long {
        if (value > MAX_SAFE_CUBE_ROOT) return Long.MAX_VALUE
        return value * value * value
    }

    private data class GridCell(val x: Long, val y: Long, val z: Long)

    companion object {
        const val DEFAULT_CELL_SIZE_LY: Double = 10.0
        private const val MAX_SAFE_CUBE_ROOT = 2_097_151L
    }
}
