package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.control.MissionMapUiState
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.RouteResult

class MapVisualEmphasis private constructor(
    val focusedSystemIds: Set<Int>,
    val activeRouteCount: Int,
) {
    val isActive: Boolean get() = activeRouteCount > 0

    fun systemAlphaMultiplier(systemId: Int): Float = when {
        !isActive || systemId in focusedSystemIds -> 1f
        else -> ROUTE_FOCUS_BACKGROUND_SYSTEM_ALPHA
    }

    fun systemLabelAlphaMultiplier(systemId: Int): Float = when {
        !isActive || systemId in focusedSystemIds -> 1f
        else -> ROUTE_FOCUS_BACKGROUND_SYSTEM_LABEL_ALPHA
    }

    val hierarchyLabelAlphaMultiplier: Float
        get() = if (isActive) ROUTE_FOCUS_BACKGROUND_HIERARCHY_LABEL_ALPHA else 1f

    val backgroundConnectionAlphaMultiplier: Float
        get() = if (isActive) ROUTE_FOCUS_BACKGROUND_CONNECTION_ALPHA else 1f

    companion object {
        val None = MapVisualEmphasis(emptySet(), 0)

        fun fromDisplayedRoutes(
            userNormalRoute: RouteResult?,
            userCapitalRoute: CapitalRouteResult?,
            missionState: MissionMapUiState,
        ): MapVisualEmphasis {
            val focusedSystemIds = linkedSetOf<Int>()
            var routeCount = 0
            sequenceOf(userNormalRoute).filterNotNull()
                .plus(missionState.normalRoutes.asSequence().map { it.route })
                .forEach { route ->
                    routeCount += 1
                    focusedSystemIds += route.systems
                }
            sequenceOf(userCapitalRoute).filterNotNull()
                .plus(missionState.capitalRoutes.asSequence().map { it.route })
                .forEach { route ->
                    routeCount += 1
                    focusedSystemIds += route.systems
                }
            return if (routeCount == 0) None else MapVisualEmphasis(focusedSystemIds, routeCount)
        }
    }
}

internal const val ROUTE_FOCUS_BACKGROUND_SYSTEM_ALPHA = 0.32f
internal const val ROUTE_FOCUS_BACKGROUND_SYSTEM_LABEL_ALPHA = 0.28f
internal const val ROUTE_FOCUS_BACKGROUND_HIERARCHY_LABEL_ALPHA = 0.25f
internal const val ROUTE_FOCUS_BACKGROUND_CONNECTION_ALPHA = 0.28f
