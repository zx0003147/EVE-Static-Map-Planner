package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionMarker
import dev.evestaticmapplanner.control.mission.MissionMarkerId
import dev.evestaticmapplanner.control.mission.MissionMarkerRole
import dev.evestaticmapplanner.core.map.MapPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MissionMarkerPresentationTest {
    @Test
    fun `single marker preserves the existing upper-right anchor`() {
        val presented = build(
            listOf(input(marker("oldest", systemId = 1), MapPoint(100.0, 80.0), labelHeightPx = 14.0)),
        ).single()

        assertEquals(MapPoint(118.0, 62.0), presented.badgeCenter)
        assertEquals(0, presented.localStackIndex)
    }

    @Test
    fun `same-system markers form a deterministic compact upward stack`() {
        val inputs = listOf(
            input(marker("a", 1), labelHeightPx = 14.0),
            input(marker("b", 1), labelHeightPx = 14.0),
            input(marker("c", 1), labelHeightPx = 24.0),
            input(marker("d", 1), labelHeightPx = 14.0),
        )

        val first = build(inputs)
        val repeated = build(inputs)

        assertEquals(first, repeated)
        assertEquals(
            missionMarkerFirstBadgeCenter(inputs.first().systemCenter, outwardSpacingPx = 7.0),
            first.first().badgeCenter,
        )
        assertEquals(listOf(0, 1, 2, 3), first.map(PresentedMissionMarker::localStackIndex))
        assertEquals(1, first.map { it.badgeCenter.x }.distinct().size)
        assertTrue(first.zipWithNext().all { (lower, upper) -> upper.badgeCenter.y < lower.badgeCenter.y })
        first.zipWithNext().forEach { (lower, upper) ->
            val lowerTop = lower.badgeCenter.y - lower.occupiedRowHeightPx / 2.0
            val upperBottom = upper.badgeCenter.y + upper.occupiedRowHeightPx / 2.0
            assertEquals(AI_MISSION_MARKER_ROW_GAP_PX, lowerTop - upperBottom, absoluteTolerance = 0.0001)
        }
    }

    @Test
    fun `unrelated systems do not affect local order or positions`() {
        val allInputs = listOf(
            input(marker("a1", 1)),
            input(marker("b1", 2)),
            input(marker("a2", 1)),
            input(marker("c1", 3)),
            input(marker("a3", 1)),
            input(marker("b2", 2)),
        )

        val all = build(allInputs)
        val systemAOnly = build(allInputs.filter { it.marker.systemId == 1 })

        assertEquals(listOf(0, 0, 1, 0, 2, 1), all.map(PresentedMissionMarker::localStackIndex))
        assertEquals(systemAOnly, all.filter { it.marker.systemId == 1 })
    }

    @Test
    fun `markers from different missions share one same-system stack`() {
        val markers = listOf(
            input(marker("first", 1, missionId = "mission-a")),
            input(marker("second", 1, missionId = "mission-b")),
            input(marker("third", 1, missionId = "mission-a")),
        )

        val presented = build(markers)

        assertEquals(listOf(0, 1, 2), presented.map(PresentedMissionMarker::localStackIndex))
        assertTrue(presented.zipWithNext().all { (lower, upper) -> upper.badgeCenter.y < lower.badgeCenter.y })
    }

    @Test
    fun `label-less marker reserves badge height and cannot overlap the next row`() {
        val presented = build(
            listOf(
                input(marker("without-label", 1), labelHeightPx = null),
                input(marker("with-label", 1), labelHeightPx = 12.0),
            ),
        )

        assertEquals(AI_MISSION_MARKER_BADGE_DIAMETER_PX, presented.first().occupiedRowHeightPx)
        val lowerTop = presented[0].badgeCenter.y - presented[0].occupiedRowHeightPx / 2.0
        val upperBottom = presented[1].badgeCenter.y + presented[1].occupiedRowHeightPx / 2.0
        assertEquals(AI_MISSION_MARKER_ROW_GAP_PX, lowerTop - upperBottom, absoluteTolerance = 0.0001)
    }

    @Test
    fun `invalid layout dimensions are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            input(marker("invalid-label", 1), labelHeightPx = Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            MissionMarkerPresentationBuilder.build(emptyList(), outwardSpacingPx = Double.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> {
            MissionMarkerPresentationBuilder.build(emptyList(), outwardSpacingPx = 7.0, rowGapPx = -1.0)
        }
    }

    private fun build(inputs: List<MissionMarkerLayoutInput>) = MissionMarkerPresentationBuilder.build(
        inputs = inputs,
        outwardSpacingPx = 7.0,
    )

    private fun input(
        marker: MissionMarker,
        systemCenter: MapPoint = MapPoint(marker.systemId * 100.0, marker.systemId * 80.0),
        labelHeightPx: Double? = null,
    ) = MissionMarkerLayoutInput(marker, systemCenter, labelHeightPx)

    private fun marker(
        markerId: String,
        systemId: Int,
        missionId: String = "mission",
    ) = MissionMarker(
        missionId = MissionId(missionId),
        markerId = MissionMarkerId(markerId),
        systemId = systemId,
        role = MissionMarkerRole.INFO,
        label = null,
        notes = null,
        colorOverride = null,
    )
}
