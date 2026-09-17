package dev.evestaticmapplanner.localization

interface SystemInfoStrings {
    val selectedSystem: String
    val noSystemSelected: String
    val loadingSystemDetails: String
    val systemId: String
    val region: String
    val constellation: String
    val securityStatus: String
    val stargates: String
    val ansiblex: String
    val jumpCoverage: String
    val ansiblexConnections: String
    val jumpOverlays: String
    val inSelectedOverlayIntersection: String
    val marker: String
    val sharedMarker: String
    val color: String
    val tags: String
    val notes: String
    val sharedMapDataMayBeStale: String
    val editSharedMarker: String
    val saved: String
    val temporary: String
    val bidirectional: String
    val outbound: String
    val inbound: String

    fun regionValue(value: String): String
    fun constellationValue(value: String): String
    fun updatedBy(displayName: String): String
    fun fallbackSystem(systemId: Int): String
}
