package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import dev.evestaticmapplanner.marker.SavedMarkerChildVisual
import dev.evestaticmapplanner.marker.SavedMarkerChildVisuals
import dev.evestaticmapplanner.preferences.MarkerPreferences
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class PresentedMapMarker(
    val marker: Marker,
    val screenCenter: MapPoint,
    val visibleName: String?,
    val systemName: String,
    val systemNameVisible: Boolean,
    val visualStyle: MarkerVisualStyle,
    val children: List<PresentedSavedMarkerChild> = emptyList(),
)

data class PresentedSavedMarkerChild(
    val child: SavedMarkerChild,
    val visual: SavedMarkerChildVisual,
    val screenCenter: MapPoint,
)

enum class MarkerVisualStyle {
    OUTER_RING,
    OUTLINE_DIAMOND,
}

object MarkerMapPresentationBuilder {
    fun build(
        scene: ProjectedMapScene,
        transform: MapTransform,
        visibleSystemIds: Collection<Int>,
        markersBySystemId: Map<Int, Marker>,
        preferences: MarkerPreferences,
        semanticMode: SemanticLabelMode,
        offsetPx: Double,
        childrenByParentSystemId: Map<Int, List<SavedMarkerChild>> = emptyMap(),
        expandedSystemIds: Set<Int> = emptySet(),
        childOrbitRadiusPx: Double = 38.0,
        systemNameVisibleIds: Set<Int> = emptySet(),
    ): List<PresentedMapMarker> {
        if (!preferences.showMarkers) return emptyList()
        return visibleSystemIds.mapNotNull { systemId ->
            val marker = markersBySystemId[systemId] ?: return@mapNotNull null
            val node = scene.nodesById[systemId] ?: return@mapNotNull null
            val systemPosition = transform.worldToScreen(node.position)
            val saved = marker.persistence == MarkerPersistence.SAVED
            PresentedMapMarker(
                marker = marker,
                screenCenter = if (saved) systemPosition else MapPoint(systemPosition.x - offsetPx, systemPosition.y - offsetPx),
                visibleName = marker.name.takeIf {
                    preferences.showMarkerNames && semanticMode == SemanticLabelMode.SYSTEM
                },
                systemName = node.system.name,
                systemNameVisible = saved && systemId in systemNameVisibleIds,
                visualStyle = if (saved) {
                    MarkerVisualStyle.OUTER_RING
                } else {
                    MarkerVisualStyle.OUTLINE_DIAMOND
                },
                children = if (saved && systemId in expandedSystemIds) {
                    radialChildren(childrenByParentSystemId[systemId].orEmpty(), systemPosition, childOrbitRadiusPx)
                } else {
                    emptyList()
                },
            )
        }
    }

    private fun radialChildren(
        children: List<SavedMarkerChild>,
        center: MapPoint,
        orbitRadiusPx: Double,
    ): List<PresentedSavedMarkerChild> = children.mapIndexed { index, child ->
        val angle = Math.toRadians(-90.0 + index * (360.0 / children.size))
        PresentedSavedMarkerChild(
            child = child,
            visual = SavedMarkerChildVisuals.resolve(child.type),
            screenCenter = MapPoint(center.x + cos(angle) * orbitRadiusPx, center.y + sin(angle) * orbitRadiusPx),
        )
    }
}

internal data class MarkerNameLabelLayout(
    val topLeft: MapPoint,
    val bounds: ScreenBounds,
    val avoidedSystemName: Boolean,
)

data class SystemNameVisualObstacles(
    val centeredRightExtentPx: Double? = null,
    val screenBounds: List<ScreenBounds> = emptyList(),
)

internal data class SystemNameLabelLayout(
    val topLeft: MapPoint,
    val bounds: ScreenBounds,
)

internal fun systemNameVisualObstaclesBySystemId(
    localMarkers: List<PresentedMapMarker>,
    sharedMarkers: List<PresentedSharedMarker>,
    sharedGeometry: SharedMarkerVisualGeometry,
    localSavedVisualRadiusPx: Double,
): Map<Int, SystemNameVisualObstacles> {
    val obstacles = linkedMapOf<Int, SystemNameVisualObstacles>()
    localMarkers.asSequence()
        .filter { it.visualStyle == MarkerVisualStyle.OUTER_RING }
        .forEach { marker ->
            obstacles[marker.marker.systemId] = SystemNameVisualObstacles(
                centeredRightExtentPx = localSavedVisualRadiusPx,
            )
        }
    sharedMarkers.forEach { marker ->
        val systemId = marker.marker.systemId
        val existing = obstacles[systemId] ?: SystemNameVisualObstacles()
        val primaryRingExtent = marker.ringRadiusPx + sharedGeometry.primaryStrokePx / 2.0
        val secondaryRingExtent = marker.ringRadiusPx +
            sharedGeometry.secondaryOffsetPx +
            sharedGeometry.secondaryStrokePx / 2.0
        val sharedRingExtent = maxOf(primaryRingExtent, secondaryRingExtent)
        val badgeRadius = sharedGeometry.badgeRadiusPx.toDouble()
        val badgeBounds = ScreenBounds(
            minX = marker.badgeCenter.x - badgeRadius,
            minY = marker.badgeCenter.y - badgeRadius,
            maxX = marker.badgeCenter.x + badgeRadius,
            maxY = marker.badgeCenter.y + badgeRadius,
        )
        obstacles[systemId] = SystemNameVisualObstacles(
            centeredRightExtentPx = maxOf(existing.centeredRightExtentPx ?: 0.0, sharedRingExtent),
            screenBounds = existing.screenBounds + badgeBounds,
        )
    }
    return obstacles
}

internal fun systemNameLabelLayout(
    center: MapPoint,
    labelSize: MapSize,
    existingOffsetPx: Double,
    visualObstacles: SystemNameVisualObstacles? = null,
    safetyGapPx: Double = 0.0,
): SystemNameLabelLayout {
    var labelMinX = center.x + existingOffsetPx
    visualObstacles?.centeredRightExtentPx?.let { rightExtent ->
        labelMinX = maxOf(labelMinX, center.x + rightExtent + safetyGapPx)
    }
    val labelMinY = center.y - labelSize.height / 2.0
    val labelMaxY = labelMinY + labelSize.height
    visualObstacles?.screenBounds.orEmpty().forEach { obstacle ->
        val verticallyIntersects = labelMaxY >= obstacle.minY && labelMinY <= obstacle.maxY
        if (verticallyIntersects) {
            labelMinX = maxOf(labelMinX, obstacle.maxX + safetyGapPx)
        }
    }
    val topLeft = MapPoint(labelMinX, labelMinY)
    return SystemNameLabelLayout(topLeft, topLeft.toScreenBounds(labelSize))
}

internal fun markerNameLabelLayout(
    marker: PresentedMapMarker,
    markerLabelSize: MapSize,
    systemLabelBounds: ScreenBounds?,
    markerRadiusPx: Double,
    rightGapPx: Double,
    belowRingGapPx: Double,
    collisionPaddingPx: Double,
): MarkerNameLabelLayout {
    val preferredTopLeft = MapPoint(
        marker.screenCenter.x + markerRadiusPx + rightGapPx,
        marker.screenCenter.y - markerLabelSize.height / 2.0,
    )
    val preferredBounds = preferredTopLeft.toScreenBounds(markerLabelSize)
    val systemBounds = systemLabelBounds
        ?.takeIf { marker.systemNameVisible && marker.visualStyle == MarkerVisualStyle.OUTER_RING }
    if (systemBounds == null || !preferredBounds.intersects(systemBounds, collisionPaddingPx)) {
        return MarkerNameLabelLayout(preferredTopLeft, preferredBounds, avoidedSystemName = false)
    }

    val belowTopLeft = MapPoint(
        marker.screenCenter.x - markerLabelSize.width / 2.0,
        maxOf(
            marker.screenCenter.y + markerRadiusPx + belowRingGapPx,
            systemBounds.maxY + collisionPaddingPx,
        ),
    )
    return MarkerNameLabelLayout(
        topLeft = belowTopLeft,
        bounds = belowTopLeft.toScreenBounds(markerLabelSize),
        avoidedSystemName = true,
    )
}

internal fun systemNameLabelBounds(center: MapPoint, labelSize: MapSize): ScreenBounds {
    return systemNameLabelLayout(
        center = center,
        labelSize = labelSize,
        existingOffsetPx = SYSTEM_LABEL_OFFSET_PX,
    ).bounds
}

private fun MapPoint.toScreenBounds(size: MapSize) = ScreenBounds(
    minX = x,
    minY = y,
    maxX = x + size.width,
    maxY = y + size.height,
)

internal const val LOCAL_MARKER_LABEL_RIGHT_GAP_DP = 4f
internal const val LOCAL_MARKER_LABEL_BELOW_RING_GAP_DP = 10f
internal const val LOCAL_MARKER_LABEL_COLLISION_PADDING_DP = 2f
internal const val SYSTEM_LABEL_MARKER_SAFETY_GAP_DP = 4f

data class SavedMarkerPointerHit(
    val parentSystemId: Int,
    val child: PresentedSavedMarkerChild?,
)

internal fun isSavedMarkerExpanded(
    isHovered: Boolean,
    isSelected: Boolean,
    isInteractionActive: Boolean = false,
): Boolean = isHovered || isSelected || isInteractionActive

internal fun hitTestSavedMarkerInteraction(
    markers: List<PresentedMapMarker>,
    point: MapPoint,
    badgeHitRadiusPx: Double,
    corridorRadiusPx: Double,
): SavedMarkerPointerHit? {
    markers.asReversed().forEach { marker ->
        marker.children.asReversed().firstOrNull { child ->
            distance(point, child.screenCenter) <= badgeHitRadiusPx
        }?.let { return SavedMarkerPointerHit(marker.marker.systemId, it) }
    }
    markers.asReversed().forEach { marker ->
        if (marker.children.any { child ->
                distanceToSegment(point, marker.screenCenter, child.screenCenter) <= corridorRadiusPx
            }
        ) return SavedMarkerPointerHit(marker.marker.systemId, null)
    }
    return null
}

private fun distance(first: MapPoint, second: MapPoint): Double = hypot(first.x - second.x, first.y - second.y)

private fun distanceToSegment(point: MapPoint, start: MapPoint, end: MapPoint): Double {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0.0) return distance(point, start)
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
    return distance(point, MapPoint(start.x + t * dx, start.y + t * dy))
}
