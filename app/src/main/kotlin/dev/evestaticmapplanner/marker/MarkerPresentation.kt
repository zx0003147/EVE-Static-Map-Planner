package dev.evestaticmapplanner.marker

import androidx.compose.ui.graphics.Color
import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.shared.PresentedSharedMarkerContextAction
import dev.evestaticmapplanner.shared.SharedMarkerContextAction
import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppStringsCatalog
import dev.evestaticmapplanner.localization.MapStrings

enum class MarkerContextAction {
    ADD_TEMPORARY,
    ADD_SAVED,
    EDIT,
    SAVE_PERMANENTLY,
    REMOVE,
    UNAVAILABLE,
}

data class PresentedMarkerContextAction(
    val action: MarkerContextAction,
    val enabled: Boolean,
)

enum class SystemContextAction {
    ADD_TEMPORARY_MARKER,
    ADD_SAVED_MARKER,
    EDIT_MARKER,
    SAVE_MARKER_PERMANENTLY,
    REMOVE_MARKER,
    MARKERS_UNAVAILABLE,
    ADD_SHARED_MARKER,
    OPEN_SHARED_MARKER,
    ADD_JUMP_RANGE_OVERLAY,
    SET_ROUTE_START,
    ADD_ROUTE_WAYPOINT,
    SET_ROUTE_DESTINATION,
    SET_CAPITAL_START,
    ADD_CAPITAL_WAYPOINT,
    SET_CAPITAL_DESTINATION,
    CREATE_WORMHOLE,
    MANAGE_WORMHOLE_CONNECTIONS,
}

data class PresentedSystemContextAction(
    val action: SystemContextAction,
    val enabled: Boolean = true,
    val label: String,
    val startsNewSection: Boolean = false,
)

object SystemContextMenuPresentationBuilder {
    fun build(
        marker: Marker?,
        state: MarkerUiState,
        sharedActions: List<PresentedSharedMarkerContextAction> = emptyList(),
        wormholeConnectionCount: Int = 0,
        strings: MapStrings = AppStringsCatalog.forLocale(AppLocale.EN_US).map,
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
                label = strings.contextActionLabel(
                    when (item.action) {
                        MarkerContextAction.ADD_TEMPORARY -> SystemContextAction.ADD_TEMPORARY_MARKER
                        MarkerContextAction.ADD_SAVED -> SystemContextAction.ADD_SAVED_MARKER
                        MarkerContextAction.EDIT -> SystemContextAction.EDIT_MARKER
                        MarkerContextAction.SAVE_PERMANENTLY -> SystemContextAction.SAVE_MARKER_PERMANENTLY
                        MarkerContextAction.REMOVE -> SystemContextAction.REMOVE_MARKER
                        MarkerContextAction.UNAVAILABLE -> SystemContextAction.MARKERS_UNAVAILABLE
                    },
                ),
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
            PresentedSystemContextAction(
                action = SystemContextAction.ADD_JUMP_RANGE_OVERLAY,
                label = strings.addJumpRangeOverlay,
                startsNewSection = true,
            ),
            PresentedSystemContextAction(
                action = SystemContextAction.SET_ROUTE_START,
                label = strings.setNormalStart,
                startsNewSection = true,
            ),
            PresentedSystemContextAction(SystemContextAction.ADD_ROUTE_WAYPOINT, label = strings.addNormalWaypoint),
            PresentedSystemContextAction(SystemContextAction.SET_ROUTE_DESTINATION, label = strings.setNormalDestination),
            PresentedSystemContextAction(
                action = SystemContextAction.SET_CAPITAL_START,
                label = strings.setCapitalStart,
                startsNewSection = true,
            ),
            PresentedSystemContextAction(SystemContextAction.ADD_CAPITAL_WAYPOINT, label = strings.addCapitalWaypoint),
            PresentedSystemContextAction(SystemContextAction.SET_CAPITAL_DESTINATION, label = strings.setCapitalDestination),
        ) + listOf(
            PresentedSystemContextAction(
                action = SystemContextAction.CREATE_WORMHOLE,
                label = strings.createWormholeConnection,
                startsNewSection = true,
            ),
        ) + if (wormholeConnectionCount > 0) {
            listOf(
                PresentedSystemContextAction(
                    action = SystemContextAction.MANAGE_WORMHOLE_CONNECTIONS,
                    label = strings.wormholeConnections(wormholeConnectionCount),
                ),
            )
        } else {
            emptyList()
        }
}

private fun MapStrings.contextActionLabel(action: SystemContextAction): String = when (action) {
    SystemContextAction.ADD_TEMPORARY_MARKER -> addTemporaryMarker
    SystemContextAction.ADD_SAVED_MARKER -> addSavedMarker
    SystemContextAction.EDIT_MARKER -> editMarker
    SystemContextAction.SAVE_MARKER_PERMANENTLY -> savePermanently
    SystemContextAction.REMOVE_MARKER -> removeMarker
    SystemContextAction.MARKERS_UNAVAILABLE -> markersUnavailable
    SystemContextAction.ADD_SHARED_MARKER -> addSharedMarker
    SystemContextAction.OPEN_SHARED_MARKER -> openSharedMarker
    SystemContextAction.ADD_JUMP_RANGE_OVERLAY -> addJumpRangeOverlay
    SystemContextAction.SET_ROUTE_START -> setNormalStart
    SystemContextAction.ADD_ROUTE_WAYPOINT -> addNormalWaypoint
    SystemContextAction.SET_ROUTE_DESTINATION -> setNormalDestination
    SystemContextAction.SET_CAPITAL_START -> setCapitalStart
    SystemContextAction.ADD_CAPITAL_WAYPOINT -> addCapitalWaypoint
    SystemContextAction.SET_CAPITAL_DESTINATION -> setCapitalDestination
    SystemContextAction.CREATE_WORMHOLE -> createWormholeConnection
    SystemContextAction.MANAGE_WORMHOLE_CONNECTIONS -> wormholeConnections
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
