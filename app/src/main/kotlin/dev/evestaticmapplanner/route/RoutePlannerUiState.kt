package dev.evestaticmapplanner.route

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexAccessPolicy
import dev.evestaticmapplanner.core.identity.CurrentIdentityContext
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.NavigationIntent
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.core.wormhole.WormholeConnection
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportMode
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportPreview
import dev.evestaticmapplanner.localization.UiMessage

data class RoutePlannerUiState(
    val isLoading: Boolean = true,
    val error: UiMessage? = null,
    val userDatabaseError: UiMessage? = null,
    val systemQuery: String = "",
    val systemResults: List<SolarSystem> = emptyList(),
    val fromQuery: String = "",
    val toQuery: String = "",
    val fromResults: List<SolarSystem> = emptyList(),
    val toResults: List<SolarSystem> = emptyList(),
    val selectedFrom: SolarSystem? = null,
    val selectedTo: SolarSystem? = null,
    val waypoints: List<SolarSystem> = emptyList(),
    val useAnsiblex: Boolean = false,
    val useWormholes: Boolean = false,
    val showAnsiblexLayer: Boolean = true,
    val routeOutcome: RouteCalculationOutcome? = null,
    val activeRoute: RouteResult? = null,
    val routeSystemNames: List<String> = emptyList(),
    val calculatedWaypointSystemIds: List<Int> = emptyList(),
    val calculatedExplicitDestinationSystemId: Int? = null,
    val isRouteStale: Boolean = false,
    val navigationMessage: UiMessage? = null,
    val ansiblexConnections: List<AnsiblexConnection> = emptyList(),
    val currentIdentityContext: CurrentIdentityContext? = null,
    val wormholeConnections: List<WormholeConnection> = emptyList(),
    val importMode: AnsiblexImportMode = AnsiblexImportMode.MERGE,
    val importPreview: AnsiblexImportPreview? = null,
    val importError: UiMessage? = null,
    val managerMessage: UiMessage? = null,
    val isImportBusy: Boolean = false,
) {
    val isAnsiblexAvailable: Boolean get() = userDatabaseError == null
    val enabledAnsiblexCount: Int get() = ansiblexConnections.count(AnsiblexConnection::enabled)
    val usableAnsiblexConnections: List<AnsiblexConnection>
        get() = AnsiblexAccessPolicy.usableConnections(ansiblexConnections, currentIdentityContext)
    val usableAnsiblexCount: Int get() = usableAnsiblexConnections.size
    val navigationIntent: NavigationIntent?
        get() = selectedFrom?.let { start ->
            NavigationIntent(start.id, waypoints.map(SolarSystem::id), selectedTo?.id)
        }
}
