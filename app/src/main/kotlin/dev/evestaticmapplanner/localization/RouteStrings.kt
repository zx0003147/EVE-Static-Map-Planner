package dev.evestaticmapplanner.localization

interface RouteStrings {
    val jumpRangeOverlays: String
    val normalRoute: String
    val capitalRoute: String
    val overlayOrigin: String
    val effectiveMaximumLy: String
    val intersect: String
    val start: String
    val destinationOptional: String
    val capitalStart: String
    val capitalDestinationOptional: String
    val calculate: String
    val calculating: String
    val needsRecalculation: String
    val useAnsiblex: String
    val useWormholes: String
    val showAnsiblexLayer: String
    val waypoints: String
    val waypointHint: String
    val routeActionsUnavailableForWormholes: String
    val stargateOnlyRoutingAvailable: String
    val validatesStaticCapitalRules: String
    val capitalLiveStateDisclaimer: String
    val phaseLabel: String
    val sameNormalSystem: String
    val normalRouteUnreachable: String
    val invalidNormalEndpoints: String
    val sameCapitalSystem: String
    val capitalRouteUnreachable: String
    val invalidCapitalEndpoints: String
    val draftOnly: String
    val succeeded: String
    val rejected: String
    val failed: String
    val unavailableSuffix: String
    val disconnectedUnavailable: String
    val selectTarget: String
    val publishNormalRoute: String
    val publishCapitalRoute: String
    val calculateBeforePublishing: String
    val connectSharedMapBeforePublishing: String
    val routeHandoffsUnsupported: String
    val publishPermissionRequired: String
    val publishingRoute: String
    val unableToLoadRouteGraph: String
    val unableToLoadCapitalRouteData: String
    val unableToLoadJumpOverlayData: String
    val jumpOverlayCalculationFailed: String
    val manualMaximumLyMustBeNumber: String
    val manualMaximumLyMustBePositive: String
    val addWaypointOrDestination: String
    val invalidNavigationStop: String
    val ansiblexDataUnavailable: String

    fun routeFound(jumps: Int): String
    fun jumpCount(count: Int): String
    fun stargateCount(count: Int): String
    fun ansiblexCount(count: Int): String
    fun wormholeCount(count: Int): String
    fun capitalJumpCount(count: Int): String
    fun totalDistanceLy(distance: Double): String
    fun jumpDistanceLy(distance: Double): String
    fun overlayIntersection(overlays: Int, systems: Int): String
    fun ansiblexManager(enabled: Int, total: Int): String
    fun wormholeManager(total: Int): String
    fun adjacentDuplicate(systemName: String): String
    fun navigationStop(role: NavigationStopUiRole, systemName: String): String
    fun segmentFailure(from: String, to: String): String
    fun capitalEndpointVerdict(endpoint: String, reason: String): String
}

enum class NavigationStopUiRole {
    START,
    WAYPOINT,
    DESTINATION,
}
