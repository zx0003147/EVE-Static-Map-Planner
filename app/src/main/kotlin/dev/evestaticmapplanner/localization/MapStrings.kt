package dev.evestaticmapplanner.localization

import dev.evestaticmapplanner.core.map.MapProjectionId

interface MapStrings {
    val loadingStaticUniverse: String
    val unableToLoadMap: String
    val database: String
    val fitMap: String
    val resetView: String
    val renameView: String
    val viewName: String
    val viewNameValidation: String
    val toggleProjection: String
    val openEmbeddedAiAssistant: String
    val collapseSidebar: String
    val expandSidebar: String
    val official2DSelected: String
    val real3DSelected: String

    val addTemporaryMarker: String
    val addSavedMarker: String
    val editMarker: String
    val savePermanently: String
    val removeMarker: String
    val markersUnavailable: String
    val addSharedMarker: String
    val openSharedMarker: String
    val viewSharedMarker: String
    val addJumpRangeOverlay: String
    val setNormalStart: String
    val addNormalWaypoint: String
    val setNormalDestination: String
    val setCapitalStart: String
    val addCapitalWaypoint: String
    val setCapitalDestination: String
    val createWormholeConnection: String
    val wormholeConnections: String
    val viewerAccess: String
    val authenticationRequired: String
    val readOnly: String
    val notConnected: String
    val connecting: String
    val temporarilyReadOnly: String
    val offline: String
    val accessRemoved: String
    val incompatibleServer: String

    fun projectionLabel(projectionId: MapProjectionId): String
    fun fallbackSystem(systemId: Int): String
    fun mapSummary(projection: String, systems: Int, stargateConnections: Int): String
    fun unavailableSystems(count: Int): String
    fun routeUnavailable(systemCount: Int, legCount: Int): String
    fun jumpOverlayUnavailable(count: Int): String
    fun capitalRouteUnavailable(count: Int): String
    fun wormholesUnavailable(count: Int): String
    fun focusSwitchedToReal3D(systemName: String): String
    fun wormholeConnections(count: Int): String
    fun sharedMarkerUnavailable(reason: String): String
}
