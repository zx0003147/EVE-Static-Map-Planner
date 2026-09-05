package dev.evestaticmapplanner.map

import androidx.compose.ui.graphics.Color
import dev.evestaticmapplanner.core.map.MapProjection
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.map.Real3DCanonicalProjection
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.feature.api.OverlayEntry
import dev.evestaticmapplanner.feature.api.OverlayEntryVisibility
import dev.evestaticmapplanner.feature.api.OverlayLayer
import dev.evestaticmapplanner.feature.api.OverlayLayerState
import dev.evestaticmapplanner.feature.api.OverlayProviderDescriptor
import dev.evestaticmapplanner.feature.api.OverlayImage
import dev.evestaticmapplanner.feature.api.OverlayState
import dev.evestaticmapplanner.feature.api.OverlaySystemMarker
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FeatureOverlayPresentationTest {
    @Test
    fun `marker-only loading and ready entries never create territory rectangles`() {
        val loadingEntry = OverlayEntry(
            layerId = "current-location",
            systemId = A1,
            title = "Character One",
            value = MARKER_ONLY_PRESENTATION_VALUE,
        )
        val readyEntry = OverlayEntry(
            layerId = "current-location",
            systemId = A1,
            title = "Character One",
            value = MARKER_ONLY_PRESENTATION_VALUE,
            systemMarker = OverlaySystemMarker(listOf(OverlayImage("image/png", byteArrayOf(1)))),
        )

        listOf(loadingEntry, readyEntry).forEach { entry ->
            val presentation = FeatureOverlayPresentationBuilder.build(state(listOf(entry)), scene())
            assertTrue(presentation.territories.isEmpty())
            assertTrue(presentation.legendSections.isEmpty())
        }
    }

    @Test
    fun `image marker without territory metadata stays out of political presentation`() {
        val entry = OverlayEntry(
            layerId = "current-location",
            systemId = A1,
            title = "Character One",
            systemMarker = OverlaySystemMarker(listOf(OverlayImage("image/png", byteArrayOf(1)))),
        )

        assertTrue(FeatureOverlayPresentationBuilder.build(state(listOf(entry)), scene()).territories.isEmpty())
    }

    @Test
    fun `nearby same-owner groups merge across a small layout gap`() {
        val presentation = FeatureOverlayPresentationBuilder.build(
            state(entries(
                entry(A1, "Alliance A", COLOR_A),
                entry(A2, "Alliance A", COLOR_A),
                entry(A3, "Alliance A", COLOR_A),
                entry(NEARBY_GAP_A, "Alliance A", COLOR_A),
            )),
            scene(),
        )

        assertEquals(1, presentation.territories.size)
        assertEquals(setOf(A1, A2, A3, NEARBY_GAP_A), presentation.territories.single().systemIds)
        assertTrue(presentation.territories.single().polygon.size >= 4)
    }

    @Test
    fun `shared field gives every cell at most one owner and neighboring owners one boundary`() {
        val overlayState = state(entries(
            entry(A1, "Alliance A", COLOR_A),
            entry(A2, "Alliance A", COLOR_A),
            entry(B2, "Alliance B", COLOR_B),
            entry(B3, "Alliance B", COLOR_B),
        ))
        val projectedScene = scene()
        val snapshot = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlayState, projectedScene)
        val presentation = FeatureOverlayPresentationBuilder.build(overlayState, projectedScene)

        assertEquals(snapshot.ownersByCell.size, snapshot.ownersByCell.keys.toSet().size)
        val start = snapshot.seedCellsBySystemId.getValue(A1)
        val end = snapshot.seedCellsBySystemId.getValue(B3)
        val rowOwners = (start.x..end.x).map { x -> snapshot.ownersByCell[FeatureTerritoryCell(x, start.y)] }
        assertTrue(rowOwners.all { it != null }, "Interacting owner envelopes must not contain an unassigned seam")
        assertEquals(1, rowOwners.zipWithNext().count { (first, second) -> first != second })
        assertAllOwnerTransitionsHaveOneSharedEdge(snapshot)

        val aPoints = presentation.territories.first { it.ownerLabel == "Alliance A" }.allContourPoints()
        val bPoints = presentation.territories.first { it.ownerLabel == "Alliance B" }.allContourPoints()
        assertTrue(aPoints.intersect(bPoints).size >= 2, "Adjacent fills must reuse the same boundary coordinates")
    }

    @Test
    fun `three-way assignment has no junction hole and remains deterministic`() {
        val overlayState = state(entries(
            entry(A1, "Alliance A", COLOR_A),
            entry(A2, "Alliance A", COLOR_A),
            entry(B2, "Alliance B", COLOR_B),
            entry(B3, "Alliance B", COLOR_B),
            entry(C1, "Alliance C", COLOR_C),
            entry(C2, "Alliance C", COLOR_C),
        ))
        val projectedScene = scene()

        val first = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlayState, projectedScene)
        val second = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlayState, projectedScene)

        assertEquals(first, second)
        assertEquals(3, first.ownersByCell.values.toSet().size)
        assertAllOwnerTransitionsHaveOneSharedEdge(first)
        val incidence = first.boundaryEdges.flatMap { listOf(it.first, it.second) }.groupingBy { it }.eachCount()
        assertTrue(incidence.values.any { it >= 3 }, "Three-way political junction must share a common graph vertex")
    }

    @Test
    fun `same owner remains one territory when presentation colors differ`() {
        val presentation = FeatureOverlayPresentationBuilder.build(
            state(entries(
                entry(A1, "Alliance A", COLOR_A),
                entry(A2, "Alliance A", COLOR_B),
            )),
            scene(),
        )

        assertEquals(1, presentation.territories.size)
        assertEquals(setOf(A1, A2), presentation.territories.single().systemIds)
    }

    @Test
    fun `territory output is deterministic for the same scene and overlay state`() {
        val overlayState = state(entries(
            entry(A1, "Alliance A", COLOR_A),
            entry(A2, "Alliance A", COLOR_A),
            entry(A3, "Alliance A", COLOR_A),
            entry(NEARBY_GAP_A, "Alliance A", COLOR_A),
            entry(B1, "Alliance B", COLOR_B),
        ))
        val projectedScene = scene()

        val first = FeatureOverlayPresentationBuilder.build(overlayState, projectedScene)
        val second = FeatureOverlayPresentationBuilder.build(overlayState, projectedScene)

        assertEquals(first, second)
    }

    @Test
    fun `nearby different owners remain separate even when colors match`() {
        val overlayState = state(entries(
            entry(A1, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
            entry(B1, "Alliance B", COLOR_A, ownerKey = "alliance:1002"),
        ))
        val presentation = FeatureOverlayPresentationBuilder.build(overlayState, scene())
        val snapshot = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlayState, scene())

        assertEquals(2, presentation.territories.size)
        assertEquals(2, snapshot.ownersByCell.values.toSet().size)
        assertEquals(
            setOf("Alliance A", "Alliance B"),
            presentation.territories.mapNotNullTo(mutableSetOf()) { it.ownerLabel },
        )
        val colors = presentation.territories.map(PresentedFeatureTerritory::color)
        assertNotEquals(colors[0], colors[1])
        assertTrue(presentationColorDistance(colors[0], colors[1]) >= ADJACENT_COLOR_DISTANCE_THRESHOLD)
    }

    @Test
    fun `stable owner metadata groups renamed entries independently of display title`() {
        val presentation = FeatureOverlayPresentationBuilder.build(
            state(entries(
                entry(A1, "Old Alliance Name", COLOR_A, ownerKey = "alliance:4242"),
                entry(A2, "New Alliance Name", COLOR_A, ownerKey = "alliance:4242"),
            )),
            scene(),
        )

        assertEquals(1, presentation.territories.size)
        assertEquals(setOf(A1, A2), presentation.territories.single().systemIds)
    }

    @Test
    fun `protected core assigns every covered cell center to its sovereign seed owner`() {
        val overlayState = state(entries(
            entry(A1, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
            entry(B2, "Alliance B", COLOR_B, ownerKey = "alliance:1002"),
        ))
        val snapshot = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlayState, scene())

        assertTrue(snapshot.protectedCoreRadius > 0.0)
        listOf(A1, B2).forEach { systemId -> assertCoreCellsOwned(snapshot, systemId) }
    }

    @Test
    fun `smoothed shared boundary remains outside feasible protected cores`() {
        val overlayState = state(entries(
            entry(A2, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
            entry(B2, "Alliance B", COLOR_B, ownerKey = "alliance:1002"),
        ))
        val snapshot = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlayState, scene())

        listOf(A2, B2).forEach { systemId -> assertBoundaryOutsideCore(snapshot, systemId) }
    }

    @Test
    fun `close rival seeds receive deterministic symmetric reduced cores with boundary between`() {
        val overlayState = state(entries(
            entry(A2, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
            entry(CLOSE_B, "Alliance B", COLOR_B, ownerKey = "alliance:1002"),
        ))
        val first = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlayState, scene())
        val second = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlayState, scene())

        assertEquals(first, second)
        val firstRadius = first.effectiveCoreRadiusBySystemId.getValue(A2)
        val secondRadius = first.effectiveCoreRadiusBySystemId.getValue(CLOSE_B)
        assertTrue(firstRadius < first.protectedCoreRadius)
        assertEquals(firstRadius, secondRadius, 1e-9)
        assertTrue(firstRadius + secondRadius < 4.0)
        assertCoreCellsOwned(first, A2)
        assertCoreCellsOwned(first, CLOSE_B)
        assertBoundaryOutsideCore(first, A2)
        assertBoundaryOutsideCore(first, CLOSE_B)
        assertNotEquals(
            first.ownersByCell.getValue(first.seedCellsBySystemId.getValue(A2)),
            first.ownersByCell.getValue(first.seedCellsBySystemId.getValue(CLOSE_B)),
        )
    }

    @Test
    fun `nearby three-way junction has no void overlap or protected-core violation`() {
        val overlayState = state(entries(
            entry(A2, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
            entry(CLOSE_B, "Alliance B", COLOR_B, ownerKey = "alliance:1002"),
            entry(CLOSE_C, "Alliance C", COLOR_C, ownerKey = "alliance:1003"),
        ))
        val snapshot = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlayState, scene())

        assertEquals(3, snapshot.ownersByCell.values.toSet().size)
        assertNoInteriorVoidBetweenSeeds(snapshot, listOf(A2, CLOSE_B, CLOSE_C))
        listOf(A2, CLOSE_B, CLOSE_C).forEach { systemId ->
            assertCoreCellsOwned(snapshot, systemId)
            assertBoundaryOutsideCore(snapshot, systemId)
        }
        assertAllOwnerTransitionsHaveOneSharedEdge(snapshot)
    }

    @Test
    fun `distant systems with the same owner produce separate islands`() {
        val presentation = FeatureOverlayPresentationBuilder.build(
            state(entries(
                entry(A1, "Alliance A", COLOR_A),
                entry(A2, "Alliance A", COLOR_A),
                entry(DISTANT_A, "Alliance A", COLOR_A),
            )),
            scene(),
        )

        assertEquals(2, presentation.territories.size)
        assertTrue(presentation.territories.any { it.systemIds == setOf(A1, A2) })
        assertTrue(presentation.territories.any { it.systemIds == setOf(DISTANT_A) })
    }

    @Test
    fun `enclosed rival territory is represented as a complementary hole rather than overlapping fill`() {
        val presentation = FeatureOverlayPresentationBuilder.build(
            state(entries(
                entry(RING_NORTH, "Alliance A", COLOR_A),
                entry(RING_EAST, "Alliance A", COLOR_A),
                entry(RING_SOUTH, "Alliance A", COLOR_A),
                entry(RING_WEST, "Alliance A", COLOR_A),
                entry(RING_CENTER, "Alliance B", COLOR_B),
            )),
            scene(),
        )

        assertEquals(2, presentation.territories.size)
        assertEquals(
            setOf(RING_NORTH, RING_EAST, RING_SOUTH, RING_WEST),
            presentation.territories.first { it.ownerLabel == "Alliance A" }.systemIds,
        )
        assertTrue(presentation.territories.first { it.ownerLabel == "Alliance A" }.holes.isNotEmpty())
        assertEquals(setOf(RING_CENTER), presentation.territories.first { it.ownerLabel == "Alliance B" }.systemIds)
    }

    @Test
    fun `small enclosed unsupported hole inside one owner domain is repaired`() {
        val projectedScene = scene()
        val overlayState = state(entries(
            entry(HOLE_NORTH, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
            entry(HOLE_EAST, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
            entry(HOLE_SOUTH, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
            entry(HOLE_WEST, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
        ))
        val snapshot = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlayState, projectedScene)
        val presentation = FeatureOverlayPresentationBuilder.build(overlayState, projectedScene)
        val ringPositions = listOf(HOLE_NORTH, HOLE_EAST, HOLE_SOUTH, HOLE_WEST)
            .map { systemId -> projectedScene.nodesById.getValue(systemId).position }
        val centerCell = cellAt(
            snapshot,
            MapPoint(ringPositions.map { it.x }.average(), ringPositions.map { it.y }.average()),
        )

        assertTrue(centerCell in snapshot.ownersByCell, "Enclosed supported-domain hole must be assigned")
        assertEquals(1, presentation.territories.size)
        assertTrue(presentation.territories.single().holes.isEmpty())
    }

    @Test
    fun `shared outer boundary smoothing reduces structural stair-step roughness`() {
        val overlayState = state(entries(
            entry(A1, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
            entry(A2, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
            entry(A3, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
            entry(NEARBY_GAP_A, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
        ))
        val snapshot = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlayState, scene())
        val rawRoughness = outerBoundaryRoughness(snapshot, smoothed = false)
        val smoothedRoughness = outerBoundaryRoughness(snapshot, smoothed = true)

        assertTrue(rawRoughness > 0.0)
        assertTrue(
            smoothedRoughness < rawRoughness * 0.65,
            "Expected materially smoother shared outer contour: raw=$rawRoughness smoothed=$smoothedRoughness",
        )
    }

    @Test
    fun `represented unknown is neutral owned territory rather than background`() {
        val overlayState = state(entries(entry(UNKNOWN, "Unknown / Unclaimed", UNKNOWN_COLOR)))
        val presentation = FeatureOverlayPresentationBuilder.build(overlayState, scene())
        val snapshot = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlayState, scene())

        assertEquals(setOf(UNKNOWN), presentation.territories.single().systemIds)
        assertEquals(Color(0xCC8EA8BD), presentation.territories.single().color)
        assertTrue(snapshot.seedCellsBySystemId.getValue(UNKNOWN) in snapshot.ownersByCell)
    }

    @Test
    fun `systems without visible entries have no territory and disabled state is empty`() {
        val hidden = entry(A1, "Alliance A", COLOR_A, OverlayEntryVisibility.HIDDEN)
        val hiddenPresentation = FeatureOverlayPresentationBuilder.build(state(entries(hidden)), scene())
        val disabledPresentation = FeatureOverlayPresentationBuilder.build(OverlayState(emptyList()), scene())

        assertTrue(hiddenPresentation.territories.isEmpty())
        assertTrue(disabledPresentation.territories.isEmpty())
        assertFalse(UNKNOWN in hiddenPresentation.territories.flatMapTo(mutableSetOf()) { it.systemIds })
    }

    @Test
    fun `true void and ordinary base systems without overlay support remain unassigned`() {
        val projectedScene = scene()
        val overlayState = state(entries(
            entry(A1, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
            entry(A2, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
        ))
        val snapshot = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlayState, projectedScene)
        val voidPosition = projectedScene.nodesById.getValue(VOID_SYSTEM).position

        assertTrue(cellAt(snapshot, voidPosition) !in snapshot.ownersByCell)
        assertTrue(snapshot.ownersByCell.keys.none { cell -> centerOf(snapshot, cell).distanceSquaredTo(voidPosition) < 100.0 })
        assertEquals(setOf(A1, A2), FeatureOverlayPresentationBuilder.build(overlayState, projectedScene)
            .territories.flatMapTo(mutableSetOf()) { it.systemIds })
    }

    @Test
    fun `distant sovereign clusters retain a genuine transparent gap`() {
        val projectedScene = scene()
        val overlayState = state(entries(
            entry(A1, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
            entry(A2, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
            entry(DISTANT_A, "Alliance B", COLOR_B, ownerKey = "alliance:1002"),
        ))
        val snapshot = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlayState, projectedScene)
        val start = snapshot.seedCellsBySystemId.getValue(A2)
        val end = snapshot.seedCellsBySystemId.getValue(DISTANT_A)
        val row = (start.x..end.x).map { FeatureTerritoryCell(it, start.y) }

        assertTrue(row.any { it !in snapshot.ownersByCell })
        assertTrue(row.windowed(8).any { cells -> cells.all { it !in snapshot.ownersByCell } })
        assertEquals(2, FeatureOverlayPresentationBuilder.build(overlayState, projectedScene).territories.size)
    }

    @Test
    fun `every assigned domain component traces to a real ownership seed`() {
        val overlayState = state(entries(
            entry(A1, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
            entry(A2, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
            entry(DISTANT_A, "Alliance B", COLOR_B, ownerKey = "alliance:1002"),
        ))
        val snapshot = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlayState, scene())
        val seedCells = snapshot.seedCellsBySystemId.values.toSet()

        assignmentComponents(snapshot.ownersByCell.keys).forEach { component ->
            assertTrue(component.any(seedCells::contains), "Seedless owned component: ${component.first()}")
        }
    }

    @Test
    fun `presentation colors and legend remain generic and reusable`() {
        val presentation = FeatureOverlayPresentationBuilder.build(
            state(entries(
                entry(A1, "Goonswarm Federation", COLOR_A),
                entry(B1, "Fraternity", COLOR_B),
            )),
            scene(),
        )

        assertEquals(Color(0xCCF2C94C), presentation.territories.first { A1 in it.systemIds }.color)
        assertEquals(
            FeatureOverlayLegendSection(
                "Sovereignty",
                listOf(
                    FeatureOverlayLegendEntry("Fraternity", Color(0xCC4D9DE0)),
                    FeatureOverlayLegendEntry("Goonswarm Federation", Color(0xCCF2C94C)),
                ),
            ),
            presentation.legendSections.single(),
        )
    }

    @Test
    fun `territory builds safely for official and real projections`() {
        val overlayState = state(entries(
            entry(A1, "Alliance A", COLOR_A),
            entry(A2, "Alliance A", COLOR_A),
            entry(A3, "Alliance A", COLOR_A),
            entry(NEARBY_GAP_A, "Alliance A", COLOR_A),
        ))

        listOf(OfficialPosition2DProjection, Real3DCanonicalProjection).forEach { projection ->
            val projectedScene = scene(projection)
            val territories = FeatureOverlayPresentationBuilder.build(overlayState, projectedScene).territories
            val snapshot = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlayState, projectedScene)
            assertEquals(1, territories.size)
            assertTrue(territories.single().polygon.all { it.x.isFinite() && it.y.isFinite() })
            assertTrue(territories.single().holes.flatten().all { it.x.isFinite() && it.y.isFinite() })
            listOf(A1, A2, A3, NEARBY_GAP_A).forEach { assertCoreCellsOwned(snapshot, it) }
        }
    }

    @Test
    fun `official and real projections both preserve unsupported base-system voids`() {
        val overlayState = state(entries(
            entry(A1, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
            entry(A2, "Alliance A", COLOR_A, ownerKey = "alliance:1001"),
        ))

        listOf(OfficialPosition2DProjection, Real3DCanonicalProjection).forEach { projection ->
            val projectedScene = scene(projection)
            val snapshot = FeatureOverlayPresentationBuilder.assignmentSnapshot(overlayState, projectedScene)
            val voidPosition = projectedScene.nodesById.getValue(VOID_SYSTEM).position

            assertTrue(cellAt(snapshot, voidPosition) !in snapshot.ownersByCell)
            listOf(A1, A2).forEach { assertCoreCellsOwned(snapshot, it) }
        }
    }

    private fun assertCoreCellsOwned(snapshot: FeatureTerritoryAssignmentSnapshot, systemId: Int) {
        val position = snapshot.seedPositionsBySystemId.getValue(systemId)
        val radius = snapshot.effectiveCoreRadiusBySystemId.getValue(systemId)
        val expectedOwner = snapshot.ownersByCell.getValue(snapshot.seedCellsBySystemId.getValue(systemId))
        cellsWhoseCentersAreWithin(snapshot, position, radius).forEach { cell ->
            assertEquals(expectedOwner, snapshot.ownersByCell[cell], "Protected core ownership failed at $cell")
        }
    }

    private fun assertBoundaryOutsideCore(snapshot: FeatureTerritoryAssignmentSnapshot, systemId: Int) {
        val position = snapshot.seedPositionsBySystemId.getValue(systemId)
        val radius = snapshot.effectiveCoreRadiusBySystemId.getValue(systemId)
        snapshot.smoothedBoundarySegments.forEach { segment ->
            assertTrue(
                distanceToSegment(position, segment) + 1e-8 >= radius,
                "Boundary entered core for $systemId (radius=$radius, segment=$segment)",
            )
        }
    }

    private fun cellsWhoseCentersAreWithin(
        snapshot: FeatureTerritoryAssignmentSnapshot,
        position: MapPoint,
        radius: Double,
    ): List<FeatureTerritoryCell> {
        val minX = floor((position.x - radius - snapshot.gridOrigin.x) / snapshot.cellSize).toInt()
        val maxX = floor((position.x + radius - snapshot.gridOrigin.x) / snapshot.cellSize).toInt()
        val minY = floor((position.y - radius - snapshot.gridOrigin.y) / snapshot.cellSize).toInt()
        val maxY = floor((position.y + radius - snapshot.gridOrigin.y) / snapshot.cellSize).toInt()
        return buildList {
            for (x in minX..maxX) for (y in minY..maxY) {
                val cell = FeatureTerritoryCell(x, y)
                val centerX = snapshot.gridOrigin.x + (x + 0.5) * snapshot.cellSize
                val centerY = snapshot.gridOrigin.y + (y + 0.5) * snapshot.cellSize
                val distance = sqrt((centerX - position.x) * (centerX - position.x) + (centerY - position.y) * (centerY - position.y))
                if (distance <= radius + 1e-9) add(cell)
            }
        }
    }

    private fun distanceToSegment(
        point: MapPoint,
        segment: FeatureTerritoryBoundarySegment,
    ): Double {
        val dx = segment.second.x - segment.first.x
        val dy = segment.second.y - segment.first.y
        val lengthSquared = dx * dx + dy * dy
        val projection = if (lengthSquared == 0.0) 0.0 else (
            (point.x - segment.first.x) * dx + (point.y - segment.first.y) * dy
        ) / lengthSquared
        val clamped = projection.coerceIn(0.0, 1.0)
        val nearestX = segment.first.x + clamped * dx
        val nearestY = segment.first.y + clamped * dy
        return sqrt((point.x - nearestX) * (point.x - nearestX) + (point.y - nearestY) * (point.y - nearestY))
    }

    private fun cellAt(snapshot: FeatureTerritoryAssignmentSnapshot, point: MapPoint) = FeatureTerritoryCell(
        floor((point.x - snapshot.gridOrigin.x) / snapshot.cellSize).toInt(),
        floor((point.y - snapshot.gridOrigin.y) / snapshot.cellSize).toInt(),
    )

    private fun centerOf(snapshot: FeatureTerritoryAssignmentSnapshot, cell: FeatureTerritoryCell) = MapPoint(
        snapshot.gridOrigin.x + (cell.x + 0.5) * snapshot.cellSize,
        snapshot.gridOrigin.y + (cell.y + 0.5) * snapshot.cellSize,
    )

    private fun assignmentComponents(cells: Set<FeatureTerritoryCell>): List<Set<FeatureTerritoryCell>> {
        val remaining = cells.toMutableSet()
        return buildList {
            while (remaining.isNotEmpty()) {
                val component = linkedSetOf<FeatureTerritoryCell>()
                val queue = ArrayDeque<FeatureTerritoryCell>()
                queue += remaining.first()
                remaining.remove(queue.first())
                while (queue.isNotEmpty()) {
                    val cell = queue.removeFirst()
                    component += cell
                    listOf(
                        FeatureTerritoryCell(cell.x - 1, cell.y),
                        FeatureTerritoryCell(cell.x + 1, cell.y),
                        FeatureTerritoryCell(cell.x, cell.y - 1),
                        FeatureTerritoryCell(cell.x, cell.y + 1),
                    ).forEach { neighbor -> if (remaining.remove(neighbor)) queue += neighbor }
                }
                add(component)
            }
        }
    }

    private fun outerBoundaryRoughness(
        snapshot: FeatureTerritoryAssignmentSnapshot,
        smoothed: Boolean,
    ): Double {
        val adjacency = linkedMapOf<FeatureTerritoryVertex, MutableSet<FeatureTerritoryVertex>>()
        snapshot.outerBoundaryEdges.forEach { edge ->
            adjacency.getOrPut(edge.first, ::linkedSetOf) += edge.second
            adjacency.getOrPut(edge.second, ::linkedSetOf) += edge.first
        }
        val ownerBorderVertices = (snapshot.boundaryEdges - snapshot.outerBoundaryEdges)
            .flatMapTo(mutableSetOf()) { edge -> listOf(edge.first, edge.second) }
        fun point(vertex: FeatureTerritoryVertex): MapPoint = if (smoothed) {
            snapshot.smoothedBoundaryPointsByVertex.getValue(vertex)
        } else {
            MapPoint(
                snapshot.gridOrigin.x + vertex.x * snapshot.cellSize,
                snapshot.gridOrigin.y + vertex.y * snapshot.cellSize,
            )
        }
        return adjacency.entries.sumOf { (vertex, neighbors) ->
            if (neighbors.size != 2 || vertex in ownerBorderVertices) return@sumOf 0.0
            val current = point(vertex)
            val points = neighbors.map(::point)
            val middle = MapPoint((points[0].x + points[1].x) / 2.0, (points[0].y + points[1].y) / 2.0)
            current.distanceSquaredTo(middle)
        }
    }

    private fun PresentedFeatureTerritory.allContourPoints() = (listOf(polygon) + holes).flatten().toSet()

    private fun assertAllOwnerTransitionsHaveOneSharedEdge(snapshot: FeatureTerritoryAssignmentSnapshot) {
        snapshot.ownersByCell.forEach { (cell, owner) ->
            listOf(FeatureTerritoryCell(cell.x + 1, cell.y), FeatureTerritoryCell(cell.x, cell.y + 1))
                .forEach { neighbor ->
                    val neighborOwner = snapshot.ownersByCell[neighbor] ?: return@forEach
                    if (neighborOwner == owner) return@forEach
                    val expected = if (neighbor.x != cell.x) {
                        FeatureTerritoryEdge.between(
                            FeatureTerritoryVertex(neighbor.x, cell.y),
                            FeatureTerritoryVertex(neighbor.x, cell.y + 1),
                        )
                    } else {
                        FeatureTerritoryEdge.between(
                            FeatureTerritoryVertex(cell.x, neighbor.y),
                            FeatureTerritoryVertex(cell.x + 1, neighbor.y),
                        )
                    }
                    assertTrue(expected in snapshot.boundaryEdges, "Missing canonical transition edge $expected")
                }
        }
    }

    private fun assertNoInteriorVoidBetweenSeeds(
        snapshot: FeatureTerritoryAssignmentSnapshot,
        systemIds: List<Int>,
    ) {
        val cells = systemIds.map(snapshot.seedCellsBySystemId::getValue)
        for (x in cells.minOf { it.x }..cells.maxOf { it.x }) {
            for (y in cells.minOf { it.y }..cells.maxOf { it.y }) {
                assertTrue(
                    FeatureTerritoryCell(x, y) in snapshot.ownersByCell,
                    "Artificial void at the interacting three-way junction: $x,$y",
                )
            }
        }
    }

    private fun state(entries: List<OverlayEntry>) = OverlayState(listOf(
        OverlayLayerState(
            OverlayProviderDescriptor("test.provider", "Test Provider"),
            OverlayLayer("sovereignty", "Sovereignty"),
            entries,
        ),
    ))

    private fun entries(vararg entries: OverlayEntry) = entries.toList()

    private fun entry(
        systemId: Int,
        owner: String,
        color: String,
        visibility: OverlayEntryVisibility = OverlayEntryVisibility.VISIBLE,
        ownerKey: String? = null,
    ) = OverlayEntry(
        layerId = "sovereignty",
        systemId = systemId,
        title = owner,
        value = listOfNotNull(ownerKey?.let { "owner-key:$it" }, "presentation-color:$color").joinToString(";"),
        visibility = visibility,
    )

    private fun scene(projection: MapProjection = OfficialPosition2DProjection) = MapSceneBuilder().build(
        StaticMapData(
            systems = listOf(
                system(A1, 0.0, 0.0),
                system(A2, 10.0, 0.0),
                system(A3, 20.0, 0.0),
                system(B1, 10.0, 10.0),
                system(UNKNOWN, 20.0, 10.0),
                system(DISTANT_A, 200.0, 0.0),
                system(NEARBY_GAP_A, 42.0, 0.0),
                system(RING_NORTH, 70.0, -10.0),
                system(RING_EAST, 80.0, 0.0),
                system(RING_SOUTH, 70.0, 10.0),
                system(RING_WEST, 60.0, 0.0),
                system(RING_CENTER, 70.0, 0.0),
                system(B2, 30.0, 0.0),
                system(B3, 40.0, 0.0),
                system(C1, 15.0, 25.0),
                system(C2, 25.0, 25.0),
                system(CLOSE_B, 14.0, 0.0),
                system(CLOSE_C, 12.0, 3.5),
                system(VOID_SYSTEM, 100.0, 45.0),
                system(HOLE_NORTH, 100.0, 84.0),
                system(HOLE_EAST, 116.0, 100.0),
                system(HOLE_SOUTH, 100.0, 116.0),
                system(HOLE_WEST, 84.0, 100.0),
            ),
            connections = listOf(
                StargateConnection.between(A1, A2),
                StargateConnection.between(A2, A3),
                StargateConnection.between(A2, B1),
                StargateConnection.between(A3, UNKNOWN),
            ),
        ),
        projection,
    )

    private fun system(id: Int, x: Double, y: Double) = SolarSystem(
        id = id,
        constellationId = 10,
        regionId = 100,
        name = "System $id",
        securityStatus = 0.0,
        securityClass = null,
        position = UniversePosition(x * COORDINATE_UNIT, 0.0, -y * COORDINATE_UNIT),
        schematicPosition = SchematicPosition(x * COORDINATE_UNIT, y * COORDINATE_UNIT),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )

    private companion object {
        const val A1 = 30_000_001
        const val A2 = 30_000_002
        const val A3 = 30_000_003
        const val B1 = 30_000_004
        const val UNKNOWN = 30_000_005
        const val DISTANT_A = 30_000_006
        const val NEARBY_GAP_A = 30_000_007
        const val RING_NORTH = 30_000_008
        const val RING_EAST = 30_000_009
        const val RING_SOUTH = 30_000_010
        const val RING_WEST = 30_000_011
        const val RING_CENTER = 30_000_012
        const val B2 = 30_000_013
        const val B3 = 30_000_014
        const val C1 = 30_000_015
        const val C2 = 30_000_016
        const val CLOSE_B = 30_000_017
        const val CLOSE_C = 30_000_018
        const val VOID_SYSTEM = 30_000_019
        const val HOLE_NORTH = 30_000_020
        const val HOLE_EAST = 30_000_021
        const val HOLE_SOUTH = 30_000_022
        const val HOLE_WEST = 30_000_023
        const val COLOR_A = "#CCF2C94C"
        const val COLOR_B = "#CC4D9DE0"
        const val COLOR_C = "#CCE76F51"
        const val UNKNOWN_COLOR = "#CC8EA8BD"
        const val COORDINATE_UNIT = 1_000_000_000_000_000.0
    }
}
