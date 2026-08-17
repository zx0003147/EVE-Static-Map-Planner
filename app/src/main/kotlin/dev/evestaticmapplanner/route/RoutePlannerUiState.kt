package dev.evestaticmapplanner.route

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportMode
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportPreview

data class RoutePlannerUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val userDatabaseError: String? = null,
    val systemQuery: String = "",
    val systemResults: List<SolarSystem> = emptyList(),
    val fromQuery: String = "",
    val toQuery: String = "",
    val fromResults: List<SolarSystem> = emptyList(),
    val toResults: List<SolarSystem> = emptyList(),
    val selectedFrom: SolarSystem? = null,
    val selectedTo: SolarSystem? = null,
    val useAnsiblex: Boolean = false,
    val showAnsiblexLayer: Boolean = true,
    val routeOutcome: RouteCalculationOutcome? = null,
    val activeRoute: RouteResult? = null,
    val routeSystemNames: List<String> = emptyList(),
    val ansiblexConnections: List<AnsiblexConnection> = emptyList(),
    val importMode: AnsiblexImportMode = AnsiblexImportMode.MERGE,
    val importPreview: AnsiblexImportPreview? = null,
    val importError: String? = null,
    val managerMessage: String? = null,
    val isImportBusy: Boolean = false,
) {
    val isAnsiblexAvailable: Boolean get() = userDatabaseError == null
    val enabledAnsiblexCount: Int get() = ansiblexConnections.count(AnsiblexConnection::enabled)
}
