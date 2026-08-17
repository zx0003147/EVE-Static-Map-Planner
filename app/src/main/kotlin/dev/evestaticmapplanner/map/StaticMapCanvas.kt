package dev.evestaticmapplanner.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.ProjectedRouteOverlayBuilder
import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.route.RouteResult
import kotlin.math.hypot

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun StaticMapCanvas(
    state: MapUiState,
    activeRoute: RouteResult?,
    ansiblexConnections: List<AnsiblexConnection>,
    showAnsiblexLayer: Boolean,
    onCanvasSizeChanged: (MapSize) -> Unit,
    onZoom: (MapPoint, Double) -> Unit,
    onPan: (MapPoint) -> Unit,
    onHover: (MapPoint) -> Unit,
    onHoverExit: () -> Unit,
    onSelect: (MapPoint) -> Unit,
    onContextMenu: (MapPoint) -> Unit,
    onContextSystemInfo: () -> Unit,
    onContextRouteStart: (Int) -> Unit,
    onContextRouteDestination: (Int) -> Unit,
    onContextDismiss: () -> Unit,
    onFirstMapDisplayed: () -> Unit,
) {
    val scene = state.scene ?: return
    val viewport = state.viewport ?: return
    if (state.canvasSize.isEmpty) return
    val transform = remember(viewport, state.canvasSize) { MapTransform(viewport, state.canvasSize) }
    val textMeasurer = rememberTextMeasurer()
    val renderCache = remember(scene, textMeasurer) { MapRenderCache() }
    val routeOverlay = remember(scene, activeRoute) {
        activeRoute?.let { ProjectedRouteOverlayBuilder.build(it, scene) }
    }
    var pressedAt by remember { mutableStateOf<MapPoint?>(null) }
    var lastDragPosition by remember { mutableStateOf<MapPoint?>(null) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(scene, viewport, state.canvasSize) {
        withFrameNanos { }
        onFirstMapDisplayed()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xFF09121D))
            .onSizeChanged { onCanvasSizeChanged(MapSize(it.width.toDouble(), it.height.toDouble())) }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                if (state.contextMenu != null) return@onPointerEvent
                event.changes.firstOrNull()?.let { change ->
                    onZoom(change.position.toMapPoint(), change.scrollDelta.y.toDouble())
                }
            }
            .onPointerEvent(PointerEventType.Press) { event ->
                if (state.contextMenu != null) return@onPointerEvent
                val awtEvent = event.awtEventOrNull
                val point = event.changes.firstOrNull()?.position?.toMapPoint() ?: return@onPointerEvent
                if (awtEvent?.button == java.awt.event.MouseEvent.BUTTON1) {
                    pressedAt = point
                    lastDragPosition = point
                    isDragging = false
                }
            }
            .onPointerEvent(PointerEventType.Move) { event ->
                if (state.contextMenu != null) return@onPointerEvent
                val point = event.changes.firstOrNull()?.position?.toMapPoint() ?: return@onPointerEvent
                val start = pressedAt
                val last = lastDragPosition
                if (start != null && last != null) {
                    if (!isDragging && hypot(point.x - start.x, point.y - start.y) >= DRAG_SLOP_PX) {
                        isDragging = true
                    }
                    if (isDragging) {
                        onPan(MapPoint(point.x - last.x, point.y - last.y))
                        lastDragPosition = point
                    }
                } else {
                    onHover(point)
                }
            }
            .onPointerEvent(PointerEventType.Exit) {
                onHoverExit()
            }
            .onPointerEvent(PointerEventType.Release) { event ->
                if (state.contextMenu != null) return@onPointerEvent
                val awtEvent = event.awtEventOrNull
                val point = event.changes.firstOrNull()?.position?.toMapPoint() ?: return@onPointerEvent
                when (awtEvent?.button) {
                    java.awt.event.MouseEvent.BUTTON1 -> {
                        if (!isDragging) onSelect(point)
                        pressedAt = null
                        lastDragPosition = null
                        isDragging = false
                    }
                    java.awt.event.MouseEvent.BUTTON3 -> onContextMenu(point)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            with(MapRenderer) {
                drawBase(scene, transform, textMeasurer, renderCache)
            }
        }
        if (showAnsiblexLayer && ansiblexConnections.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                with(MapRenderer) {
                    drawAnsiblexLayer(scene, transform, ansiblexConnections)
                }
            }
        }
        routeOverlay?.let { overlay ->
            Canvas(Modifier.fillMaxSize()) {
                with(MapRenderer) {
                    drawRoute(scene, transform, overlay)
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            with(MapRenderer) {
                drawInteraction(
                    scene = scene,
                    transform = transform,
                    hoveredSystemId = state.hoveredSystemId,
                    selectedSystemId = state.selectedSystemId,
                    textMeasurer = textMeasurer,
                    cache = renderCache,
                )
            }
        }
        state.contextMenu?.let { menu ->
            Spacer(
                Modifier
                    .fillMaxSize()
                    .zIndex(9f)
                    .onClick(onClick = onContextDismiss),
            )
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 10.dp,
                modifier = Modifier
                    .zIndex(10f)
                    .offset {
                        IntOffset(
                            menu.screenPosition.x.toInt(),
                            menu.screenPosition.y.toInt(),
                        )
                    }
                    .width(210.dp),
            ) {
                androidx.compose.foundation.layout.Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "Set Route Start",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().onClick { onContextRouteStart(menu.systemId) }.padding(10.dp),
                    )
                    Text(
                        text = "Set Route Destination",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().onClick { onContextRouteDestination(menu.systemId) }.padding(10.dp),
                    )
                    Text(
                        text = "System Info",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().onClick(onClick = onContextSystemInfo).padding(10.dp),
                    )
                }
            }
        }
    }
}

private fun Offset.toMapPoint() = MapPoint(x.toDouble(), y.toDouble())

private const val DRAG_SLOP_PX = 4.0
