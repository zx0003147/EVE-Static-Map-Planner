package dev.evestaticmapplanner.localization.en

import dev.evestaticmapplanner.localization.SystemInfoStrings
import dev.evestaticmapplanner.core.sovereignty.SovereigntyFreshness
import dev.evestaticmapplanner.core.sovereignty.SovereigntyStatus
import dev.evestaticmapplanner.core.sovereignty.SystemOwnerKind

internal object EnglishSystemInfoStrings : SystemInfoStrings {
    override val selectedSystem = "Selected System"
    override val noSystemSelected = "No system selected"
    override val loadingSystemDetails = "Loading system details…"
    override val systemId = "System ID"
    override val region = "Region"
    override val constellation = "Constellation"
    override val securityStatus = "Security Status"
    override val stargates = "Stargates"
    override val ansiblex = "Ansiblex"
    override val jumpCoverage = "Jump Coverage"
    override val ansiblexConnections = "Ansiblex Connections"
    override val jumpOverlays = "Jump Overlays"
    override val inSelectedOverlayIntersection = "In selected overlay intersection"
    override val marker = "Marker"
    override val sharedMarker = "Shared Marker"
    override val color = "Color"
    override val tags = "Tags"
    override val notes = "Notes"
    override val sharedMapDataMayBeStale = "Shared Map data may be stale"
    override val editSharedMarker = "Edit Shared Marker"
    override val saved = "Saved"
    override val temporary = "Temporary"
    override val bidirectional = "Bidirectional"
    override val outbound = "Outbound"
    override val inbound = "Inbound"
    override val available = "Available"
    override val unavailable = "Unavailable"
    override val ownerUnknown = "Owner unknown"
    override val sovereignty = "Sovereignty"
    override val sovereigntyOwnerKind = "Owner Kind"
    override val sovereigntyAlliance = "Alliance"
    override val sovereigntyAllianceId = "Alliance ID"
    override val sovereigntyCorporation = "Corporation"
    override val sovereigntyCorporationId = "Corporation ID"
    override val sovereigntyStatus = "Status"
    override val sovereigntyFreshness = "Freshness"
    override fun regionValue(value: String) = "Region: $value"
    override fun constellationValue(value: String) = "Constellation: $value"
    override fun updatedBy(displayName: String) = "Updated by $displayName"
    override fun fallbackSystem(systemId: Int) = "System $systemId"
    override fun sovereigntyOwnerKind(kind: SystemOwnerKind) = when (kind) {
        SystemOwnerKind.ALLIANCE -> "Alliance"
        SystemOwnerKind.CORPORATION -> "Corporation"
        SystemOwnerKind.FACTION -> "Faction"
        SystemOwnerKind.UNCLAIMED -> "Unclaimed"
        SystemOwnerKind.UNKNOWN -> "Unknown"
    }
    override fun sovereigntyStatus(status: SovereigntyStatus) = when (status) {
        SovereigntyStatus.CLAIMED -> "Claimed"
        SovereigntyStatus.UNCLAIMED -> "Unclaimed"
        SovereigntyStatus.UNKNOWN -> "Unknown"
    }
    override fun sovereigntyFreshness(freshness: SovereigntyFreshness) = when (freshness) {
        SovereigntyFreshness.AVAILABLE -> "Available"
        SovereigntyFreshness.STALE -> "Stale"
        SovereigntyFreshness.UNAVAILABLE -> "Unavailable"
    }
}
