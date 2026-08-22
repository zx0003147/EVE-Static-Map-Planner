package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.control.mission.Mission
import dev.evestaticmapplanner.core.jump.JumpRangeResult
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome

interface SystemReadPort {
    suspend fun searchSystems(query: String, limit: Int): List<SystemSummaryDto>
    suspend fun getSystemInfo(systemId: Int): SystemInfoDto?
}

interface RoutePlanningPort {
    suspend fun calculateNormalRoute(
        startSystemId: Int,
        destinationSystemId: Int,
        useAnsiblex: Boolean,
    ): RouteCalculationOutcome

    suspend fun calculateCapitalRoute(
        startSystemId: Int,
        destinationSystemId: Int,
        effectiveRangeLy: Double,
    ): CapitalRouteOutcome
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

fun interface MissionRenderStatePort {
    suspend fun publish(missions: List<Mission>)
}
