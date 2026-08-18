package dev.evestaticmapplanner.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import dev.evestaticmapplanner.capital.CapitalRouteUiState
import dev.evestaticmapplanner.capital.CapitalRouteViewModel
import dev.evestaticmapplanner.jump.JumpOverlayUiState
import dev.evestaticmapplanner.jump.JumpOverlayViewModel
import dev.evestaticmapplanner.core.map.ProjectedCapitalRouteOverlayBuilder
import dev.evestaticmapplanner.core.map.ProjectedJumpRangeOverlayBuilder
import java.nio.file.Path
import java.util.Locale

@Composable
fun StaticMapScreen(
    databasePath: Path,
    userDatabasePath: Path,
    state: MapUiState,
    routeState: RoutePlannerUiState,
    jumpState: JumpOverlayUiState,
    capitalState: CapitalRouteUiState,
    viewModel: MapViewModel,
    routeViewModel: RoutePlannerViewModel,
    jumpViewModel: JumpOverlayViewModel,
    capitalViewModel: CapitalRouteViewModel,
    onOpenStaticDataManager: () -> Unit,
) {
    var showAnsiblexManager by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize().background(Color(0xFF101923))) {
        RouteToolsPanel(
            state = routeState,
            viewModel = routeViewModel,
            jumpState = jumpState,
            jumpViewModel = jumpViewModel,
            capitalState = capitalState,
            capitalViewModel = capitalViewModel,
            onFocusSystem = { viewModel.selectSystemById(it.id) },
            onOpenAnsiblexManager = { showAnsiblexManager = true },
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ProjectionToolbar(state, viewModel, onOpenStaticDataManager)
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
                        onCanvasSizeChanged = viewModel::onCanvasSizeChanged,
                        onZoom = viewModel::zoomAt,
                        onPan = viewModel::panBy,
                        onHover = viewModel::hoverAt,
                        onHoverExit = viewModel::clearHover,
                        onSelect = viewModel::selectAt,
                        onContextMenu = viewModel::openContextMenuAt,
                        onContextSystemInfo = viewModel::selectContextMenuSystem,
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
                        (if (capitalOmitted > 0) " · capital route: $capitalOmitted legs unavailable" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFAAB9C7),
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF121D28)).padding(8.dp),
                )
            }
        }
        SystemInfoPanel(state, routeState, jumpState)
    }
    if (showAnsiblexManager) {
        AnsiblexManagerDialog(
            userDatabasePath = userDatabasePath,
            state = routeState,
            viewModel = routeViewModel,
            onDismiss = { showAnsiblexManager = false },
        )
    }
}

@Composable
private fun ProjectionToolbar(
    state: MapUiState,
    viewModel: MapViewModel,
    onOpenStaticDataManager: () -> Unit,
) {
    Surface(color = Color(0xFF121D28), contentColor = Color(0xFFD7E6F2)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text("Projection", style = MaterialTheme.typography.labelLarge)
            MapProjectionId.entries.forEach { projection ->
                if (projection == state.projectionId) {
                    Button(onClick = {}, enabled = false) { Text(projection.displayName) }
                } else {
                    TextButton(onClick = { viewModel.switchProjection(projection) }) { Text(projection.displayName) }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenStaticDataManager) { Text("Static Data") }
            TextButton(onClick = viewModel::fitMap, enabled = state.scene != null) { Text("Fit Map") }
        }
    }
}

@Composable
private fun SystemInfoPanel(
    state: MapUiState,
    routeState: RoutePlannerUiState,
    jumpState: JumpOverlayUiState,
) {
    Surface(
        color = Color(0xFF15212D),
        contentColor = Color(0xFFD7E6F2),
        modifier = Modifier.width(240.dp).fillMaxHeight(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("System Info", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(color = Color(0xFF314252))
            state.hoveredSystemId?.let { hoveredId ->
                val hovered = state.scene?.nodesById?.get(hoveredId)?.system
                val coveredBy = jumpState.coveringOverlays(hoveredId)
                Text(
                    "Hover · ${hovered?.name ?: hoveredId}",
                    color = Color(0xFFF3D36A),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    if (coveredBy.isEmpty()) "No enabled jump overlay hits" else
                        "${coveredBy.size} overlay hit(s): ${coveredBy.joinToString { it.label ?: it.id }}",
                    color = Color(0xFFFFD166),
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider(color = Color(0xFF314252))
            }
            val details = state.selectedSystemDetails
            when {
                state.selectedSystemId == null -> Text("Select a solar system on the map.", color = Color(0xFF91A2B2))
                details == null -> Text("Loading system details…", color = Color(0xFF91A2B2))
                else -> {
                    InfoRow("Name", details.system.name)
                    InfoRow("System ID", details.system.id.toString())
                    InfoRow("Region", details.region.name)
                    InfoRow("Constellation", details.constellation.name)
                    InfoRow("Security", String.format(Locale.ROOT, "%.6f", details.system.securityStatus))
                    InfoRow("Stargate Count", details.stargateCount.toString())
                    val ansiblex = routeState.ansiblexConnections.filter {
                        it.firstSystemId == details.system.id || it.secondSystemId == details.system.id
                    }
                    InfoRow("Ansiblex Connections", ansiblex.size.toString())
                    ansiblex.take(5).forEach {
                        val other = if (it.firstSystemId == details.system.id) it.secondSystemId else it.firstSystemId
                        Text("→ $other · ${it.direction.name}", style = MaterialTheme.typography.bodySmall)
                    }
                    val coveredBy = jumpState.coveringOverlays(details.system.id)
                    InfoRow("Jump Overlay Coverage", coveredBy.size.toString())
                    coveredBy.forEach { overlay ->
                        Text(overlay.label ?: overlay.id, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFD166))
                    }
                    if (details.system.id in jumpState.intersectionSystemIds) {
                        Text("In selected overlay intersection", color = Color(0xFFFFD166), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF8395A6))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

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
