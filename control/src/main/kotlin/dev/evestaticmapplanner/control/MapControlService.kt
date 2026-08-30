package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.control.mission.Mission

interface MapControlService {
    suspend fun searchSystems(request: SearchSystemsRequest): ControlResult<List<SystemSummaryDto>>
    suspend fun getSystemInfo(request: GetSystemInfoRequest): ControlResult<SystemInfoDto>
    suspend fun getSystemMarkers(request: GetSystemMarkersRequest): ControlResult<SystemMarkersDto>
    suspend fun calculateNormalRoute(request: CalculateNormalRouteRequest): ControlResult<NormalRouteDto>
    suspend fun calculateCapitalRoute(request: CalculateCapitalRouteRequest): ControlResult<CapitalRouteDto>
    suspend fun listViews(request: ListViewsRequest): ControlResult<List<PlanningViewDto>> = unsupported(request.requestId)
    suspend fun getCurrentView(request: GetCurrentViewRequest): ControlResult<PlanningViewDto> = unsupported(request.requestId)
    suspend fun getActiveMissions(request: GetActiveMissionsRequest): ControlResult<List<MissionSummaryDto>>
    suspend fun getMission(request: GetMissionRequest): ControlResult<Mission>

    suspend fun beginMission(command: BeginMissionCommand): ControlResult<MissionSummaryDto>
    suspend fun createView(command: CreateViewCommand): ControlResult<PlanningViewDto> = unsupported(command.requestId)
    suspend fun renameView(command: RenameViewCommand): ControlResult<PlanningViewDto> = unsupported(command.requestId)
    suspend fun switchView(command: SwitchViewCommand): ControlResult<PlanningViewDto> = unsupported(command.requestId)
    suspend fun deleteView(command: DeleteViewCommand): ControlResult<PlanningViewDto> = unsupported(command.requestId)
    suspend fun createSavedMarker(command: CreateSavedMarkerCommand): ControlResult<CreateSavedMarkerReceipt>
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

private fun <T> unsupported(requestId: String): ControlResult<T> = ControlResult.Failure(
    requestId,
    ControlError(ControlErrorCode.INVALID_ARGUMENT, "Planning Views are not supported by this host"),
)
