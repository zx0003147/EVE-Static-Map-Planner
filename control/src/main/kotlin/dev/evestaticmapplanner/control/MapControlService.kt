package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.control.mission.Mission

interface MapControlService {
    suspend fun searchSystems(request: SearchSystemsRequest): ControlResult<List<SystemSummaryDto>>
    suspend fun getSystemInfo(request: GetSystemInfoRequest): ControlResult<SystemInfoDto>
    suspend fun calculateNormalRoute(request: CalculateNormalRouteRequest): ControlResult<NormalRouteDto>
    suspend fun calculateCapitalRoute(request: CalculateCapitalRouteRequest): ControlResult<CapitalRouteDto>
    suspend fun getActiveMissions(request: GetActiveMissionsRequest): ControlResult<List<MissionSummaryDto>>
    suspend fun getMission(request: GetMissionRequest): ControlResult<Mission>

    suspend fun beginMission(command: BeginMissionCommand): ControlResult<MissionSummaryDto>
    suspend fun focusSystem(command: FocusSystemCommand): ControlResult<SystemSummaryDto>
    suspend fun showNormalRoute(command: ShowNormalRouteCommand): ControlResult<MissionRouteReceipt>
    suspend fun showCapitalRoute(command: ShowCapitalRouteCommand): ControlResult<MissionRouteReceipt>
    suspend fun removeMissionRoute(command: RemoveMissionRouteCommand): ControlResult<MissionMutationReceipt>
    suspend fun clearMissionRoutes(command: ClearMissionRoutesCommand): ControlResult<MissionMutationReceipt>
    suspend fun showJumpRange(command: ShowJumpRangeCommand): ControlResult<MissionJumpRangeReceipt>
    suspend fun removeJumpRange(command: RemoveJumpRangeCommand): ControlResult<MissionMutationReceipt>
    suspend fun clearMissionJumpRanges(command: ClearMissionJumpRangesCommand): ControlResult<MissionMutationReceipt>
    suspend fun addMissionMarker(command: AddMissionMarkerCommand): ControlResult<MissionMarkerReceipt>
    suspend fun removeMissionMarker(command: RemoveMissionMarkerCommand): ControlResult<MissionMutationReceipt>
    suspend fun clearMissionMarkers(command: ClearMissionMarkersCommand): ControlResult<MissionMutationReceipt>
    suspend fun fitMission(command: FitMissionCommand): ControlResult<MissionMutationReceipt>
    suspend fun clearMission(command: ClearMissionCommand): ControlResult<MissionMutationReceipt>
}
