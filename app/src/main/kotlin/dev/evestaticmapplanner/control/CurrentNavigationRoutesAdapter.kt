package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.capital.CapitalRouteUiState
import dev.evestaticmapplanner.core.jump.JumpEligibilityPolicy
import dev.evestaticmapplanner.core.jump.SpaceClassification
import dev.evestaticmapplanner.core.jump.SpaceClassificationClassifier
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.route.RoutePlannerUiState

class CurrentNavigationRoutesAdapter(
    private val normalStateProvider: () -> RoutePlannerUiState,
    private val capitalStateProvider: () -> CapitalRouteUiState,
    private val systemProvider: (Int) -> SolarSystem?,
) : CurrentNavigationRoutesPort {
    private val spaceClassifier = SpaceClassificationClassifier()

    override fun currentRoutes(): CurrentNavigationRoutesDto {
        val normalState = normalStateProvider()
        val capitalState = capitalStateProvider()
        return CurrentNavigationRoutesDto(
            normal = normalState.toCurrentRoute(),
            capital = capitalState.toCurrentRoute(),
        )
    }

    private fun RoutePlannerUiState.toCurrentRoute(): CurrentNavigationRouteDto {
        val route = activeRoute ?: return emptyRoute(
            routeType = CurrentNavigationRouteType.NORMAL,
            isLoading = isLoading,
            hasError = error != null,
            calculationFailed = routeOutcome != null || navigationMessage != null,
        )
        val status = if (isRouteStale) CurrentNavigationRouteStatus.STALE else CurrentNavigationRouteStatus.CALCULATED
        return CurrentNavigationRouteDto(
            routeType = CurrentNavigationRouteType.NORMAL,
            status = status,
            valid = status == CurrentNavigationRouteStatus.CALCULATED,
            stale = isRouteStale,
            unavailableReason = if (isRouteStale) "ROUTE_INPUTS_CHANGED" else null,
            startSystemId = route.startSystemId,
            destinationSystemId = route.destinationSystemId,
            systems = route.systems.mapIndexed { index, systemId -> system(systemId, index, routeSystemNames) },
            connections = route.edges.mapIndexed { index, edge ->
                CurrentNavigationConnectionDto(
                    routeIndex = index,
                    fromSystemId = edge.fromSystemId,
                    toSystemId = edge.toSystemId,
                    type = when (edge.type) {
                        RouteEdgeType.STARGATE -> CurrentNavigationConnectionType.STARGATE
                        RouteEdgeType.ANSIBLEX -> CurrentNavigationConnectionType.ANSIBLEX
                        RouteEdgeType.WORMHOLE -> CurrentNavigationConnectionType.WORMHOLE
                    },
                )
            },
            waypointSystemIds = calculatedWaypointSystemIds,
            explicitDestinationSystemId = calculatedExplicitDestinationSystemId,
        )
    }

    private fun CapitalRouteUiState.toCurrentRoute(): CurrentNavigationRouteDto {
        val route = activeRoute ?: return emptyRoute(
            routeType = CurrentNavigationRouteType.CAPITAL,
            isLoading = isLoading || isCalculating,
            hasError = error != null,
            calculationFailed = outcome != null || navigationMessage != null,
            loadingReason = if (isCalculating) "CALCULATION_IN_PROGRESS" else "ROUTE_DATA_LOADING",
        )
        val status = if (isRouteStale) CurrentNavigationRouteStatus.STALE else CurrentNavigationRouteStatus.CALCULATED
        return CurrentNavigationRouteDto(
            routeType = CurrentNavigationRouteType.CAPITAL,
            status = status,
            valid = status == CurrentNavigationRouteStatus.CALCULATED,
            stale = isRouteStale,
            unavailableReason = if (isRouteStale) "ROUTE_INPUTS_CHANGED" else null,
            startSystemId = route.startSystemId,
            destinationSystemId = route.destinationSystemId,
            systems = route.systems.mapIndexed { index, systemId -> system(systemId, index, routeSystemNames) },
            connections = route.legs.mapIndexed { index, leg ->
                CurrentNavigationConnectionDto(
                    routeIndex = index,
                    fromSystemId = leg.fromSystemId,
                    toSystemId = leg.toSystemId,
                    type = CurrentNavigationConnectionType.CAPITAL_JUMP,
                    distanceLy = leg.distanceLy,
                )
            },
            waypointSystemIds = calculatedWaypointSystemIds,
            explicitDestinationSystemId = calculatedExplicitDestinationSystemId,
            effectiveRangeLy = route.profile.maxRangeLy,
        )
    }

    private fun emptyRoute(
        routeType: CurrentNavigationRouteType,
        isLoading: Boolean,
        hasError: Boolean,
        calculationFailed: Boolean,
        loadingReason: String = "ROUTE_DATA_LOADING",
    ): CurrentNavigationRouteDto {
        val (status, reason) = when {
            isLoading -> CurrentNavigationRouteStatus.UNAVAILABLE to loadingReason
            hasError -> CurrentNavigationRouteStatus.UNAVAILABLE to "ROUTE_DATA_ERROR"
            calculationFailed -> CurrentNavigationRouteStatus.UNAVAILABLE to "CALCULATION_FAILED"
            else -> CurrentNavigationRouteStatus.NOT_PLANNED to "NO_CALCULATED_ROUTE"
        }
        return CurrentNavigationRouteDto(
            routeType = routeType,
            status = status,
            valid = false,
            stale = false,
            unavailableReason = reason,
        )
    }

    private fun system(
        systemId: Int,
        routeIndex: Int,
        routeSystemNames: List<String>,
    ): CurrentNavigationSystemDto {
        val system = systemProvider(systemId)
        val spaceClassification = system?.let(spaceClassifier::classify)
        return CurrentNavigationSystemDto(
            routeIndex = routeIndex,
            systemId = systemId,
            name = system?.name ?: routeSystemNames.getOrNull(routeIndex),
            securityStatus = system?.securityStatus,
            spaceClassification = spaceClassification?.name,
            securityBand = system?.securityStatus?.let { securityStatus ->
                securityBand(spaceClassification, securityStatus)
            },
        )
    }

    private fun securityBand(
        spaceClassification: SpaceClassification?,
        securityStatus: Double,
    ): CurrentNavigationSecurityBand? {
        if (spaceClassification != SpaceClassification.NEW_EDEN) return null
        return when {
            securityStatus >= JumpEligibilityPolicy.HIGH_SECURITY_THRESHOLD ->
                CurrentNavigationSecurityBand.HIGH_SECURITY
            securityStatus > 0.0 -> CurrentNavigationSecurityBand.LOW_SECURITY
            else -> CurrentNavigationSecurityBand.NULL_SECURITY
        }
    }
}
