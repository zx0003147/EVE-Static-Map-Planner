package dev.evestaticmapplanner.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.core.map.ProjectedRouteOverlay
import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.map.ProjectedJumpRangeOverlay
import dev.evestaticmapplanner.core.map.ProjectedCapitalRouteOverlay

enum class MapDetailLevel {
    OVERVIEW,
    NORMAL,
    DETAIL,
}

class MapRenderCache {
    private val labels = mutableMapOf<Int, TextLayoutResult>()

    fun label(systemId: Int, name: String, textMeasurer: TextMeasurer): TextLayoutResult =
        labels.getOrPut(systemId) {
            textMeasurer.measure(
                text = name,
                style = TextStyle(color = LABEL_COLOR, fontSize = 11.sp),
                softWrap = false,
            )
        }
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
    ) {
        drawRect(MAP_BACKGROUND)
        val visibleBounds = transform.visibleWorldBounds(CULL_MARGIN_PX)
        scene.edges.forEach { edge ->
            if (!edge.bounds.intersects(visibleBounds)) return@forEach
            drawLine(
                color = EDGE_COLOR,
                start = transform.worldToScreen(edge.first).toOffset(),
                end = transform.worldToScreen(edge.second).toOffset(),
                strokeWidth = 1f,
            )
        }

        val level = detailLevel(transform.viewport.zoom)
        val visibleSystemIds = scene.spatialIndex.query(visibleBounds)
        val radius = when (level) {
            MapDetailLevel.OVERVIEW -> 1.4f
            MapDetailLevel.NORMAL -> 2.2f
            MapDetailLevel.DETAIL -> 3.0f
        }
        visibleSystemIds.forEach { systemId ->
            val node = scene.nodesById.getValue(systemId)
            val screen = transform.worldToScreen(node.position).toOffset()
            drawCircle(
                color = if (node.isStargateConnected) CONNECTED_NODE_COLOR else UNCONNECTED_NODE_COLOR,
                radius = radius,
                center = screen,
            )
        }

        if (level == MapDetailLevel.DETAIL && visibleSystemIds.size <= MAX_VISIBLE_LABELS) {
            visibleSystemIds.forEach { systemId ->
                val node = scene.nodesById.getValue(systemId)
                val label = cache.label(systemId, node.system.name, textMeasurer)
                val screen = transform.worldToScreen(node.position)
                drawText(
                    textLayoutResult = label,
                    topLeft = Offset(screen.x.toFloat() + 5f, screen.y.toFloat() - label.size.height / 2f),
                )
            }
        }
    }

    fun DrawScope.drawInteraction(
        scene: ProjectedMapScene,
        transform: MapTransform,
        hoveredSystemId: Int?,
        selectedSystemId: Int?,
        textMeasurer: TextMeasurer,
        cache: MapRenderCache,
    ) {
        if (selectedSystemId != null) {
            drawHighlightedNode(scene, transform, selectedSystemId, SELECTED_COLOR, 8f, textMeasurer, cache)
        }
        if (hoveredSystemId != null && hoveredSystemId != selectedSystemId) {
            drawHighlightedNode(scene, transform, hoveredSystemId, HOVER_COLOR, 6f, textMeasurer, cache)
        }
    }

    fun DrawScope.drawAnsiblexLayer(
        scene: ProjectedMapScene,
        transform: MapTransform,
        connections: List<AnsiblexConnection>,
    ) {
        connections.asSequence().filter(AnsiblexConnection::enabled).forEach { connection ->
            val first = scene.nodesById[connection.firstSystemId]?.position ?: return@forEach
            val second = scene.nodesById[connection.secondSystemId]?.position ?: return@forEach
            drawLine(
                color = ANSIBLEX_NETWORK_COLOR,
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

    private fun DrawScope.drawHighlightedNode(
        scene: ProjectedMapScene,
        transform: MapTransform,
        systemId: Int,
        color: Color,
        radius: Float,
        textMeasurer: TextMeasurer,
        cache: MapRenderCache,
    ) {
        val node = scene.nodesById[systemId] ?: return
        val screen = transform.worldToScreen(node.position).toOffset()
        drawCircle(color = color.copy(alpha = 0.2f), radius = radius + 4f, center = screen)
        drawCircle(color = color, radius = radius, center = screen, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        val label = cache.label(systemId, node.system.name, textMeasurer)
        drawRect(
            color = MAP_BACKGROUND.copy(alpha = 0.88f),
            topLeft = Offset(screen.x + radius + 3f, screen.y - label.size.height / 2f - 2f),
            size = androidx.compose.ui.geometry.Size(label.size.width + 6f, label.size.height + 4f),
        )
        drawText(
            textLayoutResult = label,
            topLeft = Offset(screen.x + radius + 6f, screen.y - label.size.height / 2f),
        )
    }
}

private fun MapPoint.toOffset() = Offset(x.toFloat(), y.toFloat())

private val MAP_BACKGROUND = Color(0xFF09121D)
private val EDGE_COLOR = Color(0x553F6685)
private val CONNECTED_NODE_COLOR = Color(0xFF75B9E7)
private val UNCONNECTED_NODE_COLOR = Color(0xFF596673)
private val LABEL_COLOR = Color(0xFFD7E6F2)
private val HOVER_COLOR = Color(0xFFF3D36A)
private val SELECTED_COLOR = Color(0xFF76E6A5)
private val ANSIBLEX_NETWORK_COLOR = Color(0x997C5CE0)
private val ROUTE_STARGATE_COLOR = Color(0xFF42D6F5)
private val ROUTE_ANSIBLEX_COLOR = Color(0xFFFF9F43)
private val ROUTE_START_COLOR = Color(0xFF57E389)
private val ROUTE_DESTINATION_COLOR = Color(0xFFFF5D73)
private val CAPITAL_ROUTE_COLOR = Color(0xFFE28CFF)
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
private const val CULL_MARGIN_PX = 80.0
private const val NORMAL_ZOOM = 1.2
private const val DETAIL_ZOOM = 4.0
private const val MAX_VISIBLE_LABELS = 700
