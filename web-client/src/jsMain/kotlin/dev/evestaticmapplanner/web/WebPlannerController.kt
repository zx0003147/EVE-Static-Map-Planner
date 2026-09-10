package dev.evestaticmapplanner.web

import dev.evestaticmapplanner.core.jump.EligibilityVerdict
import dev.evestaticmapplanner.core.jump.JumpCoverageCalculator
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.JumpRangeOverlay
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.CapitalNavigationOutcome
import dev.evestaticmapplanner.core.route.CapitalNavigationPlanner
import dev.evestaticmapplanner.core.route.NavigationIntent
import dev.evestaticmapplanner.core.route.NavigationIntentValidation
import dev.evestaticmapplanner.core.route.NormalNavigationOutcome
import dev.evestaticmapplanner.core.route.RouteOptions
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteEdgeId
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.CapitalRouteLeg
import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.shared.protocol.RouteHandoffDto

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
    val capitalWaypointSystemIds: List<Int> = emptyList(),
    val capitalRangeLy: Double = 5.0,
    val capitalRoute: CapitalRouteResult? = null,
    val coverageRangeLy: Double = 5.0,
    val jumpOverlays: List<JumpRangeOverlay> = emptyList(),
    val mapPreferences: WebMapPreferences = WebMapPreferences.Defaults,
    val personalAnsiblex: List<PersonalAnsiblexConnection> = emptyList(),
    val personalAnsiblexPreview: PersonalAnsiblexImportPreview? = null,
    val fortizarSystemIds: Set<Int> = emptySet(),
    val keepstarSystemIds: Set<Int> = emptySet(),
    val message: String? = null,
    val error: String? = null,
    val notificationRevision: Long = 0,
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
    private val mapPreferencesStore: WebMapPreferencesStore = WebMapPreferencesStore(),
    private val personalAnsiblexStore: PersonalAnsiblexStore = PersonalAnsiblexStore(),
    private val fortizarStore: WebFortizarMarkerStore = WebFortizarMarkerStore(),
    private val keepstarStore: WebKeepstarMarkerStore = WebKeepstarMarkerStore(),
    private val idFactory: () -> String = ::browserUuid,
) {
    private val restoredPersonalAnsiblex = universe.effectivePersonalAnsiblex(personalAnsiblexStore.load().filter {
        it.firstSystemId in universe.systemsById && it.secondSystemId in universe.systemsById
    })
    private var routeGraph = universe.routeGraphWith(restoredPersonalAnsiblex)
    var state: WebPlannerState = WebPlannerState(
        mapPreferences = mapPreferencesStore.load(),
        personalAnsiblex = restoredPersonalAnsiblex,
        fortizarSystemIds = fortizarStore.load().filterTo(linkedSetOf(), universe.systemsById::containsKey),
        keepstarSystemIds = keepstarStore.load().filterTo(linkedSetOf(), universe.systemsById::containsKey),
    )
        private set
    private var nextOverlayId = 1

    fun search(query: String, limit: Int = 12): List<SolarSystem> =
        universe.searchRepository.searchSystems(query, limit)

    fun selectSystem(systemId: Int?) = notify {
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

    fun addNormalWaypoint(systemId: Int) = notify { current ->
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
            notify { it.copy(error = "Choose both route origin and destination.", message = null) }
            return
        }
        val intent = NavigationIntent(start, state.normalWaypointSystemIds, destination)
        when (val outcome = universe.normalPlanner.calculate(
            routeGraph,
            intent,
            RouteOptions(useAnsiblex = state.useAnsiblex),
        )) {
            is NormalNavigationOutcome.Found -> notify {
                it.copy(
                    normalRoute = outcome.route,
                    error = null,
                    message = "Route ready · ${outcome.route.totalJumps} jumps" +
                        if (outcome.route.ansiblexJumps > 0) " · ${outcome.route.ansiblexJumps} Ansiblex" else "",
                )
            }
            is NormalNavigationOutcome.InvalidIntent -> notify {
                it.copy(error = navigationValidationMessage(outcome.validation), message = null)
            }
            is NormalNavigationOutcome.SegmentFailed -> notify {
                val from = systemName(outcome.segment.fromSystemId)
                val to = systemName(outcome.segment.toSystemId)
                it.copy(error = "No route for waypoint segment ${outcome.segment.index + 1}: $from → $to.", message = null)
            }
        }
    }

    fun clearNormalRoute() = notify {
        it.copy(normalRoute = null, message = "Normal route cleared.", error = null)
    }

    fun setCapitalStart(systemId: Int?) = update {
        it.copy(capitalStartSystemId = known(systemId), capitalRoute = null, message = null, error = null)
    }

    fun setCapitalDestination(systemId: Int?) = update {
        it.copy(capitalDestinationSystemId = known(systemId), capitalRoute = null, message = null, error = null)
    }

    fun addCapitalWaypoint(systemId: Int) = notify { current ->
        if (systemId !in universe.systemsById) current.copy(error = "Unknown Capital waypoint system $systemId")
        else current.copy(
            capitalWaypointSystemIds = current.capitalWaypointSystemIds + systemId,
            capitalRoute = null,
            message = null,
            error = null,
        )
    }

    fun removeCapitalWaypoint(index: Int) = update { current ->
        if (index !in current.capitalWaypointSystemIds.indices) current
        else current.copy(
            capitalWaypointSystemIds = current.capitalWaypointSystemIds.filterIndexed { i, _ -> i != index },
            capitalRoute = null,
            message = null,
        )
    }

    fun moveCapitalWaypoint(index: Int, delta: Int) = update { current ->
        val target = index + delta
        if (index !in current.capitalWaypointSystemIds.indices || target !in current.capitalWaypointSystemIds.indices) current
        else current.capitalWaypointSystemIds.toMutableList().also { list ->
            val value = list.removeAt(index)
            list.add(target, value)
        }.let { current.copy(capitalWaypointSystemIds = it, capitalRoute = null, message = null) }
    }

    fun setCapitalRange(rangeLy: Double) = notify { current ->
        if (!rangeLy.isFinite() || rangeLy <= 0.0 || rangeLy > MAX_WEB_JUMP_RANGE_LY) {
            current.copy(error = "Jump range must be between 0 and 50 LY.")
        }
        else current.copy(capitalRangeLy = rangeLy, capitalRoute = null, error = null, message = null)
    }

    fun calculateCapitalRoute() {
        val start = state.capitalStartSystemId
        val destination = state.capitalDestinationSystemId
        if (start == null || destination == null) {
            notify { it.copy(error = "Choose both Capital origin and destination.", message = null) }
            return
        }
        val profile = profileOrReport() ?: return
        val intent = NavigationIntent(start, state.capitalWaypointSystemIds, destination)
        when (val outcome = CapitalNavigationPlanner(universe.capitalEngine).calculate(intent, profile)) {
            is CapitalNavigationOutcome.Found -> notify {
                it.copy(
                    capitalRoute = outcome.route,
                    error = null,
                    message = "Capital route ready · ${outcome.route.totalJumps} jumps · " +
                        "${formatDouble(outcome.route.totalDistanceLy, 2)} LY",
                )
            }
            is CapitalNavigationOutcome.InvalidIntent -> notify {
                it.copy(error = navigationValidationMessage(outcome.validation), message = null)
            }
            is CapitalNavigationOutcome.SegmentFailed -> notify {
                val from = systemName(outcome.segment.fromSystemId)
                val to = systemName(outcome.segment.toSystemId)
                val detail = when (val cause = outcome.cause) {
                    is CapitalRouteOutcome.InvalidEndpoint -> "unknown endpoint"
                    is CapitalRouteOutcome.IneligibleEndpoint ->
                        "${cause.endpoint.name.lowercase().replaceFirstChar(Char::uppercase)} is not eligible: ${verdictReason(cause.verdict)}"
                    is CapitalRouteOutcome.Unreachable -> "no route at ${formatDouble(profile.maxRangeLy, 2)} LY"
                    is CapitalRouteOutcome.Found, is CapitalRouteOutcome.SameSystem -> "invalid segment result"
                }
                it.copy(error = "Capital waypoint segment ${outcome.segment.index + 1} failed ($from → $to): $detail.", message = null)
            }
        }
    }

    fun clearCapitalRoute() = notify {
        it.copy(capitalRoute = null, message = "Capital route cleared.", error = null)
    }

    fun setCoverageRange(rangeLy: Double): Boolean {
        if (!rangeLy.isFinite() || rangeLy <= 0.0 || rangeLy > MAX_WEB_JUMP_RANGE_LY) {
            notify { it.copy(error = "Coverage range must be between 0 and 50 LY.", message = null) }
            return false
        }
        update { it.copy(coverageRangeLy = rangeLy, error = null, message = null) }
        return true
    }

    fun addJumpRange(originSystemId: Int?) {
        if (originSystemId == null) {
            notify { it.copy(error = "Choose a Jump Range source system.", message = null) }
            return
        }
        val profile = coverageProfileOrReport() ?: return
        val result = universe.jumpCandidates.reachableFrom(originSystemId, profile)
        if (result.originVerdict !is EligibilityVerdict.Eligible) {
            notify { it.copy(error = "Jump Range source is not eligible: ${verdictReason(result.originVerdict)}", message = null) }
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
        notify {
            it.copy(
                jumpOverlays = it.jumpOverlays + overlay,
                error = null,
                message = "Jump Range added · ${result.reachableSystemIds.size} reachable systems.",
            )
        }
    }

    fun removeJumpRange(id: String) = notify {
        it.copy(jumpOverlays = it.jumpOverlays.filterNot { overlay -> overlay.id == id }, message = "Coverage source removed.", error = null)
    }

    fun clearJumpRanges() = notify {
        it.copy(jumpOverlays = emptyList(), message = "Jump Range and Capital Coverage cleared.", error = null)
    }

    fun loadRouteHandoff(handoff: RouteHandoffDto) {
        val routeType = handoff.type
        val universeMismatch = handoff.mapMetadata.universeBuild != universe.metadata.sdeBuild.toString()
        val unknownSystems = handoff.resolvedSystemIds.filterNot(universe.systemsById::containsKey)
        val warning = buildString {
            append("Desktop ${routeType.lowercase().replaceFirstChar(Char::uppercase)} route loaded from snapshot.")
            if (universeMismatch) append(" Warning: publisher universe ${handoff.mapMetadata.universeBuild} differs from Web Pack ${universe.metadata.sdeBuild}.")
            if (unknownSystems.isNotEmpty()) append(" ${unknownSystems.size} systems are unknown in this Web Pack.")
        }
        try {
            when (routeType) {
                "NORMAL" -> {
                    val edges = handoff.resolvedEdges.mapIndexed { index, edge ->
                        RouteEdge(
                            id = RouteEdgeId("handoff:${handoff.routeHandoffId}:$index"),
                            connectionId = RouteConnectionId("handoff:${handoff.routeHandoffId}:$index"),
                            fromSystemId = edge.fromSystemId,
                            toSystemId = edge.toSystemId,
                            type = RouteEdgeType.valueOf(edge.type),
                        )
                    }
                    val route = RouteResult(
                        handoff.originSystemId,
                        handoff.destinationSystemId,
                        handoff.resolvedSystemIds,
                        edges,
                    )
                    notify {
                        it.copy(
                            normalStartSystemId = handoff.originSystemId,
                            normalDestinationSystemId = handoff.destinationSystemId,
                            normalWaypointSystemIds = handoff.waypointSystemIds,
                            useAnsiblex = handoff.useAnsiblex == true,
                            normalRoute = route,
                            message = warning,
                            error = null,
                        )
                    }
                }
                "CAPITAL" -> {
                    val range = requireNotNull(handoff.capitalRangeLy)
                    val profile = JumpProfile.manual(range, handoff.jumpProfileId ?: "desktop-handoff")
                    val legs = handoff.resolvedEdges.map { edge ->
                        CapitalRouteLeg(
                            edge.fromSystemId,
                            edge.toSystemId,
                            requireNotNull(edge.distanceLy) * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR,
                        )
                    }
                    val route = CapitalRouteResult(
                        handoff.originSystemId,
                        handoff.destinationSystemId,
                        profile,
                        handoff.resolvedSystemIds,
                        legs,
                    )
                    notify {
                        it.copy(
                            capitalStartSystemId = handoff.originSystemId,
                            capitalDestinationSystemId = handoff.destinationSystemId,
                            capitalWaypointSystemIds = handoff.waypointSystemIds,
                            capitalRangeLy = range,
                            capitalRoute = route,
                            message = warning,
                            error = null,
                        )
                    }
                }
                else -> notify { it.copy(error = "Unsupported Desktop Route Handoff type '$routeType'.", message = null) }
            }
        } catch (_: Throwable) {
            notify { it.copy(error = "Desktop Route Handoff snapshot is invalid.", message = null) }
        }
    }

    fun setMapThresholds(constellation: Double, system: Double) {
        if (!WebMapPreferences.isValid(constellation, system)) {
            notify { it.copy(error = "Constellation threshold must be positive and lower than System (maximum 250).") }
            return
        }
        val preferences = WebMapPreferences(constellation, system)
        mapPreferencesStore.save(preferences)
        notify { it.copy(mapPreferences = preferences, error = null, message = "Map LOD preferences saved.") }
    }

    fun resetMapThresholds() {
        val defaults = mapPreferencesStore.reset()
        notify { it.copy(mapPreferences = defaults, error = null, message = "Map LOD preferences reset to Desktop defaults.") }
    }

    fun previewPersonalAnsiblex(fileName: String, content: String) {
        val preview = PersonalAnsiblexParser.parse(
            fileName,
            content,
            universe.staticData.systems,
            universe.packAnsiblexLinks,
            state.personalAnsiblex,
        )
        notify {
            it.copy(
                personalAnsiblexPreview = preview,
                error = preview.errors.firstOrNull()?.let { issue -> "Row ${issue.row}: ${issue.message}" },
                message = if (preview.errors.isEmpty()) {
                    "Preview ready · ${preview.valid.size} valid · ${preview.duplicateRows.size} duplicate."
                } else null,
            )
        }
    }

    fun cancelPersonalAnsiblexPreview() = notify {
        it.copy(personalAnsiblexPreview = null, error = null, message = "Personal Ansiblex import cancelled.")
    }

    fun applyPersonalAnsiblexPreview() {
        val preview = state.personalAnsiblexPreview ?: return
        if (!preview.canApply) {
            notify { it.copy(error = "Fix import errors before applying Personal Ansiblex.", message = null) }
            return
        }
        val additions = preview.valid.map { draft ->
            PersonalAnsiblexConnection(
                id = idFactory(),
                firstSystemId = draft.firstSystemId,
                secondSystemId = draft.secondSystemId,
                direction = draft.direction,
                enabled = draft.enabled,
            )
        }
        replacePersonalAnsiblex(
            state.personalAnsiblex + additions,
            "Personal Ansiblex applied · ${additions.size} added.",
        )
    }

    fun setPersonalAnsiblexEnabled(id: String, enabled: Boolean) {
        if (state.personalAnsiblex.none { it.id == id }) return
        replacePersonalAnsiblex(
            state.personalAnsiblex.map { if (it.id == id) it.copy(enabled = enabled) else it },
            "Personal Ansiblex ${if (enabled) "enabled" else "disabled"}.",
        )
    }

    fun removePersonalAnsiblex(id: String) {
        val next = state.personalAnsiblex.filterNot { it.id == id }
        if (next.size == state.personalAnsiblex.size) return
        replacePersonalAnsiblex(next, "Personal Ansiblex removed.")
    }

    fun clearPersonalAnsiblex() {
        personalAnsiblexStore.clear()
        routeGraph = universe.routeGraph
        notify {
            it.copy(
                personalAnsiblex = emptyList(),
                personalAnsiblexPreview = null,
                normalRoute = null,
                error = null,
                message = "Personal Ansiblex cleared; Web Pack links were preserved.",
            )
        }
    }

    fun toggleKeepstarSavedMarker(systemId: Int) {
        if (systemId !in universe.systemsById) return
        val next = if (systemId in state.keepstarSystemIds) {
            state.keepstarSystemIds - systemId
        } else {
            state.keepstarSystemIds + systemId
        }
        keepstarStore.save(next)
        notify {
            it.copy(
                keepstarSystemIds = next,
                error = null,
                message = if (systemId in next) "Keepstar Saved Marker added." else "Keepstar Saved Marker removed.",
            )
        }
    }

    fun toggleFortizarSavedMarker(systemId: Int) {
        if (systemId !in universe.systemsById) return
        val next = if (systemId in state.fortizarSystemIds) {
            state.fortizarSystemIds - systemId
        } else {
            state.fortizarSystemIds + systemId
        }
        fortizarStore.save(next)
        notify {
            it.copy(
                fortizarSystemIds = next,
                error = null,
                message = if (systemId in next) "Fortizar Saved Marker added." else "Fortizar Saved Marker removed.",
            )
        }
    }

    fun enabledAnsiblexLinks(): List<WebAnsiblexLink> =
        (universe.packAnsiblexLinks + universe.effectivePersonalAnsiblex(state.personalAnsiblex).map(PersonalAnsiblexConnection::toVisual))
            .filter(WebAnsiblexLink::enabled)

    fun systemName(systemId: Int): String = universe.systemsById[systemId]?.name ?: systemId.toString()

    private fun profileOrReport(): JumpProfile? = runCatching {
        JumpProfile.manual(state.capitalRangeLy, "web-capital")
    }.getOrElse { failure ->
        notify { it.copy(error = failure.message ?: "Invalid jump profile.", message = null) }
        null
    }

    private fun coverageProfileOrReport(): JumpProfile? = runCatching {
        JumpProfile.manual(state.coverageRangeLy, "web-coverage")
    }.getOrElse { failure ->
        notify { it.copy(error = failure.message ?: "Invalid Coverage profile.", message = null) }
        null
    }

    private fun replacePersonalAnsiblex(next: List<PersonalAnsiblexConnection>, message: String) {
        personalAnsiblexStore.save(next)
        routeGraph = universe.routeGraphWith(next)
        notify {
            it.copy(
                personalAnsiblex = next,
                personalAnsiblexPreview = null,
                normalRoute = null,
                error = null,
                message = message,
            )
        }
    }

    private fun known(systemId: Int?): Int? = systemId?.takeIf(universe.systemsById::containsKey)

    private fun update(transform: (WebPlannerState) -> WebPlannerState) {
        state = transform(state)
        onStateChanged(state)
    }

    private fun notify(transform: (WebPlannerState) -> WebPlannerState) {
        val previous = state
        val next = transform(previous)
        state = if (next.error != null || next.message != null) {
            next.copy(notificationRevision = previous.notificationRevision + 1)
        } else {
            next
        }
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

private const val MAX_WEB_JUMP_RANGE_LY = 50.0
