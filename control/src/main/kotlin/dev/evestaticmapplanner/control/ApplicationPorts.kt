package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.control.mission.Mission
import dev.evestaticmapplanner.core.jump.JumpRangeResult
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome

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
