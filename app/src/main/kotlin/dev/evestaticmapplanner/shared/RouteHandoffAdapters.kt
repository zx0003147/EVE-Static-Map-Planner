package dev.evestaticmapplanner.shared

import dev.evestaticmapplanner.ApplicationBuildInfo
import dev.evestaticmapplanner.capital.CapitalRouteUiState
import dev.evestaticmapplanner.route.RoutePlannerUiState
import dev.evestaticmapplanner.shared.model.SharedRouteHandoffDraft
import dev.evestaticmapplanner.shared.model.SharedRouteHandoffEdge
import dev.evestaticmapplanner.shared.model.SharedRouteHandoffMapMetadata
import dev.evestaticmapplanner.shared.model.SharedRouteHandoffType

internal object RouteHandoffAdapters {
    fun normal(state: RoutePlannerUiState, universeBuild: String): SharedRouteHandoffDraft? {
        val route = state.activeRoute ?: return null
        return SharedRouteHandoffDraft(
            type = SharedRouteHandoffType.NORMAL,
            originSystemId = route.startSystemId,
            waypointSystemIds = state.calculatedWaypointSystemIds,
            destinationSystemId = route.destinationSystemId,
            useAnsiblex = state.useAnsiblex,
            capitalRangeLy = null,
            jumpProfileId = null,
            resolvedSystemIds = route.systems,
            resolvedEdges = route.edges.map {
                SharedRouteHandoffEdge(it.fromSystemId, it.toSystemId, it.type.name, null)
            },
            mapMetadata = metadata(universeBuild),
        )
    }

    fun capital(state: CapitalRouteUiState, universeBuild: String): SharedRouteHandoffDraft? {
        val route = state.activeRoute ?: return null
        return SharedRouteHandoffDraft(
            type = SharedRouteHandoffType.CAPITAL,
            originSystemId = route.startSystemId,
            waypointSystemIds = state.calculatedWaypointSystemIds,
            destinationSystemId = route.destinationSystemId,
            useAnsiblex = null,
            capitalRangeLy = route.profile.maxRangeLy,
            jumpProfileId = route.profile.id,
            resolvedSystemIds = route.systems,
            resolvedEdges = route.legs.map {
                SharedRouteHandoffEdge(it.fromSystemId, it.toSystemId, "CAPITAL", it.distanceLy)
            },
            mapMetadata = metadata(universeBuild),
        )
    }

    private fun metadata(universeBuild: String) = SharedRouteHandoffMapMetadata(
        universeBuild = universeBuild,
        plannerVersion = ApplicationBuildInfo.current.appVersion,
        webPackVersion = null,
    )
}
