package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.control.mission.Mission
import dev.evestaticmapplanner.core.jump.JumpRangeResult
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.CapitalNavigationOutcome
import dev.evestaticmapplanner.core.route.NavigationIntent
import dev.evestaticmapplanner.core.route.NavigationIntentValidation
import dev.evestaticmapplanner.core.route.NormalNavigationOutcome

interface SystemReadPort {
    suspend fun searchSystems(query: String, limit: Int): List<SystemSummaryDto>
    suspend fun getSystemInfo(systemId: Int): SystemInfoDto?
}

data class SavedMarkerCreatePortRequest(
    val systemId: Int,
    val name: String?,
    val notes: String?,
    val color: dev.evestaticmapplanner.core.marker.MarkerColor,
    val tags: List<SavedMarkerChildType> = emptyList(),
)

interface SavedMarkerControlPort {
    suspend fun getSystemMarker(systemId: Int): SavedMarkerSummaryDto?
    suspend fun createSavedMarker(request: SavedMarkerCreatePortRequest): SavedMarkerSummaryDto
}

object DeniedSavedMarkerControlPort : SavedMarkerControlPort {
    override suspend fun getSystemMarker(systemId: Int): SavedMarkerSummaryDto? = denied()
    override suspend fun createSavedMarker(request: SavedMarkerCreatePortRequest): SavedMarkerSummaryDto = denied()

    private fun denied(): Nothing = throw ControlPortFailure(
        ControlErrorCode.CAPABILITY_DENIED,
        "AI Saved Marker capability is denied",
    )
}

interface RoutePlanningPort {
    suspend fun calculateNormalRoute(
        startSystemId: Int,
        destinationSystemId: Int,
        useAnsiblex: Boolean,
    ): RouteCalculationOutcome

    suspend fun calculateNormalRoute(
        startSystemId: Int,
        destinationSystemId: Int,
        useAnsiblex: Boolean,
        useWormholes: Boolean,
    ): RouteCalculationOutcome = calculateNormalRoute(startSystemId, destinationSystemId, useAnsiblex)

    suspend fun calculateCapitalRoute(
        startSystemId: Int,
        destinationSystemId: Int,
        effectiveRangeLy: Double,
    ): CapitalRouteOutcome

    suspend fun calculateNormalRoute(
        intent: NavigationIntent,
        useAnsiblex: Boolean,
        useWormholes: Boolean,
    ): NormalNavigationOutcome {
        val validation = intent.validate()
        if (validation != NavigationIntentValidation.Valid) return NormalNavigationOutcome.InvalidIntent(validation)
        val routes = mutableListOf<dev.evestaticmapplanner.core.route.RouteResult>()
        for (segment in intent.segments()) {
            when (
                val outcome = calculateNormalRoute(
                    segment.fromSystemId,
                    segment.toSystemId,
                    useAnsiblex,
                    useWormholes,
                )
            ) {
                is RouteCalculationOutcome.Found -> routes += outcome.route
                is RouteCalculationOutcome.SameSystem -> routes += outcome.route
                else -> return NormalNavigationOutcome.SegmentFailed(segment, outcome)
            }
        }
        return NormalNavigationOutcome.Found(
            dev.evestaticmapplanner.core.route.RouteResult(
                routes.first().startSystemId,
                routes.last().destinationSystemId,
                routes.flatMapIndexed { index, route -> if (index == 0) route.systems else route.systems.drop(1) },
                routes.flatMap { it.edges },
            ),
        )
    }

    suspend fun calculateCapitalRoute(
        intent: NavigationIntent,
        effectiveRangeLy: Double,
    ): CapitalNavigationOutcome {
        val validation = intent.validate()
        if (validation != NavigationIntentValidation.Valid) return CapitalNavigationOutcome.InvalidIntent(validation)
        val routes = mutableListOf<dev.evestaticmapplanner.core.route.CapitalRouteResult>()
        for (segment in intent.segments()) {
            when (val outcome = calculateCapitalRoute(segment.fromSystemId, segment.toSystemId, effectiveRangeLy)) {
                is CapitalRouteOutcome.Found -> routes += outcome.route
                is CapitalRouteOutcome.SameSystem -> routes += outcome.route
                else -> return CapitalNavigationOutcome.SegmentFailed(segment, outcome)
            }
        }
        val profile = routes.first().profile
        return CapitalNavigationOutcome.Found(
            dev.evestaticmapplanner.core.route.CapitalRouteResult(
                routes.first().startSystemId,
                routes.last().destinationSystemId,
                profile,
                routes.flatMapIndexed { index, route -> if (index == 0) route.systems else route.systems.drop(1) },
                routes.flatMap { it.legs },
            ),
        )
    }
}

enum class WormholeCreateStatus {
    CREATED,
    ALREADY_EXISTS,
}

data class WormholeCreatePortResult(
    val connection: WormholeConnectionDto,
    val status: WormholeCreateStatus,
)

interface WormholeControlPort {
    suspend fun listWormholes(): List<WormholeConnectionDto>
    suspend fun createWormhole(fromSystemId: Int, toSystemId: Int): WormholeCreatePortResult
}

object UnavailableWormholeControlPort : WormholeControlPort {
    override suspend fun listWormholes(): List<WormholeConnectionDto> = unavailable()
    override suspend fun createWormhole(fromSystemId: Int, toSystemId: Int): WormholeCreatePortResult = unavailable()

    private fun unavailable(): Nothing = throw ControlPortFailure(
        ControlErrorCode.APP_NOT_READY,
        "Wormhole session control is unavailable",
    )
}

fun interface JumpPlanningPort {
    suspend fun calculateJumpRange(originSystemId: Int, effectiveRangeLy: Double): JumpRangeResult
}

enum class ViewportOperationOutcome {
    COMPLETED,
    NOT_FOUND,
    APP_NOT_READY,
}

interface ViewportControlPort {
    suspend fun focusSystem(systemId: Int): ViewportOperationOutcome
    suspend fun fitSystems(systemIds: Set<Int>): ViewportOperationOutcome
}

interface PlanningViewControlPort {
    suspend fun listViews(): List<PlanningViewDto>
    suspend fun currentView(): PlanningViewDto
    suspend fun createView(label: String?): PlanningViewDto
    suspend fun renameView(viewId: String, label: String): PlanningViewDto
    suspend fun switchView(viewId: String): PlanningViewDto
    suspend fun deleteView(viewId: String): PlanningViewDto
}

/** Compatibility fallback for hosts that have not yet exposed multiple Views. */
object SinglePlanningViewControlPort : PlanningViewControlPort {
    private val view = PlanningViewDto("view-1", "View 1", true)
    override suspend fun listViews() = listOf(view)
    override suspend fun currentView() = view
    override suspend fun createView(label: String?) = unsupported()
    override suspend fun renameView(viewId: String, label: String) = unsupported()
    override suspend fun switchView(viewId: String) = if (viewId == view.viewId) view else missing()
    override suspend fun deleteView(viewId: String) = unsupported()
    private fun unsupported(): Nothing = throw ControlPortFailure(ControlErrorCode.INVALID_ARGUMENT, "This host does not support multiple Views")
    private fun missing(): Nothing = throw ControlPortFailure(ControlErrorCode.NOT_FOUND, "View was not found")
}

fun interface MissionRenderStatePort {
    suspend fun publish(missions: List<Mission>)
}
