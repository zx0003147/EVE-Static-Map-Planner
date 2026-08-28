package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.marker.application.AiSavedMarkerApplicationService
import dev.evestaticmapplanner.marker.application.AiSavedMarkerCreateRequest
import dev.evestaticmapplanner.marker.application.AiSavedMarkerErrorCode
import dev.evestaticmapplanner.marker.application.AiSavedMarkerResult
import dev.evestaticmapplanner.marker.application.AiSavedMarkerSummary

class AiSavedMarkerControlAdapter(
    private val applicationService: AiSavedMarkerApplicationService,
) : SavedMarkerControlPort {
    override suspend fun getSystemMarker(systemId: Int): SavedMarkerSummaryDto? =
        applicationService.getSystemMarker(systemId).requireMarkerQuerySuccess()

    override suspend fun createSavedMarker(request: SavedMarkerCreatePortRequest): SavedMarkerSummaryDto =
        applicationService.createSavedMarker(
            AiSavedMarkerCreateRequest(
                systemId = request.systemId,
                name = request.name,
                notes = request.notes,
                color = request.color,
                children = request.tags,
            ),
        ).requireMarkerCreateSuccess()
}

private fun AiSavedMarkerSummary.toControlDto() = SavedMarkerSummaryDto(
    systemId = systemId,
    name = name,
    color = color,
    notes = notes,
    children = children.map { child ->
        SavedMarkerChildSummaryDto(
            id = child.id,
            type = child.type.key,
            orderIndex = child.orderIndex,
        )
    },
    createdBy = createdBy,
)

private fun AiSavedMarkerErrorCode.toControlCode(): ControlErrorCode = when (this) {
    AiSavedMarkerErrorCode.CAPABILITY_DENIED -> ControlErrorCode.CAPABILITY_DENIED
    AiSavedMarkerErrorCode.MARKER_ALREADY_EXISTS -> ControlErrorCode.MARKER_ALREADY_EXISTS
    AiSavedMarkerErrorCode.SYSTEM_NOT_FOUND -> ControlErrorCode.SYSTEM_NOT_FOUND
    AiSavedMarkerErrorCode.INVALID_ARGUMENT -> ControlErrorCode.INVALID_ARGUMENT
    AiSavedMarkerErrorCode.INVALID_MARKER_DATA -> ControlErrorCode.INVALID_MARKER_DATA
    AiSavedMarkerErrorCode.DATABASE_UNAVAILABLE -> ControlErrorCode.DATABASE_UNAVAILABLE
    AiSavedMarkerErrorCode.INTERNAL_FAILURE -> ControlErrorCode.INTERNAL_ERROR
}

private fun AiSavedMarkerResult<AiSavedMarkerSummary?>.requireMarkerQuerySuccess(): SavedMarkerSummaryDto? = when (this) {
    is AiSavedMarkerResult.Success -> value?.toControlDto()
    is AiSavedMarkerResult.Failure -> throw ControlPortFailure(error.code.toControlCode(), error.message)
}

private fun AiSavedMarkerResult<AiSavedMarkerSummary>.requireMarkerCreateSuccess(): SavedMarkerSummaryDto = when (this) {
    is AiSavedMarkerResult.Success -> value.toControlDto()
    is AiSavedMarkerResult.Failure -> throw ControlPortFailure(error.code.toControlCode(), error.message)
}
