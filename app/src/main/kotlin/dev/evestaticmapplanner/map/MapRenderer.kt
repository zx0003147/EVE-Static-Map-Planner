package dev.evestaticmapplanner.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.core.map.ProjectedRouteOverlay
import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.map.ProjectedJumpRangeOverlay
import dev.evestaticmapplanner.core.map.ProjectedCapitalRouteOverlay
import dev.evestaticmapplanner.preferences.MapDisplayPreferences
import dev.evestaticmapplanner.marker.markerColor
import dev.evestaticmapplanner.control.mission.MissionMarker

enum class MapDetailLevel {
    OVERVIEW,
    NORMAL,
    DETAIL,
}

class MapRenderCache {
    private val labels = mutableMapOf<LabelCacheKey, TextLayoutResult>()

    fun label(
        text: String,
        type: MapLabelType,
        preferences: MapDisplayPreferences,
        textMeasurer: TextMeasurer,
    ): TextLayoutResult {
        val style = MapLabelStyleResolver.resolve(type, preferences)
        return labels.getOrPut(LabelCacheKey(text, type, style)) {
            textMeasurer.measure(
                text = text,
                style = TextStyle(
                    fontSize = style.fontSizeSp.sp,
                    letterSpacing = style.letterSpacingSp.sp,
                ),
                softWrap = false,
            )
        }
    }

    private data class LabelCacheKey(
        val text: String,
        val type: MapLabelType,
        val style: MapLabelStyle,
    )
}

object MapRenderer {
    fun detailLevel(zoom: Double): MapDetailLevel = when {
        zoom >= DETAIL_ZOOM -> MapDetailLevel.DETAIL
        zoom >= NORMAL_ZOOM -> MapDetailLevel.NORMAL
        else -> MapDetailLevel.OVERVIEW
    }

    fun DrawScope.drawBase(
        scene: ProjectedMapScene,
        transform: MapTransform,
        textMeasurer: TextMeasurer,
        cache: MapRenderCache,
        presentation: MapLabelPresentation,
        preferences: MapDisplayPreferences,
        emphasis: MapVisualEmphasis = MapVisualEmphasis.None,
    ) {
        drawRect(MAP_BACKGROUND)

        drawPresentedLabels(
            labels = presentation.regionLabels.filter { it.type == MapLabelType.REGION_BACKGROUND },
            textMeasurer = textMeasurer,
            cache = cache,
            preferences = preferences,
            alphaMultiplier = emphasis.hierarchyLabelAlphaMultiplier,
        )

        val visibleBounds = transform.visibleWorldBounds(MAP_CONTENT_CULL_MARGIN_PX)
        scene.edges.forEach { edge ->
            if (!edge.bounds.intersects(visibleBounds)) return@forEach
            drawLine(
                color = EDGE_COLOR.multiplyAlpha(
                    emphasis.stargateAlphaMultiplier(edge.firstSystemId, edge.secondSystemId),
                ),
                start = transform.worldToScreen(edge.first).toOffset(),
                end = transform.worldToScreen(edge.second).toOffset(),
                strokeWidth = 1f,
            )
        }

        val level = detailLevel(transform.viewport.zoom)
        val radius = systemNodeRadius(level)
        presentation.visibleSystemIds.forEach { systemId ->
            val node = scene.nodesById.getValue(systemId)
            val screen = transform.worldToScreen(node.position).toOffset()
            drawCircle(
                color = (if (node.isStargateConnected) CONNECTED_NODE_COLOR else UNCONNECTED_NODE_COLOR)
                    .multiplyAlpha(emphasis.systemAlphaMultiplier(systemId)),
                radius = radius,
                center = screen,
            )
        }

        drawPresentedLabels(
            labels = presentation.regionLabels.filter { it.type == MapLabelType.REGION_PRIMARY },
            textMeasurer = textMeasurer,
            cache = cache,
            preferences = preferences,
            alphaMultiplier = emphasis.hierarchyLabelAlphaMultiplier,
        )
        drawPresentedLabels(
            presentation.constellationLabels,
            textMeasurer,
            cache,
            preferences,
            emphasis.hierarchyLabelAlphaMultiplier,
        )

        presentation.systemLabelSystemIds.forEach { systemId ->
            val node = scene.nodesById.getValue(systemId)
            val label = cache.label(node.system.name, MapLabelType.SYSTEM, preferences, textMeasurer)
            val screen = transform.worldToScreen(node.position)
            drawText(
                textLayoutResult = label,
                color = labelColor(MapLabelType.SYSTEM, preferences)
                    .multiplyAlpha(emphasis.systemLabelAlphaMultiplier(systemId)),
                topLeft = Offset(screen.x.toFloat() + 5f, screen.y.toFloat() - label.size.height / 2f),
            )
        }
    }

    fun DrawScope.drawEmphasizedSystems(
        scene: ProjectedMapScene,
        transform: MapTransform,
        presentation: MapLabelPresentation,
        emphasis: MapVisualEmphasis,
        textMeasurer: TextMeasurer,
        cache: MapRenderCache,
        preferences: MapDisplayPreferences,
    ) {
        if (!emphasis.isActive) return
        val visibleBounds = transform.visibleWorldBounds(MAP_CONTENT_CULL_MARGIN_PX)
        val radius = systemNodeRadius(detailLevel(transform.viewport.zoom)) + EMPHASIZED_NODE_RADIUS_INCREASE_PX
        emphasis.focusedSystemIds.forEach { systemId ->
            val node = scene.nodesById[systemId]?.takeIf { visibleBounds.contains(it.position) } ?: return@forEach
            drawCircle(
                color = if (node.isStargateConnected) CONNECTED_NODE_COLOR else UNCONNECTED_NODE_COLOR,
                radius = radius,
                center = transform.worldToScreen(node.position).toOffset(),
            )
        }
        presentation.emphasizedSystemLabelIds.forEach { systemId ->
            val node = scene.nodesById.getValue(systemId)
            val label = cache.label(node.system.name, MapLabelType.SYSTEM, preferences, textMeasurer)
            val screen = transform.worldToScreen(node.position)
            drawText(
                textLayoutResult = label,
                color = labelColor(MapLabelType.SYSTEM, preferences),
                topLeft = Offset(screen.x.toFloat() + 5f, screen.y.toFloat() - label.size.height / 2f),
            )
        }
    }

    fun DrawScope.drawInteraction(
        scene: ProjectedMapScene,
        transform: MapTransform,
        hoveredSystemId: Int?,
        selectedSystemId: Int?,
        textMeasurer: TextMeasurer,
        cache: MapRenderCache,
        preferences: MapDisplayPreferences,
    ) {
        if (selectedSystemId != null) {
            drawHighlightedNode(scene, transform, selectedSystemId, SELECTED_COLOR, 8f, textMeasurer, cache, preferences)
        }
        if (hoveredSystemId != null && hoveredSystemId != selectedSystemId) {
            drawHighlightedNode(scene, transform, hoveredSystemId, HOVER_COLOR, 6f, textMeasurer, cache, preferences)
        }
    }

    fun DrawScope.drawAnsiblexLayer(
        scene: ProjectedMapScene,
        transform: MapTransform,
        connections: List<AnsiblexConnection>,
        emphasis: MapVisualEmphasis = MapVisualEmphasis.None,
    ) {
        connections.asSequence().filter(AnsiblexConnection::enabled).forEach { connection ->
            val first = scene.nodesById[connection.firstSystemId]?.position ?: return@forEach
            val second = scene.nodesById[connection.secondSystemId]?.position ?: return@forEach
            drawLine(
                color = ANSIBLEX_NETWORK_COLOR.multiplyAlpha(emphasis.ansiblexAlphaMultiplier(connection.id)),
                start = transform.worldToScreen(first).toOffset(),
                end = transform.worldToScreen(second).toOffset(),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
            )
        }
    }

    fun DrawScope.drawRoute(
        scene: ProjectedMapScene,
        transform: MapTransform,
        overlay: ProjectedRouteOverlay,
    ) {
        overlay.legs.forEach { leg ->
            drawLine(
                color = if (leg.edge.type == RouteEdgeType.ANSIBLEX) ROUTE_ANSIBLEX_COLOR else ROUTE_STARGATE_COLOR,
                start = transform.worldToScreen(leg.from).toOffset(),
                end = transform.worldToScreen(leg.to).toOffset(),
                strokeWidth = if (leg.edge.type == RouteEdgeType.ANSIBLEX) 4f else 3f,
                pathEffect = if (leg.edge.type == RouteEdgeType.ANSIBLEX) {
                    PathEffect.dashPathEffect(floatArrayOf(12f, 7f))
                } else {
                    null
                },
            )
        }
        scene.nodesById[overlay.route.startSystemId]?.let { node ->
            val center = transform.worldToScreen(node.position).toOffset()
            drawCircle(ROUTE_START_COLOR.copy(alpha = 0.25f), 12f, center)
            drawCircle(ROUTE_START_COLOR, 8f, center, style = Stroke(3f))
        }
        scene.nodesById[overlay.route.destinationSystemId]?.let { node ->
            val center = transform.worldToScreen(node.position).toOffset()
            drawCircle(ROUTE_DESTINATION_COLOR.copy(alpha = 0.25f), 12f, center)
            drawCircle(ROUTE_DESTINATION_COLOR, 8f, center, style = Stroke(3f))
        }
    }

    fun DrawScope.drawJumpRangeOverlays(
        scene: ProjectedMapScene,
        transform: MapTransform,
        overlays: List<ProjectedJumpRangeOverlay>,
        intersectionSystemIds: Set<Int>,
    ) {
        overlays.forEachIndexed { index, overlay ->
            val color = JUMP_OVERLAY_COLORS[index % JUMP_OVERLAY_COLORS.size]
            val radius = 5f + (index % 4) * 2.2f
            overlay.reachableNodes.forEach { node ->
                drawCircle(
                    color = color.copy(alpha = 0.82f),
                    radius = radius,
                    center = transform.worldToScreen(node.position).toOffset(),
                    style = Stroke(1.5f),
                )
            }
            scene.nodesById[overlay.overlay.originSystemId]?.let { origin ->
                val center = transform.worldToScreen(origin.position).toOffset()
                drawCircle(color.copy(alpha = 0.2f), 11f, center)
                drawCircle(color, 7f + (index % 3), center, style = Stroke(2f))
            }
        }
        intersectionSystemIds.forEach { systemId ->
            scene.nodesById[systemId]?.let { node ->
                val center = transform.worldToScreen(node.position).toOffset()
                drawCircle(INTERSECTION_COLOR.copy(alpha = 0.24f), 13f, center)
                drawCircle(INTERSECTION_COLOR, 10f, center, style = Stroke(2.5f))
            }
        }
    }

    fun DrawScope.drawCapitalRoute(
        scene: ProjectedMapScene,
        transform: MapTransform,
        overlay: ProjectedCapitalRouteOverlay,
    ) {
        overlay.legs.forEach { leg ->
            drawLine(
                color = CAPITAL_ROUTE_COLOR,
                start = transform.worldToScreen(leg.from).toOffset(),
                end = transform.worldToScreen(leg.to).toOffset(),
                strokeWidth = 4f,
            )
        }
        scene.nodesById[overlay.route.startSystemId]?.let { node ->
            val center = transform.worldToScreen(node.position).toOffset()
            drawCircle(CAPITAL_START_COLOR.copy(alpha = 0.25f), 13f, center)
            drawCircle(CAPITAL_START_COLOR, 9f, center, style = Stroke(3f))
        }
        scene.nodesById[overlay.route.destinationSystemId]?.let { node ->
            val center = transform.worldToScreen(node.position).toOffset()
            drawCircle(CAPITAL_DESTINATION_COLOR.copy(alpha = 0.25f), 13f, center)
            drawCircle(CAPITAL_DESTINATION_COLOR, 9f, center, style = Stroke(3f))
        }
    }

    fun DrawScope.drawMissionRoute(transform: MapTransform, overlay: ProjectedRouteOverlay, index: Int) {
        val color = MISSION_ROUTE_COLORS[index % MISSION_ROUTE_COLORS.size]
        overlay.legs.forEach { leg ->
            drawLine(
                color = color,
                start = transform.worldToScreen(leg.from).toOffset(),
                end = transform.worldToScreen(leg.to).toOffset(),
                strokeWidth = 5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 5f)),
            )
        }
    }

    fun DrawScope.drawMissionCapitalRoute(transform: MapTransform, overlay: ProjectedCapitalRouteOverlay, index: Int) {
        val color = MISSION_CAPITAL_COLORS[index % MISSION_CAPITAL_COLORS.size]
        overlay.legs.forEach { leg ->
            drawLine(
                color = color,
                start = transform.worldToScreen(leg.from).toOffset(),
                end = transform.worldToScreen(leg.to).toOffset(),
                strokeWidth = 5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
            )
        }
    }

    fun DrawScope.drawMissionJumpRangeOverlays(
        scene: ProjectedMapScene,
        transform: MapTransform,
        overlays: List<ProjectedJumpRangeOverlay>,
    ) {
        overlays.forEachIndexed { index, overlay ->
            val color = MISSION_JUMP_COLORS[index % MISSION_JUMP_COLORS.size]
            overlay.reachableNodes.forEach { node ->
                drawCircle(
                    color = color.copy(alpha = 0.9f),
                    radius = 8f + (index % 3) * 2f,
                    center = transform.worldToScreen(node.position).toOffset(),
                    style = Stroke(2f),
                )
            }
            scene.nodesById[overlay.overlay.originSystemId]?.let { origin ->
                val center = transform.worldToScreen(origin.position).toOffset()
                drawCircle(color.copy(alpha = 0.28f), 14f, center)
                drawCircle(color, 10f, center, style = Stroke(3f))
            }
        }
    }

    fun DrawScope.drawMissionMarkers(
        scene: ProjectedMapScene,
        transform: MapTransform,
        markers: List<MissionMarker>,
        textMeasurer: TextMeasurer,
        cache: MapRenderCache,
        preferences: MapDisplayPreferences,
    ) {
        markers.forEachIndexed { index, marker ->
            val node = scene.nodesById[marker.systemId] ?: return@forEachIndexed
            val base = transform.worldToScreen(node.position).toOffset()
            val center = Offset(base.x + 11f + (index % 3) * 3f, base.y - 11f - (index % 3) * 3f)
            val color = markerColor(marker.color)
            drawCircle(color.copy(alpha = 0.25f), 9f, center)
            drawCircle(color, 6f, center, style = Stroke(2.5f))
            drawLine(color, Offset(center.x - 8f, center.y), Offset(center.x + 8f, center.y), 1.5f)
            drawLine(color, Offset(center.x, center.y - 8f), Offset(center.x, center.y + 8f), 1.5f)
            marker.label?.let { labelText ->
                val label = cache.label(labelText, MapLabelType.SYSTEM, preferences, textMeasurer)
                drawText(
                    textLayoutResult = label,
                    color = color,
                    topLeft = Offset(center.x + 11f, center.y - label.size.height / 2f),
                )
            }
        }
    }

    fun DrawScope.drawMarkers(
        markers: List<PresentedMapMarker>,
        textMeasurer: TextMeasurer,
        cache: MapRenderCache,
        preferences: MapDisplayPreferences,
    ) {
        val halfSize = (MARKER_DIAMOND_SIZE_DP / 2f).dp.toPx()
        markers.forEach { presented ->
            val center = presented.screenCenter.toOffset()
            val diamond = Path().apply {
                moveTo(center.x, center.y - halfSize)
                lineTo(center.x + halfSize, center.y)
                lineTo(center.x, center.y + halfSize)
                lineTo(center.x - halfSize, center.y)
                close()
            }
            val color = markerColor(presented.marker.color)
            if (presented.visualStyle == MarkerVisualStyle.SOLID_DIAMOND) {
                drawPath(diamond, color)
                drawPath(diamond, Color(0xFF18232D), style = Stroke(1.dp.toPx()))
            } else {
                drawPath(diamond, color, style = Stroke(2.dp.toPx()))
            }
            presented.visibleName?.let { name ->
                val label = cache.label(name, MapLabelType.SYSTEM, preferences, textMeasurer)
                drawText(
                    textLayoutResult = label,
                    color = color,
                    topLeft = Offset(
                        center.x + halfSize + 4.dp.toPx(),
                        center.y - label.size.height / 2f,
                    ),
                )
            }
        }
    }

    private fun DrawScope.drawHighlightedNode(
        scene: ProjectedMapScene,
        transform: MapTransform,
        systemId: Int,
        color: Color,
        radius: Float,
        textMeasurer: TextMeasurer,
        cache: MapRenderCache,
        preferences: MapDisplayPreferences,
    ) {
        val node = scene.nodesById[systemId] ?: return
        val screen = transform.worldToScreen(node.position).toOffset()
        drawCircle(color = color.copy(alpha = 0.2f), radius = radius + 4f, center = screen)
        drawCircle(color = color, radius = radius, center = screen, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        val label = cache.label(node.system.name, MapLabelType.SYSTEM, preferences, textMeasurer)
        drawRect(
            color = MAP_BACKGROUND.copy(alpha = 0.88f),
            topLeft = Offset(screen.x + radius + 3f, screen.y - label.size.height / 2f - 2f),
            size = androidx.compose.ui.geometry.Size(label.size.width + 6f, label.size.height + 4f),
        )
        drawText(
            textLayoutResult = label,
            color = labelColor(MapLabelType.SYSTEM, preferences),
            topLeft = Offset(screen.x + radius + 6f, screen.y - label.size.height / 2f),
        )
    }

    private fun DrawScope.drawPresentedLabels(
        labels: List<PresentedMapLabel>,
        textMeasurer: TextMeasurer,
        cache: MapRenderCache,
        preferences: MapDisplayPreferences,
        alphaMultiplier: Float = 1f,
    ) {
        labels.forEach { presented ->
            val label = cache.label(presented.text, presented.type, preferences, textMeasurer)
            drawText(
                textLayoutResult = label,
                color = labelColor(presented.type, preferences).multiplyAlpha(alphaMultiplier),
                topLeft = presented.screenTopLeft.toOffset(),
            )
        }
    }
}

private fun Color.multiplyAlpha(multiplier: Float): Color = copy(alpha = alpha * multiplier)

private fun systemNodeRadius(level: MapDetailLevel): Float = when (level) {
    MapDetailLevel.OVERVIEW -> 1.4f
    MapDetailLevel.NORMAL -> 2.2f
    MapDetailLevel.DETAIL -> 3.0f
}

private fun MapPoint.toOffset() = Offset(x.toFloat(), y.toFloat())

private val MAP_BACKGROUND = Color(0xFF09121D)
private val EDGE_COLOR = Color(0x553F6685)
private val CONNECTED_NODE_COLOR = Color(0xFF75B9E7)
private val UNCONNECTED_NODE_COLOR = Color(0xFF596673)
private val LABEL_COLOR = Color(0xFFD7E6F2)
private val REGION_LABEL_BASE_COLOR = Color(0xFFE8F2FA)
private val REGION_BACKGROUND_LABEL_BASE_COLOR = Color(0xFFD7E6F2)
private val CONSTELLATION_LABEL_BASE_COLOR = Color(0xFFC4D9EA)
private val HOVER_COLOR = Color(0xFFF3D36A)
private val SELECTED_COLOR = Color(0xFF76E6A5)
private val ANSIBLEX_NETWORK_COLOR = Color(0x997C5CE0)
internal val ROUTE_STARGATE_COLOR = Color(0xFF42D6F5)
internal val ROUTE_ANSIBLEX_COLOR = Color(0xFFFF9F43)
private val ROUTE_START_COLOR = Color(0xFF57E389)
private val ROUTE_DESTINATION_COLOR = Color(0xFFFF5D73)
internal val CAPITAL_ROUTE_COLOR = Color(0xFFB388FF)
private val CAPITAL_START_COLOR = Color(0xFFA98BFF)
private val CAPITAL_DESTINATION_COLOR = Color(0xFFFF7EB6)
private val INTERSECTION_COLOR = Color(0xFFFFD166)
private val JUMP_OVERLAY_COLORS = listOf(
    Color(0xFF57E389),
    Color(0xFF42D6F5),
    Color(0xFFFF9F43),
    Color(0xFFA98BFF),
    Color(0xFFFF7EB6),
    Color(0xFFB8E986),
)
internal val MISSION_ROUTE_COLORS = listOf(Color(0xFFF4E06D))
internal val MISSION_CAPITAL_ROUTE_COLOR = Color(0xFFFF5C57)
internal val MISSION_CAPITAL_COLORS = listOf(
    MISSION_CAPITAL_ROUTE_COLOR,
    Color(0xE6FF5C57),
    Color(0xCCFF5C57),
    Color(0xB3FF5C57),
)
private val MISSION_JUMP_COLORS = listOf(Color(0xFFF4E06D), Color(0xFFFFA9E7), Color(0xFF7AE7C7), Color(0xFF9CCBFF))
internal fun labelColor(type: MapLabelType, preferences: MapDisplayPreferences): Color = when (type) {
    MapLabelType.SYSTEM -> LABEL_COLOR
    MapLabelType.REGION_PRIMARY -> REGION_LABEL_BASE_COLOR.copy(alpha = MapLabelStyleResolver.resolve(type, preferences).alpha)
    MapLabelType.REGION_BACKGROUND -> REGION_BACKGROUND_LABEL_BASE_COLOR.copy(
        alpha = MapLabelStyleResolver.resolve(type, preferences).alpha,
    )
    MapLabelType.CONSTELLATION -> CONSTELLATION_LABEL_BASE_COLOR.copy(alpha = MapLabelStyleResolver.resolve(type, preferences).alpha)
}

private const val MAP_CONTENT_CULL_MARGIN_PX = 80.0
private const val NORMAL_ZOOM = 1.2
private const val DETAIL_ZOOM = 4.0
private const val MARKER_DIAMOND_SIZE_DP = 10f
private const val EMPHASIZED_NODE_RADIUS_INCREASE_PX = 0.8f
