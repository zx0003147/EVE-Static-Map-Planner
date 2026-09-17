package dev.evestaticmapplanner.shared

import dev.evestaticmapplanner.shared.model.SharedConnectionState
import dev.evestaticmapplanner.shared.model.SharedMapState
import dev.evestaticmapplanner.shared.model.SharedMarker
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole
import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppStringsCatalog
import dev.evestaticmapplanner.localization.MapStrings

enum class SharedMarkerContextAction { ADD, OPEN }

data class PresentedSharedMarkerContextAction(
    val action: SharedMarkerContextAction,
    val label: String,
    val enabled: Boolean,
)

internal object SharedMarkerContextPresentationBuilder {
    fun build(
        marker: SharedMarker?,
        state: SharedMapState,
        strings: MapStrings = AppStringsCatalog.forLocale(AppLocale.EN_US).map,
    ): List<PresentedSharedMarkerContextAction> {
        val canWrite = canWriteSharedMarkers(state)
        if (marker != null) {
            return listOf(
                PresentedSharedMarkerContextAction(
                    action = SharedMarkerContextAction.OPEN,
                    label = if (canWrite) strings.openSharedMarker else strings.viewSharedMarker,
                    enabled = true,
                ),
            )
        }
        if (state.identity?.workspace?.role == SharedWorkspaceRole.VIEWER) return emptyList()
        return listOf(
            PresentedSharedMarkerContextAction(
                action = SharedMarkerContextAction.ADD,
                label = if (canWrite) strings.addSharedMarker else {
                    strings.sharedMarkerUnavailable(sharedWriteStatusReason(state, strings))
                },
                enabled = canWrite,
            ),
        )
    }
}

internal fun canWriteSharedMarkers(state: SharedMapState): Boolean =
    state.connectionState == SharedConnectionState.ONLINE &&
        state.identity?.workspace?.role in setOf(SharedWorkspaceRole.EDITOR, SharedWorkspaceRole.ADMIN)

internal fun sharedWriteStatusReason(state: SharedMapState, strings: MapStrings): String = when (state.connectionState) {
    SharedConnectionState.ONLINE -> when (state.identity?.workspace?.role) {
        SharedWorkspaceRole.VIEWER -> strings.viewerAccess
        null -> strings.authenticationRequired
        else -> strings.readOnly
    }
    SharedConnectionState.DISCONNECTED -> strings.notConnected
    SharedConnectionState.CONNECTING -> strings.connecting
    SharedConnectionState.DEGRADED -> strings.temporarilyReadOnly
    SharedConnectionState.OFFLINE -> strings.offline
    SharedConnectionState.AUTH_REQUIRED -> strings.authenticationRequired
    SharedConnectionState.FORBIDDEN -> strings.accessRemoved
    SharedConnectionState.PROTOCOL_UNSUPPORTED -> strings.incompatibleServer
}
