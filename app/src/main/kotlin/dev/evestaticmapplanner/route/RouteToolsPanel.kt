package dev.evestaticmapplanner.route

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.capital.CapitalRouteUiState
import dev.evestaticmapplanner.capital.CapitalRouteViewModel
import dev.evestaticmapplanner.jump.JumpOverlayUiState
import dev.evestaticmapplanner.jump.JumpOverlayViewModel
import dev.evestaticmapplanner.search.SystemSearchField

@Composable
fun RouteToolsPanel(
    state: RoutePlannerUiState,
    viewModel: RoutePlannerViewModel,
    capitalState: CapitalRouteUiState,
    capitalViewModel: CapitalRouteViewModel,
    jumpState: JumpOverlayUiState,
    jumpViewModel: JumpOverlayViewModel,
    onFocusSystem: (SolarSystem) -> Unit,
    onOpenAnsiblexManager: () -> Unit,
) {
    Surface(
        color = Color(0xFF15212D),
        contentColor = Color(0xFFD7E6F2),
        modifier = Modifier.width(270.dp).fillMaxHeight(),
    ) {
        Column(
            Modifier.padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("System Search", style = MaterialTheme.typography.titleMedium)
            SystemSearchField(
                value = state.systemQuery,
                label = "Name or exact ID",
                results = state.systemResults,
                onValueChange = viewModel::updateSystemQuery,
                onSelect = {
                    viewModel.selectSystemSearch(it)
                    onFocusSystem(it)
                },
            )
            HorizontalDivider(color = Color(0xFF314252))
            Text("Normal Route", style = MaterialTheme.typography.titleMedium)
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
            HorizontalDivider(color = Color(0xFF314252))
            TextButton(onClick = onOpenAnsiblexManager, enabled = state.isAnsiblexAvailable) {
                Text("Ansiblex Manager (${state.enabledAnsiblexCount}/${state.ansiblexConnections.size})")
            }
            state.userDatabaseError?.let {
                Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
                Text("Static map and Stargate-only routing remain available.", color = Color(0xFFAAB9C7), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = Color(0xFF314252))
            Text("Jump Range Overlays", style = MaterialTheme.typography.titleMedium)
            SystemSearchField(
                value = jumpState.originQuery,
                label = "Overlay origin",
                results = jumpState.originResults,
                onValueChange = jumpViewModel::updateOriginQuery,
                onSelect = jumpViewModel::selectOrigin,
            )
            OutlinedTextField(
                value = jumpState.manualRangeText,
                onValueChange = jumpViewModel::updateManualRange,
                label = { Text("Effective maximum LY") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = jumpViewModel::addSelectedOrigin,
                    enabled = jumpState.selectedOrigin != null && !jumpState.isLoading && !jumpState.isCalculating,
                ) { Text("Add") }
                TextButton(onClick = jumpViewModel::clear, enabled = jumpState.overlays.isNotEmpty()) { Text("Clear") }
            }
            jumpState.overlays.forEach { overlay ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = overlay.enabled,
                            onCheckedChange = { jumpViewModel.setEnabled(overlay.id, it) },
                        )
                        Text(overlay.label ?: overlay.id, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = overlay.id in jumpState.intersectionOverlayIds,
                            onCheckedChange = { jumpViewModel.toggleIntersectionSelection(overlay.id, it) },
                            enabled = overlay.enabled,
                        )
                        Text("Intersect", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { jumpViewModel.updateWithCurrentRange(overlay.id) }) { Text("Update") }
                        TextButton(onClick = { jumpViewModel.remove(overlay.id) }) { Text("Remove") }
                    }
                }
            }
            if (jumpState.intersectionOverlayIds.isNotEmpty()) {
                Text(
                    "Intersection (${jumpState.intersectionOverlayIds.size} overlays): ${jumpState.intersectionSystemIds.size} systems",
                    color = Color(0xFFFFD166),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            jumpState.error?.let { Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider(color = Color(0xFF314252))
            Text("Capital Route", style = MaterialTheme.typography.titleMedium)
            SystemSearchField(
                value = capitalState.fromQuery,
                label = "Capital From",
                results = capitalState.fromResults,
                onValueChange = capitalViewModel::updateFromQuery,
                onSelect = capitalViewModel::selectFrom,
            )
            SystemSearchField(
                value = capitalState.toQuery,
                label = "Capital To",
                results = capitalState.toResults,
                onValueChange = capitalViewModel::updateToQuery,
                onSelect = capitalViewModel::selectTo,
            )
            OutlinedTextField(
                value = capitalState.manualRangeText,
                onValueChange = capitalViewModel::updateManualRange,
                label = { Text("Effective maximum LY") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = capitalViewModel::calculate,
                    enabled = capitalState.selectedFrom != null && capitalState.selectedTo != null &&
                        !capitalState.isLoading && !capitalState.isCalculating,
                ) { Text(if (capitalState.isCalculating) "Calculating…" else "Calculate") }
                TextButton(onClick = capitalViewModel::clear, enabled = capitalState.outcome != null) { Text("Clear") }
            }
            CapitalRouteSummary(capitalState)
            capitalState.error?.let { Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall) }
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
            Text("Phase 5 · Jump Range Overlays + Capital Route V1", style = MaterialTheme.typography.labelSmall, color = Color(0xFF728495))
        }
    }
}

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
