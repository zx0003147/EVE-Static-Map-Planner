package dev.evestaticmapplanner.localization.en

import dev.evestaticmapplanner.localization.NavigationStopUiRole
import dev.evestaticmapplanner.localization.RouteStrings
import java.util.Locale

internal object EnglishRouteStrings : RouteStrings {
    override val jumpRangeOverlays = "Jump Range Overlays"
    override val normalRoute = "Normal Route"
    override val capitalRoute = "Capital Route"
    override val overlayOrigin = "Overlay origin"
    override val effectiveMaximumLy = "Effective maximum LY"
    override val intersect = "Intersect"
    override val start = "Start"
    override val destinationOptional = "Destination (optional)"
    override val capitalStart = "Capital Start"
    override val capitalDestinationOptional = "Capital Destination (optional)"
    override val calculate = "Calculate"
    override val calculating = "Calculating…"
    override val needsRecalculation = "Needs recalculation"
    override val useAnsiblex = "Use Ansiblex"
    override val useWormholes = "Use Wormholes"
    override val showAnsiblexLayer = "Show Ansiblex layer"
    override val waypoints = "Waypoints"
    override val waypointHint = "Waypoints · add from a system's right-click menu"
    override val routeActionsUnavailableForWormholes = "Route actions unavailable for routes containing Wormholes."
    override val stargateOnlyRoutingAvailable = "Static map and Stargate-only routing remain available."
    override val validatesStaticCapitalRules =
        "Validates real XYZ geometry, manual max range, and implemented static eligibility only."
    override val capitalLiveStateDisclaimer =
        "Does not verify live cyno/type, jammer, ACL, fuel, capacitor, fatigue, scram, or server state."
    override val phaseLabel = "Phase 5 · Jump Range Overlays + Capital Route V1"
    override val sameNormalSystem = "Start and destination are the same system · 0 jumps"
    override val normalRouteUnreachable = "No route is reachable with the selected connection types."
    override val invalidNormalEndpoints = "One or both route endpoints are invalid."
    override val sameCapitalSystem = "Start and destination are the same · 0 jumps"
    override val capitalRouteUnreachable = "No statically eligible route within the manual range."
    override val invalidCapitalEndpoints = "One or both capital endpoints are invalid."
    override val draftOnly = "Draft only — EVE changes only after you press a button below."
    override val succeeded = "Succeeded"
    override val rejected = "Rejected"
    override val failed = "Failed"
    override val unavailableSuffix = "unavailable"
    override val disconnectedUnavailable = "disconnected / unavailable"
    override val selectTarget = "Select…"
    override val publishNormalRoute = "Publish Normal Route to Web"
    override val publishCapitalRoute = "Publish Capital Route to Web"
    override val calculateBeforePublishing = "Calculate a route before publishing."
    override val connectSharedMapBeforePublishing = "Connect to Shared Map before publishing."
    override val routeHandoffsUnsupported = "This Shared Map server does not support Route Handoffs."
    override val publishPermissionRequired = "EDITOR or ADMIN permission is required to publish."
    override val publishingRoute = "Publishing route…"
    override val unableToLoadRouteGraph = "Unable to load route graph"
    override val unableToLoadCapitalRouteData = "Unable to load capital route data"
    override val unableToLoadJumpOverlayData = "Unable to load jump overlay data"
    override val jumpOverlayCalculationFailed = "Jump overlay calculation failed"
    override val manualMaximumLyMustBeNumber = "Manual maximum LY must be a number"
    override val manualMaximumLyMustBePositive = "Manual maximum LY must be finite and positive"
    override val addWaypointOrDestination = "Add a Waypoint or Destination before calculating."
    override val invalidNavigationStop = "A navigation stop is invalid."
    override val ansiblexDataUnavailable = "Ansiblex data unavailable."

    override fun routeFound(jumps: Int) = "Route found: $jumps jumps"
    override fun jumpCount(count: Int) = countedNoun(count, "jump")
    override fun stargateCount(count: Int) = countedNoun(count, "Stargate")
    override fun ansiblexCount(count: Int) = "$count Ansiblex"
    override fun wormholeCount(count: Int) = countedNoun(count, "Wormhole")
    override fun capitalJumpCount(count: Int) = countedNoun(count, "capital jump")
    override fun totalDistanceLy(distance: Double) = String.format(Locale.ROOT, "%.3f LY total", distance)
    override fun jumpDistanceLy(distance: Double) = String.format(Locale.ROOT, "%.3f LY", distance)
    override fun overlayIntersection(overlays: Int, systems: Int) =
        "Intersection ($overlays overlays): $systems systems"
    override fun ansiblexManager(enabled: Int, total: Int) = "Ansiblex Manager ($enabled/$total)"
    override fun wormholeManager(total: Int) = "Wormhole Manager ($total)"
    override fun adjacentDuplicate(systemName: String) =
        "Adjacent navigation stops cannot both be $systemName."
    override fun navigationStop(role: NavigationStopUiRole, systemName: String) = "${role.englishLabel} $systemName"
    override fun segmentFailure(from: String, to: String) = "Unable to calculate segment: $from → $to"
    override fun capitalEndpointVerdict(endpoint: String, reason: String) = "$endpoint: $reason"

    private fun countedNoun(count: Int, singular: String, plural: String = "${singular}s") =
        "$count ${if (count == 1) singular else plural}"

    private val NavigationStopUiRole.englishLabel: String
        get() = when (this) {
            NavigationStopUiRole.START -> "Start"
            NavigationStopUiRole.WAYPOINT -> "Waypoint"
            NavigationStopUiRole.DESTINATION -> "Destination"
        }
}
