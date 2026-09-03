package dev.evestaticmapplanner.marker

import androidx.compose.ui.graphics.Color
import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.shared.PresentedSharedMarkerContextAction
import dev.evestaticmapplanner.shared.SharedMarkerContextAction

enum class MarkerContextAction(val label: String) {
    ADD_TEMPORARY("Add Temporary Marker"),
    ADD_SAVED("Add Saved Marker…"),
    EDIT("Edit Marker…"),
    SAVE_PERMANENTLY("Save Permanently…"),
    REMOVE("Remove Marker"),
    UNAVAILABLE("Markers unavailable"),
}

data class PresentedMarkerContextAction(
    val action: MarkerContextAction,
    val enabled: Boolean,
)

enum class SystemContextAction(val label: String) {
    ADD_TEMPORARY_MARKER("Add Temporary Marker"),
    ADD_SAVED_MARKER("Add Saved Marker…"),
    EDIT_MARKER("Edit Marker…"),
    SAVE_MARKER_PERMANENTLY("Save Permanently…"),
    REMOVE_MARKER("Remove Marker"),
    MARKERS_UNAVAILABLE("Markers unavailable"),
    ADD_SHARED_MARKER("Add Shared Marker…"),
    OPEN_SHARED_MARKER("Shared Marker…"),
    ADD_JUMP_RANGE_OVERLAY("Add Jump Range Overlay"),
    SET_ROUTE_START("Set as Normal Start"),
    ADD_ROUTE_WAYPOINT("Add as Normal Waypoint"),
    SET_ROUTE_DESTINATION("Set as Normal Destination"),
    SET_CAPITAL_START("Set as Capital Start"),
    ADD_CAPITAL_WAYPOINT("Add as Capital Waypoint"),
    SET_CAPITAL_DESTINATION("Set as Capital Destination"),
    CREATE_WORMHOLE("Create Wormhole Connection…"),
    MANAGE_WORMHOLE_CONNECTIONS("Wormhole Connections…"),
}

data class PresentedSystemContextAction(
    val action: SystemContextAction,
    val enabled: Boolean = true,
    val label: String = action.label,
    val startsNewSection: Boolean = false,
)

object SystemContextMenuPresentationBuilder {
    fun build(
        marker: Marker?,
        state: MarkerUiState,
        sharedActions: List<PresentedSharedMarkerContextAction> = emptyList(),
        wormholeConnectionCount: Int = 0,
    ): List<PresentedSystemContextAction> =
        MarkerContextPresentationBuilder.build(marker, state).map { item ->
            PresentedSystemContextAction(
                action = when (item.action) {
                    MarkerContextAction.ADD_TEMPORARY -> SystemContextAction.ADD_TEMPORARY_MARKER
                    MarkerContextAction.ADD_SAVED -> SystemContextAction.ADD_SAVED_MARKER
                    MarkerContextAction.EDIT -> SystemContextAction.EDIT_MARKER
                    MarkerContextAction.SAVE_PERMANENTLY -> SystemContextAction.SAVE_MARKER_PERMANENTLY
                    MarkerContextAction.REMOVE -> SystemContextAction.REMOVE_MARKER
                    MarkerContextAction.UNAVAILABLE -> SystemContextAction.MARKERS_UNAVAILABLE
                },
                enabled = item.enabled,
            )
        } + sharedActions.map { item ->
            PresentedSystemContextAction(
                action = when (item.action) {
                    SharedMarkerContextAction.ADD -> SystemContextAction.ADD_SHARED_MARKER
                    SharedMarkerContextAction.OPEN -> SystemContextAction.OPEN_SHARED_MARKER
                },
                enabled = item.enabled,
                label = item.label,
            )
        } + listOf(
            PresentedSystemContextAction(SystemContextAction.ADD_JUMP_RANGE_OVERLAY),
            PresentedSystemContextAction(SystemContextAction.SET_ROUTE_START),
            PresentedSystemContextAction(SystemContextAction.ADD_ROUTE_WAYPOINT),
            PresentedSystemContextAction(SystemContextAction.SET_ROUTE_DESTINATION),
            PresentedSystemContextAction(SystemContextAction.SET_CAPITAL_START),
            PresentedSystemContextAction(SystemContextAction.ADD_CAPITAL_WAYPOINT),
            PresentedSystemContextAction(SystemContextAction.SET_CAPITAL_DESTINATION),
        ) + listOf(
            PresentedSystemContextAction(
                action = SystemContextAction.CREATE_WORMHOLE,
                startsNewSection = true,
            ),
        ) + if (wormholeConnectionCount > 0) {
            listOf(
                PresentedSystemContextAction(
                    action = SystemContextAction.MANAGE_WORMHOLE_CONNECTIONS,
                    label = "Wormhole Connections… ($wormholeConnectionCount)",
                ),
            )
        } else {
            emptyList()
        }
}

object MarkerContextPresentationBuilder {
    fun build(marker: Marker?, state: MarkerUiState): List<PresentedMarkerContextAction> {
        if (!state.canCreateMarkers) {
            return listOf(PresentedMarkerContextAction(MarkerContextAction.UNAVAILABLE, enabled = false))
        }
        val busy = marker?.systemId in state.busySystemIds
        return when (marker?.persistence) {
            null -> listOf(
                PresentedMarkerContextAction(MarkerContextAction.ADD_TEMPORARY, enabled = true),
                PresentedMarkerContextAction(MarkerContextAction.ADD_SAVED, enabled = true),
            )
            MarkerPersistence.TEMPORARY -> listOf(
                PresentedMarkerContextAction(MarkerContextAction.EDIT, enabled = !busy),
                PresentedMarkerContextAction(MarkerContextAction.SAVE_PERMANENTLY, enabled = !busy),
                PresentedMarkerContextAction(MarkerContextAction.REMOVE, enabled = !busy),
            )
            MarkerPersistence.SAVED -> listOf(
                PresentedMarkerContextAction(MarkerContextAction.EDIT, enabled = !busy),
                PresentedMarkerContextAction(MarkerContextAction.REMOVE, enabled = !busy),
            )
        }
    }
}

internal fun markerColor(color: MarkerColor): Color = when (color) {
    MarkerColor.RED -> Color(0xFFFF5D73)
    MarkerColor.ORANGE -> Color(0xFFFF9F43)
    MarkerColor.YELLOW -> Color(0xFFFFD166)
    MarkerColor.GREEN -> Color(0xFF57E389)
    MarkerColor.BLUE -> Color(0xFF42BFF5)
    MarkerColor.PURPLE -> Color(0xFFA98BFF)
    MarkerColor.WHITE -> Color(0xFFF1F5F8)
}
