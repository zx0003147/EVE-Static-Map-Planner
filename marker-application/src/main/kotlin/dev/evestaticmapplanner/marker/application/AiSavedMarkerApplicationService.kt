package dev.evestaticmapplanner.marker.application

import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.core.marker.SavedMarkerCreatedBy
import dev.evestaticmapplanner.core.repository.UniverseRepository

enum class AiSavedMarkerCapability {
    READ_SAVED_MARKERS,
    CREATE_SAVED_MARKERS,
}

fun interface AiSavedMarkerPermissionPolicy {
    fun isAllowed(capability: AiSavedMarkerCapability): Boolean
}

enum class AiSavedMarkerErrorCode {
    CAPABILITY_DENIED,
    MARKER_ALREADY_EXISTS,
    SYSTEM_NOT_FOUND,
    INVALID_ARGUMENT,
    DATABASE_UNAVAILABLE,
    INVALID_MARKER_DATA,
    INTERNAL_FAILURE,
}

data class AiSavedMarkerError(
    val code: AiSavedMarkerErrorCode,
    val message: String,
)

sealed interface AiSavedMarkerResult<out T> {
    data class Success<T>(val value: T) : AiSavedMarkerResult<T>
    data class Failure(val error: AiSavedMarkerError) : AiSavedMarkerResult<Nothing>
}

data class AiSavedMarkerChildSummary(
    val id: String,
    val type: SavedMarkerChildType,
    val orderIndex: Int,
)

data class AiSavedMarkerSummary(
    val systemId: Int,
    val name: String?,
    val color: MarkerColor,
    val notes: String?,
    val children: List<AiSavedMarkerChildSummary>,
    val createdBy: SavedMarkerCreatedBy,
)

data class AiSavedMarkerCreateRequest(
    val systemId: Int,
    val name: String? = null,
    val notes: String? = null,
    val color: MarkerColor = MarkerColor.YELLOW,
    val children: List<SavedMarkerChildType> = emptyList(),
)

/**
 * Narrow application boundary intended for future AI Control consumers.
 *
 * It deliberately exposes only read and create operations. Existing marker mutation APIs stay private to
 * trusted UI consumers through [SavedMarkerService].
 */
class AiSavedMarkerApplicationService(
    private val savedMarkerService: SavedMarkerService,
    private val universeRepository: UniverseRepository,
    private val permissionPolicy: AiSavedMarkerPermissionPolicy,
) {
    fun getSystemMarker(systemId: Int): AiSavedMarkerResult<AiSavedMarkerSummary?> {
        deniedUnless(AiSavedMarkerCapability.READ_SAVED_MARKERS)?.let { return it }
        validateSystemId(systemId)?.let { return it }
        requireExistingSystem(systemId)?.let { return it }
        databaseAvailabilityFailure()?.let { return it }

        val marker = savedMarkerService.get(systemId) ?: return AiSavedMarkerResult.Success(null)
        return AiSavedMarkerResult.Success(marker.toSummary(childrenFor(systemId)))
    }

    suspend fun createSavedMarker(
        request: AiSavedMarkerCreateRequest,
    ): AiSavedMarkerResult<AiSavedMarkerSummary> {
        deniedUnless(AiSavedMarkerCapability.CREATE_SAVED_MARKERS)?.let { return it }
        validateSystemId(request.systemId)?.let { return it }
        requireExistingSystem(request.systemId)?.let { return it }
        databaseAvailabilityFailure()?.let { return it }

        val draft = runCatching { MarkerDraft.create(request.name, request.notes, request.color) }
            .getOrElse {
                return failure(AiSavedMarkerErrorCode.INVALID_MARKER_DATA, it.safeMessage("Saved marker data is invalid"))
            }
        return try {
            val initialChildren = SavedMarkerChildType.normalizeSupported(request.children)
            val marker = savedMarkerService.create(
                systemId = request.systemId,
                draft = draft,
                initialChildTypes = initialChildren,
                createdBy = SavedMarkerCreatedBy.AI,
            )
            AiSavedMarkerResult.Success(marker.toSummary(childrenFor(request.systemId)))
        } catch (_: SavedMarkerAlreadyExistsException) {
            failure(
                AiSavedMarkerErrorCode.MARKER_ALREADY_EXISTS,
                "A saved marker already exists for solar system ${request.systemId}",
            )
        } catch (error: IllegalArgumentException) {
            failure(AiSavedMarkerErrorCode.INVALID_MARKER_DATA, error.safeMessage("Saved marker data is invalid"))
        } catch (error: IllegalStateException) {
            if (savedMarkerService.state.value.databaseError != null) {
                failure(AiSavedMarkerErrorCode.DATABASE_UNAVAILABLE, "Saved marker database is unavailable")
            } else {
                failure(AiSavedMarkerErrorCode.INTERNAL_FAILURE, error.safeMessage("Saved marker creation failed"))
            }
        } catch (error: Throwable) {
            failure(AiSavedMarkerErrorCode.INTERNAL_FAILURE, error.safeMessage("Saved marker creation failed"))
        }
    }

    private fun deniedUnless(capability: AiSavedMarkerCapability): AiSavedMarkerResult.Failure? =
        if (permissionPolicy.isAllowed(capability)) {
            null
        } else {
            failure(AiSavedMarkerErrorCode.CAPABILITY_DENIED, "AI Saved Marker capability is denied")
        }

    private fun validateSystemId(systemId: Int): AiSavedMarkerResult.Failure? =
        if (systemId > 0) {
            null
        } else {
            failure(AiSavedMarkerErrorCode.INVALID_ARGUMENT, "Solar system ID must be positive")
        }

    private fun requireExistingSystem(systemId: Int): AiSavedMarkerResult.Failure? = try {
        if (universeRepository.getSystem(systemId) == null) {
            failure(AiSavedMarkerErrorCode.SYSTEM_NOT_FOUND, "Solar system was not found")
        } else {
            null
        }
    } catch (error: Throwable) {
        failure(AiSavedMarkerErrorCode.INTERNAL_FAILURE, error.safeMessage("Solar system lookup failed"))
    }

    private fun databaseAvailabilityFailure(): AiSavedMarkerResult.Failure? {
        val state = savedMarkerService.state.value
        return when {
            state.databaseError != null -> failure(
                AiSavedMarkerErrorCode.DATABASE_UNAVAILABLE,
                "Saved marker database is unavailable",
            )
            state.isLoading -> failure(
                AiSavedMarkerErrorCode.DATABASE_UNAVAILABLE,
                "Saved marker database is not ready",
            )
            else -> null
        }
    }

    private fun childrenFor(systemId: Int): List<SavedMarkerChild> =
        savedMarkerService.state.value.childrenByParentSystemId[systemId].orEmpty()
}

private fun Marker.toSummary(children: List<SavedMarkerChild>): AiSavedMarkerSummary = AiSavedMarkerSummary(
    systemId = systemId,
    name = name,
    color = color,
    notes = notes,
    children = children.map { child ->
        AiSavedMarkerChildSummary(child.id, child.type, child.orderIndex)
    },
    createdBy = checkNotNull(createdBy),
)

private fun failure(code: AiSavedMarkerErrorCode, message: String) =
    AiSavedMarkerResult.Failure(AiSavedMarkerError(code, message))

private fun Throwable.safeMessage(fallback: String): String = message?.takeIf(String::isNotBlank) ?: fallback
