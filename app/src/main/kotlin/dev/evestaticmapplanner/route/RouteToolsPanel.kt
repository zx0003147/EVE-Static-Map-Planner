package dev.evestaticmapplanner.route

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.capital.CapitalRouteUiState
import dev.evestaticmapplanner.capital.CapitalRouteViewModel
import dev.evestaticmapplanner.jump.JumpOverlayUiState
import dev.evestaticmapplanner.jump.JumpOverlayViewModel
import dev.evestaticmapplanner.search.SystemSearchField
import dev.evestaticmapplanner.feature.api.RouteSnapshot
import dev.evestaticmapplanner.featurepack.RouteActionKey
import dev.evestaticmapplanner.featurepack.RouteActionUiState
import dev.evestaticmapplanner.map.confirmGlobalSystemSearch

internal enum class ToolSidebarSection {
    JUMP_RANGE,
    NORMAL_ROUTE,
    CAPITAL_ROUTE,
}

internal val TOOL_SIDEBAR_SECTION_ORDER = listOf(
    ToolSidebarSection.JUMP_RANGE,
    ToolSidebarSection.NORMAL_ROUTE,
    ToolSidebarSection.CAPITAL_ROUTE,
)

internal data class ToolSidebarExpansionState(
    val expandedSections: Set<ToolSidebarSection> = emptySet(),
) {
    fun isExpanded(section: ToolSidebarSection): Boolean = section in expandedSections

    fun toggle(section: ToolSidebarSection): ToolSidebarExpansionState = copy(
        expandedSections = if (isExpanded(section)) expandedSections - section else expandedSections + section,
    )
}

@Composable
internal fun RouteToolsPanel(
    state: RoutePlannerUiState,
    viewModel: RoutePlannerViewModel,
    capitalState: CapitalRouteUiState,
    capitalViewModel: CapitalRouteViewModel,
    jumpState: JumpOverlayUiState,
    jumpViewModel: JumpOverlayViewModel,
    routeActions: List<RouteActionUiState>,
    normalRouteSnapshot: RouteSnapshot?,
    capitalRouteSnapshot: RouteSnapshot?,
    onInvokeRouteAction: (RouteActionKey, RouteSnapshot) -> Unit,
    onOpenAnsiblexManager: () -> Unit,
    onFocusSystem: (Int) -> Unit,
) {
    var expansionState by remember { mutableStateOf(ToolSidebarExpansionState()) }
    Surface(
        color = Color(0xFF15212D),
        contentColor = Color(0xFFD7E6F2),
        modifier = Modifier.width(TOOL_SIDEBAR_WIDTH).fillMaxHeight(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SystemSearchField(
                value = state.systemQuery,
                label = SIDEBAR_SEARCH_LABEL,
                results = state.systemResults,
                onValueChange = viewModel::updateSystemQuery,
                onSelect = { system ->
                    confirmGlobalSystemSearch(system, viewModel::selectSystemSearch, onFocusSystem)
                },
                modifier = Modifier.fillMaxWidth(),
                compact = true,
            )
            HorizontalDivider(color = Color(0xFF314252))
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TOOL_SIDEBAR_SECTION_ORDER.forEachIndexed { index, section ->
                if (index > 0) HorizontalDivider(color = Color(0xFF314252))
                val expanded = expansionState.isExpanded(section)
                when (section) {
                    ToolSidebarSection.JUMP_RANGE -> CollapsibleToolSection(
                        title = "Jump Range Overlays",
                        summary = jumpState.overlays.takeIf(List<*>::isNotEmpty)?.let {
                            "${jumpState.overlays.count { overlay -> overlay.enabled }}/${jumpState.overlays.size}"
                        },
                        expanded = expanded,
                        onToggle = { expansionState = expansionState.toggle(section) },
                    ) {
                        JumpRangeSectionContent(jumpState, jumpViewModel)
                    }
                    ToolSidebarSection.NORMAL_ROUTE -> CollapsibleToolSection(
                        title = "Normal Route",
                        summary = state.activeRoute?.let { "${it.totalJumps} jumps" },
                        expanded = expanded,
                        onToggle = { expansionState = expansionState.toggle(section) },
                    ) {
                        NormalRouteSectionContent(
                            state,
                            viewModel,
                            routeActions,
                            normalRouteSnapshot,
                            onInvokeRouteAction,
                            onOpenAnsiblexManager,
                        )
                    }
                    ToolSidebarSection.CAPITAL_ROUTE -> CollapsibleToolSection(
                        title = "Capital Route",
                        summary = capitalState.activeRoute?.let { "${it.totalJumps} jumps" },
                        expanded = expanded,
                        onToggle = { expansionState = expansionState.toggle(section) },
                    ) {
                        CapitalRouteSectionContent(
                            capitalState,
                            capitalViewModel,
                            routeActions,
                            capitalRouteSnapshot,
                            onInvokeRouteAction,
                        )
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleToolSection(
    title: String,
    summary: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
        ) {
            Text(if (expanded) "▼" else "▶", style = MaterialTheme.typography.labelMedium)
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            summary?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAB9C7))
            }
        }
        if (expanded) content()
    }
}

@Composable
private fun JumpRangeSectionContent(
    state: JumpOverlayUiState,
    viewModel: JumpOverlayViewModel,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SystemSearchField(
            value = state.originQuery,
            label = "Overlay origin",
            results = state.originResults,
            onValueChange = viewModel::updateOriginQuery,
            onSelect = viewModel::selectOrigin,
        )
        OutlinedTextField(
            value = state.manualRangeText,
            onValueChange = viewModel::updateManualRange,
            label = { Text("Effective maximum LY") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = viewModel::addSelectedOrigin,
                enabled = state.selectedOrigin != null && !state.isLoading && !state.isCalculating,
            ) { Text("Add") }
            TextButton(onClick = viewModel::clear, enabled = state.overlays.isNotEmpty()) { Text("Clear") }
        }
        state.overlays.forEach { overlay ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = overlay.enabled,
                        onCheckedChange = { viewModel.setEnabled(overlay.id, it) },
                    )
                    Text(overlay.label ?: overlay.id, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = overlay.id in state.intersectionOverlayIds,
                        onCheckedChange = { viewModel.toggleIntersectionSelection(overlay.id, it) },
                        enabled = overlay.enabled,
                    )
                    Text("Intersect", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { viewModel.updateWithCurrentRange(overlay.id) }) { Text("Update") }
                    TextButton(onClick = { viewModel.remove(overlay.id) }) { Text("Remove") }
                }
            }
        }
        if (state.intersectionOverlayIds.isNotEmpty()) {
            Text(
                "Intersection (${state.intersectionOverlayIds.size} overlays): ${state.intersectionSystemIds.size} systems",
                color = Color(0xFFFFD166),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.error?.let { Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun NormalRouteSectionContent(
    state: RoutePlannerUiState,
    viewModel: RoutePlannerViewModel,
    routeActions: List<RouteActionUiState>,
    routeSnapshot: RouteSnapshot?,
    onInvokeRouteAction: (RouteActionKey, RouteSnapshot) -> Unit,
    onOpenAnsiblexManager: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SystemSearchField(
            value = state.fromQuery,
            label = "From",
            results = state.fromResults,
            onValueChange = viewModel::updateFromQuery,
            onSelect = viewModel::selectFrom,
        )
        SystemSearchField(
            value = state.toQuery,
            label = "To",
            results = state.toResults,
            onValueChange = viewModel::updateToQuery,
            onSelect = viewModel::selectTo,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.useAnsiblex,
                onCheckedChange = viewModel::setUseAnsiblex,
                enabled = state.isAnsiblexAvailable,
            )
            Text("Use Ansiblex")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.showAnsiblexLayer,
                onCheckedChange = viewModel::setShowAnsiblexLayer,
                enabled = state.isAnsiblexAvailable,
            )
            Text("Show Ansiblex layer")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::calculateRoute,
                enabled = state.selectedFrom != null && state.selectedTo != null && !state.isLoading,
            ) { Text("Calculate") }
            TextButton(onClick = viewModel::clearRoute, enabled = state.routeOutcome != null) { Text("Clear") }
        }
        RouteSummary(state)
        RouteActionButtons(routeActions, routeSnapshot, onInvokeRouteAction)
        TextButton(onClick = onOpenAnsiblexManager, enabled = state.isAnsiblexAvailable) {
            Text("Ansiblex Manager (${state.enabledAnsiblexCount}/${state.ansiblexConnections.size})")
        }
        state.userDatabaseError?.let {
            Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
            Text(
                "Static map and Stargate-only routing remain available.",
                color = Color(0xFFAAB9C7),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CapitalRouteSectionContent(
    state: CapitalRouteUiState,
    viewModel: CapitalRouteViewModel,
    routeActions: List<RouteActionUiState>,
    routeSnapshot: RouteSnapshot?,
    onInvokeRouteAction: (RouteActionKey, RouteSnapshot) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SystemSearchField(
            value = state.fromQuery,
            label = "Capital From",
            results = state.fromResults,
            onValueChange = viewModel::updateFromQuery,
            onSelect = viewModel::selectFrom,
        )
        SystemSearchField(
            value = state.toQuery,
            label = "Capital To",
            results = state.toResults,
            onValueChange = viewModel::updateToQuery,
            onSelect = viewModel::selectTo,
        )
        OutlinedTextField(
            value = state.manualRangeText,
            onValueChange = viewModel::updateManualRange,
            label = { Text("Effective maximum LY") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::calculate,
                enabled = state.selectedFrom != null && state.selectedTo != null &&
                    !state.isLoading && !state.isCalculating,
            ) { Text(if (state.isCalculating) "Calculating…" else "Calculate") }
            TextButton(onClick = viewModel::clear, enabled = state.outcome != null) { Text("Clear") }
        }
        CapitalRouteSummary(state)
        RouteActionButtons(routeActions, routeSnapshot, onInvokeRouteAction)
        state.error?.let { Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall) }
        Text(
            "Validates real XYZ geometry, manual max range, and implemented static eligibility only.",
            color = Color(0xFFB8CAD8),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Does not verify live cyno/type, jammer, ACL, fuel, capacitor, fatigue, scram, or server state.",
            color = Color(0xFFFFB86C),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Phase 5 · Jump Range Overlays + Capital Route V1",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF728495),
        )
    }
}

internal val TOOL_SIDEBAR_WIDTH = 270.dp
internal const val SIDEBAR_SEARCH_LABEL = "Search system..."

@Composable
private fun RouteSummary(state: RoutePlannerUiState) {
    when (val outcome = state.routeOutcome) {
        null -> Unit
        is RouteCalculationOutcome.Found -> {
            val route = outcome.route
            Text(
                "${route.totalJumps} jumps · ${route.stargateJumps} Stargate · ${route.ansiblexJumps} Ansiblex",
                color = Color(0xFFBFE7F5),
            )
            Text(state.routeSystemNames.joinToString(" → "), style = MaterialTheme.typography.bodySmall)
        }
        is RouteCalculationOutcome.SameSystem -> Text("Start and destination are the same system · 0 jumps")
        is RouteCalculationOutcome.Unreachable -> Text("No route is reachable with the selected connection types.", color = Color(0xFFFFB86C))
        is RouteCalculationOutcome.InvalidEndpoint -> Text("One or both route endpoints are invalid.", color = Color(0xFFFF8A80))
    }
}

@Composable
private fun CapitalRouteSummary(state: CapitalRouteUiState) {
    when (val outcome = state.outcome) {
        null -> Unit
        is CapitalRouteOutcome.Found -> {
            Text(
                "${outcome.route.totalJumps} capital jumps · " +
                    String.format(java.util.Locale.ROOT, "%.3f LY total", outcome.route.totalDistanceLy),
                color = Color(0xFFE3C5FF),
            )
            Text(state.routeSystemNames.joinToString(" → "), style = MaterialTheme.typography.bodySmall)
            outcome.route.legs.forEach { leg ->
                Text(
                    "${leg.fromSystemId} → ${leg.toSystemId} · " +
                        String.format(java.util.Locale.ROOT, "%.3f LY", leg.distanceLy),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        is CapitalRouteOutcome.SameSystem -> Text("Start and destination are the same · 0 jumps")
        is CapitalRouteOutcome.Unreachable -> Text("No statically eligible route within the manual range.", color = Color(0xFFFFB86C))
        is CapitalRouteOutcome.InvalidEndpoint -> Text("One or both capital endpoints are invalid.", color = Color(0xFFFF8A80))
        is CapitalRouteOutcome.IneligibleEndpoint -> Text(
            "${outcome.endpoint}: ${outcome.verdict}",
            color = Color(0xFFFFB86C),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
