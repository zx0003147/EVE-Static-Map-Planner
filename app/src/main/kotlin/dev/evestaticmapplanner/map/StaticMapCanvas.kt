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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.IntSize
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
import dev.evestaticmapplanner.core.wormhole.WormholeConnection
import dev.evestaticmapplanner.core.map.ProjectedJumpRangeOverlayBuilder
import dev.evestaticmapplanner.core.map.ProjectedCapitalRouteOverlayBuilder
import dev.evestaticmapplanner.marker.MarkerContextAction
import dev.evestaticmapplanner.marker.MarkerUiState
import dev.evestaticmapplanner.marker.SystemContextAction
import dev.evestaticmapplanner.marker.SystemContextMenuPresentationBuilder
import dev.evestaticmapplanner.control.MissionMapUiState
import dev.evestaticmapplanner.feature.api.OverlayState
import dev.evestaticmapplanner.shared.SharedMarkerContextAction
import dev.evestaticmapplanner.shared.SharedMarkerContextPresentationBuilder
import dev.evestaticmapplanner.shared.model.SharedMapState
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
    wormholeConnections: List<WormholeConnection>,
    showAnsiblexLayer: Boolean,
    markerState: MarkerUiState,
    sharedMapState: SharedMapState,
    sharedMarkerState: SharedMarkerPresentationState,
    missionState: MissionMapUiState,
    featureOverlayState: OverlayState,
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
    onContextSharedMarkerAction: (Int, SharedMarkerContextAction) -> Unit,
    onContextCreateWormhole: (Int) -> Unit,
    onContextManageWormholes: (Int) -> Unit,
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
    val savedMarkerAppearance = state.appPreferences.marker.savedMarkerAppearance
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
        wormholeConnections,
    ) {
        MapVisualEmphasis.fromDisplayedMapState(
            userNormalRoute = activeRoute,
            userCapitalRoute = capitalRoute,
            missionState = missionState,
            selectedSystemId = state.selectedSystemId?.takeIf(scene.nodesById::containsKey),
            stargateEdges = scene.edges,
            visibleAnsiblexConnections = visibleAnsiblexConnections,
            wormholeConnections = wormholeConnections,
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
    val wormholePresentation = remember(scene, wormholeConnections) {
        WormholeMapPresentationBuilder.build(wormholeConnections, scene)
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
    val featureOverlayScope = rememberCoroutineScope()
    val featureOverlayCoordinator = remember(featureOverlayScope) {
        FeatureOverlayPresentationCoordinator(
            scope = featureOverlayScope,
            computer = { overlayState, projectedScene ->
                FeatureOverlayPresentationBuilder.build(
                    state = overlayState,
                    scene = projectedScene,
                )
            },
        )
    }
    val featureOverlayKey = remember(scene, featureOverlayState) {
        FeatureOverlayGeometryKey.from(scene, featureOverlayState)
    }
    val cachedFeatureOverlayPresentation = remember(featureOverlayKey) {
        featureOverlayCoordinator.peek(featureOverlayKey)
    }
    var completedFeatureOverlayPresentation by remember { mutableStateOf<KeyedFeatureOverlayPresentation?>(null) }
    val featureOverlayPresentation = cachedFeatureOverlayPresentation
        ?: completedFeatureOverlayPresentation
            ?.takeIf { it.key == featureOverlayKey }
            ?.presentation
        ?: FeatureOverlayPresentation.Empty
    LaunchedEffect(featureOverlayKey, featureOverlayCoordinator) {
        when (val request = featureOverlayCoordinator.request(featureOverlayKey, featureOverlayState, scene)) {
            is FeatureOverlayPresentationRequest.Cached -> {
                completedFeatureOverlayPresentation = KeyedFeatureOverlayPresentation(
                    featureOverlayKey,
                    request.presentation,
                )
            }
            is FeatureOverlayPresentationRequest.Pending -> {
                val presentation = request.result.await()
                if (featureOverlayCoordinator.isCurrent(featureOverlayKey)) {
                    completedFeatureOverlayPresentation = KeyedFeatureOverlayPresentation(
                        featureOverlayKey,
                        presentation,
                    )
                }
            }
        }
    }
    val emblemRepository = remember(featureOverlayScope) {
        PresentationEmblemAssetRepository(
            scope = featureOverlayScope,
            loader = JdkPresentationEmblemImageLoader(),
        )
    }
    val emblemZoomPolicy = remember(mapDisplayPreferences.sovereigntyLogoEmphasisZoom) {
        FeatureOverlayEmblemZoomPolicy(
            emphasisZoom = mapDisplayPreferences.sovereigntyLogoEmphasisZoom,
        )
    }
    val eligibleEmblemPlacements = remember(featureOverlayPresentation, transform, emblemZoomPolicy) {
        FeatureOverlayEmblemLod.placements(
            featureOverlayPresentation.emblemCandidates,
            transform,
            emblemZoomPolicy,
        )
    }
    val stableEmblemSelector = remember(featureOverlayPresentation, emblemZoomPolicy) {
        StableFeatureOverlayEmblemSelector()
    }
    val emblemPlacements = remember(eligibleEmblemPlacements, viewport.zoom, stableEmblemSelector) {
        stableEmblemSelector.select(eligibleEmblemPlacements, viewport.zoom)
    }
    val requestedEmblemReferences = remember(emblemPlacements) {
        emblemPlacements.map { it.candidate.reference }.distinctBy(PresentationEmblemReference::key)
    }
    LaunchedEffect(requestedEmblemReferences, emblemRepository) {
        requestedEmblemReferences.forEach(emblemRepository::request)
    }
    val emblemAssetStates by emblemRepository.states.collectAsState()
    val presentedFeatureEmblems = remember(emblemPlacements, emblemAssetStates) {
        FeatureOverlayEmblemLod.readyEmblems(emblemPlacements, emblemAssetStates)
    }
    val presentedOverlaySystemMarkers = remember(featureOverlayState, scene, transform) {
        presentOverlaySystemMarkers(featureOverlayState, scene, transform)
    }
    val markerOffsetPx = with(density) { 10.dp.toPx().toDouble() }
    val childOrbitRadiusPx = with(density) {
        savedMarkerChildOrbitRadiusDp(savedMarkerAppearance.ringRadiusDp).dp.toPx().toDouble()
    }
    val childBadgeHitRadiusPx = with(density) { 12.dp.toPx().toDouble() }
    val childCorridorRadiusPx = with(density) { 7.dp.toPx().toDouble() }
    var activeMarkerInteractionSystemId by remember { mutableStateOf<Int?>(null) }
    var hoveredSavedMarkerChild by remember { mutableStateOf<PresentedSavedMarkerChild?>(null) }
    var hoveredOverlaySystemMarker by remember { mutableStateOf<PresentedOverlaySystemMarker?>(null) }
    val expandedMarkerSystemIds = buildSet {
        state.hoveredSystemId?.let(::add)
        state.selectedSystemId?.let(::add)
        activeMarkerInteractionSystemId?.let(::add)
    }
    val visibleSystemNameIds = remember(
        labelPresentation.systemLabelSystemIds,
        labelPresentation.emphasizedSystemLabelIds,
        state.hoveredSystemId,
        state.selectedSystemId,
    ) {
        systemNameVisibilityIds(
            regularSystemLabelIds = labelPresentation.systemLabelSystemIds,
            emphasizedSystemLabelIds = labelPresentation.emphasizedSystemLabelIds,
            hoveredSystemId = state.hoveredSystemId,
            selectedSystemId = state.selectedSystemId,
        )
    }
    val presentedMarkers = remember(
        scene,
        transform,
        labelPresentation.visibleSystemIds,
        markerState.markersBySystemId,
        markerState.childrenByParentSystemId,
        expandedMarkerSystemIds,
        state.appPreferences.marker,
        state.semanticLabelMode,
        markerOffsetPx,
        childOrbitRadiusPx,
        visibleSystemNameIds,
    ) {
        MarkerMapPresentationBuilder.build(
            scene = scene,
            transform = transform,
            visibleSystemIds = labelPresentation.visibleSystemIds,
            markersBySystemId = markerState.markersBySystemId,
            childrenByParentSystemId = markerState.childrenByParentSystemId,
            expandedSystemIds = expandedMarkerSystemIds,
            preferences = state.appPreferences.marker,
            semanticMode = state.semanticLabelMode,
            offsetPx = markerOffsetPx,
            childOrbitRadiusPx = childOrbitRadiusPx,
            systemNameVisibleIds = visibleSystemNameIds,
        )
    }
    val sharedMarkerGeometry = remember(density) {
        with(density) {
            SharedMarkerVisualGeometry(
                baseRingRadiusPx = SHARED_MARKER_BASE_RING_RADIUS_DP.dp.toPx().toDouble(),
                localRingClearancePx = SHARED_MARKER_LOCAL_RING_CLEARANCE_DP.dp.toPx().toDouble(),
                primaryStrokePx = SHARED_MARKER_PRIMARY_STROKE_DP.dp.toPx(),
                secondaryOffsetPx = SHARED_MARKER_SECONDARY_OFFSET_DP.dp.toPx().toDouble(),
                secondaryStrokePx = SHARED_MARKER_SECONDARY_STROKE_DP.dp.toPx(),
                badgeOutwardOffsetPx = SHARED_MARKER_BADGE_OUTWARD_OFFSET_DP.dp.toPx().toDouble(),
                badgeRadiusPx = SHARED_MARKER_BADGE_RADIUS_DP.dp.toPx(),
                badgeBorderWidthPx = SHARED_MARKER_BADGE_BORDER_WIDTH_DP.dp.toPx(),
                badgeDotRadiusPx = SHARED_MARKER_BADGE_DOT_RADIUS_DP.dp.toPx(),
                badgeLinkWidthPx = SHARED_MARKER_BADGE_LINK_WIDTH_DP.dp.toPx(),
            )
        }
    }
    val localSavedRingRadiusPx = with(density) { savedMarkerAppearance.ringRadiusDp.dp.toPx().toDouble() }
    val presentedSharedMarkers = remember(
        scene,
        transform,
        labelPresentation.visibleSystemIds,
        sharedMarkerState,
        markerState.markersBySystemId,
        missionState.markers,
        sharedMarkerGeometry,
        localSavedRingRadiusPx,
    ) {
        SharedMarkerMapPresentationBuilder.build(
            scene = scene,
            transform = transform,
            visibleSystemIds = labelPresentation.visibleSystemIds,
            state = sharedMarkerState,
            localMarkersBySystemId = markerState.markersBySystemId,
            missionMarkers = missionState.markers,
            geometry = sharedMarkerGeometry,
            localSavedRingRadiusPx = localSavedRingRadiusPx,
        )
    }
    val localSavedVisualRadiusPx = with(density) {
        savedMarkerRingRenderState(savedMarkerAppearance).visualRadiusDp().dp.toPx().toDouble()
    }
    val systemNameSafetyGapPx = with(density) { SYSTEM_LABEL_MARKER_SAFETY_GAP_DP.dp.toPx().toDouble() }
    val systemNameVisualObstaclesBySystemId = remember(
        presentedMarkers,
        presentedSharedMarkers,
        sharedMarkerGeometry,
        localSavedVisualRadiusPx,
    ) {
        systemNameVisualObstaclesBySystemId(
            localMarkers = presentedMarkers,
            sharedMarkers = presentedSharedMarkers,
            sharedGeometry = sharedMarkerGeometry,
            localSavedVisualRadiusPx = localSavedVisualRadiusPx,
        )
    }
    val hoveredSharedMarker = remember(
        state.hoveredSystemId,
        state.selectedSystemId,
        presentedSharedMarkers,
    ) {
        state.hoveredSystemId
            ?.takeUnless { it == state.selectedSystemId }
            ?.let { hoveredId -> presentedSharedMarkers.firstOrNull { it.marker.systemId == hoveredId } }
    }
    var pressedAt by remember { mutableStateOf<MapPoint?>(null) }
    var lastDragPosition by remember { mutableStateOf<MapPoint?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var isPointerGestureBlocked by remember { mutableStateOf(false) }
    var compactCardBounds by remember { mutableStateOf<Rect?>(null) }
    var canvasSizePx by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(compactSystemInfo) {
        if (compactSystemInfo == null) compactCardBounds = null
    }

    LaunchedEffect(scene, viewport, state.canvasSize) {
        activeMarkerInteractionSystemId = null
        hoveredSavedMarkerChild = null
        hoveredOverlaySystemMarker = null
        withFrameNanos { }
        onFirstMapDisplayed()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xFF09121D))
            .onSizeChanged {
                canvasSizePx = it
                onCanvasSizeChanged(MapSize(it.width.toDouble(), it.height.toDouble()))
            }
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
                if (hitTestOverlaySystemMarker(presentedOverlaySystemMarkers, point) != null) {
                    isPointerGestureBlocked = true
                    pressedAt = null
                    lastDragPosition = null
                    isDragging = false
                    return@onPointerEvent
                }
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
                    val overlayMarkerHit = hitTestOverlaySystemMarker(presentedOverlaySystemMarkers, point)
                    if (overlayMarkerHit != null) {
                        hoveredOverlaySystemMarker = overlayMarkerHit
                        activeMarkerInteractionSystemId = null
                        hoveredSavedMarkerChild = null
                        onHoverExit()
                        return@onPointerEvent
                    }
                    hoveredOverlaySystemMarker = null
                    val markerHit = hitTestSavedMarkerInteraction(
                        markers = presentedMarkers,
                        point = point,
                        badgeHitRadiusPx = childBadgeHitRadiusPx,
                        corridorRadiusPx = childCorridorRadiusPx,
                    )
                    if (markerHit != null) {
                        activeMarkerInteractionSystemId = markerHit.parentSystemId
                        hoveredSavedMarkerChild = markerHit.child
                    } else {
                        activeMarkerInteractionSystemId = null
                        hoveredSavedMarkerChild = null
                        onHover(point)
                    }
                }
            }
            .onPointerEvent(PointerEventType.Exit) {
                isPointerGestureBlocked = false
                pressedAt = null
                lastDragPosition = null
                isDragging = false
                activeMarkerInteractionSystemId = null
                hoveredSavedMarkerChild = null
                hoveredOverlaySystemMarker = null
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
                    scene = scene,
                    transform = transform,
                    textMeasurer = textMeasurer,
                    cache = renderCache,
                    presentation = labelPresentation,
                    featureOverlayPresentation = featureOverlayPresentation,
                    preferences = mapDisplayPreferences,
                    emphasis = visualEmphasis,
                    featureEmblems = presentedFeatureEmblems,
                    systemNameVisualObstaclesBySystemId = systemNameVisualObstaclesBySystemId,
                    systemNameSafetyGapPx = systemNameSafetyGapPx,
                )
            }
        }
        if (showAnsiblexLayer && ansiblexConnections.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize().zIndex(StaticMapVisualLayerOrder.ANSIBLEX)) {
                with(MapRenderer) {
                    drawAnsiblexLayer(scene, transform, ansiblexConnections, visualEmphasis)
                }
            }
        }
        if (wormholePresentation.connections.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize().zIndex(StaticMapVisualLayerOrder.WORMHOLE)) {
                with(MapRenderer) {
                    drawWormholeLayer(transform, wormholePresentation, visualEmphasis)
                }
            }
        }
        if (projectedJumpOverlays.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize().zIndex(StaticMapVisualLayerOrder.RANGE_OVERLAY)) {
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
            Canvas(Modifier.fillMaxSize().zIndex(StaticMapVisualLayerOrder.RANGE_OVERLAY)) {
                with(MapRenderer) { drawMissionJumpRangeOverlays(scene, transform, projectedMissionJumpRanges) }
            }
        }
        if (presentedSharedMarkers.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize().zIndex(StaticMapVisualLayerOrder.SHARED_MARKER)) {
                with(MapRenderer) { drawSharedMarkers(presentedSharedMarkers, sharedMarkerGeometry) }
            }
        }
        routeOverlay?.let { overlay ->
            Canvas(Modifier.fillMaxSize().zIndex(StaticMapVisualLayerOrder.ROUTE)) {
                with(MapRenderer) {
                    drawRoute(scene, transform, overlay)
                }
            }
        }
        projectedCapitalRoute?.let { overlay ->
            Canvas(Modifier.fillMaxSize().zIndex(StaticMapVisualLayerOrder.ROUTE)) {
                with(MapRenderer) { drawCapitalRoute(scene, transform, overlay) }
            }
        }
        projectedMissionNormalRoutes.forEachIndexed { index, overlay ->
            Canvas(Modifier.fillMaxSize().zIndex(StaticMapVisualLayerOrder.ROUTE)) {
                with(MapRenderer) { drawMissionRoute(transform, overlay, index) }
            }
        }
        projectedMissionCapitalRoutes.forEachIndexed { index, overlay ->
            Canvas(Modifier.fillMaxSize().zIndex(StaticMapVisualLayerOrder.ROUTE)) {
                with(MapRenderer) { drawMissionCapitalRoute(transform, overlay, index) }
            }
        }
        if (visualEmphasis.isActive) {
            Canvas(Modifier.fillMaxSize().zIndex(StaticMapVisualLayerOrder.ROUTE_FOCUS)) {
                with(MapRenderer) {
                    drawEmphasizedSystems(
                        scene = scene,
                        transform = transform,
                        presentation = labelPresentation,
                        emphasis = visualEmphasis,
                        textMeasurer = textMeasurer,
                        cache = renderCache,
                        preferences = mapDisplayPreferences,
                        systemNameVisualObstaclesBySystemId = systemNameVisualObstaclesBySystemId,
                        systemNameSafetyGapPx = systemNameSafetyGapPx,
                    )
                }
            }
        }
        if (presentedMarkers.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize().zIndex(StaticMapVisualLayerOrder.SAVED_MARKER)) {
                with(MapRenderer) {
                    drawMarkers(
                        presentedMarkers,
                        textMeasurer,
                        renderCache,
                        mapDisplayPreferences,
                        savedMarkerAppearance,
                        systemNameVisualObstaclesBySystemId,
                        systemNameSafetyGapPx,
                    )
                }
            }
        }
        if (missionState.markers.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize().zIndex(StaticMapVisualLayerOrder.SAVED_MARKER)) {
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
        if (presentedOverlaySystemMarkers.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize().zIndex(StaticMapVisualLayerOrder.FEATURE_SYSTEM_MARKER)) {
                drawOverlaySystemMarkers(presentedOverlaySystemMarkers, textMeasurer)
            }
        }
        Canvas(Modifier.fillMaxSize().zIndex(StaticMapVisualLayerOrder.SELECTED_SYSTEM_FOCUS)) {
            with(MapRenderer) {
                drawInteraction(
                    scene = scene,
                    transform = transform,
                    hoveredSystemId = state.hoveredSystemId,
                    selectedSystemId = state.selectedSystemId,
                    textMeasurer = textMeasurer,
                    cache = renderCache,
                    preferences = mapDisplayPreferences,
                    systemNameVisualObstaclesBySystemId = systemNameVisualObstaclesBySystemId,
                    systemNameSafetyGapPx = systemNameSafetyGapPx,
                )
            }
        }
        hoveredSharedMarker?.let { marker ->
            MapMarkerTooltip(
                lines = marker.hoverLines,
                offset = IntOffset(
                    marker.screenCenter.x.toInt() + marker.ringRadiusPx.toInt() + 8,
                    marker.screenCenter.y.toInt() - marker.ringRadiusPx.toInt() - 12,
                ),
            )
        }
        hoveredSavedMarkerChild?.let { child ->
            MapMarkerTooltip(
                lines = listOf(child.visual.label),
                offset = IntOffset(
                    child.screenCenter.x.toInt() + 12,
                    child.screenCenter.y.toInt() - 16,
                ),
            )
        }
        hoveredOverlaySystemMarker?.takeIf { it.tooltipLines.isNotEmpty() }?.let { marker ->
            MapMarkerTooltip(
                lines = marker.tooltipLines,
                offset = IntOffset(marker.center.x.toInt() + 28, marker.center.y.toInt() - 16),
            )
        }
        compactSystemInfo?.let { presentation ->
            CompactSystemInfoCard(
                presentation = presentation,
                onEditSharedMarker = if (
                    presentation.sharedMarker != null &&
                    dev.evestaticmapplanner.shared.canWriteSharedMarkers(sharedMapState)
                ) {
                    { onContextSharedMarkerAction(presentation.selectedSystemId, SharedMarkerContextAction.OPEN) }
                } else {
                    null
                },
                onBoundsChanged = { compactCardBounds = it },
                modifier = Modifier
                    .align(CompactSystemInfoCardDefaults.alignment)
                    .padding(CompactSystemInfoCardDefaults.margin)
                    .zIndex(CompactSystemInfoCardDefaults.zIndex),
            )
        }
        FeatureOverlayLegend(
            sections = featureOverlayPresentation.legendSections,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomStart)
                .padding(12.dp)
                .zIndex(FEATURE_OVERLAY_LEGEND_Z_INDEX),
        )
        state.contextMenu?.let { menu ->
            var menuSizePx by remember(menu.systemId, menu.screenPosition) { mutableStateOf(IntSize.Zero) }
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
                        calculateContextMenuPosition(
                            anchor = IntOffset(menu.screenPosition.x.toInt(), menu.screenPosition.y.toInt()),
                            popupSize = menuSizePx,
                            viewportSize = canvasSizePx,
                        )
                    }
                    .onSizeChanged { menuSizePx = it }
                    .width(210.dp),
            ) {
                androidx.compose.foundation.layout.Column(Modifier.padding(vertical = 4.dp)) {
                    SystemContextMenuPresentationBuilder.build(
                        markerState.markersBySystemId[menu.systemId],
                        markerState,
                        SharedMarkerContextPresentationBuilder.build(
                            sharedMapState.snapshot?.markers?.values?.singleOrNull { it.systemId == menu.systemId },
                            sharedMapState,
                        ),
                        wormholeConnections.count {
                            it.firstSystemId == menu.systemId || it.secondSystemId == menu.systemId
                        },
                    ).forEach { item ->
                        if (item.startsNewSection) {
                            HorizontalDivider(
                                color = androidx.compose.ui.graphics.Color(0xFF314252),
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        Text(
                            text = item.label,
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
                                    SystemContextAction.ADD_SHARED_MARKER -> onContextSharedMarkerAction(
                                        menu.systemId,
                                        SharedMarkerContextAction.ADD,
                                    )
                                    SystemContextAction.OPEN_SHARED_MARKER -> onContextSharedMarkerAction(
                                        menu.systemId,
                                        SharedMarkerContextAction.OPEN,
                                    )
                                    SystemContextAction.ADD_JUMP_RANGE_OVERLAY -> onContextJumpOverlay(menu.systemId)
                                    SystemContextAction.SET_ROUTE_START -> onContextRouteStart(menu.systemId)
                                    SystemContextAction.SET_ROUTE_DESTINATION -> onContextRouteDestination(menu.systemId)
                                    SystemContextAction.SET_CAPITAL_START -> onContextCapitalStart(menu.systemId)
                                    SystemContextAction.SET_CAPITAL_DESTINATION -> onContextCapitalDestination(menu.systemId)
                                    SystemContextAction.CREATE_WORMHOLE -> onContextCreateWormhole(menu.systemId)
                                    SystemContextAction.MANAGE_WORMHOLE_CONNECTIONS -> onContextManageWormholes(menu.systemId)
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

@Composable
private fun MapMarkerTooltip(
    lines: List<String>,
    offset: IntOffset,
) {
    Surface(
        color = androidx.compose.ui.graphics.Color(0xF21B2A37),
        contentColor = androidx.compose.ui.graphics.Color(0xFFF1F5F8),
        shadowElevation = 6.dp,
        modifier = Modifier
            .zIndex(SAVED_MARKER_TOOLTIP_Z_INDEX)
            .offset { offset },
    ) {
        Text(
            lines.joinToString("\n"),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(7.dp),
        )
    }
}

private fun Offset.toMapPoint() = MapPoint(x.toDouble(), y.toDouble())

internal fun Rect?.containsPoint(point: MapPoint): Boolean =
    this?.contains(Offset(point.x.toFloat(), point.y.toFloat())) == true

internal fun Rect?.containsPoint(point: Offset): Boolean =
    this?.contains(point) == true

internal fun systemNameVisibilityIds(
    regularSystemLabelIds: Collection<Int>,
    emphasizedSystemLabelIds: Collection<Int>,
    hoveredSystemId: Int?,
    selectedSystemId: Int?,
): Set<Int> = buildSet {
    addAll(regularSystemLabelIds)
    addAll(emphasizedSystemLabelIds)
    hoveredSystemId?.let(::add)
    selectedSystemId?.let(::add)
}

internal fun calculateContextMenuPosition(
    anchor: IntOffset,
    popupSize: IntSize,
    viewportSize: IntSize,
): IntOffset {
    val maxX = (viewportSize.width - popupSize.width).coerceAtLeast(0)
    val maxY = (viewportSize.height - popupSize.height).coerceAtLeast(0)
    val preferredX = if (anchor.x + popupSize.width <= viewportSize.width) {
        anchor.x
    } else {
        anchor.x - popupSize.width
    }
    val preferredY = if (anchor.y + popupSize.height <= viewportSize.height) {
        anchor.y
    } else {
        anchor.y - popupSize.height
    }
    return IntOffset(
        x = preferredX.coerceIn(0, maxX),
        y = preferredY.coerceIn(0, maxY),
    )
}

private const val DRAG_SLOP_PX = 4.0
internal const val CONTEXT_DISMISS_Z_INDEX = 9f
internal const val CONTEXT_MENU_Z_INDEX = 10f
internal const val SAVED_MARKER_TOOLTIP_Z_INDEX = 8f
internal const val FEATURE_OVERLAY_LEGEND_Z_INDEX = 6f

internal object StaticMapVisualLayerOrder {
    const val BASE_MAP = 0f
    const val ANSIBLEX = 2f
    const val WORMHOLE = 2.25f
    const val RANGE_OVERLAY = 2.5f
    const val SHARED_MARKER = 2.75f
    const val ROUTE = 3f
    const val ROUTE_FOCUS = 3.5f
    const val SAVED_MARKER = 4f
    const val FEATURE_SYSTEM_MARKER = 4.5f
    const val SELECTED_SYSTEM_FOCUS = 5f
}

internal fun savedMarkerChildOrbitRadiusDp(parentRingRadiusDp: Float): Float =
    DEFAULT_SAVED_MARKER_CHILD_ORBIT_RADIUS_DP.coerceAtLeast(
        parentRingRadiusDp + SAVED_MARKER_CHILD_BADGE_RADIUS_DP + SAVED_MARKER_CHILD_CLEARANCE_DP,
    )

private const val DEFAULT_SAVED_MARKER_CHILD_ORBIT_RADIUS_DP = 38f
private const val SAVED_MARKER_CHILD_BADGE_RADIUS_DP = 9f
private const val SAVED_MARKER_CHILD_CLEARANCE_DP = 6f
