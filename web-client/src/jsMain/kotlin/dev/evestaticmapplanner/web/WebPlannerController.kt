package dev.evestaticmapplanner.web

import dev.evestaticmapplanner.core.jump.EligibilityVerdict
import dev.evestaticmapplanner.core.jump.JumpCoverageCalculator
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.JumpRangeOverlay
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.NavigationIntent
import dev.evestaticmapplanner.core.route.NavigationIntentValidation
import dev.evestaticmapplanner.core.route.NormalNavigationOutcome
import dev.evestaticmapplanner.core.route.RouteOptions
import dev.evestaticmapplanner.core.route.RouteResult

data class WebPlannerState(
    val selectedSystemId: Int? = null,
    val hoveredSystemId: Int? = null,
    val normalStartSystemId: Int? = null,
    val normalDestinationSystemId: Int? = null,
    val normalWaypointSystemIds: List<Int> = emptyList(),
    val useAnsiblex: Boolean = false,
    val normalRoute: RouteResult? = null,
    val capitalStartSystemId: Int? = null,
    val capitalDestinationSystemId: Int? = null,
    val capitalRangeLy: Double = 5.0,
    val capitalRoute: CapitalRouteResult? = null,
    val jumpOverlays: List<JumpRangeOverlay> = emptyList(),
    val message: String? = null,
    val error: String? = null,
) {
    val coverageCounts: Map<Int, Int> get() = JumpCoverageCalculator.coverageCounts(jumpOverlays)
    val routeSystemIds: Set<Int> get() = buildSet {
        normalRoute?.systems?.let(::addAll)
        capitalRoute?.systems?.let(::addAll)
    }
}

class WebPlannerController(
    val universe: WebUniverse,
    private val onStateChanged: (WebPlannerState) -> Unit,
) {
    var state: WebPlannerState = WebPlannerState()
        private set
    private var nextOverlayId = 1

    fun search(query: String, limit: Int = 12): List<SolarSystem> =
        universe.searchRepository.searchSystems(query, limit)

    fun selectSystem(systemId: Int?) = update {
        val error = systemId?.takeIf { it !in universe.systemsById }?.let { "Unknown system $it" }
        it.copy(selectedSystemId = if (error == null) systemId else it.selectedSystemId, error = error, message = null)
    }

    fun hoverSystem(systemId: Int?) {
        if (state.hoveredSystemId != systemId) update { it.copy(hoveredSystemId = systemId) }
    }

    fun setNormalStart(systemId: Int?) = update {
        it.copy(normalStartSystemId = known(systemId), normalRoute = null, message = null, error = null)
    }

    fun setNormalDestination(systemId: Int?) = update {
        it.copy(normalDestinationSystemId = known(systemId), normalRoute = null, message = null, error = null)
    }

    fun addNormalWaypoint(systemId: Int) = update { current ->
        if (systemId !in universe.systemsById) current.copy(error = "Unknown waypoint system $systemId")
        else current.copy(
            normalWaypointSystemIds = current.normalWaypointSystemIds + systemId,
            normalRoute = null,
            message = null,
            error = null,
        )
    }

    fun removeNormalWaypoint(index: Int) = update { current ->
        if (index !in current.normalWaypointSystemIds.indices) current
        else current.copy(
            normalWaypointSystemIds = current.normalWaypointSystemIds.filterIndexed { i, _ -> i != index },
            normalRoute = null,
            message = null,
        )
    }

    fun moveNormalWaypoint(index: Int, delta: Int) = update { current ->
        val target = index + delta
        if (index !in current.normalWaypointSystemIds.indices || target !in current.normalWaypointSystemIds.indices) current
        else current.normalWaypointSystemIds.toMutableList().also { list ->
            val value = list.removeAt(index)
            list.add(target, value)
        }.let { current.copy(normalWaypointSystemIds = it, normalRoute = null, message = null) }
    }

    fun setUseAnsiblex(enabled: Boolean) = update {
        it.copy(useAnsiblex = enabled, normalRoute = null, message = null, error = null)
    }

    fun calculateNormalRoute() {
        val start = state.normalStartSystemId
        val destination = state.normalDestinationSystemId
        if (start == null || destination == null) {
            update { it.copy(error = "Choose both route origin and destination.", message = null) }
            return
        }
        val intent = NavigationIntent(start, state.normalWaypointSystemIds, destination)
        when (val outcome = universe.normalPlanner.calculate(
            universe.routeGraph,
            intent,
            RouteOptions(useAnsiblex = state.useAnsiblex),
        )) {
            is NormalNavigationOutcome.Found -> update {
                it.copy(
                    normalRoute = outcome.route,
                    error = null,
                    message = "Route ready · ${outcome.route.totalJumps} jumps" +
                        if (outcome.route.ansiblexJumps > 0) " · ${outcome.route.ansiblexJumps} Ansiblex" else "",
                )
            }
            is NormalNavigationOutcome.InvalidIntent -> update {
                it.copy(error = navigationValidationMessage(outcome.validation), message = null)
            }
            is NormalNavigationOutcome.SegmentFailed -> update {
                val from = systemName(outcome.segment.fromSystemId)
                val to = systemName(outcome.segment.toSystemId)
                it.copy(error = "No route for waypoint segment ${outcome.segment.index + 1}: $from → $to.", message = null)
            }
        }
    }

    fun clearNormalRoute() = update {
        it.copy(normalRoute = null, message = "Normal route cleared.", error = null)
    }

    fun setCapitalStart(systemId: Int?) = update {
        it.copy(capitalStartSystemId = known(systemId), capitalRoute = null, message = null, error = null)
    }

    fun setCapitalDestination(systemId: Int?) = update {
        it.copy(capitalDestinationSystemId = known(systemId), capitalRoute = null, message = null, error = null)
    }

    fun setCapitalRange(rangeLy: Double) = update { current ->
        if (!rangeLy.isFinite() || rangeLy <= 0.0) current.copy(error = "Jump range must be a positive number.")
        else current.copy(capitalRangeLy = rangeLy, capitalRoute = null, error = null, message = null)
    }

    fun calculateCapitalRoute() {
        val start = state.capitalStartSystemId
        val destination = state.capitalDestinationSystemId
        if (start == null || destination == null) {
            update { it.copy(error = "Choose both Capital origin and destination.", message = null) }
            return
        }
        val profile = profileOrReport() ?: return
        when (val outcome = universe.capitalEngine.calculate(start, destination, profile)) {
            is CapitalRouteOutcome.Found -> update {
                it.copy(
                    capitalRoute = outcome.route,
                    error = null,
                    message = "Capital route ready · ${outcome.route.totalJumps} jumps · " +
                        "${formatDouble(outcome.route.totalDistanceLy, 2)} LY",
                )
            }
            is CapitalRouteOutcome.SameSystem -> update {
                it.copy(capitalRoute = outcome.route, error = null, message = "Capital origin and destination are the same system.")
            }
            is CapitalRouteOutcome.InvalidEndpoint -> update {
                it.copy(error = "Capital route contains an unknown endpoint.", message = null)
            }
            is CapitalRouteOutcome.IneligibleEndpoint -> update {
                it.copy(error = "${outcome.endpoint.name.lowercase().replaceFirstChar(Char::uppercase)} is not eligible: ${verdictReason(outcome.verdict)}", message = null)
            }
            is CapitalRouteOutcome.Unreachable -> update {
                it.copy(error = "No Capital route exists at ${formatDouble(profile.maxRangeLy, 2)} LY.", message = null)
            }
        }
    }

    fun clearCapitalRoute() = update {
        it.copy(capitalRoute = null, message = "Capital route cleared.", error = null)
    }

    fun addJumpRange(originSystemId: Int?) {
        if (originSystemId == null) {
            update { it.copy(error = "Choose a Jump Range source system.", message = null) }
            return
        }
        val profile = profileOrReport() ?: return
        val result = universe.jumpCandidates.reachableFrom(originSystemId, profile)
        if (result.originVerdict !is EligibilityVerdict.Eligible) {
            update { it.copy(error = "Jump Range source is not eligible: ${verdictReason(result.originVerdict)}", message = null) }
            return
        }
        val id = "coverage-${nextOverlayId++}"
        val overlay = JumpRangeOverlay(
            id = id,
            originSystemId = originSystemId,
            profile = profile.copy(id = "$id-profile"),
            reachableSystemIds = result.reachableSystemIds,
            label = "${systemName(originSystemId)} · ${formatDouble(profile.maxRangeLy, 2)} LY",
        )
        update {
            it.copy(
                jumpOverlays = it.jumpOverlays + overlay,
                error = null,
                message = "Jump Range added · ${result.reachableSystemIds.size} reachable systems.",
            )
        }
    }

    fun removeJumpRange(id: String) = update {
        it.copy(jumpOverlays = it.jumpOverlays.filterNot { overlay -> overlay.id == id }, message = "Coverage source removed.", error = null)
    }

    fun clearJumpRanges() = update {
        it.copy(jumpOverlays = emptyList(), message = "Jump Range and Capital Coverage cleared.", error = null)
    }

    fun systemName(systemId: Int): String = universe.systemsById[systemId]?.name ?: systemId.toString()

    private fun profileOrReport(): JumpProfile? = runCatching {
        JumpProfile.manual(state.capitalRangeLy, "web-capital")
    }.getOrElse { failure ->
        update { it.copy(error = failure.message ?: "Invalid jump profile.", message = null) }
        null
    }

    private fun known(systemId: Int?): Int? = systemId?.takeIf(universe.systemsById::containsKey)

    private fun update(transform: (WebPlannerState) -> WebPlannerState) {
        state = transform(state)
        onStateChanged(state)
    }

    private fun navigationValidationMessage(validation: NavigationIntentValidation): String = when (validation) {
        NavigationIntentValidation.Valid -> ""
        NavigationIntentValidation.MissingTerminalStop -> "Choose a route destination."
        NavigationIntentValidation.InvalidSystemId -> "A route stop is invalid."
        is NavigationIntentValidation.AdjacentDuplicate ->
            "Adjacent route stops cannot both be ${systemName(validation.systemId)}."
    }

    private fun verdictReason(verdict: EligibilityVerdict): String = when (verdict) {
        EligibilityVerdict.Eligible -> "eligible"
        is EligibilityVerdict.Ineligible -> verdict.reason
        is EligibilityVerdict.Unknown -> verdict.reason
    }
}

internal fun formatDouble(value: Double, digits: Int): String = value.asDynamic().toFixed(digits) as String
