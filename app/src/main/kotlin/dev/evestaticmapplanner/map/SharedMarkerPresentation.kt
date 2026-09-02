package dev.evestaticmapplanner.map

import androidx.compose.ui.graphics.Color
import dev.evestaticmapplanner.control.mission.MissionMarker
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.shared.model.SharedMapState
import dev.evestaticmapplanner.shared.model.SharedMarkerColor
import java.time.Instant
import java.util.Locale
import kotlin.math.sqrt

data class SharedMarkerPresentation(
    val markerId: String,
    val systemId: Int,
    val name: String,
    val color: SharedMarkerColor,
    val tags: List<String>,
    val notes: String?,
    val updatedByUserId: String,
    val updatedByDisplayName: String,
    val updatedAt: Instant,
)

data class SkippedSharedMarkerPresentation(
    val markerId: String,
    val systemId: Int,
)

data class SharedMarkerPresentationState(
    val workspaceId: String? = null,
    val revision: Long? = null,
    val isVisible: Boolean = true,
    val isStale: Boolean = false,
    val markersBySystemId: Map<Int, SharedMarkerPresentation> = emptyMap(),
    val skippedUnknownSystems: List<SkippedSharedMarkerPresentation> = emptyList(),
) {
    companion object {
        val Empty = SharedMarkerPresentationState()
    }
}

object SharedMarkerPresentationAdapter {
    fun build(
        state: SharedMapState,
        knownSystemIds: Set<Int>,
        isVisible: Boolean,
    ): SharedMarkerPresentationState {
        val snapshot = state.snapshot ?: return SharedMarkerPresentationState(
            workspaceId = state.selectedWorkspaceId,
            isVisible = isVisible,
            isStale = state.stale,
        )
        val markers = linkedMapOf<Int, SharedMarkerPresentation>()
        val skipped = mutableListOf<SkippedSharedMarkerPresentation>()
        snapshot.markers.values.sortedWith(compareBy({ it.systemId }, { it.markerId })).forEach { marker ->
            if (marker.systemId !in knownSystemIds) {
                skipped += SkippedSharedMarkerPresentation(marker.markerId, marker.systemId)
            } else {
                markers[marker.systemId] = SharedMarkerPresentation(
                    markerId = marker.markerId,
                    systemId = marker.systemId,
                    name = marker.name,
                    color = marker.color,
                    tags = marker.tags,
                    notes = marker.notes,
                    updatedByUserId = marker.updatedBy.userId,
                    updatedByDisplayName = marker.updatedBy.displayName,
                    updatedAt = marker.updatedAt,
                )
            }
        }
        return SharedMarkerPresentationState(
            workspaceId = snapshot.workspaceId,
            revision = snapshot.revision,
            isVisible = isVisible,
            isStale = state.stale,
            markersBySystemId = markers,
            skippedUnknownSystems = skipped,
        )
    }
}

data class SharedMarkerVisualGeometry(
    val baseRingRadiusPx: Double,
    val localRingClearancePx: Double,
    val primaryStrokePx: Float,
    val secondaryOffsetPx: Double,
    val secondaryStrokePx: Float,
    val badgeOutwardOffsetPx: Double,
    val badgeRadiusPx: Float,
    val badgeBorderWidthPx: Float,
    val badgeDotRadiusPx: Float,
    val badgeLinkWidthPx: Float,
) {
    fun ringRadius(localSavedRingRadiusPx: Double?): Double = maxOf(
        baseRingRadiusPx,
        localSavedRingRadiusPx?.plus(localRingClearancePx) ?: baseRingRadiusPx,
    )

    fun badgeCenter(center: MapPoint, ringRadiusPx: Double): MapPoint {
        val diagonal = (ringRadiusPx + badgeOutwardOffsetPx) / sqrt(2.0)
        return MapPoint(center.x + diagonal, center.y + diagonal)
    }
}

data class PresentedSharedMarker(
    val marker: SharedMarkerPresentation,
    val screenCenter: MapPoint,
    val ringRadiusPx: Double,
    val badgeCenter: MapPoint,
    val hasLocalSavedMarker: Boolean,
    val hasAiMissionMarker: Boolean,
) {
    val hoverLines: List<String>
        get() = listOf(
            marker.name,
            marker.tags.firstOrNull()?.let { "Shared Marker · ${sharedMarkerTagLabel(it)}" } ?: "Shared Marker",
        )
}

object SharedMarkerMapPresentationBuilder {
    fun build(
        scene: ProjectedMapScene,
        transform: MapTransform,
        visibleSystemIds: Collection<Int>,
        state: SharedMarkerPresentationState,
        localMarkersBySystemId: Map<Int, Marker>,
        missionMarkers: List<MissionMarker>,
        geometry: SharedMarkerVisualGeometry,
        localSavedRingRadiusPx: Double,
    ): List<PresentedSharedMarker> {
        if (!state.isVisible || state.markersBySystemId.isEmpty()) return emptyList()
        val visible = visibleSystemIds.toHashSet()
        val aiSystemIds = missionMarkers.asSequence().map(MissionMarker::systemId).toHashSet()
        return state.markersBySystemId.values.asSequence()
            .filter { it.systemId in visible }
            .mapNotNull { marker ->
                val node = scene.nodesById[marker.systemId] ?: return@mapNotNull null
                val localSaved = localMarkersBySystemId[marker.systemId]
                    ?.persistence == MarkerPersistence.SAVED
                val center = transform.worldToScreen(node.position)
                val radius = geometry.ringRadius(localSavedRingRadiusPx.takeIf { localSaved })
                PresentedSharedMarker(
                    marker = marker,
                    screenCenter = center,
                    ringRadiusPx = radius,
                    badgeCenter = geometry.badgeCenter(center, radius),
                    hasLocalSavedMarker = localSaved,
                    hasAiMissionMarker = marker.systemId in aiSystemIds,
                )
            }
            .sortedWith(compareBy({ it.marker.systemId }, { it.marker.markerId }))
            .toList()
    }
}

internal fun sharedMarkerColor(color: SharedMarkerColor): Color = when (color) {
    SharedMarkerColor.RED -> Color(0xFFFF5D73)
    SharedMarkerColor.ORANGE -> Color(0xFFFF9F43)
    SharedMarkerColor.YELLOW -> Color(0xFFFFD166)
    SharedMarkerColor.GREEN -> Color(0xFF57E389)
    SharedMarkerColor.BLUE -> Color(0xFF42BFF5)
    SharedMarkerColor.PURPLE -> Color(0xFFA98BFF)
    SharedMarkerColor.WHITE -> Color(0xFFF1F5F8)
}

internal fun sharedMarkerTagLabel(tag: String): String = tag.uppercase(Locale.ROOT)

internal const val SHARED_MARKER_BASE_RING_RADIUS_DP = 17f
internal const val SHARED_MARKER_LOCAL_RING_CLEARANCE_DP = 4f
internal const val SHARED_MARKER_PRIMARY_STROKE_DP = 1.75f
internal const val SHARED_MARKER_SECONDARY_OFFSET_DP = 2.5f
internal const val SHARED_MARKER_SECONDARY_STROKE_DP = 1f
internal const val SHARED_MARKER_BADGE_OUTWARD_OFFSET_DP = 2.5f
internal const val SHARED_MARKER_BADGE_RADIUS_DP = 5.5f
internal const val SHARED_MARKER_BADGE_BORDER_WIDTH_DP = 1f
internal const val SHARED_MARKER_BADGE_DOT_RADIUS_DP = 1.25f
internal const val SHARED_MARKER_BADGE_LINK_WIDTH_DP = 1f
