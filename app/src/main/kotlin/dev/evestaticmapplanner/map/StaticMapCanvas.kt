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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.ProjectedRouteOverlayBuilder
import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.core.jump.JumpRangeOverlay
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.map.ProjectedJumpRangeOverlayBuilder
import dev.evestaticmapplanner.core.map.ProjectedCapitalRouteOverlayBuilder
import dev.evestaticmapplanner.marker.MarkerContextAction
import dev.evestaticmapplanner.marker.MarkerUiState
import dev.evestaticmapplanner.marker.SystemContextAction
import dev.evestaticmapplanner.marker.SystemContextMenuPresentationBuilder
import dev.evestaticmapplanner.control.MissionMapUiState
import kotlin.math.hypot

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun StaticMapCanvas(
    state: MapUiState,
    activeRoute: RouteResult?,
    capitalRoute: CapitalRouteResult?,
    jumpOverlays: List<JumpRangeOverlay>,
    intersectionSystemIds: Set<Int>,
    ansiblexConnections: List<AnsiblexConnection>,
    showAnsiblexLayer: Boolean,
    markerState: MarkerUiState,
    missionState: MissionMapUiState,
    compactSystemInfo: CompactSystemInfoPresentation?,
    onCanvasSizeChanged: (MapSize) -> Unit,
    onZoom: (MapPoint, Double) -> Unit,
    onPan: (MapPoint) -> Unit,
    onHover: (MapPoint) -> Unit,
    onHoverExit: () -> Unit,
    onSelect: (MapPoint) -> Unit,
    onContextMenu: (MapPoint) -> Unit,
    onContextRouteStart: (Int) -> Unit,
    onContextRouteDestination: (Int) -> Unit,
    onContextJumpOverlay: (Int) -> Unit,
    onContextCapitalStart: (Int) -> Unit,
    onContextCapitalDestination: (Int) -> Unit,
    onContextMarkerAction: (Int, MarkerContextAction) -> Unit,
    onContextDismiss: () -> Unit,
    onFirstMapDisplayed: () -> Unit,
) {
    val scene = state.scene ?: return
    val viewport = state.viewport ?: return
    if (state.canvasSize.isEmpty) return
    val transform = remember(viewport, state.canvasSize) { MapTransform(viewport, state.canvasSize) }
    val textMeasurer = rememberTextMeasurer()
    val renderCache = remember(scene, textMeasurer) { MapRenderCache() }
    val density = LocalDensity.current
    val mapDisplayPreferences = state.appPreferences.mapDisplay
    val visibleAnsiblexConnections = remember(ansiblexConnections, showAnsiblexLayer) {
        if (showAnsiblexLayer) ansiblexConnections.filter(AnsiblexConnection::enabled) else emptyList()
    }
    val visualEmphasis = remember(
        activeRoute,
        capitalRoute,
        missionState.normalRoutes,
        missionState.capitalRoutes,
        state.selectedSystemId,
        scene,
        visibleAnsiblexConnections,
    ) {
        MapVisualEmphasis.fromDisplayedMapState(
            userNormalRoute = activeRoute,
            userCapitalRoute = capitalRoute,
            missionState = missionState,
            selectedSystemId = state.selectedSystemId?.takeIf(scene.nodesById::containsKey),
            stargateEdges = scene.edges,
            visibleAnsiblexConnections = visibleAnsiblexConnections,
        )
    }
    val labelPresentation = remember(
        scene,
        transform,
        state.semanticLabelMode,
        mapDisplayPreferences,
        textMeasurer,
        renderCache,
        visualEmphasis,
    ) {
        MapLabelPresentationBuilder.build(
            scene = scene,
            transform = transform,
            semanticMode = state.semanticLabelMode,
            metricsProvider = MapLabelMetricsProvider { text, type ->
                val size = renderCache.label(text, type, mapDisplayPreferences, textMeasurer).size
                MapSize(size.width.toDouble(), size.height.toDouble())
            },
            emphasizedSystemIds = visualEmphasis.prioritizedSystemIds,
        )
    }
    val routeOverlay = remember(scene, activeRoute) {
        activeRoute?.let { ProjectedRouteOverlayBuilder.build(it, scene) }
    }
    val projectedJumpOverlays = remember(scene, jumpOverlays) {
        jumpOverlays.filter(JumpRangeOverlay::enabled).map { ProjectedJumpRangeOverlayBuilder.build(it, scene) }
    }
    val projectedCapitalRoute = remember(scene, capitalRoute) {
        capitalRoute?.let { ProjectedCapitalRouteOverlayBuilder.build(it, scene) }
    }
    val projectedMissionNormalRoutes = remember(scene, missionState.normalRoutes) {
        missionState.normalRoutes.map { ProjectedRouteOverlayBuilder.build(it.route, scene) }
    }
    val projectedMissionCapitalRoutes = remember(scene, missionState.capitalRoutes) {
        missionState.capitalRoutes.map { ProjectedCapitalRouteOverlayBuilder.build(it.route, scene) }
    }
    val projectedMissionJumpRanges = remember(scene, missionState.jumpRanges) {
        missionState.jumpRanges.map { range ->
            ProjectedJumpRangeOverlayBuilder.build(
                JumpRangeOverlay(
                    id = "mission:${range.jumpRangeId.value}",
                    originSystemId = range.originSystemId,
                    profile = range.profile,
                    reachableSystemIds = range.reachableSystemIds,
                    label = range.label,
                ),
                scene,
            )
        }
    }
    val markerOffsetPx = with(density) { 10.dp.toPx().toDouble() }
    val presentedMarkers = remember(
        scene,
        transform,
        labelPresentation.visibleSystemIds,
        markerState.markersBySystemId,
        state.appPreferences.marker,
        state.semanticLabelMode,
        markerOffsetPx,
    ) {
        MarkerMapPresentationBuilder.build(
            scene = scene,
            transform = transform,
            visibleSystemIds = labelPresentation.visibleSystemIds,
            markersBySystemId = markerState.markersBySystemId,
            preferences = state.appPreferences.marker,
            semanticMode = state.semanticLabelMode,
            offsetPx = markerOffsetPx,
        )
    }
    var pressedAt by remember { mutableStateOf<MapPoint?>(null) }
    var lastDragPosition by remember { mutableStateOf<MapPoint?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var isPointerGestureBlocked by remember { mutableStateOf(false) }
    var compactCardBounds by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(compactSystemInfo) {
        if (compactSystemInfo == null) compactCardBounds = null
    }

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
                    if (change.isConsumed || compactCardBounds.containsPoint(change.position)) return@onPointerEvent
                    onZoom(change.position.toMapPoint(), change.scrollDelta.y.toDouble())
                }
            }
            .onPointerEvent(PointerEventType.Press) { event ->
                if (state.contextMenu != null) return@onPointerEvent
                val awtEvent = event.awtEventOrNull
                val point = event.changes.firstOrNull()?.position?.toMapPoint() ?: return@onPointerEvent
                if (compactCardBounds.containsPoint(point)) {
                    isPointerGestureBlocked = true
                    pressedAt = null
                    lastDragPosition = null
                    isDragging = false
                    onHoverExit()
                    return@onPointerEvent
                }
                isPointerGestureBlocked = false
                if (awtEvent?.button == java.awt.event.MouseEvent.BUTTON1) {
                    pressedAt = point
                    lastDragPosition = point
                    isDragging = false
                }
            }
            .onPointerEvent(PointerEventType.Move) { event ->
                if (state.contextMenu != null) return@onPointerEvent
                val point = event.changes.firstOrNull()?.position?.toMapPoint() ?: return@onPointerEvent
                if (isPointerGestureBlocked) return@onPointerEvent
                if (compactCardBounds.containsPoint(point)) {
                    if (pressedAt != null || isDragging) isPointerGestureBlocked = true
                    pressedAt = null
                    lastDragPosition = null
                    isDragging = false
                    onHoverExit()
                    return@onPointerEvent
                }
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
                isPointerGestureBlocked = false
                pressedAt = null
                lastDragPosition = null
                isDragging = false
                onHoverExit()
            }
            .onPointerEvent(PointerEventType.Release) { event ->
                if (state.contextMenu != null) return@onPointerEvent
                val awtEvent = event.awtEventOrNull
                val point = event.changes.firstOrNull()?.position?.toMapPoint() ?: return@onPointerEvent
                if (isPointerGestureBlocked || compactCardBounds.containsPoint(point)) {
                    isPointerGestureBlocked = false
                    pressedAt = null
                    lastDragPosition = null
                    isDragging = false
                    return@onPointerEvent
                }
                when (awtEvent?.button) {
                    java.awt.event.MouseEvent.BUTTON1 -> {
                        if (pressedAt != null && !isDragging) onSelect(point)
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
                drawBase(
                    scene,
                    transform,
                    textMeasurer,
                    renderCache,
                    labelPresentation,
                    mapDisplayPreferences,
                    visualEmphasis,
                )
            }
        }
        if (showAnsiblexLayer && ansiblexConnections.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                with(MapRenderer) {
                    drawAnsiblexLayer(scene, transform, ansiblexConnections, visualEmphasis)
                }
            }
        }
        if (projectedJumpOverlays.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                with(MapRenderer) {
                    drawJumpRangeOverlays(
                        scene = scene,
                        transform = transform,
                        overlays = projectedJumpOverlays,
                        intersectionSystemIds = intersectionSystemIds,
                    )
                }
            }
        }
        if (projectedMissionJumpRanges.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                with(MapRenderer) { drawMissionJumpRangeOverlays(scene, transform, projectedMissionJumpRanges) }
            }
        }
        routeOverlay?.let { overlay ->
            Canvas(Modifier.fillMaxSize()) {
                with(MapRenderer) {
                    drawRoute(scene, transform, overlay)
                }
            }
        }
        projectedCapitalRoute?.let { overlay ->
            Canvas(Modifier.fillMaxSize()) {
                with(MapRenderer) { drawCapitalRoute(scene, transform, overlay) }
            }
        }
        projectedMissionNormalRoutes.forEachIndexed { index, overlay ->
            Canvas(Modifier.fillMaxSize()) {
                with(MapRenderer) { drawMissionRoute(transform, overlay, index) }
            }
        }
        projectedMissionCapitalRoutes.forEachIndexed { index, overlay ->
            Canvas(Modifier.fillMaxSize()) {
                with(MapRenderer) { drawMissionCapitalRoute(transform, overlay, index) }
            }
        }
        if (visualEmphasis.isActive) {
            Canvas(Modifier.fillMaxSize()) {
                with(MapRenderer) {
                    drawEmphasizedSystems(
                        scene = scene,
                        transform = transform,
                        presentation = labelPresentation,
                        emphasis = visualEmphasis,
                        textMeasurer = textMeasurer,
                        cache = renderCache,
                        preferences = mapDisplayPreferences,
                    )
                }
            }
        }
        if (presentedMarkers.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                with(MapRenderer) {
                    drawMarkers(presentedMarkers, textMeasurer, renderCache, mapDisplayPreferences)
                }
            }
        }
        if (missionState.markers.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                with(MapRenderer) {
                    drawMissionMarkers(
                        scene,
                        transform,
                        missionState.markers,
                        textMeasurer,
                        renderCache,
                        mapDisplayPreferences,
                    )
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
                    preferences = mapDisplayPreferences,
                )
            }
        }
        compactSystemInfo?.let { presentation ->
            CompactSystemInfoCard(
                presentation = presentation,
                onBoundsChanged = { compactCardBounds = it },
                modifier = Modifier
                    .align(CompactSystemInfoCardDefaults.alignment)
                    .padding(CompactSystemInfoCardDefaults.margin)
                    .zIndex(CompactSystemInfoCardDefaults.zIndex),
            )
        }
        state.contextMenu?.let { menu ->
            Spacer(
                Modifier
                    .fillMaxSize()
                    .zIndex(CONTEXT_DISMISS_Z_INDEX)
                    .onClick(onClick = onContextDismiss),
            )
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 10.dp,
                modifier = Modifier
                    .zIndex(CONTEXT_MENU_Z_INDEX)
                    .offset {
                        IntOffset(
                            menu.screenPosition.x.toInt(),
                            menu.screenPosition.y.toInt(),
                        )
                    }
                    .width(210.dp),
            ) {
                androidx.compose.foundation.layout.Column(Modifier.padding(vertical = 4.dp)) {
                    SystemContextMenuPresentationBuilder.build(
                        markerState.markersBySystemId[menu.systemId],
                        markerState,
                    ).forEach { item ->
                        Text(
                            text = item.action.label,
                            color = if (item.enabled) androidx.compose.ui.graphics.Color.Unspecified else {
                                androidx.compose.ui.graphics.Color(0xFF71808D)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().onClick(enabled = item.enabled) {
                                when (item.action) {
                                    SystemContextAction.ADD_TEMPORARY_MARKER -> onContextMarkerAction(
                                        menu.systemId,
                                        MarkerContextAction.ADD_TEMPORARY,
                                    )
                                    SystemContextAction.ADD_SAVED_MARKER -> onContextMarkerAction(
                                        menu.systemId,
                                        MarkerContextAction.ADD_SAVED,
                                    )
                                    SystemContextAction.EDIT_MARKER -> onContextMarkerAction(
                                        menu.systemId,
                                        MarkerContextAction.EDIT,
                                    )
                                    SystemContextAction.SAVE_MARKER_PERMANENTLY -> onContextMarkerAction(
                                        menu.systemId,
                                        MarkerContextAction.SAVE_PERMANENTLY,
                                    )
                                    SystemContextAction.REMOVE_MARKER -> onContextMarkerAction(
                                        menu.systemId,
                                        MarkerContextAction.REMOVE,
                                    )
                                    SystemContextAction.MARKERS_UNAVAILABLE -> Unit
                                    SystemContextAction.ADD_JUMP_RANGE_OVERLAY -> onContextJumpOverlay(menu.systemId)
                                    SystemContextAction.SET_ROUTE_START -> onContextRouteStart(menu.systemId)
                                    SystemContextAction.SET_ROUTE_DESTINATION -> onContextRouteDestination(menu.systemId)
                                    SystemContextAction.SET_CAPITAL_START -> onContextCapitalStart(menu.systemId)
                                    SystemContextAction.SET_CAPITAL_DESTINATION -> onContextCapitalDestination(menu.systemId)
                                }
                            }.padding(10.dp),
                        )
                        if (item.action == SystemContextAction.MARKERS_UNAVAILABLE) {
                            markerState.databaseError?.let { error ->
                                Text(
                                    text = error,
                                    color = androidx.compose.ui.graphics.Color(0xFFFF9F9F),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Offset.toMapPoint() = MapPoint(x.toDouble(), y.toDouble())

internal fun Rect?.containsPoint(point: MapPoint): Boolean =
    this?.contains(Offset(point.x.toFloat(), point.y.toFloat())) == true

internal fun Rect?.containsPoint(point: Offset): Boolean =
    this?.contains(point) == true

private const val DRAG_SLOP_PX = 4.0
internal const val CONTEXT_DISMISS_Z_INDEX = 9f
internal const val CONTEXT_MENU_Z_INDEX = 10f
