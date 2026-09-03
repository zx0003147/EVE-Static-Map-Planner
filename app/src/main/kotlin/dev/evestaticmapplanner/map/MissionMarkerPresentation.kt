package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.control.mission.MissionMarker
import dev.evestaticmapplanner.core.map.MapPoint

internal data class MissionMarkerLayoutInput(
    val marker: MissionMarker,
    val systemCenter: MapPoint,
    val labelHeightPx: Double?,
) {
    init {
        require(labelHeightPx == null || labelHeightPx.isFinite() && labelHeightPx >= 0.0) {
            "Mission marker label height must be finite and non-negative"
        }
    }

    val occupiedRowHeightPx: Double
        get() = maxOf(AI_MISSION_MARKER_BADGE_DIAMETER_PX, labelHeightPx ?: 0.0)
}

internal data class PresentedMissionMarker(
    val marker: MissionMarker,
    val badgeCenter: MapPoint,
    val localStackIndex: Int,
    val occupiedRowHeightPx: Double,
)

internal object MissionMarkerPresentationBuilder {
    fun build(
        inputs: List<MissionMarkerLayoutInput>,
        outwardSpacingPx: Double,
        rowGapPx: Double = AI_MISSION_MARKER_ROW_GAP_PX,
    ): List<PresentedMissionMarker> {
        require(outwardSpacingPx.isFinite() && outwardSpacingPx >= 0.0)
        require(rowGapPx.isFinite() && rowGapPx >= 0.0)

        val stackCursors = mutableMapOf<Int, MissionMarkerStackCursor>()
        return inputs.map { input ->
            val existing = stackCursors[input.marker.systemId]
            val firstCenter = missionMarkerFirstBadgeCenter(input.systemCenter, outwardSpacingPx)
            val localStackIndex = existing?.nextLocalStackIndex ?: 0
            val badgeCenter = if (existing == null) {
                firstCenter
            } else {
                MapPoint(
                    x = existing.horizontalAnchorPx,
                    y = existing.topEdgePx - rowGapPx - input.occupiedRowHeightPx / 2.0,
                )
            }
            stackCursors[input.marker.systemId] = MissionMarkerStackCursor(
                horizontalAnchorPx = existing?.horizontalAnchorPx ?: firstCenter.x,
                topEdgePx = badgeCenter.y - input.occupiedRowHeightPx / 2.0,
                nextLocalStackIndex = localStackIndex + 1,
            )
            PresentedMissionMarker(
                marker = input.marker,
                badgeCenter = badgeCenter,
                localStackIndex = localStackIndex,
                occupiedRowHeightPx = input.occupiedRowHeightPx,
            )
        }
    }
}

internal fun missionMarkerFirstBadgeCenter(
    systemCenter: MapPoint,
    outwardSpacingPx: Double,
): MapPoint {
    require(outwardSpacingPx.isFinite() && outwardSpacingPx >= 0.0)
    val diagonalOffset = AI_MISSION_MARKER_BASE_OFFSET_PX + outwardSpacingPx
    return MapPoint(
        x = systemCenter.x + diagonalOffset,
        y = systemCenter.y - diagonalOffset,
    )
}

private data class MissionMarkerStackCursor(
    val horizontalAnchorPx: Double,
    val topEdgePx: Double,
    val nextLocalStackIndex: Int,
)

internal const val AI_MISSION_MARKER_BASE_OFFSET_PX = 11.0
internal const val AI_MISSION_MARKER_OUTWARD_SPACING_DP = 7f
internal const val AI_MISSION_MARKER_BADGE_OUTER_RADIUS_PX = 9f
internal const val AI_MISSION_MARKER_BADGE_DIAMETER_PX = AI_MISSION_MARKER_BADGE_OUTER_RADIUS_PX * 2.0
internal const val AI_MISSION_MARKER_ROW_GAP_PX = 2.0
