package dev.evestaticmapplanner.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.Real3DFrame
import dev.evestaticmapplanner.core.map.Real3DFrameProjectionWorkspace
import dev.evestaticmapplanner.core.map.Real3DStaticGeometry
import dev.evestaticmapplanner.core.map.Real3DProjectedCapitalRoute
import dev.evestaticmapplanner.core.map.Real3DProjectedRoute
import dev.evestaticmapplanner.core.map.Real3DRouteProjector
import dev.evestaticmapplanner.core.map.Real3DJumpSphereBuilder
import dev.evestaticmapplanner.core.map.Real3DJumpSphereProjector
import dev.evestaticmapplanner.core.map.Real3DProjectedJumpSphere
import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.jump.JumpRangeOverlay
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.core.wormhole.WormholeConnection
import dev.evestaticmapplanner.control.MissionMapUiState
import dev.evestaticmapplanner.feature.api.OverlayState
import dev.evestaticmapplanner.marker.MarkerContextAction
import dev.evestaticmapplanner.marker.MarkerUiState
import dev.evestaticmapplanner.marker.SystemContextAction
import dev.evestaticmapplanner.marker.SystemContextMenuPresentationBuilder
import dev.evestaticmapplanner.shared.SharedMarkerContextAction
import dev.evestaticmapplanner.shared.SharedMarkerContextPresentationBuilder
import dev.evestaticmapplanner.shared.model.SharedMapState

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun Real3DMapCanvas(
    state: MapUiState,
    activeRoute: RouteResult?,
    normalWaypointSystemIds: List<Int>,
    normalExplicitDestinationSystemId: Int?,
    capitalRoute: CapitalRouteResult?,
    capitalWaypointSystemIds: List<Int>,
    capitalExplicitDestinationSystemId: Int?,
    jumpOverlays: List<JumpRangeOverlay>,
    ansiblexConnections: List<AnsiblexConnection>,
    showAnsiblexLayer: Boolean,
    missionState: MissionMapUiState,
    featureOverlayState: OverlayState,
    markerState: MarkerUiState,
    sharedMapState: SharedMapState,
    sharedMarkerState: SharedMarkerPresentationState,
    wormholeConnections: List<WormholeConnection>,
    compactSystemInfo: CompactSystemInfoPresentation?,
    onCanvasSizeChanged: (MapSize) -> Unit,
    onZoom: (MapPoint, Double) -> Unit,
    onPan: (MapPoint) -> Unit,
    onRotate: (MapPoint) -> Unit,
    onHover: (MapPoint) -> Unit,
    onHoverExit: () -> Unit,
    onSelect: (MapPoint) -> Unit,
    onFocus: (MapPoint) -> Unit,
    onContextMenu: (MapPoint) -> Unit,
    onContextRouteStart: (Int) -> Unit,
    onContextRouteWaypoint: (Int) -> Unit,
    onContextRouteDestination: (Int) -> Unit,
    onContextJumpOverlay: (Int) -> Unit,
    onContextCapitalStart: (Int) -> Unit,
    onContextCapitalWaypoint: (Int) -> Unit,
    onContextCapitalDestination: (Int) -> Unit,
    onContextMarkerAction: (Int, MarkerContextAction) -> Unit,
    onContextSharedMarkerAction: (Int, SharedMarkerContextAction) -> Unit,
    onContextCreateWormhole: (Int) -> Unit,
    onContextManageWormholes: (Int) -> Unit,
    onContextDismiss: () -> Unit,
    onFirstMapDisplayed: () -> Unit,
) {
    val scene = state.scene ?: return
    val camera = state.real3DCamera ?: return
    if (state.canvasSize.isEmpty) return
    val geometry = remember(scene) { Real3DStaticGeometry.from(scene) }
    val featurePresentation = remember(featureOverlayState, geometry) {
        Real3DFeatureOverlayPresentationBuilder.build(featureOverlayState, geometry)
    }
    val visibleStargateConnectionKeys = remember(
        geometry,
        state.selectedSystemId,
        state.appPreferences.mapDisplay.real3DStargateVisibilityFilteringEnabled,
    ) {
        Real3DStargateVisibility.visibleConnectionKeys(
            geometry = geometry,
            focusedSystemId = state.selectedSystemId,
            filteringEnabled = state.appPreferences.mapDisplay.real3DStargateVisibilityFilteringEnabled,
        )
    }
    val projectedEdgeKeys = remember(visibleStargateConnectionKeys, featurePresentation.linkColorsBySystemPair) {
        visibleStargateConnectionKeys?.let { visibleStargates ->
            buildSet {
                addAll(visibleStargates)
                addAll(featurePresentation.linkColorsBySystemPair.keys)
            }
        }
    }
    val frameProjectionWorkspace = remember(geometry) { Real3DFrameProjectionWorkspace() }
    val frame = remember(geometry, camera, state.canvasSize, frameProjectionWorkspace, projectedEdgeKeys) {
        if (projectedEdgeKeys == null) {
            frameProjectionWorkspace.project(geometry, camera, state.canvasSize)
        } else {
            frameProjectionWorkspace.project(geometry, camera, state.canvasSize) { edge ->
                real3DSystemPairKey(edge.firstSystemId, edge.secondSystemId) in projectedEdgeKeys
            }
        }
    }
    val gesture = remember { Real3DPointerGestureTracker() }
    val textMeasurer = rememberTextMeasurer()
    val renderCache = remember(scene, textMeasurer) { MapRenderCache() }
    val density = LocalDensity.current
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
    val enabledJumpOverlays = remember(jumpOverlays) { jumpOverlays.filter(JumpRangeOverlay::enabled) }
    val missionJumpOverlays = remember(missionState.jumpRanges) {
        missionState.jumpRanges.map { range ->
            JumpRangeOverlay(
                id = "mission:${range.jumpRangeId.value}",
                originSystemId = range.originSystemId,
                profile = range.profile,
                reachableSystemIds = range.reachableSystemIds,
                label = range.label,
            )
        }
    }
    val jumpSphereGeometry = remember(enabledJumpOverlays, missionJumpOverlays, geometry) {
        Real3DJumpSphereBuilder.build(enabledJumpOverlays + missionJumpOverlays, geometry)
    }
    val jumpSpheres = remember(jumpSphereGeometry, camera, state.canvasSize) {
        Real3DJumpSphereProjector.project(jumpSphereGeometry, camera, state.canvasSize)
    }
    val route = remember(activeRoute, geometry, camera, state.canvasSize) {
        activeRoute?.let { Real3DRouteProjector.project(it, geometry, camera, state.canvasSize) }
    }
    val capital = remember(capitalRoute, geometry, camera, state.canvasSize) {
        capitalRoute?.let { Real3DRouteProjector.project(it, geometry, camera, state.canvasSize) }
    }
    val missionRoutes = remember(missionState.normalRoutes, geometry, camera, state.canvasSize) {
        missionState.normalRoutes.map { Real3DRouteProjector.project(it.route, geometry, camera, state.canvasSize) }
    }
    val missionCapitalRoutes = remember(missionState.capitalRoutes, geometry, camera, state.canvasSize) {
        missionState.capitalRoutes.map { Real3DRouteProjector.project(it.route, geometry, camera, state.canvasSize) }
    }
    val waypoints = remember(
        scene,
        activeRoute,
        normalWaypointSystemIds,
        capitalRoute,
        capitalWaypointSystemIds,
        missionState.normalRoutes,
        missionState.capitalRoutes,
    ) {
        RouteWaypointPresentationBuilder.build(
            scene,
            buildList {
                if (activeRoute != null) {
                    add(RouteWaypointSource("user-normal", RouteWaypointKind.USER_NORMAL, normalWaypointSystemIds))
                }
                if (capitalRoute != null) {
                    add(RouteWaypointSource("user-capital", RouteWaypointKind.USER_CAPITAL, capitalWaypointSystemIds))
                }
                missionState.normalRoutes.forEach { mission ->
                    add(
                        RouteWaypointSource(
                            "mission-normal:${mission.routeId.value}",
                            RouteWaypointKind.MISSION_NORMAL,
                            mission.waypointSystemIds,
                        ),
                    )
                }
                missionState.capitalRoutes.forEach { mission ->
                    add(
                        RouteWaypointSource(
                            "mission-capital:${mission.routeId.value}",
                            RouteWaypointKind.MISSION_CAPITAL,
                            mission.waypointSystemIds,
                        ),
                    )
                }
            },
        )
    }
    val markerOffsetPx = with(density) { 10.dp.toPx().toDouble() }
    val savedMarkerAppearance = state.appPreferences.marker.savedMarkerAppearance
    val childOrbitRadiusPx = with(density) {
        savedMarkerChildOrbitRadiusDp(savedMarkerAppearance.ringRadiusDp).dp.toPx().toDouble()
    }
    val childBadgeHitRadiusPx = with(density) { 12.dp.toPx().toDouble() }
    val childCorridorRadiusPx = with(density) { 7.dp.toPx().toDouble() }
    var activeMarkerInteractionSystemId by remember { mutableStateOf<Int?>(null) }
    var hoveredSavedMarkerChild by remember { mutableStateOf<PresentedSavedMarkerChild?>(null) }
    var hoveredOverlaySystemMarker by remember { mutableStateOf<PresentedOverlaySystemMarker?>(null) }
    val expandedMarkerSystemIds = remember(
        state.hoveredSystemId,
        state.selectedSystemId,
        activeMarkerInteractionSystemId,
    ) {
        buildSet {
            state.hoveredSystemId?.let(::add)
            state.selectedSystemId?.let(::add)
            activeMarkerInteractionSystemId?.let(::add)
        }
    }
    val presentedMarkers = remember(
        frame,
        camera,
        markerState.markersBySystemId,
        markerState.childrenByParentSystemId,
        expandedMarkerSystemIds,
        state.appPreferences.marker,
        state.semanticLabelMode,
        markerOffsetPx,
        childOrbitRadiusPx,
    ) {
        MarkerMapPresentationBuilder.buildAtScreenPositions(
            scene = scene,
            visibleSystemIds = frame.projectedBySystemId.keys,
            markersBySystemId = markerState.markersBySystemId,
            childrenByParentSystemId = markerState.childrenByParentSystemId,
            expandedSystemIds = expandedMarkerSystemIds,
            preferences = state.appPreferences.marker,
            semanticMode = state.semanticLabelMode,
            offsetPx = markerOffsetPx,
            childOrbitRadiusPx = childOrbitRadiusPx,
            systemNameVisibleIds = if (state.semanticLabelMode == SemanticLabelMode.SYSTEM) {
                frame.projectedBySystemId.keys
            } else {
                emptySet()
            },
            screenPosition = { systemId -> frame.projectedBySystemId[systemId]?.screen },
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
        frame,
        camera,
        sharedMarkerState,
        markerState.markersBySystemId,
        missionState.markers,
        sharedMarkerGeometry,
        localSavedRingRadiusPx,
    ) {
        SharedMarkerMapPresentationBuilder.buildAtScreenPositions(
            visibleSystemIds = frame.projectedBySystemId.keys,
            state = sharedMarkerState,
            localMarkersBySystemId = markerState.markersBySystemId,
            missionMarkers = missionState.markers,
            geometry = sharedMarkerGeometry,
            localSavedRingRadiusPx = localSavedRingRadiusPx,
            screenPosition = { systemId -> frame.projectedBySystemId[systemId]?.screen },
        )
    }
    val decodedOverlaySystemMarkers = remember(featureOverlayState, geometry) {
        decodeOverlaySystemMarkers(featureOverlayState, geometry.nodesById.keys)
    }
    val presentedOverlaySystemMarkers = remember(decodedOverlaySystemMarkers, frame, camera) {
        positionOverlaySystemMarkers(decodedOverlaySystemMarkers) { systemId ->
            frame.projectedBySystemId[systemId]?.screen
        }
    }
    val featureScope = rememberCoroutineScope()
    val featureEmblemRepository = remember(featureScope) {
        PresentationEmblemAssetRepository(
            scope = featureScope,
            loader = JdkPresentationEmblemImageLoader(),
        )
    }
    val featureEmblemReferences = remember(featurePresentation) {
        featurePresentation.emblems.map(Real3DFeatureEmblemCandidate::reference).distinctBy(PresentationEmblemReference::key)
    }
    LaunchedEffect(featureEmblemReferences, featureEmblemRepository) {
        featureEmblemReferences.forEach(featureEmblemRepository::request)
    }
    val featureEmblemStates by featureEmblemRepository.states.collectAsState()
    val readyFeatureEmblems = remember(featurePresentation, featureEmblemStates, camera, state.canvasSize) {
        val projector = dev.evestaticmapplanner.core.map.Real3DProjector(camera, state.canvasSize)
        featurePresentation.emblems.mapNotNull { candidate ->
            val ready = featureEmblemStates[candidate.reference.key]
                as? PresentationEmblemAssetState.Ready<androidx.compose.ui.graphics.ImageBitmap>
                ?: return@mapNotNull null
            val screen = projector.project(candidate.anchor)?.screen ?: return@mapNotNull null
            Real3DReadyFeatureEmblem(screen, ready.asset)
        }
    }
    var canvasSizePx by remember { mutableStateOf(IntSize.Zero) }
    var compactCardBounds by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(compactSystemInfo) {
        if (compactSystemInfo == null) compactCardBounds = null
    }

    LaunchedEffect(scene, state.canvasSize) {
        withFrameNanos { }
        onFirstMapDisplayed()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(REAL_3D_BACKGROUND)
            .onSizeChanged {
                canvasSizePx = it
                onCanvasSizeChanged(MapSize(it.width.toDouble(), it.height.toDouble()))
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                if (state.contextMenu != null) return@onPointerEvent
                event.changes.firstOrNull()?.let { change ->
                    if (!change.isConsumed) onZoom(change.position.toMapPoint(), change.scrollDelta.y.toDouble())
                }
            }
            .onPointerEvent(PointerEventType.Press) { event ->
                if (state.contextMenu != null) return@onPointerEvent
                val position = event.changes.firstOrNull()?.position?.toMapPoint() ?: return@onPointerEvent
                if (compactCardBounds.containsPoint(position)) {
                    gesture.cancel()
                    onHoverExit()
                    return@onPointerEvent
                }
                if (hitTestOverlaySystemMarker(presentedOverlaySystemMarkers, position) != null) {
                    gesture.cancel()
                    return@onPointerEvent
                }
                when (event.awtEventOrNull?.button) {
                    java.awt.event.MouseEvent.BUTTON1 -> gesture.press(MapPointerButton.PRIMARY, position)
                    java.awt.event.MouseEvent.BUTTON3 -> gesture.press(MapPointerButton.SECONDARY, position)
                }
            }
            .onPointerEvent(PointerEventType.Move) { event ->
                if (state.contextMenu != null) return@onPointerEvent
                val position = event.changes.firstOrNull()?.position?.toMapPoint() ?: return@onPointerEvent
                if (compactCardBounds.containsPoint(position)) {
                    gesture.cancel()
                    onHoverExit()
                    return@onPointerEvent
                }
                val drag = gesture.move(position)
                when (drag?.button) {
                    MapPointerButton.PRIMARY -> {
                        activeMarkerInteractionSystemId = null
                        hoveredSavedMarkerChild = null
                        hoveredOverlaySystemMarker = null
                        onPan(drag.screenDelta)
                    }
                    MapPointerButton.SECONDARY -> {
                        activeMarkerInteractionSystemId = null
                        hoveredSavedMarkerChild = null
                        hoveredOverlaySystemMarker = null
                        onRotate(drag.screenDelta)
                    }
                    null -> if (!gesture.isActive) {
                        val overlayMarkerHit = hitTestOverlaySystemMarker(presentedOverlaySystemMarkers, position)
                        if (overlayMarkerHit != null) {
                            hoveredOverlaySystemMarker = overlayMarkerHit
                            activeMarkerInteractionSystemId = null
                            hoveredSavedMarkerChild = null
                            onHoverExit()
                        } else {
                            hoveredOverlaySystemMarker = null
                            val markerHit = hitTestSavedMarkerInteraction(
                                markers = presentedMarkers,
                                point = position,
                                badgeHitRadiusPx = childBadgeHitRadiusPx,
                                corridorRadiusPx = childCorridorRadiusPx,
                            )
                            if (markerHit != null) {
                                activeMarkerInteractionSystemId = markerHit.parentSystemId
                                hoveredSavedMarkerChild = markerHit.child
                                onHoverExit()
                            } else {
                                activeMarkerInteractionSystemId = null
                                hoveredSavedMarkerChild = null
                                onHover(position)
                            }
                        }
                    }
                }
            }
            .onPointerEvent(PointerEventType.Release) { event ->
                if (state.contextMenu != null) return@onPointerEvent
                val position = event.changes.firstOrNull()?.position?.toMapPoint() ?: return@onPointerEvent
                gesture.release(position, event.awtEventOrNull?.clickCount ?: 1)?.let { click ->
                    dispatchMapClick(
                        click = click,
                        onSelect = onSelect,
                        onFocus = onFocus,
                        onContextMenu = onContextMenu,
                    )
                }
            }
            .onPointerEvent(PointerEventType.Exit) {
                gesture.cancel()
                activeMarkerInteractionSystemId = null
                hoveredSavedMarkerChild = null
                hoveredOverlaySystemMarker = null
                onHoverExit()
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(REAL_3D_BACKGROUND)
            drawReal3DFeatureOwnership(frame, featurePresentation)
            drawReal3DBase(
                frame = frame,
                visibleStargateConnectionKeys = visibleStargateConnectionKeys,
                sovereigntyColorsBySystemId = featurePresentation.sovereigntyColorsBySystemId,
                emphasis = visualEmphasis,
            )
            drawReal3DJumpSpheres(
                frame = frame,
                spheres = jumpSpheres,
                userOverlayCount = enabledJumpOverlays.size,
            )
            if (showAnsiblexLayer) {
                drawReal3DAnsiblexLayer(
                    geometry,
                    camera,
                    state.canvasSize,
                    visibleAnsiblexConnections,
                    visualEmphasis,
                )
            }
            drawReal3DWormholes(geometry, camera, state.canvasSize, wormholeConnections, visualEmphasis)
            drawReal3DLabels(
                frame = frame,
                semanticMode = state.semanticLabelMode,
                textMeasurer = textMeasurer,
                cache = renderCache,
                preferences = state.appPreferences.mapDisplay,
                emphasis = visualEmphasis,
            )
            missionRoutes.forEachIndexed { index, missionRoute ->
                drawReal3DMissionRoute(missionRoute, MISSION_ROUTE_COLORS[index % MISSION_ROUTE_COLORS.size])
            }
            missionCapitalRoutes.forEachIndexed { index, missionRoute ->
                drawReal3DMissionCapitalRoute(
                    missionRoute,
                    MISSION_CAPITAL_COLORS[index % MISSION_CAPITAL_COLORS.size],
                )
            }
            route?.let {
                drawReal3DRoute(
                    frame,
                    it,
                    showDestination = normalExplicitDestinationSystemId != null,
                )
            }
            capital?.let {
                drawReal3DCapitalRoute(
                    frame,
                    it,
                    showDestination = capitalExplicitDestinationSystemId != null,
                )
            }
            drawReal3DEmphasizedSystems(
                frame = frame,
                emphasis = visualEmphasis,
                sovereigntyColorsBySystemId = featurePresentation.sovereigntyColorsBySystemId,
                textMeasurer = textMeasurer,
                cache = renderCache,
                preferences = state.appPreferences.mapDisplay,
            )
            drawReal3DFeatureEmblems(readyFeatureEmblems)
            with(MapRenderer) {
                drawSharedMarkers(presentedSharedMarkers, sharedMarkerGeometry)
                drawMarkers(
                    markers = presentedMarkers,
                    textMeasurer = textMeasurer,
                    cache = renderCache,
                    preferences = state.appPreferences.mapDisplay,
                    savedMarkerAppearance = savedMarkerAppearance,
                )
                drawMissionMarkersAtScreenPositions(
                    markers = missionState.markers,
                    textMeasurer = textMeasurer,
                    cache = renderCache,
                    preferences = state.appPreferences.mapDisplay,
                    screenPosition = { systemId -> frame.projectedBySystemId[systemId]?.screen },
                )
            }
            drawOverlaySystemMarkers(presentedOverlaySystemMarkers, textMeasurer)
            drawReal3DRouteWaypoints(frame, waypoints, textMeasurer)
            drawReal3DInteraction(
                frame = frame,
                hoveredSystemId = state.hoveredSystemId,
                selectedSystemId = state.selectedSystemId,
                textMeasurer = textMeasurer,
                cache = renderCache,
                preferences = state.appPreferences.mapDisplay,
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
        state.hoveredSystemId
            ?.takeUnless { it == state.selectedSystemId }
            ?.let { hoveredId -> presentedSharedMarkers.firstOrNull { it.marker.systemId == hoveredId } }
            ?.let { marker ->
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
        FeatureOverlayLegend(
            sections = featurePresentation.legendSections,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .zIndex(FEATURE_OVERLAY_LEGEND_Z_INDEX),
        )
        state.contextMenu?.let { menu ->
            Real3DSystemContextMenu(
                menu = menu,
                canvasSizePx = canvasSizePx,
                markerState = markerState,
                sharedMapState = sharedMapState,
                wormholeConnections = wormholeConnections,
                onContextRouteStart = onContextRouteStart,
                onContextRouteWaypoint = onContextRouteWaypoint,
                onContextRouteDestination = onContextRouteDestination,
                onContextJumpOverlay = onContextJumpOverlay,
                onContextCapitalStart = onContextCapitalStart,
                onContextCapitalWaypoint = onContextCapitalWaypoint,
                onContextCapitalDestination = onContextCapitalDestination,
                onContextMarkerAction = onContextMarkerAction,
                onContextSharedMarkerAction = onContextSharedMarkerAction,
                onContextCreateWormhole = onContextCreateWormhole,
                onContextManageWormholes = onContextManageWormholes,
                onContextDismiss = onContextDismiss,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Real3DSystemContextMenu(
    menu: MapContextMenuState,
    canvasSizePx: IntSize,
    markerState: MarkerUiState,
    sharedMapState: SharedMapState,
    wormholeConnections: List<WormholeConnection>,
    onContextRouteStart: (Int) -> Unit,
    onContextRouteWaypoint: (Int) -> Unit,
    onContextRouteDestination: (Int) -> Unit,
    onContextJumpOverlay: (Int) -> Unit,
    onContextCapitalStart: (Int) -> Unit,
    onContextCapitalWaypoint: (Int) -> Unit,
    onContextCapitalDestination: (Int) -> Unit,
    onContextMarkerAction: (Int, MarkerContextAction) -> Unit,
    onContextSharedMarkerAction: (Int, SharedMarkerContextAction) -> Unit,
    onContextCreateWormhole: (Int) -> Unit,
    onContextManageWormholes: (Int) -> Unit,
    onContextDismiss: () -> Unit,
) {
    var menuSizePx by remember(menu.systemId, menu.screenPosition) { mutableStateOf(IntSize.Zero) }
    Spacer(Modifier.fillMaxSize().zIndex(CONTEXT_DISMISS_Z_INDEX).onClick(onClick = onContextDismiss))
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
        Column(Modifier.padding(vertical = 4.dp)) {
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
                    HorizontalDivider(color = Color(0xFF314252), modifier = Modifier.padding(vertical = 4.dp))
                }
                Text(
                    text = item.label,
                    color = if (item.enabled) Color.Unspecified else Color(0xFF71808D),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().onClick(enabled = item.enabled) {
                        dispatchReal3DContextAction(
                            item.action,
                            menu.systemId,
                            onContextRouteStart,
                            onContextRouteWaypoint,
                            onContextRouteDestination,
                            onContextJumpOverlay,
                            onContextCapitalStart,
                            onContextCapitalWaypoint,
                            onContextCapitalDestination,
                            onContextMarkerAction,
                            onContextSharedMarkerAction,
                            onContextCreateWormhole,
                            onContextManageWormholes,
                        )
                    }.padding(10.dp),
                )
                if (item.action == SystemContextAction.MARKERS_UNAVAILABLE) {
                    markerState.databaseError?.let { error ->
                        Text(
                            text = error,
                            color = Color(0xFFFF9F9F),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Suppress("LongParameterList")
private fun dispatchReal3DContextAction(
    action: SystemContextAction,
    systemId: Int,
    onContextRouteStart: (Int) -> Unit,
    onContextRouteWaypoint: (Int) -> Unit,
    onContextRouteDestination: (Int) -> Unit,
    onContextJumpOverlay: (Int) -> Unit,
    onContextCapitalStart: (Int) -> Unit,
    onContextCapitalWaypoint: (Int) -> Unit,
    onContextCapitalDestination: (Int) -> Unit,
    onContextMarkerAction: (Int, MarkerContextAction) -> Unit,
    onContextSharedMarkerAction: (Int, SharedMarkerContextAction) -> Unit,
    onContextCreateWormhole: (Int) -> Unit,
    onContextManageWormholes: (Int) -> Unit,
) {
    when (action) {
        SystemContextAction.ADD_TEMPORARY_MARKER -> onContextMarkerAction(systemId, MarkerContextAction.ADD_TEMPORARY)
        SystemContextAction.ADD_SAVED_MARKER -> onContextMarkerAction(systemId, MarkerContextAction.ADD_SAVED)
        SystemContextAction.EDIT_MARKER -> onContextMarkerAction(systemId, MarkerContextAction.EDIT)
        SystemContextAction.SAVE_MARKER_PERMANENTLY -> onContextMarkerAction(systemId, MarkerContextAction.SAVE_PERMANENTLY)
        SystemContextAction.REMOVE_MARKER -> onContextMarkerAction(systemId, MarkerContextAction.REMOVE)
        SystemContextAction.MARKERS_UNAVAILABLE -> Unit
        SystemContextAction.ADD_SHARED_MARKER -> onContextSharedMarkerAction(systemId, SharedMarkerContextAction.ADD)
        SystemContextAction.OPEN_SHARED_MARKER -> onContextSharedMarkerAction(systemId, SharedMarkerContextAction.OPEN)
        SystemContextAction.ADD_JUMP_RANGE_OVERLAY -> onContextJumpOverlay(systemId)
        SystemContextAction.SET_ROUTE_START -> onContextRouteStart(systemId)
        SystemContextAction.ADD_ROUTE_WAYPOINT -> onContextRouteWaypoint(systemId)
        SystemContextAction.SET_ROUTE_DESTINATION -> onContextRouteDestination(systemId)
        SystemContextAction.SET_CAPITAL_START -> onContextCapitalStart(systemId)
        SystemContextAction.ADD_CAPITAL_WAYPOINT -> onContextCapitalWaypoint(systemId)
        SystemContextAction.SET_CAPITAL_DESTINATION -> onContextCapitalDestination(systemId)
        SystemContextAction.CREATE_WORMHOLE -> onContextCreateWormhole(systemId)
        SystemContextAction.MANAGE_WORMHOLE_CONNECTIONS -> onContextManageWormholes(systemId)
    }
}

private fun DrawScope.drawReal3DBase(
    frame: Real3DFrame,
    visibleStargateConnectionKeys: Set<Long>?,
    sovereigntyColorsBySystemId: Map<Int, Color>,
    emphasis: MapVisualEmphasis,
) {
    frame.edges.forEach { edge ->
        val connectionKey = real3DSystemPairKey(edge.edge.firstSystemId, edge.edge.secondSystemId)
        if (visibleStargateConnectionKeys != null && connectionKey !in visibleStargateConnectionKeys) return@forEach
        drawLine(
            color = REAL_3D_EDGE_COLOR.copy(
                alpha = edge.alpha * emphasis.stargateAlphaMultiplier(
                    edge.edge.firstSystemId,
                    edge.edge.secondSystemId,
                ),
            ),
            start = edge.first.toOffset(),
            end = edge.second.toOffset(),
            strokeWidth = REAL_3D_EDGE_WIDTH_PX,
        )
    }
    frame.nodesFarToNear.forEach { projected ->
        drawCircle(
            color = real3DSystemBaseColor(projected, sovereigntyColorsBySystemId)
                .multiplyAlpha(emphasis.systemAlphaMultiplier(projected.node.system.id)),
            radius = projected.radiusPx,
            center = projected.screen.toOffset(),
        )
    }
}

private data class Real3DReadyFeatureEmblem(
    val screen: MapPoint,
    val image: androidx.compose.ui.graphics.ImageBitmap,
)

private fun DrawScope.drawReal3DFeatureOwnership(
    frame: Real3DFrame,
    presentation: Real3DFeatureOverlayPresentation,
) {
    if (presentation.entries.isEmpty()) return
    frame.edges.forEach { edge ->
        presentation.linkColorsBySystemPair[
            real3DSystemPairKey(edge.edge.firstSystemId, edge.edge.secondSystemId)
        ]?.let { color ->
            drawLine(
                color = color.copy(alpha = REAL_3D_FEATURE_LINK_ALPHA),
                start = edge.first.toOffset(),
                end = edge.second.toOffset(),
                strokeWidth = REAL_3D_FEATURE_LINK_WIDTH_PX,
            )
        }
    }
    presentation.decorativeEntriesBySystemId.forEach { (systemId, entries) ->
        val center = frame.projectedBySystemId[systemId]?.screen?.toOffset() ?: return@forEach
        entries.forEachIndexed { index, entry ->
            val radius = REAL_3D_FEATURE_RING_RADIUS_PX + index * REAL_3D_FEATURE_RING_STEP_PX
            drawCircle(entry.color.copy(alpha = REAL_3D_FEATURE_NODE_FILL_ALPHA), radius, center)
            drawCircle(
                entry.color.copy(alpha = REAL_3D_FEATURE_NODE_RING_ALPHA),
                radius,
                center,
                style = Stroke(REAL_3D_FEATURE_NODE_RING_WIDTH_PX),
            )
        }
    }
}

private fun DrawScope.drawReal3DFeatureEmblems(emblems: List<Real3DReadyFeatureEmblem>) {
    emblems.forEach { emblem ->
        val halfSize = REAL_3D_FEATURE_EMBLEM_SIZE_PX / 2
        drawImage(
            image = emblem.image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(emblem.image.width, emblem.image.height),
            dstOffset = IntOffset(emblem.screen.x.toInt() - halfSize, emblem.screen.y.toInt() - halfSize),
            dstSize = IntSize(REAL_3D_FEATURE_EMBLEM_SIZE_PX, REAL_3D_FEATURE_EMBLEM_SIZE_PX),
            alpha = REAL_3D_FEATURE_EMBLEM_ALPHA,
            filterQuality = FilterQuality.Medium,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawReal3DLabels(
    frame: Real3DFrame,
    semanticMode: SemanticLabelMode,
    textMeasurer: TextMeasurer,
    cache: MapRenderCache,
    preferences: dev.evestaticmapplanner.preferences.MapDisplayPreferences,
    emphasis: MapVisualEmphasis,
) {
    val regionType = if (semanticMode == SemanticLabelMode.REGION_ONLY) {
        MapLabelType.REGION_PRIMARY
    } else {
        MapLabelType.REGION_BACKGROUND
    }
    frame.regions.forEach { projected ->
        drawCenteredReal3DLabel(
            projected.anchor.name,
            regionType,
            projected.screen,
            textMeasurer,
            cache,
            preferences,
            emphasis.hierarchyLabelAlphaMultiplier,
        )
    }
    if (semanticMode == SemanticLabelMode.CONSTELLATION) {
        frame.constellations.forEach { projected ->
            drawCenteredReal3DLabel(
                projected.anchor.name,
                MapLabelType.CONSTELLATION,
                projected.screen,
                textMeasurer,
                cache,
                preferences,
                emphasis.hierarchyLabelAlphaMultiplier,
            )
        }
    }
    if (semanticMode == SemanticLabelMode.SYSTEM) {
        frame.nodesFarToNear.forEach { projected ->
            val systemId = projected.node.system.id
            if (systemId !in emphasis.focusedSystemIds) {
                drawReal3DSystemLabel(
                    projected = projected,
                    textMeasurer = textMeasurer,
                    cache = cache,
                    preferences = preferences,
                    alphaMultiplier = emphasis.systemLabelAlphaMultiplier(systemId),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCenteredReal3DLabel(
    text: String,
    type: MapLabelType,
    center: MapPoint,
    textMeasurer: TextMeasurer,
    cache: MapRenderCache,
    preferences: dev.evestaticmapplanner.preferences.MapDisplayPreferences,
    alphaMultiplier: Float,
) {
    val layout = cache.label(text, type, preferences, textMeasurer)
    drawText(
        textLayoutResult = layout,
        color = labelColor(type, preferences).multiplyAlpha(alphaMultiplier),
        topLeft = Offset(
            (center.x - layout.size.width / 2.0).toFloat(),
            (center.y - layout.size.height / 2.0).toFloat(),
        ),
    )
}

private fun DrawScope.drawReal3DEmphasizedSystems(
    frame: Real3DFrame,
    emphasis: MapVisualEmphasis,
    sovereigntyColorsBySystemId: Map<Int, Color>,
    textMeasurer: TextMeasurer,
    cache: MapRenderCache,
    preferences: dev.evestaticmapplanner.preferences.MapDisplayPreferences,
) {
    if (!emphasis.isActive) return
    emphasis.focusedSystemIds.forEach { systemId ->
        val projected = frame.projectedBySystemId[systemId] ?: return@forEach
        drawCircle(
            color = real3DSystemBaseColor(projected, sovereigntyColorsBySystemId),
            radius = projected.radiusPx + REAL_3D_EMPHASIZED_NODE_RADIUS_INCREASE_PX,
            center = projected.screen.toOffset(),
        )
    }
    emphasis.prioritizedSystemIds.asSequence()
        .take(REAL_3D_MAX_EMPHASIZED_SYSTEM_LABELS)
        .forEach { systemId ->
            frame.projectedBySystemId[systemId]?.let { projected ->
                drawReal3DSystemLabel(projected, textMeasurer, cache, preferences)
            }
        }
}

private fun DrawScope.drawReal3DSystemLabel(
    projected: dev.evestaticmapplanner.core.map.Real3DProjectedNode,
    textMeasurer: TextMeasurer,
    cache: MapRenderCache,
    preferences: dev.evestaticmapplanner.preferences.MapDisplayPreferences,
    alphaMultiplier: Float = 1f,
    offsetPx: Double = REAL_3D_SYSTEM_LABEL_OFFSET_PX,
    drawBackground: Boolean = false,
) {
    val layout = cache.label(projected.node.system.name, MapLabelType.SYSTEM, preferences, textMeasurer)
    val topLeft = Offset(
        (projected.screen.x + offsetPx).toFloat(),
        (projected.screen.y - layout.size.height / 2.0).toFloat(),
    )
    if (drawBackground) {
        drawRect(
            color = REAL_3D_BACKGROUND.copy(alpha = 0.88f),
            topLeft = Offset(topLeft.x - 3f, topLeft.y - 2f),
            size = androidx.compose.ui.geometry.Size(layout.size.width + 6f, layout.size.height + 4f),
        )
    }
    drawText(
        textLayoutResult = layout,
        color = labelColor(MapLabelType.SYSTEM, preferences).multiplyAlpha(alphaMultiplier),
        topLeft = topLeft,
    )
}

private fun real3DSystemBaseColor(
    projected: dev.evestaticmapplanner.core.map.Real3DProjectedNode,
    sovereigntyColorsBySystemId: Map<Int, Color>,
): Color = sovereigntyColorsBySystemId[projected.node.system.id]
    ?: if (projected.node.isStargateConnected) REAL_3D_CONNECTED_NODE else REAL_3D_UNCONNECTED_NODE

private fun DrawScope.drawReal3DAnsiblexLayer(
    geometry: Real3DStaticGeometry,
    camera: dev.evestaticmapplanner.core.map.Real3DCamera,
    viewportSize: MapSize,
    connections: List<AnsiblexConnection>,
    emphasis: MapVisualEmphasis,
) {
    val projector = dev.evestaticmapplanner.core.map.Real3DProjector(camera, viewportSize)
    val path = Path()
    connections.asSequence().filter(AnsiblexConnection::enabled).forEach { connection ->
        val first = geometry.nodesById[connection.firstSystemId]?.position ?: return@forEach
        val second = geometry.nodesById[connection.secondSystemId]?.position ?: return@forEach
        val segment = projector.projectSegment(first, second) ?: return@forEach
        val curve = ansiblexConnectionGeometry(
            firstSystemId = connection.firstSystemId,
            secondSystemId = connection.secondSystemId,
            first = segment.first.screen,
            second = segment.second.screen,
        )
        path.reset()
        path.moveTo(curve.start.x.toFloat(), curve.start.y.toFloat())
        path.quadraticTo(
            curve.control.x.toFloat(),
            curve.control.y.toFloat(),
            curve.end.x.toFloat(),
            curve.end.y.toFloat(),
        )
        drawPath(
            path = path,
            color = ANSIBLEX_NETWORK_COLOR.multiplyAlpha(emphasis.ansiblexAlphaMultiplier(connection.id)),
            style = Stroke(width = 1.5f, pathEffect = REAL_3D_ANSIBLEX_NETWORK_DASH_EFFECT),
        )
    }
}

private fun DrawScope.drawReal3DWormholes(
    geometry: Real3DStaticGeometry,
    camera: dev.evestaticmapplanner.core.map.Real3DCamera,
    viewportSize: MapSize,
    connections: List<WormholeConnection>,
    emphasis: MapVisualEmphasis,
) {
    val projector = dev.evestaticmapplanner.core.map.Real3DProjector(camera, viewportSize)
    connections.forEach { connection ->
        val alphaMultiplier = emphasis.wormholeAlphaMultiplier(connection.id)
        val first = geometry.nodesById[connection.firstSystemId]?.position ?: return@forEach
        val second = geometry.nodesById[connection.secondSystemId]?.position ?: return@forEach
        val projected = projector.projectSegment(first, second) ?: return@forEach
        val visual = wormholeConnectionVisualGeometry(
            firstSystemId = connection.firstSystemId,
            secondSystemId = connection.secondSystemId,
            first = projected.first.screen,
            second = projected.second.screen,
        )
        drawLine(
            color = WORMHOLE_PEACOCK_TEAL.copy(alpha = WORMHOLE_NETWORK_LINE_ALPHA * alphaMultiplier),
            start = visual.lineStart.toOffset(),
            end = visual.lineEnd.toOffset(),
            strokeWidth = WORMHOLE_NETWORK_LINE_WIDTH_PX,
        )
        visual.firstHalfChevrons.forEach { chevron ->
            drawLine(
                WORMHOLE_PEACOCK_TEAL.multiplyAlpha(alphaMultiplier),
                chevron.firstTail.toOffset(),
                chevron.tip.toOffset(),
                WORMHOLE_NETWORK_CHEVRON_WIDTH_PX,
            )
            drawLine(
                WORMHOLE_PEACOCK_TEAL.multiplyAlpha(alphaMultiplier),
                chevron.secondTail.toOffset(),
                chevron.tip.toOffset(),
                WORMHOLE_NETWORK_CHEVRON_WIDTH_PX,
            )
        }
        visual.secondHalfChevrons.forEach { chevron ->
            drawLine(
                WORMHOLE_PEACOCK_TEAL.multiplyAlpha(alphaMultiplier),
                chevron.firstTail.toOffset(),
                chevron.tip.toOffset(),
                WORMHOLE_NETWORK_CHEVRON_WIDTH_PX,
            )
            drawLine(
                WORMHOLE_PEACOCK_TEAL.multiplyAlpha(alphaMultiplier),
                chevron.secondTail.toOffset(),
                chevron.tip.toOffset(),
                WORMHOLE_NETWORK_CHEVRON_WIDTH_PX,
            )
        }
        fun drawEndpoint(endpoint: MapPoint) {
            drawCircle(
                WORMHOLE_PEACOCK_TEAL.copy(alpha = 0.9f * alphaMultiplier),
                WORMHOLE_ENDPOINT_OUTER_RADIUS_PX,
                endpoint.toOffset(),
                style = Stroke(WORMHOLE_ENDPOINT_STROKE_WIDTH_PX),
            )
            drawCircle(
                WORMHOLE_PEACOCK_TEAL.copy(alpha = 0.72f * alphaMultiplier),
                WORMHOLE_ENDPOINT_INNER_RADIUS_PX,
                endpoint.toOffset(),
                style = Stroke(WORMHOLE_ENDPOINT_STROKE_WIDTH_PX),
            )
        }
        drawEndpoint(visual.firstEndpoint)
        drawEndpoint(visual.secondEndpoint)
        drawCircle(
            WORMHOLE_PEACOCK_TEAL.multiplyAlpha(alphaMultiplier),
            WORMHOLE_CENTER_MARKER_RADIUS_PX,
            visual.center.toOffset(),
        )
    }
}

private fun DrawScope.drawReal3DJumpSpheres(
    frame: Real3DFrame,
    spheres: List<Real3DProjectedJumpSphere>,
    userOverlayCount: Int,
) {
    val trianglePath = Path()
    spheres.forEachIndexed { index, projected ->
        val color = if (index < userOverlayCount) {
            JUMP_OVERLAY_COLORS[index % JUMP_OVERLAY_COLORS.size]
        } else {
            MISSION_JUMP_COLORS[(index - userOverlayCount) % MISSION_JUMP_COLORS.size]
        }
        projected.fillTrianglesFarToNear.forEach { triangle ->
            trianglePath.reset()
            trianglePath.moveTo(triangle.first.x.toFloat(), triangle.first.y.toFloat())
            trianglePath.lineTo(triangle.second.x.toFloat(), triangle.second.y.toFloat())
            trianglePath.lineTo(triangle.third.x.toFloat(), triangle.third.y.toFloat())
            trianglePath.close()
            drawPath(trianglePath, color.copy(alpha = REAL_3D_JUMP_FILL_ALPHA))
        }
        projected.shellSegments.forEach { segment ->
            drawLine(
                color = color.copy(alpha = REAL_3D_JUMP_SHELL_ALPHA),
                start = segment.first.screen.toOffset(),
                end = segment.second.screen.toOffset(),
                strokeWidth = REAL_3D_JUMP_SHELL_WIDTH_PX,
            )
        }
        projected.sphere.overlay.reachableSystemIds.forEach { systemId ->
            frame.projectedBySystemId[systemId]?.let { node ->
                drawCircle(
                    color = color.copy(alpha = 0.82f),
                    radius = 5f + (index % 4) * 2.2f,
                    center = node.screen.toOffset(),
                    style = Stroke(1.5f),
                )
            }
        }
        frame.projectedBySystemId[projected.sphere.overlay.originSystemId]?.let { origin ->
            val center = origin.screen.toOffset()
            drawCircle(color.copy(alpha = 0.2f), 11f, center)
            drawCircle(color, 7f + (index % 3), center, style = Stroke(2f))
        }
    }
}

private fun DrawScope.drawReal3DRoute(
    frame: Real3DFrame,
    overlay: Real3DProjectedRoute,
    showDestination: Boolean,
) {
    val curvedPath = Path()
    overlay.legs.forEach { leg ->
        val style = routeLegRenderStyle(leg.edge.type)
        val connection = activeRouteConnectionGeometry(
            firstSystemId = leg.edge.fromSystemId,
            secondSystemId = leg.edge.toSystemId,
            edgeType = leg.edge.type,
            start = leg.segment.first.screen,
            end = leg.segment.second.screen,
        )
        when (connection) {
            is StraightMapConnectionGeometry -> drawLine(
                color = style.color,
                start = connection.start.toOffset(),
                end = connection.end.toOffset(),
                strokeWidth = style.strokeWidth,
            )
            is QuadraticMapConnectionGeometry -> {
                curvedPath.reset()
                curvedPath.moveTo(connection.start.x.toFloat(), connection.start.y.toFloat())
                curvedPath.quadraticTo(
                    connection.control.x.toFloat(),
                    connection.control.y.toFloat(),
                    connection.end.x.toFloat(),
                    connection.end.y.toFloat(),
                )
                drawPath(
                    path = curvedPath,
                    color = style.color,
                    style = Stroke(
                        width = style.strokeWidth,
                        pathEffect = REAL_3D_ROUTE_ANSIBLEX_DASH_EFFECT,
                    ),
                )
            }
        }
        if (leg.edge.type == RouteEdgeType.WORMHOLE) {
            drawCircle(
                color = Color(0xFFF0FFFC),
                radius = 1.6f,
                center = MapPoint(
                    (leg.segment.first.screen.x + leg.segment.second.screen.x) / 2.0,
                    (leg.segment.first.screen.y + leg.segment.second.screen.y) / 2.0,
                ).toOffset(),
            )
        }
    }
    drawReal3DRouteEndpoint(frame, overlay.route.startSystemId, ROUTE_START_COLOR, 12f, 8f)
    if (showDestination) {
        drawReal3DRouteEndpoint(frame, overlay.route.destinationSystemId, ROUTE_DESTINATION_COLOR, 12f, 8f)
    }
}

private fun DrawScope.drawReal3DCapitalRoute(
    frame: Real3DFrame,
    overlay: Real3DProjectedCapitalRoute,
    showDestination: Boolean,
) {
    overlay.legs.forEach { leg ->
        drawLine(
            color = CAPITAL_ROUTE_COLOR,
            start = leg.segment.first.screen.toOffset(),
            end = leg.segment.second.screen.toOffset(),
            strokeWidth = 4f,
        )
    }
    drawReal3DRouteEndpoint(frame, overlay.route.startSystemId, CAPITAL_START_COLOR, 13f, 9f)
    if (showDestination) {
        drawReal3DRouteEndpoint(frame, overlay.route.destinationSystemId, CAPITAL_DESTINATION_COLOR, 13f, 9f)
    }
}

private fun DrawScope.drawReal3DMissionRoute(
    overlay: Real3DProjectedRoute,
    color: Color,
) {
    overlay.legs.forEach { leg ->
        drawLine(
            color = color,
            start = leg.segment.first.screen.toOffset(),
            end = leg.segment.second.screen.toOffset(),
            strokeWidth = 5f,
            pathEffect = REAL_3D_MISSION_ROUTE_DASH_EFFECT,
        )
    }
}

private fun DrawScope.drawReal3DMissionCapitalRoute(
    overlay: Real3DProjectedCapitalRoute,
    color: Color,
) {
    overlay.legs.forEach { leg ->
        drawLine(
            color = color,
            start = leg.segment.first.screen.toOffset(),
            end = leg.segment.second.screen.toOffset(),
            strokeWidth = 5f,
            pathEffect = REAL_3D_MISSION_CAPITAL_DASH_EFFECT,
        )
    }
}

private fun DrawScope.drawReal3DRouteEndpoint(
    frame: Real3DFrame,
    systemId: Int,
    color: Color,
    haloRadius: Float,
    ringRadius: Float,
) {
    val center = frame.projectedBySystemId[systemId]?.screen?.toOffset() ?: return
    drawCircle(color.copy(alpha = 0.25f), haloRadius, center)
    drawCircle(color, ringRadius, center, style = Stroke(3f))
}

private fun DrawScope.drawReal3DRouteWaypoints(
    frame: Real3DFrame,
    waypoints: List<PresentedRouteWaypoint>,
    textMeasurer: TextMeasurer,
) {
    waypoints.forEach { waypoint ->
        val base = frame.projectedBySystemId[waypoint.systemId]?.screen?.toOffset() ?: return@forEach
        val column = waypoint.stackIndex % 3
        val row = waypoint.stackIndex / 3
        val center = Offset(base.x + 11f + column * 14f, base.y - 10f - row * 14f)
        val color = when (waypoint.kind) {
            RouteWaypointKind.USER_NORMAL -> ROUTE_STARGATE_COLOR
            RouteWaypointKind.USER_CAPITAL -> CAPITAL_ROUTE_COLOR
            RouteWaypointKind.MISSION_NORMAL -> MISSION_ROUTE_COLORS.first()
            RouteWaypointKind.MISSION_CAPITAL -> MISSION_CAPITAL_COLORS.first()
        }
        drawCircle(Color(0xE6162430), 7f, center)
        drawCircle(color.copy(alpha = 0.86f), 7f, center, style = Stroke(1.25f))
        val label = textMeasurer.measure(
            waypoint.sequenceNumber.toString(),
            style = TextStyle(fontSize = 8.sp, color = Color(0xFFF1F5F8)),
            softWrap = false,
        )
        drawText(label, topLeft = Offset(center.x - label.size.width / 2f, center.y - label.size.height / 2f))
    }
}

private fun DrawScope.drawReal3DInteraction(
    frame: Real3DFrame,
    hoveredSystemId: Int?,
    selectedSystemId: Int?,
    textMeasurer: TextMeasurer,
    cache: MapRenderCache,
    preferences: dev.evestaticmapplanner.preferences.MapDisplayPreferences,
) {
    selectedSystemId?.let { frame.projectedBySystemId[it] }?.let { node ->
        drawReal3DHighlightedNode(
            node,
            REAL_3D_SELECTED_COLOR,
            8f,
            textMeasurer,
            cache,
            preferences,
        )
    }
    hoveredSystemId?.takeUnless { it == selectedSystemId }?.let { frame.projectedBySystemId[it] }?.let { node ->
        drawReal3DHighlightedNode(
            node,
            REAL_3D_HOVER_COLOR,
            6f,
            textMeasurer,
            cache,
            preferences,
        )
    }
}

private fun DrawScope.drawReal3DHighlightedNode(
    projected: dev.evestaticmapplanner.core.map.Real3DProjectedNode,
    color: Color,
    radius: Float,
    textMeasurer: TextMeasurer,
    cache: MapRenderCache,
    preferences: dev.evestaticmapplanner.preferences.MapDisplayPreferences,
) {
    val center = projected.screen.toOffset()
    drawCircle(color.copy(alpha = 0.2f), radius = radius + 4f, center = center)
    drawCircle(color, radius = radius, center = center, style = Stroke(2f))
    drawReal3DSystemLabel(
        projected = projected,
        textMeasurer = textMeasurer,
        cache = cache,
        preferences = preferences,
        offsetPx = radius + 6.0,
        drawBackground = true,
    )
}

private fun Offset.toMapPoint(): MapPoint = MapPoint(x.toDouble(), y.toDouble())
private fun MapPoint.toOffset(): Offset = Offset(x.toFloat(), y.toFloat())
private fun Color.multiplyAlpha(multiplier: Float): Color = copy(alpha = alpha * multiplier)

private val REAL_3D_BACKGROUND = Color(0xFF09121D)
private val REAL_3D_EDGE_COLOR = Color(0xFF5A7185)
private val REAL_3D_CONNECTED_NODE = Color(0xFF75B9E7)
private val REAL_3D_UNCONNECTED_NODE = Color(0xFF596673)
private val REAL_3D_HOVER_COLOR = Color(0xFFF3D36A)
private val REAL_3D_SELECTED_COLOR = Color(0xFF76E6A5)
private const val REAL_3D_EDGE_WIDTH_PX = 1f
private const val REAL_3D_SYSTEM_LABEL_OFFSET_PX = 5.0
private const val REAL_3D_EMPHASIZED_NODE_RADIUS_INCREASE_PX = 0.8f
private const val REAL_3D_MAX_EMPHASIZED_SYSTEM_LABELS = 80
private const val REAL_3D_JUMP_FILL_ALPHA = 0.012f
private const val REAL_3D_JUMP_SHELL_ALPHA = 0.34f
private const val REAL_3D_JUMP_SHELL_WIDTH_PX = 0.85f
private const val REAL_3D_FEATURE_LINK_ALPHA = 0.22f
private const val REAL_3D_FEATURE_LINK_WIDTH_PX = 2.4f
private const val REAL_3D_FEATURE_NODE_FILL_ALPHA = 0.18f
private const val REAL_3D_FEATURE_NODE_RING_ALPHA = 0.72f
private const val REAL_3D_FEATURE_NODE_RING_WIDTH_PX = 1.2f
private const val REAL_3D_FEATURE_RING_RADIUS_PX = 7f
private const val REAL_3D_FEATURE_RING_STEP_PX = 2.5f
private const val REAL_3D_FEATURE_EMBLEM_SIZE_PX = 34
private const val REAL_3D_FEATURE_EMBLEM_ALPHA = 0.42f
private val REAL_3D_ANSIBLEX_NETWORK_DASH_EFFECT = PathEffect.dashPathEffect(ANSIBLEX_NETWORK_DASH_PATTERN)
private val REAL_3D_ROUTE_ANSIBLEX_DASH_EFFECT = PathEffect.dashPathEffect(ROUTE_ANSIBLEX_DASH_PATTERN.toFloatArray())
private val REAL_3D_MISSION_ROUTE_DASH_EFFECT = PathEffect.dashPathEffect(floatArrayOf(14f, 5f))
private val REAL_3D_MISSION_CAPITAL_DASH_EFFECT = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
