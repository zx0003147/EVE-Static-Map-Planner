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

@Composable
fun RouteToolsPanel(
    state: RoutePlannerUiState,
    viewModel: RoutePlannerViewModel,
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
            SearchField(
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
            SearchField(
                value = state.fromQuery,
                label = "From",
                results = state.fromResults,
                onValueChange = viewModel::updateFromQuery,
                onSelect = viewModel::selectFrom,
            )
            SearchField(
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
            Text("Capital Route", color = Color(0xFF738394))
            Text("Jump Overlays", color = Color(0xFF738394))
            Text("Phase 4 · Normal Route + Manual Ansiblex", style = MaterialTheme.typography.labelSmall, color = Color(0xFF728495))
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    label: String,
    results: List<SolarSystem>,
    onValueChange: (String) -> Unit,
    onSelect: (SolarSystem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        results.take(6).forEach { system ->
            TextButton(onClick = { onSelect(system) }, modifier = Modifier.fillMaxWidth()) {
                Text("${system.name}  ·  ${system.id}", style = MaterialTheme.typography.bodySmall)
            }
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
