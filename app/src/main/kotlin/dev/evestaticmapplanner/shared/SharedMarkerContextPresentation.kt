package dev.evestaticmapplanner.shared

import dev.evestaticmapplanner.shared.model.SharedConnectionState
import dev.evestaticmapplanner.shared.model.SharedMapState
import dev.evestaticmapplanner.shared.model.SharedMarker
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole

enum class SharedMarkerContextAction { ADD, OPEN }

data class PresentedSharedMarkerContextAction(
    val action: SharedMarkerContextAction,
    val label: String,
    val enabled: Boolean,
)

internal object SharedMarkerContextPresentationBuilder {
    fun build(marker: SharedMarker?, state: SharedMapState): List<PresentedSharedMarkerContextAction> {
        val canWrite = canWriteSharedMarkers(state)
        if (marker != null) {
            return listOf(
                PresentedSharedMarkerContextAction(
                    action = SharedMarkerContextAction.OPEN,
                    label = if (canWrite) "Shared Marker…" else "View Shared Marker…",
                    enabled = true,
                ),
            )
        }
        if (state.identity?.workspace?.role == SharedWorkspaceRole.VIEWER) return emptyList()
        return listOf(
            PresentedSharedMarkerContextAction(
                action = SharedMarkerContextAction.ADD,
                label = if (canWrite) "Add Shared Marker…" else "Add Shared Marker… (${sharedWriteStatusReason(state)})",
                enabled = canWrite,
            ),
        )
    }
}

internal fun canWriteSharedMarkers(state: SharedMapState): Boolean =
    state.connectionState == SharedConnectionState.ONLINE &&
        state.identity?.workspace?.role in setOf(SharedWorkspaceRole.EDITOR, SharedWorkspaceRole.ADMIN)

internal fun sharedWriteStatusReason(state: SharedMapState): String = when (state.connectionState) {
    SharedConnectionState.ONLINE -> when (state.identity?.workspace?.role) {
        SharedWorkspaceRole.VIEWER -> "viewer access"
        null -> "authentication required"
        else -> "read-only"
    }
    SharedConnectionState.DISCONNECTED -> "not connected"
    SharedConnectionState.CONNECTING -> "connecting"
    SharedConnectionState.DEGRADED -> "temporarily read-only"
    SharedConnectionState.OFFLINE -> "offline"
    SharedConnectionState.AUTH_REQUIRED -> "authentication required"
    SharedConnectionState.FORBIDDEN -> "access removed"
    SharedConnectionState.PROTOCOL_UNSUPPORTED -> "incompatible server"
}
