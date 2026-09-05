package dev.evestaticmapplanner.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.core.map.MapProjectionId
import dev.evestaticmapplanner.core.map.ProjectedRouteOverlayBuilder
import dev.evestaticmapplanner.ansiblex.AnsiblexManagerDialog
import dev.evestaticmapplanner.route.RoutePlannerUiState
import dev.evestaticmapplanner.route.RoutePlannerViewModel
import dev.evestaticmapplanner.route.RouteToolsPanel
import dev.evestaticmapplanner.search.SearchSuggestionsPresentation
import dev.evestaticmapplanner.search.SystemSearchField
import dev.evestaticmapplanner.capital.CapitalRouteUiState
import dev.evestaticmapplanner.capital.CapitalRouteViewModel
import dev.evestaticmapplanner.jump.JumpOverlayUiState
import dev.evestaticmapplanner.jump.JumpOverlayViewModel
import dev.evestaticmapplanner.core.map.ProjectedCapitalRouteOverlayBuilder
import dev.evestaticmapplanner.core.map.ProjectedJumpRangeOverlayBuilder
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.marker.MarkerContextAction
import dev.evestaticmapplanner.marker.MarkerEditorDialog
import dev.evestaticmapplanner.marker.MarkerEditorMode
import dev.evestaticmapplanner.marker.MarkerEditorRequest
import dev.evestaticmapplanner.marker.MarkerUiState
import dev.evestaticmapplanner.marker.MarkerViewModel
import dev.evestaticmapplanner.shared.SharedMapViewModel
import dev.evestaticmapplanner.shared.SharedMarkerContextAction
import dev.evestaticmapplanner.shared.SharedMarkerEditorDialog
import dev.evestaticmapplanner.shared.SharedMarkerEditorMode
import dev.evestaticmapplanner.shared.SharedMarkerEditorRequest
import dev.evestaticmapplanner.shared.SharedMarkerMutationUiState
import dev.evestaticmapplanner.shared.canWriteSharedMarkers
import dev.evestaticmapplanner.shared.model.SharedMapState
import dev.evestaticmapplanner.control.MissionMapUiState
import dev.evestaticmapplanner.feature.api.OverlayState
import dev.evestaticmapplanner.feature.api.SystemInfoState
import dev.evestaticmapplanner.feature.api.RouteSnapshot
import dev.evestaticmapplanner.feature.api.NavigationSnapshot
import dev.evestaticmapplanner.feature.api.RouteActionTargetId
import dev.evestaticmapplanner.featurepack.RouteActionKey
import dev.evestaticmapplanner.featurepack.RouteActionUiState
import dev.evestaticmapplanner.view.PlanningViewCoordinator
import dev.evestaticmapplanner.view.PlanningView
import dev.evestaticmapplanner.view.PlanningViewId
import dev.evestaticmapplanner.view.PlanningViewsState
import dev.evestaticmapplanner.wormhole.CreateWormholeDialog
import dev.evestaticmapplanner.wormhole.WormholeConnectionsDialog
import dev.evestaticmapplanner.wormhole.WormholeManagerDialog
import dev.evestaticmapplanner.wormhole.WormholeUiState
import dev.evestaticmapplanner.wormhole.WormholeViewModel
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveDropdownMenu as DropdownMenu
import dev.evestaticmapplanner.ui.EveDropdownMenuItem as DropdownMenuItem
import dev.evestaticmapplanner.ui.EveOutlinedTextField as OutlinedTextField
import dev.evestaticmapplanner.ui.EveTab
import dev.evestaticmapplanner.ui.EveTextButton as TextButton
import java.nio.file.Path

@Composable
internal fun StaticMapScreen(
    modifier: Modifier = Modifier,
    databasePath: Path,
    userDatabasePath: Path,
    state: MapUiState,
    routeState: RoutePlannerUiState,
    wormholeState: WormholeUiState,
    jumpState: JumpOverlayUiState,
    capitalState: CapitalRouteUiState,
    planningViewsState: PlanningViewsState,
    markerState: MarkerUiState,
    sharedMapState: SharedMapState,
    sharedMarkerState: SharedMarkerPresentationState,
    sharedMarkerMutation: SharedMarkerMutationUiState,
    missionState: MissionMapUiState,
    featureOverlayState: OverlayState,
    systemInfoState: SystemInfoState,
    routeActions: List<RouteActionUiState>,
    normalRouteSnapshot: RouteSnapshot?,
    normalNavigationSnapshot: NavigationSnapshot?,
    capitalRouteSnapshot: RouteSnapshot?,
    onInvokeRouteAction: (RouteActionKey, RouteSnapshot, RouteActionTargetId?) -> Unit,
    onInvokeNavigationAction: (RouteActionKey, NavigationSnapshot, RouteActionTargetId?) -> Unit,
    viewModel: MapViewModel,
    routeViewModel: RoutePlannerViewModel,
    wormholeViewModel: WormholeViewModel,
    jumpViewModel: JumpOverlayViewModel,
    capitalViewModel: CapitalRouteViewModel,
    planningViewCoordinator: PlanningViewCoordinator,
    markerViewModel: MarkerViewModel,
    sharedMapViewModel: SharedMapViewModel,
    onFirstMapDisplayed: () -> Unit,
    suppressMarkerOperationErrorDialog: Boolean = false,
) {
    var showAnsiblexManager by remember { mutableStateOf(false) }
    var showWormholeManager by remember { mutableStateOf(false) }
    var wormholeConnectionsSystemId by remember { mutableStateOf<Int?>(null) }
    var markerEditor by remember { mutableStateOf<MarkerEditorRequest?>(null) }
    var expectedMarkerDraft by remember { mutableStateOf<MarkerDraft?>(null) }
    var markerPendingRemoval by remember { mutableStateOf<Int?>(null) }
    var savedRemovalStarted by remember { mutableStateOf(false) }
    var sharedMarkerEditor by remember { mutableStateOf<SharedMarkerEditorRequest?>(null) }
    val rendererNormalRoute = activeNormalRouteForRenderer(routeState.activeRoute)

    LaunchedEffect(sharedMapState.selectedWorkspaceId) {
        if (sharedMarkerEditor?.workspaceId != sharedMapState.selectedWorkspaceId) {
            sharedMarkerEditor = null
            sharedMapViewModel.clearMarkerMutationFeedback()
        }
    }

    LaunchedEffect(markerState.markersBySystemId, markerState.busySystemIds, markerState.operationError) {
        val editor = markerEditor
        val expected = expectedMarkerDraft
        val editorSystemId = editor?.systemId
        if (editorSystemId != null && expected != null && editorSystemId !in markerState.busySystemIds &&
            markerState.operationError == null && markerState.markersBySystemId[editorSystemId]?.toDraft() == expected
        ) {
            markerEditor = null
            expectedMarkerDraft = null
        }
        val removalId = markerPendingRemoval
        if (removalId != null && savedRemovalStarted && removalId !in markerState.busySystemIds &&
            markerState.markersBySystemId[removalId] == null
        ) {
            markerPendingRemoval = null
            savedRemovalStarted = false
        }
    }
    Row(modifier.fillMaxSize().background(EveColors.MapBackground)) {
        RouteToolsPanel(
            state = routeState,
            viewModel = routeViewModel,
            jumpState = jumpState,
            jumpViewModel = jumpViewModel,
            capitalState = capitalState,
            capitalViewModel = capitalViewModel,
            routeActions = routeActions,
            normalRouteSnapshot = normalRouteSnapshot,
            normalNavigationSnapshot = normalNavigationSnapshot,
            capitalRouteSnapshot = capitalRouteSnapshot,
            selectedRouteActionTargets = planningViewsState.currentView.selectedRouteActionTargets,
            onSelectRouteActionTarget = planningViewCoordinator::selectRouteActionTarget,
            onInvokeRouteAction = onInvokeRouteAction,
            onInvokeNavigationAction = onInvokeNavigationAction,
            onOpenAnsiblexManager = { showAnsiblexManager = true },
            onOpenWormholeManager = { showWormholeManager = true },
            onFocusSystem = viewModel::selectAndFocusSystem,
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            MapToolbar(
                state = state,
                planningViewsState = planningViewsState,
                viewModel = viewModel,
                planningViewCoordinator = planningViewCoordinator,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> CenterMessage("Loading static universe…")
                    state.error != null -> CenterMessage("Unable to load map\n${state.error}\n\nDatabase: $databasePath")
                    state.scene != null && state.viewport != null -> StaticMapCanvas(
                        state = state,
                        activeRoute = rendererNormalRoute,
                        normalWaypointSystemIds = routeState.calculatedWaypointSystemIds,
                        normalExplicitDestinationSystemId = routeState.calculatedExplicitDestinationSystemId,
                        capitalRoute = capitalState.activeRoute,
                        capitalWaypointSystemIds = capitalState.calculatedWaypointSystemIds,
                        capitalExplicitDestinationSystemId = capitalState.calculatedExplicitDestinationSystemId,
                        jumpOverlays = jumpState.overlays,
                        intersectionSystemIds = jumpState.intersectionSystemIds,
                        ansiblexConnections = routeState.ansiblexConnections,
                        wormholeConnections = routeState.wormholeConnections,
                        showAnsiblexLayer = routeState.showAnsiblexLayer,
                        markerState = markerState,
                        sharedMapState = sharedMapState,
                        sharedMarkerState = sharedMarkerState,
                        missionState = missionState,
                        featureOverlayState = featureOverlayState,
                        compactSystemInfo = CompactSystemInfoPresentationBuilder.build(
                            state,
                            routeState,
                            jumpState,
                            state.selectedSystemId?.let(markerState.markersBySystemId::get),
                            systemInfoState,
                            sharedMarkerState,
                        ),
                        onCanvasSizeChanged = viewModel::onCanvasSizeChanged,
                        onZoom = viewModel::zoomAt,
                        onPan = viewModel::panBy,
                        onRotate = viewModel::rotateBy,
                        onHover = viewModel::hoverAt,
                        onHoverExit = viewModel::clearHover,
                        onSelect = viewModel::selectAt,
                        onFocus = viewModel::selectAndFocusAt,
                        onContextMenu = viewModel::openContextMenuAt,
                        onContextRouteStart = {
                            routeViewModel.setRouteStart(it)
                            viewModel.dismissContextMenu()
                        },
                        onContextRouteWaypoint = {
                            routeViewModel.addRouteWaypoint(it)
                            viewModel.dismissContextMenu()
                        },
                        onContextRouteDestination = {
                            routeViewModel.setRouteDestination(it)
                            viewModel.dismissContextMenu()
                        },
                        onContextJumpOverlay = {
                            jumpViewModel.addForSystem(it)
                            viewModel.dismissContextMenu()
                        },
                        onContextCapitalStart = {
                            capitalViewModel.setRouteStart(it)
                            viewModel.dismissContextMenu()
                        },
                        onContextCapitalWaypoint = {
                            capitalViewModel.addRouteWaypoint(it)
                            viewModel.dismissContextMenu()
                        },
                        onContextCapitalDestination = {
                            capitalViewModel.setRouteDestination(it)
                            viewModel.dismissContextMenu()
                        },
                        onContextMarkerAction = { systemId, action ->
                            val systemName = state.scene.nodesById[systemId]?.system?.name ?: "System $systemId"
                            val marker = markerState.markersBySystemId[systemId]
                            when (action) {
                                MarkerContextAction.ADD_TEMPORARY -> markerViewModel.addTemporary(systemId)
                                MarkerContextAction.ADD_SAVED -> markerEditor = MarkerEditorRequest(
                                    MarkerEditorMode.CREATE_SAVED,
                                    systemId,
                                    systemName,
                                )
                                MarkerContextAction.EDIT -> marker?.let {
                                    markerEditor = MarkerEditorRequest(
                                        if (it.persistence == MarkerPersistence.SAVED) {
                                            MarkerEditorMode.EDIT_SAVED
                                        } else {
                                            MarkerEditorMode.EDIT_TEMPORARY
                                        },
                                        systemId,
                                        systemName,
                                        it,
                                    )
                                }
                                MarkerContextAction.SAVE_PERMANENTLY -> markerViewModel.saveTemporaryPermanently(systemId)
                                MarkerContextAction.REMOVE -> if (marker?.persistence == MarkerPersistence.SAVED) {
                                    markerPendingRemoval = systemId
                                    savedRemovalStarted = false
                                } else {
                                    markerViewModel.removeTemporary(systemId)
                                }
                                MarkerContextAction.UNAVAILABLE -> Unit
                            }
                            viewModel.dismissContextMenu()
                        },
                        onContextSharedMarkerAction = { systemId, action ->
                            val workspaceId = sharedMapState.selectedWorkspaceId
                            val systemName = state.scene.nodesById[systemId]?.system?.name ?: "System $systemId"
                            val marker = sharedMapState.snapshot?.markers?.values?.singleOrNull {
                                it.systemId == systemId
                            }
                            if (workspaceId != null) {
                                sharedMapViewModel.clearMarkerMutationFeedback()
                                sharedMarkerEditor = when (action) {
                                    SharedMarkerContextAction.ADD -> SharedMarkerEditorRequest(
                                        workspaceId = workspaceId,
                                        mode = SharedMarkerEditorMode.CREATE,
                                        systemId = systemId,
                                        systemName = systemName,
                                    )
                                    SharedMarkerContextAction.OPEN -> marker?.let {
                                        SharedMarkerEditorRequest(
                                            workspaceId = workspaceId,
                                            mode = if (canWriteSharedMarkers(sharedMapState)) {
                                                SharedMarkerEditorMode.EDIT
                                            } else {
                                                SharedMarkerEditorMode.VIEW
                                            },
                                            systemId = systemId,
                                            systemName = systemName,
                                            marker = it,
                                        )
                                    }
                                }
                            }
                            viewModel.dismissContextMenu()
                        },
                        onContextCreateWormhole = { systemId ->
                            state.scene.nodesById[systemId]?.system?.let(wormholeViewModel::beginQuickCreate)
                            viewModel.dismissContextMenu()
                        },
                        onContextManageWormholes = { systemId ->
                            wormholeConnectionsSystemId = systemId
                            viewModel.dismissContextMenu()
                        },
                        onContextDismiss = viewModel::dismissContextMenu,
                        onFirstMapDisplayed = {
                            viewModel.onFirstMapDisplayed()
                            onFirstMapDisplayed()
                        },
                    )
                    state.scene != null -> CanvasSizeProbe(viewModel::onCanvasSizeChanged)
                }
            }
            state.scene?.let { scene ->
                val routeOverlay = rendererNormalRoute
                    ?.let { ProjectedRouteOverlayBuilder.build(it, scene) }
                val routeWarning = routeOverlay?.takeIf { it.omittedSystemIds.isNotEmpty() }?.let {
                    " · route: ${it.omittedSystemIds.size} systems / ${it.omittedLegCount} legs unavailable; use Real 3D"
                }.orEmpty()
                val jumpOmitted = jumpState.overlays.sumOf {
                    ProjectedJumpRangeOverlayBuilder.build(it, scene).omittedSystemIds.size
                }
                val capitalOmitted = capitalState.activeRoute?.let {
                    ProjectedCapitalRouteOverlayBuilder.build(it, scene).omittedLegCount
                } ?: 0
                val wormholeOmitted = remember(scene, routeState.wormholeConnections) {
                    WormholeMapPresentationBuilder
                        .build(routeState.wormholeConnections, scene)
                        .omittedConnectionCount
                }
                Text(
                    text = "${scene.projectionId.displayName}: ${scene.nodes.size} systems · ${scene.edges.size} stargate connections" +
                        (if (scene.omittedSystemIds.isNotEmpty()) " · ${scene.omittedSystemIds.size} unavailable" else "") +
                        routeWarning +
                        (if (jumpOmitted > 0) " · jump overlay: $jumpOmitted unavailable" else "") +
                        (if (capitalOmitted > 0) " · capital route: $capitalOmitted legs unavailable" else "") +
                        (if (wormholeOmitted > 0) " · wormholes: $wormholeOmitted unavailable" else "") +
                        state.focusNotice?.let { " · $it" }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = EveColors.SecondaryText,
                    modifier = Modifier.fillMaxWidth().background(EveColors.SecondarySurface)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
    }
    if (showAnsiblexManager) {
        AnsiblexManagerDialog(
            userDatabasePath = userDatabasePath,
            state = routeState,
            viewModel = routeViewModel,
            onDismiss = { showAnsiblexManager = false },
        )
    }
    if (showWormholeManager) {
        WormholeManagerDialog(
            state = wormholeState,
            viewModel = wormholeViewModel,
            onDismiss = { showWormholeManager = false },
        )
    }
    if (wormholeState.quickOrigin != null) {
        CreateWormholeDialog(
            state = wormholeState,
            viewModel = wormholeViewModel,
            onCreated = wormholeViewModel::endQuickCreate,
            onDismiss = wormholeViewModel::endQuickCreate,
        )
    }
    wormholeConnectionsSystemId?.let { systemId ->
        val systemName = state.scene?.nodesById?.get(systemId)?.system?.name ?: "System $systemId"
        WormholeConnectionsDialog(
            systemId = systemId,
            systemName = systemName,
            state = wormholeState,
            viewModel = wormholeViewModel,
            onDismiss = { wormholeConnectionsSystemId = null },
        )
    }
    markerEditor?.let { request ->
        MarkerEditorDialog(
            request = request,
            isBusy = request.systemId?.let { it in markerState.busySystemIds } == true,
            error = markerState.operationError,
            children = request.systemId?.let { markerState.childrenByParentSystemId[it] }.orEmpty(),
            onAddChild = { type -> request.systemId?.let { markerViewModel.addChild(it, type) } },
            onRemoveChild = { childId -> request.systemId?.let { markerViewModel.removeChild(it, childId) } },
            onSave = { systemId, draft, initialTags ->
                when (request.mode) {
                    MarkerEditorMode.EDIT_TEMPORARY -> if (markerViewModel.updateTemporary(systemId, draft)) {
                        markerEditor = null
                        expectedMarkerDraft = null
                    }
                    MarkerEditorMode.CREATE_SAVED -> if (markerViewModel.createSaved(systemId, draft, initialTags)) {
                        expectedMarkerDraft = draft
                    }
                    MarkerEditorMode.EDIT_SAVED -> if (markerViewModel.updateSaved(systemId, draft)) {
                        expectedMarkerDraft = draft
                    }
                }
            },
            onDismiss = {
                markerEditor = null
                expectedMarkerDraft = null
                markerViewModel.clearOperationError()
            },
        )
    }
    sharedMarkerEditor?.let { request ->
        val currentMarker = request.marker?.markerId?.let { markerId ->
            sharedMapState.snapshot?.markers?.get(markerId)
        }
        SharedMarkerEditorDialog(
            request = request,
            currentMarker = if (request.mode == SharedMarkerEditorMode.CREATE) null else currentMarker,
            canWrite = canWriteSharedMarkers(sharedMapState),
            mutation = sharedMarkerMutation,
            onCreate = sharedMapViewModel::createSharedMarker,
            onUpdate = sharedMapViewModel::updateSharedMarker,
            onDelete = sharedMapViewModel::deleteSharedMarker,
            onClearFeedback = sharedMapViewModel::clearMarkerMutationFeedback,
            onDismiss = {
                sharedMarkerEditor = null
                sharedMapViewModel.clearMarkerMutationFeedback()
            },
        )
    }
    markerPendingRemoval?.let { systemId ->
        val name = state.scene?.nodesById?.get(systemId)?.system?.name ?: "System $systemId"
        AlertDialog(
            onDismissRequest = {
                if (!savedRemovalStarted) {
                    markerPendingRemoval = null
                    markerViewModel.clearOperationError()
                }
            },
            title = { Text("Remove saved marker?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Remove the saved marker from $name? This cannot be undone.")
                    markerState.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = systemId !in markerState.busySystemIds,
                    onClick = { savedRemovalStarted = markerViewModel.removeSaved(systemId) },
                ) { Text(if (systemId in markerState.busySystemIds) "Removing…" else "Remove") }
            },
            dismissButton = {
                TextButton(
                    enabled = systemId !in markerState.busySystemIds,
                    onClick = {
                        markerPendingRemoval = null
                        savedRemovalStarted = false
                        markerViewModel.clearOperationError()
                    },
                ) { Text("Cancel") }
            },
        )
    }
    if (!suppressMarkerOperationErrorDialog && markerState.operationError != null &&
        markerEditor == null && markerPendingRemoval == null
    ) {
        AlertDialog(
            onDismissRequest = markerViewModel::clearOperationError,
            title = { Text("Marker operation failed") },
            text = { Text(checkNotNull(markerState.operationError)) },
            confirmButton = { TextButton(onClick = markerViewModel::clearOperationError) { Text("OK") } },
        )
    }
}

/** Phase 4 policy seam: Wormhole legs are renderer-supported and must never suppress the route. */
internal fun activeNormalRouteForRenderer(
    route: dev.evestaticmapplanner.core.route.RouteResult?,
): dev.evestaticmapplanner.core.route.RouteResult? = route

@Composable
private fun MapToolbar(
    state: MapUiState,
    planningViewsState: PlanningViewsState,
    viewModel: MapViewModel,
    planningViewCoordinator: PlanningViewCoordinator,
) {
    var renameViewId by remember { mutableStateOf<PlanningViewId?>(null) }
    MapToolbarContent(
        projectionId = state.projectionId,
        fitEnabled = state.scene != null,
        planningViewsState = planningViewsState,
        onSwitchProjection = viewModel::switchProjection,
        onSwitchView = planningViewCoordinator::switchView,
        onCreateView = planningViewCoordinator::createView,
        onRenameView = { view -> renameViewId = view.id },
        onDeleteView = planningViewCoordinator::deleteView,
        onFitMap = viewModel::fitMap,
        onResetView = viewModel::resetView,
    )
    renameViewId?.let { id ->
        planningViewsState.views.firstOrNull { it.id == id }?.let { view ->
            ViewRenameDialog(
                view = view,
                onRename = planningViewCoordinator::renameView,
                onDismiss = { renameViewId = null },
            )
        }
    }
}

@Composable
internal fun ViewRenameDialog(
    view: PlanningView,
    onRename: (PlanningViewId, String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var renameText by remember(view.id) { mutableStateOf(view.label) }
    var renameError by remember(view.id) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename View") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it; renameError = null },
                    label = { Text("View name") },
                    singleLine = true,
                )
                renameError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (onRename(view.id, renameText)) onDismiss()
                else renameError = "View names must be non-empty and unique."
            }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun MapToolbarContent(
    projectionId: MapProjectionId,
    fitEnabled: Boolean,
    planningViewsState: PlanningViewsState,
    onSwitchProjection: (MapProjectionId) -> Unit,
    onSwitchView: (PlanningViewId) -> Boolean,
    onCreateView: () -> PlanningViewId,
    onRenameView: (PlanningView) -> Unit,
    onDeleteView: (PlanningViewId) -> Boolean,
    onFitMap: () -> Unit,
    onResetView: () -> Unit = {},
    viewScrollState: ScrollState? = null,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides MAP_TOOLBAR_BUTTON_HEIGHT) {
        Surface(modifier = modifier, color = EveColors.SecondarySurface, contentColor = EveColors.PrimaryText) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = MAP_TOOLBAR_VERTICAL_PADDING),
            ) {
                ProjectionControls(projectionId, onSwitchProjection)
                ViewStrip(
                    state = planningViewsState,
                    onSwitch = onSwitchView,
                    onCreate = onCreateView,
                    onRename = onRenameView,
                    onDelete = onDeleteView,
                    scrollState = viewScrollState,
                    modifier = Modifier.weight(1f),
                )
                CompactToolbarTextButton(onClick = onFitMap, enabled = fitEnabled) { Text("Fit Map") }
                if (projectionId == MapProjectionId.REAL_3D) {
                    CompactToolbarTextButton(onClick = onResetView, enabled = fitEnabled) { Text("Reset View") }
                }
            }
        }
    }
}

@Composable
private fun ProjectionControls(
    projectionId: MapProjectionId,
    onSwitchProjection: (MapProjectionId) -> Unit,
) {
    MapProjectionId.entries.forEach { projection ->
        CompactToolbarTab(
            selected = projection == projectionId,
            enabled = projection != projectionId,
            onClick = { onSwitchProjection(projection) },
        ) { Text(projection.displayName) }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun ViewStrip(
    state: PlanningViewsState,
    onSwitch: (PlanningViewId) -> Boolean,
    onCreate: () -> PlanningViewId,
    onRename: (PlanningView) -> Unit,
    onDelete: (PlanningViewId) -> Boolean,
    scrollState: ScrollState? = null,
    modifier: Modifier = Modifier,
) {
    val scroll = scrollState ?: rememberScrollState()
    var contextMenuViewId by remember { mutableStateOf<PlanningViewId?>(null) }
    Row(
        modifier = modifier
            .horizontalScroll(scroll)
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                dispatchViewWheelScroll(scroll, delta)
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        state.views.forEach { view ->
            Box(
                Modifier.onPointerEvent(PointerEventType.Press) { event ->
                    if (event.buttons.isSecondaryPressed) {
                        contextMenuViewId = view.id
                    }
                },
            ) {
                CompactToolbarTab(
                    selected = view.id == state.currentViewId,
                    onClick = { onSwitch(view.id) },
                ) { Text(view.label) }
                DropdownMenu(
                    expanded = contextMenuViewId == view.id,
                    onDismissRequest = { contextMenuViewId = null },
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            contextMenuViewId = null
                            onRename(view)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        enabled = state.views.size > 1,
                        onClick = {
                            contextMenuViewId = null
                            onDelete(view.id)
                        },
                    )
                }
            }
        }
        CompactToolbarTextButton(onClick = { onCreate() }, action = true) { Text("+") }
    }
}

internal fun dispatchViewWheelScroll(scroll: ScrollState, verticalDelta: Float): Float =
    if (verticalDelta == 0f) 0f else scroll.dispatchRawDelta(verticalDelta * VIEW_SCROLL_MULTIPLIER)

@Composable
private fun CompactToolbarTab(
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    EveTab(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.height(MAP_TOOLBAR_BUTTON_HEIGHT),
        contentPadding = MAP_TOOLBAR_CONTENT_PADDING,
        content = content,
    )
}

@Composable
private fun CompactToolbarTextButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    action: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .then(if (action) Modifier.width(MAP_TOOLBAR_ACTION_BUTTON_WIDTH) else Modifier)
            .height(MAP_TOOLBAR_BUTTON_HEIGHT),
        contentPadding = MAP_TOOLBAR_CONTENT_PADDING,
        content = content,
    )
}

internal fun confirmGlobalSystemSearch(
    system: dev.evestaticmapplanner.core.model.SolarSystem,
    updateSearchSelection: (dev.evestaticmapplanner.core.model.SolarSystem) -> Unit,
    focusSystem: (Int) -> Unit,
) {
    updateSearchSelection(system)
    focusSystem(system.id)
}

internal val MAP_TOOLBAR_VERTICAL_PADDING = 2.dp
internal val MAP_TOOLBAR_BUTTON_HEIGHT = 36.dp
internal val MAP_TOOLBAR_ACTION_BUTTON_WIDTH = 36.dp
internal val MAP_TOOLBAR_EXPECTED_HEIGHT = MAP_TOOLBAR_BUTTON_HEIGHT + MAP_TOOLBAR_VERTICAL_PADDING * 2
private val MAP_TOOLBAR_CONTENT_PADDING = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
internal const val VIEW_SCROLL_MULTIPLIER = 48f

@Composable
private fun CenterMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = EveColors.PrimaryText, modifier = Modifier.padding(24.dp))
    }
}

@Composable
private fun CanvasSizeProbe(onSizeChanged: (dev.evestaticmapplanner.core.map.MapSize) -> Unit) {
    Box(
        Modifier.fillMaxSize().then(
            Modifier.onSizeChanged {
                onSizeChanged(dev.evestaticmapplanner.core.map.MapSize(it.width.toDouble(), it.height.toDouble()))
            },
        ),
    )
}
