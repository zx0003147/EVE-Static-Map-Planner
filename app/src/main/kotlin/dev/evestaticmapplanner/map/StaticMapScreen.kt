package dev.evestaticmapplanner.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
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
import dev.evestaticmapplanner.control.MissionMapUiState
import java.nio.file.Path

@Composable
fun StaticMapScreen(
    databasePath: Path,
    userDatabasePath: Path,
    state: MapUiState,
    routeState: RoutePlannerUiState,
    jumpState: JumpOverlayUiState,
    capitalState: CapitalRouteUiState,
    markerState: MarkerUiState,
    missionState: MissionMapUiState,
    viewModel: MapViewModel,
    routeViewModel: RoutePlannerViewModel,
    jumpViewModel: JumpOverlayViewModel,
    capitalViewModel: CapitalRouteViewModel,
    markerViewModel: MarkerViewModel,
    suppressMarkerOperationErrorDialog: Boolean = false,
    onOpenStaticDataManager: () -> Unit,
) {
    var showAnsiblexManager by remember { mutableStateOf(false) }
    var markerEditor by remember { mutableStateOf<MarkerEditorRequest?>(null) }
    var expectedMarkerDraft by remember { mutableStateOf<MarkerDraft?>(null) }
    var markerPendingRemoval by remember { mutableStateOf<Int?>(null) }
    var savedRemovalStarted by remember { mutableStateOf(false) }

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
    Row(Modifier.fillMaxSize().background(Color(0xFF101923))) {
        RouteToolsPanel(
            state = routeState,
            viewModel = routeViewModel,
            jumpState = jumpState,
            jumpViewModel = jumpViewModel,
            capitalState = capitalState,
            capitalViewModel = capitalViewModel,
            onOpenAnsiblexManager = { showAnsiblexManager = true },
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            MapToolbar(
                state = state,
                routeState = routeState,
                viewModel = viewModel,
                routeViewModel = routeViewModel,
                onOpenStaticDataManager = onOpenStaticDataManager,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> CenterMessage("Loading static universe…")
                    state.error != null -> CenterMessage("Unable to load map\n${state.error}\n\nDatabase: $databasePath")
                    state.scene != null && state.viewport != null -> StaticMapCanvas(
                        state = state,
                        activeRoute = routeState.activeRoute,
                        capitalRoute = capitalState.activeRoute,
                        jumpOverlays = jumpState.overlays,
                        intersectionSystemIds = jumpState.intersectionSystemIds,
                        ansiblexConnections = routeState.ansiblexConnections,
                        showAnsiblexLayer = routeState.showAnsiblexLayer,
                        markerState = markerState,
                        missionState = missionState,
                        compactSystemInfo = CompactSystemInfoPresentationBuilder.build(
                            state,
                            routeState,
                            jumpState,
                            state.selectedSystemId?.let(markerState.markersBySystemId::get),
                        ),
                        onCanvasSizeChanged = viewModel::onCanvasSizeChanged,
                        onZoom = viewModel::zoomAt,
                        onPan = viewModel::panBy,
                        onHover = viewModel::hoverAt,
                        onHoverExit = viewModel::clearHover,
                        onSelect = viewModel::selectAt,
                        onContextMenu = viewModel::openContextMenuAt,
                        onContextRouteStart = {
                            routeViewModel.setRouteStart(it)
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
                        onContextCapitalDestination = {
                            capitalViewModel.setRouteDestination(it)
                            viewModel.dismissContextMenu()
                        },
                        onContextMarkerAction = { systemId, action ->
                            val systemName = state.scene?.nodesById?.get(systemId)?.system?.name ?: "System $systemId"
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
                        onContextDismiss = viewModel::dismissContextMenu,
                        onFirstMapDisplayed = viewModel::onFirstMapDisplayed,
                    )
                    state.scene != null -> CanvasSizeProbe(viewModel::onCanvasSizeChanged)
                }
            }
            state.scene?.let { scene ->
                val routeOverlay = routeState.activeRoute?.let { ProjectedRouteOverlayBuilder.build(it, scene) }
                val routeWarning = routeOverlay?.takeIf { it.omittedSystemIds.isNotEmpty() }?.let {
                    " · route: ${it.omittedSystemIds.size} systems / ${it.omittedLegCount} legs unavailable; use Real X-Z"
                }.orEmpty()
                val jumpOmitted = jumpState.overlays.sumOf {
                    ProjectedJumpRangeOverlayBuilder.build(it, scene).omittedSystemIds.size
                }
                val capitalOmitted = capitalState.activeRoute?.let {
                    ProjectedCapitalRouteOverlayBuilder.build(it, scene).omittedLegCount
                } ?: 0
                Text(
                    text = "${scene.projectionId.displayName}: ${scene.nodes.size} systems · ${scene.edges.size} stargate connections" +
                        (if (scene.omittedSystemIds.isNotEmpty()) " · ${scene.omittedSystemIds.size} unavailable" else "") +
                        routeWarning +
                        (if (jumpOmitted > 0) " · jump overlay: $jumpOmitted unavailable" else "") +
                        (if (capitalOmitted > 0) " · capital route: $capitalOmitted legs unavailable" else "") +
                        state.focusNotice?.let { " · $it" }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFAAB9C7),
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF121D28)).padding(8.dp),
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
    markerEditor?.let { request ->
        MarkerEditorDialog(
            request = request,
            isBusy = request.systemId?.let { it in markerState.busySystemIds } == true,
            error = markerState.operationError,
            children = request.systemId?.let { markerState.childrenByParentSystemId[it] }.orEmpty(),
            onAddChild = { type -> request.systemId?.let { markerViewModel.addChild(it, type) } },
            onRemoveChild = { childId -> request.systemId?.let { markerViewModel.removeChild(it, childId) } },
            onSave = { systemId, draft ->
                when (request.mode) {
                    MarkerEditorMode.EDIT_TEMPORARY -> if (markerViewModel.updateTemporary(systemId, draft)) {
                        markerEditor = null
                        expectedMarkerDraft = null
                    }
                    MarkerEditorMode.CREATE_SAVED -> if (markerViewModel.createSaved(systemId, draft)) {
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

@Composable
private fun MapToolbar(
    state: MapUiState,
    routeState: RoutePlannerUiState,
    viewModel: MapViewModel,
    routeViewModel: RoutePlannerViewModel,
    onOpenStaticDataManager: () -> Unit,
) {
    Surface(color = Color(0xFF121D28), contentColor = Color(0xFFD7E6F2)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= MAP_TOOLBAR_SINGLE_ROW_MIN_WIDTH) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    ProjectionControls(state, viewModel)
                    Spacer(Modifier.weight(1f))
                    GlobalSystemSearch(routeState, routeViewModel, viewModel)
                    TextButton(onClick = onOpenStaticDataManager) { Text("Static Data") }
                    TextButton(onClick = viewModel::fitMap, enabled = state.scene != null) { Text("Fit Map") }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ProjectionControls(state, viewModel)
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        GlobalSystemSearch(routeState, routeViewModel, viewModel)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = onOpenStaticDataManager) { Text("Static Data") }
                        TextButton(onClick = viewModel::fitMap, enabled = state.scene != null) { Text("Fit Map") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectionControls(
    state: MapUiState,
    viewModel: MapViewModel,
) {
    Text("Projection", style = MaterialTheme.typography.labelLarge)
    MapProjectionId.entries.forEach { projection ->
        if (projection == state.projectionId) {
            Button(onClick = {}, enabled = false) { Text(projection.displayName) }
        } else {
            TextButton(onClick = { viewModel.switchProjection(projection) }) { Text(projection.displayName) }
        }
    }
}

@Composable
private fun GlobalSystemSearch(
    state: RoutePlannerUiState,
    routeViewModel: RoutePlannerViewModel,
    mapViewModel: MapViewModel,
) {
    SystemSearchField(
        value = state.systemQuery,
        label = "Search system",
        results = state.systemResults,
        onValueChange = routeViewModel::updateSystemQuery,
        onSelect = { system ->
            confirmGlobalSystemSearch(
                system = system,
                updateSearchSelection = routeViewModel::selectSystemSearch,
                focusSystem = mapViewModel::selectAndFocusSystem,
            )
        },
        modifier = Modifier.widthIn(min = GLOBAL_SEARCH_MIN_WIDTH, max = GLOBAL_SEARCH_MAX_WIDTH).fillMaxWidth(),
        suggestionsPresentation = GLOBAL_SEARCH_SUGGESTIONS_PRESENTATION,
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

internal val GLOBAL_SEARCH_MIN_WIDTH = 260.dp
internal val GLOBAL_SEARCH_MAX_WIDTH = 360.dp
internal val MAP_TOOLBAR_SINGLE_ROW_MIN_WIDTH = 820.dp
internal val GLOBAL_SEARCH_SUGGESTIONS_PRESENTATION = SearchSuggestionsPresentation.DROPDOWN

@Composable
private fun CenterMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = Color(0xFFC5D4E0), modifier = Modifier.padding(24.dp))
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
