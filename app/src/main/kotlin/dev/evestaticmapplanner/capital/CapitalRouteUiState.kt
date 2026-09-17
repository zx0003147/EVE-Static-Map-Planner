package dev.evestaticmapplanner.capital

import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.localization.UiMessage

data class CapitalRouteUiState(
    val isLoading: Boolean = true,
    val isCalculating: Boolean = false,
    val error: UiMessage? = null,
    val fromQuery: String = "",
    val toQuery: String = "",
    val fromResults: List<SolarSystem> = emptyList(),
    val toResults: List<SolarSystem> = emptyList(),
    val selectedFrom: SolarSystem? = null,
    val selectedTo: SolarSystem? = null,
    val waypoints: List<SolarSystem> = emptyList(),
    val manualRangeText: String = "5",
    val outcome: CapitalRouteOutcome? = null,
    val activeRoute: CapitalRouteResult? = null,
    val routeSystemNames: List<String> = emptyList(),
    val calculatedWaypointSystemIds: List<Int> = emptyList(),
    val calculatedExplicitDestinationSystemId: Int? = null,
    val isRouteStale: Boolean = false,
    val navigationMessage: UiMessage? = null,
)
