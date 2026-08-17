package dev.evestaticmapplanner.capital

import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.CapitalRouteResult

data class CapitalRouteUiState(
    val isLoading: Boolean = true,
    val isCalculating: Boolean = false,
    val error: String? = null,
    val fromQuery: String = "",
    val toQuery: String = "",
    val fromResults: List<SolarSystem> = emptyList(),
    val toResults: List<SolarSystem> = emptyList(),
    val selectedFrom: SolarSystem? = null,
    val selectedTo: SolarSystem? = null,
    val manualRangeText: String = "5",
    val outcome: CapitalRouteOutcome? = null,
    val activeRoute: CapitalRouteResult? = null,
    val routeSystemNames: List<String> = emptyList(),
)
