package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionJumpRangeId
import dev.evestaticmapplanner.control.mission.MissionMarkerId
import dev.evestaticmapplanner.control.mission.MissionMarkerRole
import dev.evestaticmapplanner.control.mission.MissionRouteId
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.core.marker.SavedMarkerCreatedBy

sealed interface ControlResult<out T> {
    data class Success<T>(
        val requestId: String,
        val value: T,
        val missionRevision: Long? = null,
    ) : ControlResult<T>

    data class Failure(
        val requestId: String,
        val error: ControlError,
    ) : ControlResult<Nothing>
}

data class ControlError(
    val code: ControlErrorCode,
    val message: String,
)

enum class ControlErrorCode {
    NOT_FOUND,
    OBJECT_NOT_FOUND,
    AMBIGUOUS_SYSTEM,
    INVALID_ARGUMENT,
    INVALID_MARKER_DATA,
    CAPABILITY_DENIED,
    MARKER_ALREADY_EXISTS,
    SYSTEM_NOT_FOUND,
    MISSION_NOT_FOUND,
    MISSION_LIMIT_EXCEEDED,
    ROUTE_NOT_FOUND,
    APP_NOT_READY,
    DATABASE_UNAVAILABLE,
    RATE_LIMITED,
    IDEMPOTENCY_CONFLICT,
    TIMEOUT,
    INTERNAL_ERROR,
}

data class SystemSummaryDto(
    val systemId: Int,
    val name: String,
    val regionId: Int,
    val constellationId: Int,
    val securityStatus: Double,
)

data class SystemInfoDto(
    val system: SystemSummaryDto,
    val regionName: String,
    val constellationName: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val stargateCount: Int,
)

data class NormalRouteDto(
    val startSystemId: Int,
    val destinationSystemId: Int,
    val systemIds: List<Int>,
    val totalJumps: Int,
    val stargateJumps: Int,
    val ansiblexJumps: Int,
    val wormholeJumps: Int = 0,
    val waypointSystemIds: List<Int> = emptyList(),
    val explicitDestinationSystemId: Int? = destinationSystemId,
)

data class WormholeConnectionDto(
    val connectionId: String,
    val firstSystemId: Int,
    val secondSystemId: Int,
    val firstSystemName: String? = null,
    val secondSystemName: String? = null,
)

data class CreateWormholeReceipt(
    val connection: WormholeConnectionDto,
    val created: Boolean,
    val status: String,
)

data class CapitalRouteLegDto(
    val fromSystemId: Int,
    val toSystemId: Int,
    val distanceLy: Double,
)

data class CapitalRouteDto(
    val startSystemId: Int,
    val destinationSystemId: Int,
    val effectiveRangeLy: Double,
    val systemIds: List<Int>,
    val legs: List<CapitalRouteLegDto>,
    val totalJumps: Int,
    val totalDistanceLy: Double,
    val waypointSystemIds: List<Int> = emptyList(),
    val explicitDestinationSystemId: Int? = destinationSystemId,
)

data class MissionSummaryDto(
    val missionId: MissionId,
    val title: String,
    val createdAtEpochMillis: Long,
    val revision: Long,
    val routeCount: Int,
    val jumpRangeCount: Int,
    val markerCount: Int,
    val referencedSystemCount: Int,
    val viewId: String = "view-1",
)

data class PlanningViewDto(
    val viewId: String,
    val label: String,
    val current: Boolean,
)

data class MissionRouteReceipt(
    val missionId: MissionId,
    val routeId: MissionRouteId,
    val route: AnyRouteDto,
)

sealed interface AnyRouteDto {
    data class Normal(val value: NormalRouteDto) : AnyRouteDto
    data class Capital(val value: CapitalRouteDto) : AnyRouteDto
}

data class MissionJumpRangeReceipt(
    val missionId: MissionId,
    val jumpRangeId: MissionJumpRangeId,
    val originSystemId: Int,
    val effectiveRangeLy: Double,
    val reachableSystemCount: Int,
)

data class MissionMarkerReceipt(
    val missionId: MissionId,
    val markerId: MissionMarkerId,
    val systemId: Int,
    val role: MissionMarkerRole,
)

data class SavedMarkerChildSummaryDto(
    val id: String,
    val type: String,
    val orderIndex: Int,
)

data class SavedMarkerSummaryDto(
    val systemId: Int,
    val name: String?,
    val color: MarkerColor,
    val notes: String?,
    val children: List<SavedMarkerChildSummaryDto>,
    val createdBy: SavedMarkerCreatedBy,
)

data class MissionMarkerSummaryDto(
    val missionId: MissionId,
    val markerId: MissionMarkerId,
    val systemId: Int,
    val role: MissionMarkerRole,
    val label: String?,
    val notes: String?,
    val color: MarkerColor,
)

data class SystemMarkersDto(
    val systemId: Int,
    val savedMarker: SavedMarkerSummaryDto?,
    val missionMarkers: List<MissionMarkerSummaryDto>,
)

data class CreateSavedMarkerReceipt(
    val marker: SavedMarkerSummaryDto,
)

data class MissionMutationReceipt(
    val missionId: MissionId,
)

interface QueryRequest {
    val requestId: String
}

interface MutationCommand {
    val requestId: String
    val idempotencyKey: String
}

data class SearchSystemsRequest(
    override val requestId: String,
    val query: String,
    val limit: Int = ControlLimits.MAX_SEARCH_RESULTS,
) : QueryRequest

data class GetSystemInfoRequest(override val requestId: String, val systemId: Int) : QueryRequest
data class GetSystemMarkersRequest(override val requestId: String, val systemId: Int) : QueryRequest
data class CalculateNormalRouteRequest(
    override val requestId: String,
    val startSystemId: Int,
    val destinationSystemId: Int?,
    val useAnsiblex: Boolean,
    val useWormholes: Boolean = false,
    val waypointSystemIds: List<Int> = emptyList(),
) : QueryRequest
data class ListWormholesRequest(override val requestId: String) : QueryRequest
data class CalculateCapitalRouteRequest(
    override val requestId: String,
    val startSystemId: Int,
    val destinationSystemId: Int?,
    val effectiveRangeLy: Double,
    val waypointSystemIds: List<Int> = emptyList(),
) : QueryRequest
data class ListViewsRequest(override val requestId: String) : QueryRequest
data class GetCurrentViewRequest(override val requestId: String) : QueryRequest
data class GetActiveMissionsRequest(override val requestId: String, val viewId: String? = null) : QueryRequest
data class GetMissionRequest(override val requestId: String, val missionId: MissionId) : QueryRequest

data class BeginMissionCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val title: String,
    val viewId: String? = null,
) : MutationCommand
data class CreateViewCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val label: String? = null,
) : MutationCommand
data class RenameViewCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val viewId: String,
    val label: String,
) : MutationCommand
data class SwitchViewCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val viewId: String,
) : MutationCommand
data class DeleteViewCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val viewId: String,
) : MutationCommand
data class CreateSavedMarkerCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val systemId: Int,
    val name: String? = null,
    val notes: String? = null,
    val color: MarkerColor,
    val tags: List<SavedMarkerChildType> = emptyList(),
) : MutationCommand
data class FocusSystemCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val systemId: Int,
) : MutationCommand
data class CreateWormholeCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val fromSystemId: Int,
    val toSystemId: Int,
) : MutationCommand
data class ShowNormalRouteCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val missionId: MissionId,
    val startSystemId: Int,
    val destinationSystemId: Int?,
    val useAnsiblex: Boolean,
    val useWormholes: Boolean = false,
    val waypointSystemIds: List<Int> = emptyList(),
) : MutationCommand
data class ShowCapitalRouteCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val missionId: MissionId,
    val startSystemId: Int,
    val destinationSystemId: Int?,
    val effectiveRangeLy: Double,
    val waypointSystemIds: List<Int> = emptyList(),
) : MutationCommand
data class RemoveMissionRouteCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val missionId: MissionId,
    val routeId: MissionRouteId,
) : MutationCommand
data class ClearMissionRoutesCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val missionId: MissionId,
) : MutationCommand
data class ShowJumpRangeCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val missionId: MissionId,
    val originSystemId: Int,
    val effectiveRangeLy: Double,
    val label: String? = null,
) : MutationCommand
data class RemoveJumpRangeCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val missionId: MissionId,
    val jumpRangeId: MissionJumpRangeId,
) : MutationCommand
data class ClearMissionJumpRangesCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val missionId: MissionId,
) : MutationCommand
data class AddMissionMarkerCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val missionId: MissionId,
    val systemId: Int,
    val role: MissionMarkerRole,
    val label: String? = null,
    val notes: String? = null,
    val colorOverride: MarkerColor? = null,
) : MutationCommand
data class RemoveMissionMarkerCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val missionId: MissionId,
    val markerId: MissionMarkerId,
) : MutationCommand
data class ClearMissionMarkersCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val missionId: MissionId,
) : MutationCommand
data class FitMissionCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val missionId: MissionId,
) : MutationCommand
data class ClearMissionCommand(
    override val requestId: String,
    override val idempotencyKey: String,
    val missionId: MissionId,
) : MutationCommand

object ControlLimits {
    const val MAX_ACTIVE_MISSIONS = 4
    const val MAX_ROUTES_PER_MISSION = 4
    const val MAX_JUMP_RANGES_PER_MISSION = 32
    const val MAX_MARKERS_PER_MISSION = 64
    const val MAX_REFERENCED_SYSTEMS_PER_MISSION = 128
    const val MAX_TITLE_CODE_POINTS = 120
    const val MAX_LABEL_CODE_POINTS = 120
    const val MAX_NOTES_CODE_POINTS = 1024
    const val MAX_SEARCH_QUERY_CODE_POINTS = 64
    const val MAX_SEARCH_RESULTS = 20
    const val COMMAND_QUEUE_CAPACITY = 64
    const val MAX_CONCURRENT_EXPENSIVE_QUERIES = 2

    // Technical Control API safety bound, not a statement of current EVE game rules.
    const val MAX_EFFECTIVE_RANGE_LY = 20.0
}
