package dev.evestaticmapplanner.core.route

import dev.evestaticmapplanner.core.jump.JumpProfile

/** An ordered route request. Waypoints are required stops and are never reordered or deduplicated. */
data class NavigationIntent(
    val startSystemId: Int,
    val waypointSystemIds: List<Int> = emptyList(),
    val destinationSystemId: Int? = null,
) {
    val explicitStops: List<Int>
        get() = buildList {
            add(startSystemId)
            addAll(waypointSystemIds)
            destinationSystemId?.let(::add)
        }

    val effectiveDestinationSystemId: Int?
        get() = destinationSystemId ?: waypointSystemIds.lastOrNull()

    fun validate(): NavigationIntentValidation = when {
        startSystemId <= 0 || waypointSystemIds.any { it <= 0 } ||
            (destinationSystemId != null && destinationSystemId <= 0) ->
            NavigationIntentValidation.InvalidSystemId
        effectiveDestinationSystemId == null -> NavigationIntentValidation.MissingTerminalStop
        else -> explicitStops.zipWithNext().indexOfFirst { (from, to) -> from == to }.let { duplicateIndex ->
            if (duplicateIndex >= 0) {
                NavigationIntentValidation.AdjacentDuplicate(
                    systemId = explicitStops[duplicateIndex],
                    firstStopIndex = duplicateIndex,
                )
            } else {
                NavigationIntentValidation.Valid
            }
        }
    }

    fun segments(): List<NavigationSegment> = explicitStops.zipWithNext().mapIndexed { index, (from, to) ->
        NavigationSegment(
            index = index,
            fromSystemId = from,
            toSystemId = to,
            fromRole = if (index == 0) NavigationStopRole.START else NavigationStopRole.WAYPOINT,
            toRole = if (destinationSystemId != null && index == explicitStops.lastIndex - 1) {
                NavigationStopRole.DESTINATION
            } else {
                NavigationStopRole.WAYPOINT
            },
        )
    }
}

sealed interface NavigationIntentValidation {
    data object Valid : NavigationIntentValidation
    data object MissingTerminalStop : NavigationIntentValidation
    data object InvalidSystemId : NavigationIntentValidation
    data class AdjacentDuplicate(val systemId: Int, val firstStopIndex: Int) : NavigationIntentValidation
}

enum class NavigationStopRole { START, WAYPOINT, DESTINATION }

data class NavigationSegment(
    val index: Int,
    val fromSystemId: Int,
    val toSystemId: Int,
    val fromRole: NavigationStopRole,
    val toRole: NavigationStopRole,
)

sealed interface NormalNavigationOutcome {
    data class Found(val route: RouteResult) : NormalNavigationOutcome
    data class InvalidIntent(val validation: NavigationIntentValidation) : NormalNavigationOutcome
    data class SegmentFailed(
        val segment: NavigationSegment,
        val cause: RouteCalculationOutcome,
    ) : NormalNavigationOutcome
}

class NormalNavigationPlanner(
    private val engine: NormalRouteEngine = NormalRouteEngine(),
) {
    fun calculate(
        graph: RouteGraph,
        intent: NavigationIntent,
        options: RouteOptions = RouteOptions(),
    ): NormalNavigationOutcome {
        val validation = intent.validate()
        if (validation != NavigationIntentValidation.Valid) return NormalNavigationOutcome.InvalidIntent(validation)

        val routes = mutableListOf<RouteResult>()
        for (segment in intent.segments()) {
            when (val outcome = engine.calculate(graph, segment.fromSystemId, segment.toSystemId, options)) {
                is RouteCalculationOutcome.Found -> routes += outcome.route
                is RouteCalculationOutcome.SameSystem -> routes += outcome.route
                else -> return NormalNavigationOutcome.SegmentFailed(segment, outcome)
            }
        }
        return NormalNavigationOutcome.Found(mergeNormalRoutes(routes))
    }

    private fun mergeNormalRoutes(routes: List<RouteResult>): RouteResult {
        require(routes.isNotEmpty())
        return RouteResult(
            startSystemId = routes.first().startSystemId,
            destinationSystemId = routes.last().destinationSystemId,
            systems = buildList {
                routes.forEachIndexed { index, route ->
                    addAll(if (index == 0) route.systems else route.systems.drop(1))
                }
            },
            edges = routes.flatMap(RouteResult::edges),
        )
    }
}

sealed interface CapitalNavigationOutcome {
    data class Found(val route: CapitalRouteResult) : CapitalNavigationOutcome
    data class InvalidIntent(val validation: NavigationIntentValidation) : CapitalNavigationOutcome
    data class SegmentFailed(
        val segment: NavigationSegment,
        val cause: CapitalRouteOutcome,
    ) : CapitalNavigationOutcome
}

class CapitalNavigationPlanner(
    private val engine: CapitalRouteEngine,
) {
    fun calculate(intent: NavigationIntent, profile: JumpProfile): CapitalNavigationOutcome {
        val validation = intent.validate()
        if (validation != NavigationIntentValidation.Valid) return CapitalNavigationOutcome.InvalidIntent(validation)

        val routes = mutableListOf<CapitalRouteResult>()
        for (segment in intent.segments()) {
            when (val outcome = engine.calculate(segment.fromSystemId, segment.toSystemId, profile)) {
                is CapitalRouteOutcome.Found -> routes += outcome.route
                is CapitalRouteOutcome.SameSystem -> routes += outcome.route
                else -> return CapitalNavigationOutcome.SegmentFailed(segment, outcome)
            }
        }
        return CapitalNavigationOutcome.Found(mergeCapitalRoutes(routes, profile))
    }

    private fun mergeCapitalRoutes(routes: List<CapitalRouteResult>, profile: JumpProfile): CapitalRouteResult {
        require(routes.isNotEmpty())
        return CapitalRouteResult(
            startSystemId = routes.first().startSystemId,
            destinationSystemId = routes.last().destinationSystemId,
            profile = profile,
            systems = buildList {
                routes.forEachIndexed { index, route ->
                    addAll(if (index == 0) route.systems else route.systems.drop(1))
                }
            },
            legs = routes.flatMap(CapitalRouteResult::legs),
        )
    }
}
