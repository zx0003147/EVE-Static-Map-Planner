package dev.evestaticmapplanner.localization.en

import dev.evestaticmapplanner.core.map.MapProjectionId
import dev.evestaticmapplanner.localization.MapStrings

internal object EnglishMapStrings : MapStrings {
    override val loadingStaticUniverse = "Loading static universe…"
    override val unableToLoadMap = "Unable to load map"
    override val database = "Database"
    override val fitMap = "Fit Map"
    override val resetView = "Reset View"
    override val renameView = "Rename View"
    override val viewName = "View name"
    override val viewNameValidation = "View names must be non-empty and unique."
    override val toggleProjection = "Toggle 2D/3D map mode"
    override val openEmbeddedAiAssistant = "Open Embedded AI Assistant"
    override val collapseSidebar = "Collapse sidebar"
    override val expandSidebar = "Expand sidebar"
    override val official2DSelected = "Official 2D selected"
    override val real3DSelected = "Real 3D selected"
    override val mapOverlays = "Map overlays"
    override val addTemporaryMarker = "Add Temporary Marker"
    override val addSavedMarker = "Add Saved Marker…"
    override val editMarker = "Edit Marker…"
    override val savePermanently = "Save Permanently…"
    override val removeMarker = "Remove Marker"
    override val markersUnavailable = "Markers unavailable"
    override val addSharedMarker = "Add Shared Marker…"
    override val openSharedMarker = "Shared Marker…"
    override val viewSharedMarker = "View Shared Marker…"
    override val addJumpRangeOverlay = "Add Jump Range Overlay"
    override val setNormalStart = "Set as Normal Start"
    override val addNormalWaypoint = "Add as Normal Waypoint"
    override val setNormalDestination = "Set as Normal Destination"
    override val setCapitalStart = "Set as Capital Start"
    override val addCapitalWaypoint = "Add as Capital Waypoint"
    override val setCapitalDestination = "Set as Capital Destination"
    override val createWormholeConnection = "Create Wormhole Connection…"
    override val wormholeConnections = "Wormhole Connections…"
    override val viewerAccess = "viewer access"
    override val authenticationRequired = "authentication required"
    override val readOnly = "read-only"
    override val notConnected = "not connected"
    override val connecting = "connecting"
    override val temporarilyReadOnly = "temporarily read-only"
    override val offline = "offline"
    override val accessRemoved = "access removed"
    override val incompatibleServer = "incompatible server"

    override fun projectionLabel(projectionId: MapProjectionId): String = when (projectionId) {
        MapProjectionId.OFFICIAL_2D -> "Official 2D"
        MapProjectionId.REAL_3D -> "Real 3D"
    }

    override fun fallbackSystem(systemId: Int) = "System $systemId"
    override fun mapSummary(projection: String, systems: Int, stargateConnections: Int) =
        "$projection: $systems systems · $stargateConnections stargate connections"
    override fun unavailableSystems(count: Int) = "$count unavailable"
    override fun routeUnavailable(systemCount: Int, legCount: Int) =
        "route: $systemCount systems / $legCount legs unavailable; use Real 3D"
    override fun jumpOverlayUnavailable(count: Int) = "jump overlay: $count unavailable"
    override fun capitalRouteUnavailable(count: Int) = "capital route: $count legs unavailable"
    override fun wormholesUnavailable(count: Int) = "wormholes: $count unavailable"
    override fun focusSwitchedToReal3D(systemName: String) =
        "$systemName is unavailable in Official 2D; switched to Real 3D."
    override fun wormholeConnections(count: Int) = "Wormhole Connections… ($count)"
    override fun sharedMarkerUnavailable(reason: String) = "Add Shared Marker… ($reason)"
}
