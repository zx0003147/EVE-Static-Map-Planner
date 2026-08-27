package dev.evestaticmapplanner.map

import androidx.compose.ui.graphics.Color
import dev.evestaticmapplanner.core.map.MapBounds
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.feature.api.OverlayEntryVisibility
import dev.evestaticmapplanner.feature.api.OverlayState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

data class PresentedFeatureTerritory(
    val ownerLabel: String?,
    val systemIds: Set<Int>,
    val color: Color,
    val polygon: List<MapPoint>,
    val holes: List<List<MapPoint>> = emptyList(),
    val bounds: MapBounds,
)

data class PresentedFeatureBoundary(
    val points: List<MapPoint>,
    val closed: Boolean,
    val bounds: MapBounds,
)

data class FeatureOverlayLegendEntry(val label: String, val color: Color)

data class FeatureOverlayLegendSection(
    val title: String,
    val entries: List<FeatureOverlayLegendEntry>,
)

data class FeatureOverlayPresentation(
    val territories: List<PresentedFeatureTerritory>,
    val boundaries: List<PresentedFeatureBoundary> = emptyList(),
    val legendSections: List<FeatureOverlayLegendSection> = emptyList(),
    val emblemCandidates: List<PresentedFeatureEmblemCandidate> = emptyList(),
) {
    companion object {
        val Empty = FeatureOverlayPresentation(emptyList())
    }
}

data class FeatureOverlayPreparationMetrics(
    val totalMillis: Double,
    val overlayConversionMillis: Double,
    val sharedDomainMillis: Double,
    val ownershipAssignmentMillis: Double,
    val protectedCoreMillis: Double,
    val cleanupMillis: Double,
    val boundaryExtractionMillis: Double,
    val smoothingMillis: Double,
    val polygonPreparationMillis: Double,
) {
    fun diagnosticLine(projectionId: String, cacheStatus: String): String =
        "MAP_TERRITORY projection=$projectionId cache=$cacheStatus totalMs=${format(totalMillis)} " +
            "overlayMs=${format(overlayConversionMillis)} domainMs=${format(sharedDomainMillis)} " +
            "assignmentMs=${format(ownershipAssignmentMillis)} coresMs=${format(protectedCoreMillis)} " +
            "cleanupMs=${format(cleanupMillis)} boundaryMs=${format(boundaryExtractionMillis)} " +
            "smoothingMs=${format(smoothingMillis)} polygonsMs=${format(polygonPreparationMillis)}"

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
}

internal data class FeatureTerritoryCell(val x: Int, val y: Int)
internal data class FeatureTerritoryVertex(val x: Int, val y: Int)

internal data class FeatureTerritoryEdge(
    val first: FeatureTerritoryVertex,
    val second: FeatureTerritoryVertex,
) {
    companion object {
        fun between(first: FeatureTerritoryVertex, second: FeatureTerritoryVertex): FeatureTerritoryEdge =
            if (compareVertices(first, second) <= 0) FeatureTerritoryEdge(first, second)
            else FeatureTerritoryEdge(second, first)
    }
}

internal data class FeatureTerritoryAssignmentSnapshot(
    val ownersByCell: Map<FeatureTerritoryCell, String>,
    val seedCellsBySystemId: Map<Int, FeatureTerritoryCell>,
    val boundaryEdges: Set<FeatureTerritoryEdge>,
    val gridOrigin: MapPoint = MapPoint(0.0, 0.0),
    val cellSize: Double = 0.0,
    val protectedCoreRadius: Double = 0.0,
    val effectiveCoreRadiusBySystemId: Map<Int, Double> = emptyMap(),
    val seedPositionsBySystemId: Map<Int, MapPoint> = emptyMap(),
    val smoothedBoundarySegments: List<FeatureTerritoryBoundarySegment> = emptyList(),
    val outerBoundaryEdges: Set<FeatureTerritoryEdge> = emptySet(),
    val smoothedBoundaryPointsByVertex: Map<FeatureTerritoryVertex, MapPoint> = emptyMap(),
)

internal data class FeatureTerritoryBoundarySegment(val first: MapPoint, val second: MapPoint)

private enum class FeatureOverlayPreparationStage {
    OVERLAY_CONVERSION,
    SHARED_DOMAIN,
    OWNERSHIP_ASSIGNMENT,
    PROTECTED_CORE,
    CLEANUP,
    BOUNDARY_EXTRACTION,
    SMOOTHING,
    POLYGON_PREPARATION,
}

private class FeatureOverlayPreparationProfiler(
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val stageNanos = LongArray(FeatureOverlayPreparationStage.entries.size)

    fun <T> measure(stage: FeatureOverlayPreparationStage, block: () -> T): T {
        val started = clockNanos()
        return try {
            block()
        } finally {
            stageNanos[stage.ordinal] += clockNanos() - started
        }
    }

    fun finish(totalNanos: Long) = FeatureOverlayPreparationMetrics(
        totalMillis = totalNanos.toMillis(),
        overlayConversionMillis = nanos(FeatureOverlayPreparationStage.OVERLAY_CONVERSION),
        sharedDomainMillis = nanos(FeatureOverlayPreparationStage.SHARED_DOMAIN),
        ownershipAssignmentMillis = nanos(FeatureOverlayPreparationStage.OWNERSHIP_ASSIGNMENT),
        protectedCoreMillis = nanos(FeatureOverlayPreparationStage.PROTECTED_CORE),
        cleanupMillis = nanos(FeatureOverlayPreparationStage.CLEANUP),
        boundaryExtractionMillis = nanos(FeatureOverlayPreparationStage.BOUNDARY_EXTRACTION),
        smoothingMillis = nanos(FeatureOverlayPreparationStage.SMOOTHING),
        polygonPreparationMillis = nanos(FeatureOverlayPreparationStage.POLYGON_PREPARATION),
    )

    private fun nanos(stage: FeatureOverlayPreparationStage): Double = stageNanos[stage.ordinal].toMillis()
}

private fun Long.toMillis(): Double = this / 1_000_000.0

/** Converts generic system Overlay entries into one cached, mutually exclusive political territory partition. */
object FeatureOverlayPresentationBuilder {
    fun build(
        state: OverlayState,
        scene: ProjectedMapScene,
        metricsSink: (FeatureOverlayPreparationMetrics) -> Unit = {},
        clockNanos: () -> Long = System::nanoTime,
    ): FeatureOverlayPresentation {
        val started = clockNanos()
        val profiler = FeatureOverlayPreparationProfiler(clockNanos)
        val input = profiler.measure(FeatureOverlayPreparationStage.OVERLAY_CONVERSION) {
            collectInput(state, scene)
        }
        if (input.seeds.isEmpty()) {
            return FeatureOverlayPresentation(emptyList(), legendSections = input.legendSections).also {
                metricsSink(profiler.finish(clockNanos() - started))
            }
        }
        val field = buildSharedField(input.seeds, scene, profiler)
        val boundaryEdges = profiler.measure(FeatureOverlayPreparationStage.BOUNDARY_EXTRACTION) {
            sharedBoundaryEdges(field.assignment)
        }
        val vertexPositions = smoothSharedBoundaryVertices(
            field.assignment,
            field.grid,
            field.protectedCores,
            boundaryEdges,
            profiler,
        )
        val territoryBuild = profiler.measure(FeatureOverlayPreparationStage.POLYGON_PREPARATION) {
            buildTerritories(field, scene, vertexPositions)
        }
        val presentation = FeatureOverlayPresentation(
            territories = territoryBuild.territories,
            boundaries = profiler.measure(FeatureOverlayPreparationStage.BOUNDARY_EXTRACTION) {
                buildPresentedBoundaries(boundaryEdges, vertexPositions)
            },
            legendSections = profiler.measure(FeatureOverlayPreparationStage.OVERLAY_CONVERSION) {
                adjustedLegendSections(input.legendSections, territoryBuild.territories)
            },
            emblemCandidates = territoryBuild.emblemCandidates,
        )
        metricsSink(profiler.finish(clockNanos() - started))
        return presentation
    }

    internal fun assignmentSnapshot(
        state: OverlayState,
        scene: ProjectedMapScene,
    ): FeatureTerritoryAssignmentSnapshot {
        val seeds = collectInput(state, scene).seeds
        if (seeds.isEmpty()) return FeatureTerritoryAssignmentSnapshot(emptyMap(), emptyMap(), emptySet())
        val field = buildSharedField(seeds, scene)
        val boundaryEdges = sharedBoundaryEdges(field.assignment)
        val vertexPositions = smoothSharedBoundaryVertices(
            field.assignment,
            field.grid,
            field.protectedCores,
            boundaryEdges,
        )
        return FeatureTerritoryAssignmentSnapshot(
            ownersByCell = field.assignment.entries.associate { (cell, ownerIndex) ->
                cell.toFeatureCell() to field.owners[ownerIndex].identity.stableKey
            },
            seedCellsBySystemId = seeds.associate { seed ->
                seed.systemId to field.grid.cell(scene.nodesById.getValue(seed.systemId).position).toFeatureCell()
            },
            boundaryEdges = boundaryEdges.mapTo(linkedSetOf()) { edge ->
                FeatureTerritoryEdge.between(edge.first.toFeatureVertex(), edge.second.toFeatureVertex())
            },
            gridOrigin = field.grid.origin,
            cellSize = field.grid.cellSize,
            protectedCoreRadius = field.protectedCoreRadius,
            effectiveCoreRadiusBySystemId = field.protectedCores.associate { it.systemId to it.radius },
            seedPositionsBySystemId = field.protectedCores.associate { it.systemId to it.position },
            smoothedBoundarySegments = boundaryEdges.map { edge ->
                FeatureTerritoryBoundarySegment(
                    vertexPositions.getValue(edge.first),
                    vertexPositions.getValue(edge.second),
                )
            },
            outerBoundaryEdges = boundaryEdges.filterTo(linkedSetOf()) { edge ->
                boundaryKind(field.assignment, edge) == SharedBoundaryKind.OUTER
            }.mapTo(linkedSetOf()) { edge ->
                FeatureTerritoryEdge.between(edge.first.toFeatureVertex(), edge.second.toFeatureVertex())
            },
            smoothedBoundaryPointsByVertex = vertexPositions.mapKeys { (vertex, _) -> vertex.toFeatureVertex() },
        )
    }
}

private fun collectInput(state: OverlayState, scene: ProjectedMapScene): TerritoryInput {
    val legendSections = mutableListOf<FeatureOverlayLegendSection>()
    val seeds = mutableListOf<TerritorySeed>()
    state.layers.forEach { layerState ->
        val visibleEntries = layerState.entries.asSequence()
            .filter { it.visibility == OverlayEntryVisibility.VISIBLE }
            .filter { scene.nodesById.containsKey(it.systemId) }
            .sortedBy { it.systemId }
            .toList()
        val legendEntries = visibleEntries.mapNotNull { entry ->
            val color = parsePresentationMetadata(entry.value).color ?: return@mapNotNull null
            entry.title?.let { FeatureOverlayLegendEntry(it, color) }
        }.distinct().sortedBy { it.label }
        if (legendEntries.isNotEmpty()) {
            legendSections += FeatureOverlayLegendSection(layerState.layer.name, legendEntries)
        }
        visibleEntries.forEach { entry ->
            val metadata = parsePresentationMetadata(entry.value)
            val ownerKey = metadata.ownerKey
                ?: entry.title?.lowercase(Locale.ROOT)
                ?: entry.value?.lowercase(Locale.ROOT)
                ?: "system:${entry.systemId}"
            seeds += TerritorySeed(
                systemId = entry.systemId,
                ownerLabel = entry.title,
                color = metadata.color ?: DEFAULT_FEATURE_OVERLAY_COLOR,
                identity = TerritoryIdentity(layerState.provider.id, layerState.layer.id, ownerKey),
                emblemReference = metadata.emblemReference,
            )
        }
    }
    return TerritoryInput(seeds, legendSections)
}

private fun buildSharedField(
    seeds: List<TerritorySeed>,
    scene: ProjectedMapScene,
    profiler: FeatureOverlayPreparationProfiler = FeatureOverlayPreparationProfiler(),
): SharedTerritoryField {
    val setup = profiler.measure(FeatureOverlayPreparationStage.SHARED_DOMAIN) {
        val scale = territoryScale(scene)
        val influenceDistance = scale * MAX_INFLUENCE_SCALE
        val grid = TerritoryGrid(
            origin = MapPoint(
                scene.sceneBounds.minX - influenceDistance * 1.5,
                scene.sceneBounds.minY - influenceDistance * 1.5,
            ),
            cellSize = max(scale / GRID_CELLS_PER_SCALE, MIN_GEOMETRY_SCALE),
        )
        val owners = seeds.groupBy(TerritorySeed::identity)
            .toSortedMap(compareBy<TerritoryIdentity> { it.providerId }
                .thenBy { it.layerId }
                .thenBy { it.ownerKey })
            .map { (identity, ownerSeeds) -> OwnerField(identity, ownerSeeds) }
        val ownerIndexByIdentity = owners.mapIndexed { index, owner -> owner.identity to index }.toMap()
        val influenceSeeds = seeds.map { seed ->
            InfluenceSeed(
                systemId = seed.systemId,
                ownerIndex = ownerIndexByIdentity.getValue(seed.identity),
                position = scene.nodesById.getValue(seed.systemId).position,
            )
        }
        SharedFieldSetup(scale, influenceDistance, grid, owners, influenceSeeds)
    }
    val scale = setup.scale
    val influenceDistance = setup.influenceDistance
    val grid = setup.grid
    val owners = setup.owners
    val influenceSeeds = setup.influenceSeeds
    val protectedCoreRadius = scale * PROTECTED_CORE_SCALE_FACTOR
    val (protectedCores, protectedCells) = profiler.measure(FeatureOverlayPreparationStage.PROTECTED_CORE) {
        val cores = buildProtectedCores(influenceSeeds, protectedCoreRadius, grid.cellSize)
        cores to buildProtectedCells(cores, grid)
    }
    val supportedDomain = buildSupportedDomainMask(
        seeds = influenceSeeds,
        grid = grid,
        supportRadius = scale * SOVEREIGNTY_DOMAIN_SUPPORT_SCALE,
        profiler = profiler,
    )
    val assignment = profiler.measure(FeatureOverlayPreparationStage.OWNERSHIP_ASSIGNMENT) {
        val assignmentDistance = influenceDistance
        val influenceBuckets = influenceSeeds.groupBy { InfluenceBucket.forPoint(it.position, assignmentDistance) }
            .mapValues { (_, bucketSeeds) ->
                bucketSeeds.sortedWith(compareBy<InfluenceSeed> { it.systemId }.thenBy { it.ownerIndex })
            }
        val assigned = linkedMapOf<GridCell, Int>()
        supportedDomain.sortedWith(GRID_CELL_COMPARATOR).forEach { cell ->
            protectedCells[cell]?.let { ownerIndex ->
                assigned[cell] = ownerIndex
                return@forEach
            }
            val center = grid.center(cell)
            val influences = linkedMapOf<Int, OwnerInfluence>()
            val centerBucket = InfluenceBucket.forPoint(center, assignmentDistance)
            for (bucketX in centerBucket.x - 1..centerBucket.x + 1) {
                for (bucketY in centerBucket.y - 1..centerBucket.y + 1) {
                    influenceBuckets[InfluenceBucket(bucketX, bucketY)].orEmpty().forEach seedLoop@{ seed ->
                        val distance = sqrt(seed.position.distanceSquaredTo(center))
                        if (distance > assignmentDistance) return@seedLoop
                        val normalizedDistance = distance / influenceDistance
                        val reinforcement = (1.0 - normalizedDistance).coerceAtLeast(0.0)
                        val previous = influences[seed.ownerIndex]
                        influences[seed.ownerIndex] = OwnerInfluence(
                            nearestDistance = minOf(
                                previous?.nearestDistance ?: Double.POSITIVE_INFINITY,
                                normalizedDistance,
                            ),
                            reinforcement = (previous?.reinforcement ?: 0.0) + reinforcement * reinforcement,
                        )
                    }
                }
            }
            val winner = influences.keys.minWithOrNull(compareBy<Int> { ownerIndex ->
                val influence = influences.getValue(ownerIndex)
                influence.nearestDistance - OWNER_REINFORCEMENT_WEIGHT * influence.reinforcement.coerceAtMost(3.0)
            }.thenBy { it })
            if (winner != null) assigned[cell] = winner
        }
        protectedCells.forEach { (cell, ownerIndex) -> assigned[cell] = ownerIndex }
        assigned
    }
    val cleaned = profiler.measure(FeatureOverlayPreparationStage.CLEANUP) {
        val smoothed = smoothSharedAssignment(assignment, protectedCells, ASSIGNMENT_SMOOTHING_PASSES)
        absorbUnseededComponents(smoothed, protectedCells)
    }
    return SharedTerritoryField(
        grid = grid,
        owners = owners,
        assignment = cleaned,
        ownerColors = adjustedOwnerColors(owners, cleaned),
        protectedCoreRadius = protectedCoreRadius,
        protectedCores = protectedCores,
    )
}

/** Defines where ownership may exist; owner competition never expands beyond this seed-supported mask. */
private fun buildSupportedDomainMask(
    seeds: List<InfluenceSeed>,
    grid: TerritoryGrid,
    supportRadius: Double,
    profiler: FeatureOverlayPreparationProfiler,
): Set<GridCell> {
    val locallyClosed = profiler.measure(FeatureOverlayPreparationStage.SHARED_DOMAIN) {
        val directSupport = linkedSetOf<GridCell>()
        seeds.forEach { seed -> markDisc(directSupport, grid, seed.position, supportRadius) }
        closeMask(directSupport, SOVEREIGNTY_DOMAIN_CLOSING_RADIUS_CELLS)
    }
    return profiler.measure(FeatureOverlayPreparationStage.CLEANUP) {
        val cleaned = fillEnclosedHoles(locallyClosed, MAX_SUPPORTED_DOMAIN_HOLE_CELLS)
        val seedCells = seeds.mapTo(linkedSetOf()) { grid.cell(it.position) }
        connectedCellComponents(cleaned)
            .filter { component -> component.any(seedCells::contains) }
            .flatten()
            .toSortedSet(GRID_CELL_COMPARATOR)
    }
}

private fun buildProtectedCores(
    seeds: List<InfluenceSeed>,
    protectedCoreRadius: Double,
    cellSize: Double,
): List<ProtectedCore> {
    val cellHalfDiagonal = cellSize * sqrt(2.0) / 2.0
    val epsilon = cellSize * PROTECTED_CORE_EPSILON_CELLS
    val conflictDistance = 2.0 * (protectedCoreRadius + cellHalfDiagonal + epsilon)
    val buckets = seeds.groupBy { InfluenceBucket.forPoint(it.position, conflictDistance) }
    return seeds.sortedWith(compareBy<InfluenceSeed> { it.systemId }.thenBy { it.ownerIndex }).map { seed ->
        val bucket = InfluenceBucket.forPoint(seed.position, conflictDistance)
        var nearestRivalDistance = Double.POSITIVE_INFINITY
        for (x in bucket.x - 1..bucket.x + 1) for (y in bucket.y - 1..bucket.y + 1) {
            buckets[InfluenceBucket(x, y)].orEmpty().forEach { rival ->
                if (rival.ownerIndex != seed.ownerIndex) {
                    nearestRivalDistance = minOf(
                        nearestRivalDistance,
                        sqrt(seed.position.distanceSquaredTo(rival.position)),
                    )
                }
            }
        }
        val effectiveRadius = if (nearestRivalDistance.isFinite()) {
            minOf(
                protectedCoreRadius,
                (nearestRivalDistance / 2.0 - cellHalfDiagonal - epsilon).coerceAtLeast(0.0),
            )
        } else {
            protectedCoreRadius
        }
        ProtectedCore(seed.systemId, seed.ownerIndex, seed.position, effectiveRadius)
    }
}

private fun buildProtectedCells(
    cores: List<ProtectedCore>,
    grid: TerritoryGrid,
): Map<GridCell, Int> {
    val cellHalfDiagonal = grid.cellSize * sqrt(2.0) / 2.0
    val claims = linkedMapOf<GridCell, MutableList<ProtectedCore>>()
    cores.forEach { core ->
        val cells = linkedSetOf<GridCell>()
        markDisc(cells, grid, core.position, core.radius + cellHalfDiagonal)
        cells.forEach { cell -> claims.getOrPut(cell, ::mutableListOf) += core }
    }
    return claims.toSortedMap(GRID_CELL_COMPARATOR).mapValues { (cell, cellClaims) ->
        cellClaims.minWith(
            compareBy<ProtectedCore> { it.position.distanceSquaredTo(grid.center(cell)) }
                .thenBy { it.ownerIndex }
                .thenBy { it.systemId },
        ).ownerIndex
    }
}

private fun smoothSharedAssignment(
    assignment: Map<GridCell, Int>,
    protectedCells: Map<GridCell, Int>,
    passes: Int,
): Map<GridCell, Int> {
    var current = assignment
    repeat(passes) {
        val next = linkedMapOf<GridCell, Int>()
        current.keys.sortedWith(GRID_CELL_COMPARATOR).forEach cellLoop@{ cell ->
            protectedCells[cell]?.let { protectedOwner ->
                next[cell] = protectedOwner
                return@cellLoop
            }
            val counts = linkedMapOf<Int, Int>()
            cell.allNeighbors().mapNotNull(current::get).forEach { ownerIndex ->
                counts[ownerIndex] = counts.getOrDefault(ownerIndex, 0) + 1
            }
            val currentOwner = current.getValue(cell)
            val winner = counts.keys.minWithOrNull(compareByDescending<Int> { counts.getValue(it) }.thenBy { it })
            next[cell] = if (winner != null && counts.getValue(winner) >= ASSIGNMENT_MAJORITY_THRESHOLD) winner
            else currentOwner
        }
        current = next
    }
    return current
}

private fun absorbUnseededComponents(
    assignment: Map<GridCell, Int>,
    protectedCells: Map<GridCell, Int>,
): Map<GridCell, Int> {
    val result = assignment.toMutableMap()
    val cellsByOwner = sortedMapOf<Int, MutableSet<GridCell>>()
    assignment.forEach { (cell, ownerIndex) -> cellsByOwner.getOrPut(ownerIndex, ::linkedSetOf) += cell }
    cellsByOwner.forEach { (ownerIndex, ownerCells) ->
        connectedCellComponents(ownerCells).forEach componentLoop@{ component ->
            if (component.any { protectedCells[it] == ownerIndex }) return@componentLoop
            val neighbors = component.asSequence()
                .flatMap { it.neighbors().asSequence() }
                .filter { it !in component }
                .mapNotNull(assignment::get)
                .filter { it != ownerIndex }
                .groupingBy { it }
                .eachCount()
            val replacement = neighbors.keys.minWithOrNull(
                compareByDescending<Int> { neighbors.getValue(it) }.thenBy { it },
            )
            component.forEach { cell -> if (replacement == null) result.remove(cell) else result[cell] = replacement }
        }
    }
    return result.toSortedMap(GRID_CELL_COMPARATOR)
}

private fun adjustedOwnerColors(
    owners: List<OwnerField>,
    assignment: Map<GridCell, Int>,
): List<Color> {
    val adjacency = owners.indices.associateWith { linkedSetOf<Int>() }.toMutableMap()
    assignment.forEach { (cell, ownerIndex) ->
        listOf(GridCell(cell.x + 1, cell.y), GridCell(cell.x, cell.y + 1)).forEach { neighbor ->
            val neighborOwner = assignment[neighbor] ?: return@forEach
            if (neighborOwner != ownerIndex) {
                adjacency.getValue(ownerIndex) += neighborOwner
                adjacency.getValue(neighborOwner) += ownerIndex
            }
        }
    }
    val selected = mutableListOf<Color>()
    owners.forEachIndexed { ownerIndex, owner ->
        val base = owner.seeds.first().color
        val priorNeighbors = adjacency.getValue(ownerIndex).filter { it < ownerIndex }.map(selected::get)
        if (priorNeighbors.none { presentationColorDistance(base, it) < ADJACENT_COLOR_DISTANCE_THRESHOLD }) {
            selected += base
        } else {
            val variants = contrastVariants(base, owner.identity.stableKey)
            selected += variants.firstOrNull { candidate ->
                priorNeighbors.all { presentationColorDistance(candidate, it) >= ADJACENT_COLOR_DISTANCE_THRESHOLD }
            } ?: variants.maxBy { candidate ->
                priorNeighbors.minOfOrNull { presentationColorDistance(candidate, it) } ?: Double.POSITIVE_INFINITY
            }
        }
    }
    return selected
}

private fun contrastVariants(base: Color, stableKey: String): List<Color> {
    val hsv = base.toHsv()
    val direction = if (stableKey.fold(0) { hash, character -> hash * 31 + character.code } and 1 == 0) 1.0 else -1.0
    return listOf(42.0, -42.0, 78.0, -78.0, 118.0, -118.0).map { hueOffset ->
        hsvColor(
            hue = hsv.hue + hueOffset * direction,
            saturation = max(hsv.saturation.toDouble(), ADJACENT_VARIANT_MIN_SATURATION),
            value = max(hsv.value.toDouble(), ADJACENT_VARIANT_MIN_VALUE),
            alpha = base.alpha,
        )
    }
}

internal fun presentationColorDistance(first: Color, second: Color): Double = sqrt(
    (first.red - second.red) * (first.red - second.red) +
        (first.green - second.green) * (first.green - second.green) +
        (first.blue - second.blue) * (first.blue - second.blue),
).toDouble() / sqrt(3.0)

private data class HsvColor(val hue: Double, val saturation: Float, val value: Float)

private fun Color.toHsv(): HsvColor {
    val maximum = maxOf(red, green, blue)
    val minimum = minOf(red, green, blue)
    val delta = maximum - minimum
    val hue = when {
        delta == 0f -> 0.0
        maximum == red -> 60.0 * (((green - blue) / delta) % 6.0)
        maximum == green -> 60.0 * ((blue - red) / delta + 2.0)
        else -> 60.0 * ((red - green) / delta + 4.0)
    }
    return HsvColor((hue + 360.0) % 360.0, if (maximum == 0f) 0f else delta / maximum, maximum)
}

private fun hsvColor(hue: Double, saturation: Double, value: Double, alpha: Float): Color {
    val normalizedHue = ((hue % 360.0) + 360.0) % 360.0
    val chroma = value * saturation
    val secondary = chroma * (1.0 - abs((normalizedHue / 60.0) % 2.0 - 1.0))
    val (redPrime, greenPrime, bluePrime) = when ((normalizedHue / 60.0).toInt()) {
        0 -> Triple(chroma, secondary, 0.0)
        1 -> Triple(secondary, chroma, 0.0)
        2 -> Triple(0.0, chroma, secondary)
        3 -> Triple(0.0, secondary, chroma)
        4 -> Triple(secondary, 0.0, chroma)
        else -> Triple(chroma, 0.0, secondary)
    }
    val match = value - chroma
    return Color((redPrime + match).toFloat(), (greenPrime + match).toFloat(), (bluePrime + match).toFloat(), alpha)
}

private fun adjustedLegendSections(
    sections: List<FeatureOverlayLegendSection>,
    territories: List<PresentedFeatureTerritory>,
): List<FeatureOverlayLegendSection> {
    val displayedColorByLabel = territories.groupBy(PresentedFeatureTerritory::ownerLabel)
        .mapValues { (_, ownerTerritories) -> ownerTerritories.first().color }
    return sections.map { section ->
        section.copy(entries = section.entries.map { entry ->
            entry.copy(color = displayedColorByLabel[entry.label] ?: entry.color)
        })
    }
}

private fun buildTerritories(
    field: SharedTerritoryField,
    scene: ProjectedMapScene,
    vertexPositions: Map<GridVertex, MapPoint>,
): TerritoryPresentationBuild {
    val cellsByOwner = mutableMapOf<Int, MutableSet<GridCell>>()
    field.assignment.forEach { (cell, ownerIndex) -> cellsByOwner.getOrPut(ownerIndex, ::linkedSetOf) += cell }
    val territories = mutableListOf<PresentedFeatureTerritory>()
    val emblemCandidates = mutableListOf<PresentedFeatureEmblemCandidate>()
    val labelExclusions = stableRegionLabelExclusions(scene, field.grid)
    field.owners.forEachIndexed { ownerIndex, owner ->
        connectedCellComponents(cellsByOwner[ownerIndex].orEmpty()).forEach componentLoop@{ component ->
            val loops = boundaryLoops(component)
                .map { loop -> loop.map(vertexPositions::getValue) }
                .filter { it.size >= 4 }
            val polygon = loops.maxByOrNull { abs(signedArea(it)) } ?: return@componentLoop
            val componentSeeds = owner.seeds.filter { seed ->
                field.grid.cell(scene.nodesById.getValue(seed.systemId).position) in component
            }
            if (componentSeeds.isEmpty()) return@componentLoop
            val territory = PresentedFeatureTerritory(
                ownerLabel = owner.seeds.first().ownerLabel,
                systemIds = componentSeeds.mapTo(linkedSetOf(), TerritorySeed::systemId),
                color = field.ownerColors[ownerIndex],
                polygon = polygon,
                holes = loops.filterNot { it === polygon },
                bounds = MapBounds.fromPoints(polygon),
            )
            territories += territory
            buildEmblemCandidate(owner, component, componentSeeds, field.grid, territory, labelExclusions)?.let {
                emblemCandidates += it
            }
        }
    }
    return TerritoryPresentationBuild(territories, emblemCandidates)
}

private fun buildEmblemCandidate(
    owner: OwnerField,
    component: Set<GridCell>,
    componentSeeds: List<TerritorySeed>,
    grid: TerritoryGrid,
    territory: PresentedFeatureTerritory,
    labelExclusions: List<StableRegionLabelExclusion>,
): PresentedFeatureEmblemCandidate? {
    if (componentSeeds.size < MIN_EMBLEM_COMPONENT_SYSTEM_COUNT || component.size < MIN_EMBLEM_COMPONENT_CELL_COUNT) {
        return null
    }
    val reference = owner.seeds.map(TerritorySeed::emblemReference).distinct().singleOrNull() ?: return null
    val distanceByCell = boundaryDistanceByCell(component)
    val interior = selectStableEmblemAnchor(
        component = component,
        distanceByCell = distanceByCell,
        labelExclusions = labelExclusions,
    ) ?: return null
    val firstCell = component.minWithOrNull(GRID_CELL_COMPARATOR) ?: return null
    return PresentedFeatureEmblemCandidate(
        componentKey = "${owner.identity.stableKey}|${firstCell.x},${firstCell.y}",
        reference = reference,
        anchor = grid.center(interior.cell),
        bounds = territory.bounds,
        mapArea = component.size * grid.cellSize * grid.cellSize,
        systemCount = componentSeeds.size,
        boundaryClearance = (interior.clearanceCells + 0.5) * grid.cellSize,
        clipTerritory = territory,
    )
}

internal fun boundaryDistanceByCell(component: Set<GridCell>): Map<GridCell, Int> {
    if (component.isEmpty()) return emptyMap()
    val distanceByCell = mutableMapOf<GridCell, Int>()
    val queue = ArrayDeque<GridCell>()
    component.asSequence().filter { cell -> cell.neighbors().any { it !in component } }
        .sortedWith(GRID_CELL_COMPARATOR)
        .forEach { cell ->
            distanceByCell[cell] = 0
            queue += cell
        }
    while (queue.isNotEmpty()) {
        val cell = queue.removeFirst()
        val nextDistance = distanceByCell.getValue(cell) + 1
        cell.neighbors().asSequence().filter(component::contains).sortedWith(GRID_CELL_COMPARATOR).forEach { neighbor ->
            if (neighbor !in distanceByCell) {
                distanceByCell[neighbor] = nextDistance
                queue += neighbor
            }
        }
    }
    return distanceByCell
}

/**
 * Selects one component-owned anchor during presentation preparation. Region labels use their
 * projection-stable canonical anchors and conservative grid-space exclusion rectangles, so neither
 * pan nor zoom participates in placement.
 */
internal fun selectStableEmblemAnchor(
    component: Set<GridCell>,
    distanceByCell: Map<GridCell, Int>,
    labelExclusions: List<StableRegionLabelExclusion> = emptyList(),
): InteriorCell? {
    if (component.isEmpty()) return null
    val centroidX = component.sumOf { it.x + 0.5 } / component.size
    val centroidY = component.sumOf { it.y + 0.5 } / component.size
    val candidates = component.asSequence()
        .filter { distanceByCell.getValue(it) >= MIN_EMBLEM_BOUNDARY_CLEARANCE_CELLS }
        .filter { cell -> labelExclusions.none { it.contains(cell) } }
        .sortedWith(GRID_CELL_COMPARATOR)
        .toList()
    if (candidates.isEmpty()) return null

    val centroidCell = GridCell(floor(centroidX).toInt(), floor(centroidY).toInt())
    if (centroidCell in candidates) {
        return InteriorCell(centroidCell, distanceByCell.getValue(centroidCell))
    }

    val maximumDistance = candidates.maxOf { cell ->
        sqrt(cell.centerDistanceSquaredTo(centroidX, centroidY))
    }.coerceAtLeast(1.0)
    val maximumClearance = candidates.maxOf(distanceByCell::getValue).coerceAtLeast(1)
    val selected = candidates.maxWithOrNull(
        compareBy<GridCell> { cell ->
            val centrality = 1.0 - sqrt(cell.centerDistanceSquaredTo(centroidX, centroidY)) / maximumDistance
            val clearance = distanceByCell.getValue(cell).toDouble() / maximumClearance
            EMBLEM_CENTRALITY_WEIGHT * centrality + EMBLEM_CLEARANCE_WEIGHT * clearance
        }.thenBy { -it.centerDistanceSquaredTo(centroidX, centroidY) }
            .thenBy(distanceByCell::getValue)
            .then(GRID_CELL_COMPARATOR.reversed()),
    ) ?: return null
    return InteriorCell(selected, distanceByCell.getValue(selected))
}

private fun stableRegionLabelExclusions(
    scene: ProjectedMapScene,
    grid: TerritoryGrid,
): List<StableRegionLabelExclusion> = scene.regions.map { region ->
    StableRegionLabelExclusion(
        centerXCells = (region.canonicalAnchor.x - grid.origin.x) / grid.cellSize,
        centerYCells = (region.canonicalAnchor.y - grid.origin.y) / grid.cellSize,
        halfWidthCells = (REGION_LABEL_BASE_HALF_WIDTH_CELLS + region.name.length * REGION_LABEL_CHARACTER_WIDTH_CELLS)
            .coerceIn(REGION_LABEL_MIN_HALF_WIDTH_CELLS, REGION_LABEL_MAX_HALF_WIDTH_CELLS),
        halfHeightCells = REGION_LABEL_HALF_HEIGHT_CELLS,
    )
}

internal data class StableRegionLabelExclusion(
    val centerXCells: Double,
    val centerYCells: Double,
    val halfWidthCells: Double,
    val halfHeightCells: Double,
) {
    fun contains(cell: GridCell): Boolean {
        val centerX = cell.x + 0.5
        val centerY = cell.y + 0.5
        return abs(centerX - centerXCells) <= halfWidthCells && abs(centerY - centerYCells) <= halfHeightCells
    }
}

private fun GridCell.centerDistanceSquaredTo(x: Double, y: Double): Double {
    val dx = this.x + 0.5 - x
    val dy = this.y + 0.5 - y
    return dx * dx + dy * dy
}

private fun buildPresentedBoundaries(
    edges: Set<UndirectedGridEdge>,
    vertexPositions: Map<GridVertex, MapPoint>,
): List<PresentedFeatureBoundary> = boundaryChains(edges).mapNotNull { chain ->
    val points = chain.vertices.map(vertexPositions::getValue)
    points.takeIf { it.size >= 2 }?.let { PresentedFeatureBoundary(it, chain.closed, MapBounds.fromPoints(it)) }
}

private fun smoothSharedBoundaryVertices(
    assignment: Map<GridCell, Int>,
    grid: TerritoryGrid,
    protectedCores: List<ProtectedCore>,
    edges: Set<UndirectedGridEdge> = sharedBoundaryEdges(assignment),
    profiler: FeatureOverlayPreparationProfiler = FeatureOverlayPreparationProfiler(),
): Map<GridVertex, MapPoint> {
    return profiler.measure(FeatureOverlayPreparationStage.SMOOTHING) {
        val adjacency = linkedMapOf<GridVertex, MutableSet<GridVertex>>()
        val incidentKinds = linkedMapOf<GridVertex, MutableSet<SharedBoundaryKind>>()
        edges.forEach { edge ->
            adjacency.getOrPut(edge.first, ::linkedSetOf) += edge.second
            adjacency.getOrPut(edge.second, ::linkedSetOf) += edge.first
            val kind = boundaryKind(assignment, edge)
            incidentKinds.getOrPut(edge.first, ::linkedSetOf) += kind
            incidentKinds.getOrPut(edge.second, ::linkedSetOf) += kind
        }
        val boundaryKindByVertex = adjacency.keys.associateWith { vertex ->
            incidentKinds.getValue(vertex).singleOrNull()
        }
        val protectedCoreIndex = ProtectedCoreIndex(protectedCores, grid.cellSize)
        var positions = adjacency.keys.associateWith(grid::point)
        repeat(OUTER_BOUNDARY_SMOOTHING_PASSES) { pass ->
            val previous = positions
            val next = previous.toMutableMap()
            adjacency.keys.sortedWith(GRID_VERTEX_COMPARATOR).forEach { vertex ->
                val current = previous.getValue(vertex)
                val neighbors = adjacency.getValue(vertex).sortedWith(GRID_VERTEX_COMPARATOR)
                if (neighbors.size != 2) return@forEach
                val kind = boundaryKindByVertex[vertex] ?: return@forEach
                if (kind == SharedBoundaryKind.OWNER_BORDER && pass >= OWNER_BOUNDARY_SMOOTHING_PASSES) return@forEach
                val neighborWeight = if (kind == SharedBoundaryKind.OUTER) {
                    OUTER_BOUNDARY_NEIGHBOR_WEIGHT
                } else {
                    OWNER_BOUNDARY_NEIGHBOR_WEIGHT
                }
                val centerWeight = 1.0 - neighborWeight * 2.0
                val first = previous.getValue(neighbors[0])
                val second = previous.getValue(neighbors[1])
                val candidate = MapPoint(
                    current.x * centerWeight + (first.x + second.x) * neighborWeight,
                    current.y * centerWeight + (first.y + second.y) * neighborWeight,
                )
                if (!protectedCoreIndex.contains(candidate) && neighbors.none { neighbor ->
                        protectedCoreIndex.intersectsSegment(candidate, next.getValue(neighbor))
                    }
                ) next[vertex] = candidate
            }
            positions = next
        }
        positions
    }
}

private class ProtectedCoreIndex(cores: List<ProtectedCore>, cellSize: Double) {
    private val bucketSize = max(cores.maxOfOrNull(ProtectedCore::radius) ?: cellSize, cellSize)
    private val buckets = cores.groupBy { InfluenceBucket.forPoint(it.position, bucketSize) }

    fun contains(point: MapPoint): Boolean {
        val bucket = InfluenceBucket.forPoint(point, bucketSize)
        for (x in bucket.x - 1..bucket.x + 1) for (y in bucket.y - 1..bucket.y + 1) {
            if (buckets[InfluenceBucket(x, y)].orEmpty().any { core ->
                    point.distanceSquaredTo(core.position) + MIN_GEOMETRY_SCALE < core.radius * core.radius
                }
            ) return true
        }
        return false
    }

    fun intersectsSegment(first: MapPoint, second: MapPoint): Boolean {
        val firstBucket = InfluenceBucket.forPoint(first, bucketSize)
        val secondBucket = InfluenceBucket.forPoint(second, bucketSize)
        for (x in minOf(firstBucket.x, secondBucket.x) - 1..maxOf(firstBucket.x, secondBucket.x) + 1) {
            for (y in minOf(firstBucket.y, secondBucket.y) - 1..maxOf(firstBucket.y, secondBucket.y) + 1) {
                if (buckets[InfluenceBucket(x, y)].orEmpty().any { core ->
                        distanceSquaredToSegment(core.position, first, second) + MIN_GEOMETRY_SCALE < core.radius * core.radius
                    }
                ) return true
            }
        }
        return false
    }
}

private fun distanceSquaredToSegment(point: MapPoint, first: MapPoint, second: MapPoint): Double {
    val dx = second.x - first.x
    val dy = second.y - first.y
    val lengthSquared = dx * dx + dy * dy
    val projection = if (lengthSquared <= MIN_GEOMETRY_SCALE) 0.0 else (
        (point.x - first.x) * dx + (point.y - first.y) * dy
    ) / lengthSquared
    val clamped = projection.coerceIn(0.0, 1.0)
    val nearest = MapPoint(first.x + clamped * dx, first.y + clamped * dy)
    return point.distanceSquaredTo(nearest)
}

private fun sharedBoundaryEdges(assignment: Map<GridCell, Int>): Set<UndirectedGridEdge> {
    val edges = linkedSetOf<UndirectedGridEdge>()
    assignment.keys.sortedWith(GRID_CELL_COMPARATOR).forEach { cell ->
        val owner = assignment.getValue(cell)
        cell.sides().forEach { side ->
            if (assignment[side.neighbor] != owner) edges += UndirectedGridEdge.between(side.first, side.second)
        }
    }
    return edges
}

private enum class SharedBoundaryKind { OUTER, OWNER_BORDER }

private fun boundaryKind(assignment: Map<GridCell, Int>, edge: UndirectedGridEdge): SharedBoundaryKind {
    val adjacentCells = if (edge.first.y == edge.second.y) {
        val x = minOf(edge.first.x, edge.second.x)
        listOf(GridCell(x, edge.first.y - 1), GridCell(x, edge.first.y))
    } else {
        val y = minOf(edge.first.y, edge.second.y)
        listOf(GridCell(edge.first.x - 1, y), GridCell(edge.first.x, y))
    }
    return if (adjacentCells.mapNotNull(assignment::get).distinct().size >= 2) {
        SharedBoundaryKind.OWNER_BORDER
    } else {
        SharedBoundaryKind.OUTER
    }
}

private fun boundaryChains(edges: Set<UndirectedGridEdge>): List<GridBoundaryChain> {
    val adjacency = linkedMapOf<GridVertex, MutableSet<GridVertex>>()
    edges.forEach { edge ->
        adjacency.getOrPut(edge.first, ::linkedSetOf) += edge.second
        adjacency.getOrPut(edge.second, ::linkedSetOf) += edge.first
    }
    val unused = edges.toMutableSet()
    val chains = mutableListOf<GridBoundaryChain>()
    while (unused.isNotEmpty()) {
        val firstEdge = unused.minWith(UNDIRECTED_EDGE_COMPARATOR)
        val start = listOf(firstEdge.first, firstEdge.second)
            .filter { adjacency.getValue(it).size != 2 }
            .minWithOrNull(GRID_VERTEX_COMPARATOR)
            ?: firstEdge.first
        val vertices = mutableListOf(start)
        var previous: GridVertex? = null
        var current = start
        var closed = false
        while (true) {
            val next = adjacency.getValue(current).asSequence()
                .filter { neighbor -> UndirectedGridEdge.between(current, neighbor) in unused }
                .filter { it != previous || adjacency.getValue(current).size == 1 }
                .minWithOrNull(GRID_VERTEX_COMPARATOR)
                ?: break
            unused -= UndirectedGridEdge.between(current, next)
            previous = current
            current = next
            if (current == start) {
                closed = true
                break
            }
            vertices += current
            if (adjacency.getValue(current).size != 2) break
        }
        chains += GridBoundaryChain(vertices, closed)
    }
    return chains
}

private fun boundaryLoops(cells: Set<GridCell>): List<List<GridVertex>> {
    val unused = linkedSetOf<DirectedGridEdge>()
    cells.forEach { cell ->
        if (GridCell(cell.x, cell.y - 1) !in cells) {
            unused += DirectedGridEdge(GridVertex(cell.x, cell.y), GridVertex(cell.x + 1, cell.y))
        }
        if (GridCell(cell.x + 1, cell.y) !in cells) {
            unused += DirectedGridEdge(GridVertex(cell.x + 1, cell.y), GridVertex(cell.x + 1, cell.y + 1))
        }
        if (GridCell(cell.x, cell.y + 1) !in cells) {
            unused += DirectedGridEdge(GridVertex(cell.x + 1, cell.y + 1), GridVertex(cell.x, cell.y + 1))
        }
        if (GridCell(cell.x - 1, cell.y) !in cells) {
            unused += DirectedGridEdge(GridVertex(cell.x, cell.y + 1), GridVertex(cell.x, cell.y))
        }
    }
    val outgoing = unused.groupBy(DirectedGridEdge::start)
    val loops = mutableListOf<List<GridVertex>>()
    while (unused.isNotEmpty()) {
        val first = unused.minWith(DIRECTED_EDGE_COMPARATOR)
        val loop = mutableListOf<GridVertex>()
        var edge = first
        while (edge in unused) {
            unused -= edge
            loop += edge.start
            if (edge.end == first.start) break
            edge = outgoing[edge.end].orEmpty().filter { it in unused }
                .minByOrNull { turnPriority(edge.direction, it.direction) }
                ?: break
        }
        if (loop.size >= 4) loops += loop
    }
    return loops
}

private fun connectedCellComponents(cells: Set<GridCell>): List<Set<GridCell>> {
    val remaining = cells.toMutableSet()
    val components = mutableListOf<Set<GridCell>>()
    while (remaining.isNotEmpty()) {
        val start = remaining.minWith(GRID_CELL_COMPARATOR)
        val component = linkedSetOf<GridCell>()
        val queue = ArrayDeque<GridCell>()
        queue += start
        remaining -= start
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            component += cell
            cell.neighbors().forEach { neighbor -> if (remaining.remove(neighbor)) queue += neighbor }
        }
        components += component
    }
    return components
}

private fun markDisc(cells: MutableSet<GridCell>, grid: TerritoryGrid, center: MapPoint, radius: Double) {
    val minimum = grid.cell(MapPoint(center.x - radius, center.y - radius))
    val maximum = grid.cell(MapPoint(center.x + radius, center.y + radius))
    val radiusSquared = radius * radius
    for (x in minimum.x..maximum.x) for (y in minimum.y..maximum.y) {
        val cell = GridCell(x, y)
        if (grid.center(cell).distanceSquaredTo(center) <= radiusSquared) cells += cell
    }
}

private fun closeMask(cells: Set<GridCell>, radius: Int): Set<GridCell> {
    if (cells.isEmpty() || radius == 0) return cells
    val offsets = morphologyOffsets(radius)
    val dilated = linkedSetOf<GridCell>()
    cells.forEach { cell -> offsets.forEach { offset -> dilated += cell + offset } }
    return dilated.filterTo(linkedSetOf()) { cell -> offsets.all { offset -> cell + offset in dilated } }
}

private fun fillEnclosedHoles(cells: Set<GridCell>, maximumHoleSize: Int): Set<GridCell> {
    if (cells.isEmpty()) return cells
    val bounds = GridBounds.from(cells).expanded(1)
    val exterior = linkedSetOf<GridCell>()
    val exteriorQueue = ArrayDeque<GridCell>()
    for (x in bounds.minX..bounds.maxX) {
        listOf(GridCell(x, bounds.minY), GridCell(x, bounds.maxY)).forEach { cell ->
            if (cell !in cells && exterior.add(cell)) exteriorQueue += cell
        }
    }
    for (y in bounds.minY..bounds.maxY) {
        listOf(GridCell(bounds.minX, y), GridCell(bounds.maxX, y)).forEach { cell ->
            if (cell !in cells && exterior.add(cell)) exteriorQueue += cell
        }
    }
    while (exteriorQueue.isNotEmpty()) {
        val cell = exteriorQueue.removeFirst()
        cell.neighbors().forEach { neighbor ->
            if (neighbor in bounds && neighbor !in cells && exterior.add(neighbor)) exteriorQueue += neighbor
        }
    }

    val result = cells.toMutableSet()
    val visitedHoles = mutableSetOf<GridCell>()
    cells.asSequence().flatMap { it.neighbors().asSequence() }
        .filter { it in bounds && it !in cells && it !in exterior }
        .distinct().sortedWith(GRID_CELL_COMPARATOR)
        .forEach startLoop@{ start ->
            if (!visitedHoles.add(start)) return@startLoop
            val hole = linkedSetOf<GridCell>()
            val queue = ArrayDeque<GridCell>()
            queue += start
            while (queue.isNotEmpty()) {
                val cell = queue.removeFirst()
                hole += cell
                cell.neighbors().forEach { neighbor ->
                    if (neighbor in bounds && neighbor !in cells && neighbor !in exterior && visitedHoles.add(neighbor)) {
                        queue += neighbor
                    }
                }
            }
            if (hole.size <= maximumHoleSize) result += hole
        }
    return result
}

private fun territoryScale(scene: ProjectedMapScene): Double {
    val edgeDistances = scene.edges.asSequence()
        .map { sqrt(it.first.distanceSquaredTo(it.second)) }
        .filter { it.isFinite() && it > MIN_GEOMETRY_SCALE }
        .sorted().toList()
    if (edgeDistances.isNotEmpty()) return edgeDistances[edgeDistances.size / 2]
    val span = max(scene.sceneBounds.width, scene.sceneBounds.height)
    val estimated = span / sqrt(scene.nodes.size.toDouble().coerceAtLeast(1.0))
    return estimated.takeIf { it.isFinite() && it > MIN_GEOMETRY_SCALE } ?: 1.0
}

private fun signedArea(points: List<MapPoint>): Double = points.indices.sumOf { index ->
    val current = points[index]
    val next = points[(index + 1) % points.size]
    current.x * next.y - next.x * current.y
} / 2.0

private fun turnPriority(fromDirection: Int, toDirection: Int): Int = when ((toDirection - fromDirection + 4) % 4) {
    1 -> 0
    0 -> 1
    3 -> 2
    else -> 3
}

internal fun parsePresentationColor(value: String?): Color? {
    return parsePresentationMetadata(value).color
}

private fun parsePresentationMetadata(value: String?): PresentationMetadata {
    var color: Color? = null
    var ownerKey: String? = null
    var emblemKey: String? = null
    var emblemUrl: String? = null
    value?.split(';')?.forEach { token ->
        val colorMatch = PRESENTATION_COLOR_PATTERN.matchEntire(token)
        if (colorMatch != null) color = Color(colorMatch.groupValues[1].toLong(16))
        if (token.startsWith(OWNER_KEY_PREFIX)) ownerKey = token.removePrefix(OWNER_KEY_PREFIX).takeIf(String::isNotBlank)
        if (token.startsWith(EMBLEM_KEY_PREFIX)) emblemKey = token.removePrefix(EMBLEM_KEY_PREFIX).takeIf(String::isNotBlank)
        if (token.startsWith(EMBLEM_URL_PREFIX)) emblemUrl = token.removePrefix(EMBLEM_URL_PREFIX).takeIf(String::isNotBlank)
    }
    val emblemReference = if (emblemKey != null && emblemUrl != null) {
        PresentationEmblemReference(emblemKey, emblemUrl)
    } else {
        null
    }
    return PresentationMetadata(color, ownerKey, emblemReference)
}

internal val DEFAULT_FEATURE_OVERLAY_COLOR = Color(0xFF8EA8BD)
private val PRESENTATION_COLOR_PATTERN = Regex("presentation-color:#([0-9A-Fa-f]{8})")
private const val OWNER_KEY_PREFIX = "owner-key:"
private const val EMBLEM_KEY_PREFIX = "presentation-emblem-key:"
private const val EMBLEM_URL_PREFIX = "presentation-emblem-url:"

private data class PresentationMetadata(
    val color: Color?,
    val ownerKey: String?,
    val emblemReference: PresentationEmblemReference?,
)

private data class TerritoryInput(
    val seeds: List<TerritorySeed>,
    val legendSections: List<FeatureOverlayLegendSection>,
)

private data class SharedFieldSetup(
    val scale: Double,
    val influenceDistance: Double,
    val grid: TerritoryGrid,
    val owners: List<OwnerField>,
    val influenceSeeds: List<InfluenceSeed>,
)

private data class TerritorySeed(
    val systemId: Int,
    val ownerLabel: String?,
    val color: Color,
    val identity: TerritoryIdentity,
    val emblemReference: PresentationEmblemReference?,
)

private data class TerritoryPresentationBuild(
    val territories: List<PresentedFeatureTerritory>,
    val emblemCandidates: List<PresentedFeatureEmblemCandidate>,
)

internal data class InteriorCell(val cell: GridCell, val clearanceCells: Int)

private data class TerritoryIdentity(val providerId: String, val layerId: String, val ownerKey: String) {
    val stableKey: String = "$providerId|$layerId|$ownerKey"
}

private data class OwnerField(val identity: TerritoryIdentity, val seeds: List<TerritorySeed>)
private data class OwnerInfluence(val nearestDistance: Double, val reinforcement: Double)
private data class InfluenceSeed(val systemId: Int, val ownerIndex: Int, val position: MapPoint)
private data class ProtectedCore(
    val systemId: Int,
    val ownerIndex: Int,
    val position: MapPoint,
    val radius: Double,
)
private data class InfluenceBucket(val x: Int, val y: Int) {
    companion object {
        fun forPoint(point: MapPoint, bucketSize: Double) = InfluenceBucket(
            floor(point.x / bucketSize).toInt(),
            floor(point.y / bucketSize).toInt(),
        )
    }
}
private data class SharedTerritoryField(
    val grid: TerritoryGrid,
    val owners: List<OwnerField>,
    val assignment: Map<GridCell, Int>,
    val ownerColors: List<Color>,
    val protectedCoreRadius: Double,
    val protectedCores: List<ProtectedCore>,
)

internal data class GridCell(val x: Int, val y: Int) {
    operator fun plus(other: GridCell) = GridCell(x + other.x, y + other.y)
    fun neighbors() = listOf(GridCell(x - 1, y), GridCell(x + 1, y), GridCell(x, y - 1), GridCell(x, y + 1))
    fun allNeighbors() = listOf(
        GridCell(x - 1, y - 1), GridCell(x, y - 1), GridCell(x + 1, y - 1),
        GridCell(x - 1, y), GridCell(x + 1, y),
        GridCell(x - 1, y + 1), GridCell(x, y + 1), GridCell(x + 1, y + 1),
    )
    fun sides() = listOf(
        GridSide(GridCell(x, y - 1), GridVertex(x, y), GridVertex(x + 1, y)),
        GridSide(GridCell(x + 1, y), GridVertex(x + 1, y), GridVertex(x + 1, y + 1)),
        GridSide(GridCell(x, y + 1), GridVertex(x + 1, y + 1), GridVertex(x, y + 1)),
        GridSide(GridCell(x - 1, y), GridVertex(x, y + 1), GridVertex(x, y)),
    )
    fun toFeatureCell() = FeatureTerritoryCell(x, y)
}

internal data class GridSide(val neighbor: GridCell, val first: GridVertex, val second: GridVertex)

private data class TerritoryGrid(val origin: MapPoint, val cellSize: Double) {
    fun cell(point: MapPoint) = GridCell(
        floor((point.x - origin.x) / cellSize).toInt(),
        floor((point.y - origin.y) / cellSize).toInt(),
    )
    fun center(cell: GridCell) = MapPoint(
        origin.x + (cell.x + 0.5) * cellSize,
        origin.y + (cell.y + 0.5) * cellSize,
    )
    fun point(vertex: GridVertex) = MapPoint(origin.x + vertex.x * cellSize, origin.y + vertex.y * cellSize)
}

private data class GridBounds(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
    operator fun contains(cell: GridCell): Boolean = cell.x in minX..maxX && cell.y in minY..maxY
    fun expanded(amount: Int) = GridBounds(minX - amount, minY - amount, maxX + amount, maxY + amount)
    fun isBoundary(cell: GridCell) = cell.x == minX || cell.x == maxX || cell.y == minY || cell.y == maxY
    companion object {
        fun from(cells: Set<GridCell>) = GridBounds(
            cells.minOf(GridCell::x), cells.minOf(GridCell::y), cells.maxOf(GridCell::x), cells.maxOf(GridCell::y),
        )
    }
}

internal data class GridVertex(val x: Int, val y: Int)

private fun GridVertex.toFeatureVertex() = FeatureTerritoryVertex(x, y)

private data class DirectedGridEdge(val start: GridVertex, val end: GridVertex) {
    val direction: Int = when {
        end.x > start.x -> 0
        end.y > start.y -> 1
        end.x < start.x -> 2
        else -> 3
    }
}

private data class UndirectedGridEdge(val first: GridVertex, val second: GridVertex) {
    companion object {
        fun between(first: GridVertex, second: GridVertex): UndirectedGridEdge =
            if (GRID_VERTEX_COMPARATOR.compare(first, second) <= 0) UndirectedGridEdge(first, second)
            else UndirectedGridEdge(second, first)
    }
}

private data class GridBoundaryChain(val vertices: List<GridVertex>, val closed: Boolean)

private fun morphologyOffsets(radius: Int): List<GridCell> = buildList {
    for (x in -radius..radius) for (y in -radius..radius) {
        if (x * x + y * y <= radius * radius) add(GridCell(x, y))
    }
}

private fun compareVertices(first: FeatureTerritoryVertex, second: FeatureTerritoryVertex): Int =
    compareValuesBy(first, second, FeatureTerritoryVertex::x, FeatureTerritoryVertex::y)

private val GRID_CELL_COMPARATOR = compareBy<GridCell> { it.x }.thenBy { it.y }
private val GRID_VERTEX_COMPARATOR = compareBy<GridVertex> { it.x }.thenBy { it.y }
private val DIRECTED_EDGE_COMPARATOR = compareBy<DirectedGridEdge> { it.start.x }
    .thenBy { it.start.y }.thenBy { it.direction }
private val UNDIRECTED_EDGE_COMPARATOR = compareBy<UndirectedGridEdge> { it.first.x }
    .thenBy { it.first.y }.thenBy { it.second.x }.thenBy { it.second.y }

private const val MAX_INFLUENCE_SCALE = 4.0
internal const val SOVEREIGNTY_DOMAIN_SUPPORT_SCALE = 1.35
private const val GRID_CELLS_PER_SCALE = 3.5
internal const val MIN_EMBLEM_COMPONENT_SYSTEM_COUNT = 4
internal const val MIN_EMBLEM_COMPONENT_CELL_COUNT = 80
internal const val MIN_EMBLEM_BOUNDARY_CLEARANCE_CELLS = 2
private const val EMBLEM_CENTRALITY_WEIGHT = 0.65
private const val EMBLEM_CLEARANCE_WEIGHT = 0.35
private const val REGION_LABEL_BASE_HALF_WIDTH_CELLS = 2.5
private const val REGION_LABEL_CHARACTER_WIDTH_CELLS = 0.38
private const val REGION_LABEL_MIN_HALF_WIDTH_CELLS = 4.5
private const val REGION_LABEL_MAX_HALF_WIDTH_CELLS = 8.0
private const val REGION_LABEL_HALF_HEIGHT_CELLS = 3.25
internal const val PROTECTED_CORE_SCALE_FACTOR = 0.28
private const val PROTECTED_CORE_EPSILON_CELLS = 0.05
private const val SOVEREIGNTY_DOMAIN_CLOSING_RADIUS_CELLS = 1
internal const val MAX_SUPPORTED_DOMAIN_HOLE_CELLS = 96
private const val OWNER_REINFORCEMENT_WEIGHT = 0.16
private const val ASSIGNMENT_SMOOTHING_PASSES = 4
private const val ASSIGNMENT_MAJORITY_THRESHOLD = 5
private const val OWNER_BOUNDARY_SMOOTHING_PASSES = 3
private const val OUTER_BOUNDARY_SMOOTHING_PASSES = 6
private const val OWNER_BOUNDARY_NEIGHBOR_WEIGHT = 0.21
private const val OUTER_BOUNDARY_NEIGHBOR_WEIGHT = 0.24
internal const val ADJACENT_COLOR_DISTANCE_THRESHOLD = 0.24
private const val ADJACENT_VARIANT_MIN_SATURATION = 0.66
private const val ADJACENT_VARIANT_MIN_VALUE = 0.84
private const val MIN_GEOMETRY_SCALE = 1e-9
