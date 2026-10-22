package dev.evestaticmapplanner.control

data class GetCurrentNavigationRoutesRequest(
    val requestId: String,
)

data class CurrentNavigationRoutesDto(
    val normal: CurrentNavigationRouteDto,
    val capital: CurrentNavigationRouteDto,
)

data class CurrentNavigationRouteDto(
    val routeType: CurrentNavigationRouteType,
    val status: CurrentNavigationRouteStatus,
    val valid: Boolean,
    val stale: Boolean,
    val unavailableReason: String? = null,
    val startSystemId: Int? = null,
    val destinationSystemId: Int? = null,
    val systems: List<CurrentNavigationSystemDto> = emptyList(),
    val connections: List<CurrentNavigationConnectionDto> = emptyList(),
    val waypointSystemIds: List<Int> = emptyList(),
    val explicitDestinationSystemId: Int? = null,
    val effectiveRangeLy: Double? = null,
) {
    init {
        require((status == CurrentNavigationRouteStatus.CALCULATED) == valid) {
            "Only a current calculated route can be valid"
        }
        require(!valid || systems.isNotEmpty()) { "A valid route must contain at least one system" }
        require(connections.size == (systems.size - 1).coerceAtLeast(0)) {
            "Route systems and connections must be contiguous"
        }
    }

    val exists: Boolean get() = systems.isNotEmpty()
    val totalJumps: Int get() = connections.size
}

enum class CurrentNavigationRouteType {
    NORMAL,
    CAPITAL,
}

enum class CurrentNavigationRouteStatus {
    NOT_PLANNED,
    CALCULATED,
    STALE,
    UNAVAILABLE,
}

data class CurrentNavigationSystemDto(
    val routeIndex: Int,
    val systemId: Int,
    val name: String?,
    val securityStatus: Double?,
    val spaceClassification: String?,
    val securityBand: CurrentNavigationSecurityBand?,
)

enum class CurrentNavigationSecurityBand {
    HIGH_SECURITY,
    LOW_SECURITY,
    NULL_SECURITY,
}

data class CurrentNavigationConnectionDto(
    val routeIndex: Int,
    val fromSystemId: Int,
    val toSystemId: Int,
    val type: CurrentNavigationConnectionType,
    val distanceLy: Double? = null,
)

enum class CurrentNavigationConnectionType {
    STARGATE,
    ANSIBLEX,
    WORMHOLE,
    CAPITAL_JUMP,
}
