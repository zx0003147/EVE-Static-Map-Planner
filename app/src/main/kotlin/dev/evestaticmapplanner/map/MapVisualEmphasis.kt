package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.control.MissionMapUiState
import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.map.ProjectedStargateEdge
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.core.wormhole.WormholeConnection

internal data class MapSystemConnection(
    val firstSystemId: Int,
    val secondSystemId: Int,
) {
    init {
        require(firstSystemId < secondSystemId) { "Map connection endpoints must be canonical and distinct" }
    }

    companion object {
        fun between(firstSystemId: Int, secondSystemId: Int): MapSystemConnection =
            if (firstSystemId < secondSystemId) {
                MapSystemConnection(firstSystemId, secondSystemId)
            } else {
                MapSystemConnection(secondSystemId, firstSystemId)
            }
    }
}

class MapVisualEmphasis private constructor(
    val prioritizedSystemIds: List<Int>,
    val focusedSystemIds: Set<Int>,
    val selectedSystemId: Int?,
    internal val selectedStargateConnections: Set<MapSystemConnection>,
    val selectedAnsiblexConnectionIds: Set<String>,
    val selectedWormholeConnectionIds: Set<String>,
    val activeRouteCount: Int,
) {
    val isActive: Boolean get() = activeRouteCount > 0 || selectedSystemId != null

    fun systemAlphaMultiplier(systemId: Int): Float = when {
        !isActive || systemId in focusedSystemIds -> 1f
        else -> ROUTE_FOCUS_BACKGROUND_SYSTEM_ALPHA
    }

    fun systemLabelAlphaMultiplier(systemId: Int): Float = when {
        !isActive || systemId in focusedSystemIds -> 1f
        else -> ROUTE_FOCUS_BACKGROUND_SYSTEM_LABEL_ALPHA
    }

    fun stargateAlphaMultiplier(firstSystemId: Int, secondSystemId: Int): Float = when {
        !isActive || selectedSystemId == firstSystemId || selectedSystemId == secondSystemId -> 1f
        else -> ROUTE_FOCUS_BACKGROUND_CONNECTION_ALPHA
    }

    fun ansiblexAlphaMultiplier(connectionId: String): Float = when {
        !isActive || connectionId in selectedAnsiblexConnectionIds -> 1f
        else -> ROUTE_FOCUS_BACKGROUND_CONNECTION_ALPHA
    }

    fun wormholeAlphaMultiplier(connectionId: String): Float = when {
        !isActive || connectionId in selectedWormholeConnectionIds -> 1f
        else -> ROUTE_FOCUS_BACKGROUND_CONNECTION_ALPHA
    }

    val hierarchyLabelAlphaMultiplier: Float
        get() = if (isActive) ROUTE_FOCUS_BACKGROUND_HIERARCHY_LABEL_ALPHA else 1f

    companion object {
        val None = MapVisualEmphasis(
            prioritizedSystemIds = emptyList(),
            focusedSystemIds = emptySet(),
            selectedSystemId = null,
            selectedStargateConnections = emptySet(),
            selectedAnsiblexConnectionIds = emptySet(),
            selectedWormholeConnectionIds = emptySet(),
            activeRouteCount = 0,
        )

        fun fromDisplayedMapState(
            userNormalRoute: RouteResult?,
            userCapitalRoute: CapitalRouteResult?,
            missionState: MissionMapUiState,
            selectedSystemId: Int?,
            stargateEdges: List<ProjectedStargateEdge>,
            visibleAnsiblexConnections: List<AnsiblexConnection>,
            wormholeConnections: List<WormholeConnection> = emptyList(),
        ): MapVisualEmphasis {
            val routeSystemIds = linkedSetOf<Int>()
            var routeCount = 0
            sequenceOf(userNormalRoute).filterNotNull()
                .plus(missionState.normalRoutes.asSequence().map { it.route })
                .forEach { route ->
                    routeCount += 1
                    routeSystemIds += route.systems
                }
            sequenceOf(userCapitalRoute).filterNotNull()
                .plus(missionState.capitalRoutes.asSequence().map { it.route })
                .forEach { route ->
                    routeCount += 1
                    routeSystemIds += route.systems
                }

            val selectedStargates = selectedSystemId?.let { selected ->
                stargateEdges.asSequence()
                    .filter { it.firstSystemId == selected || it.secondSystemId == selected }
                    .map { MapSystemConnection.between(it.firstSystemId, it.secondSystemId) }
                    .toSet()
            }.orEmpty()
            val selectedAnsiblex = selectedSystemId?.let { selected ->
                visibleAnsiblexConnections.asSequence()
                    .filter(AnsiblexConnection::enabled)
                    .filter { it.firstSystemId == selected || it.secondSystemId == selected }
                    .toList()
            }.orEmpty()
            val selectedWormholes = selectedSystemId?.let { selected ->
                wormholeConnections.filter { it.firstSystemId == selected || it.secondSystemId == selected }
            }.orEmpty()

            if (routeCount == 0 && selectedSystemId == null) return None

            val prioritizedSystemIds = linkedSetOf<Int>()
            selectedSystemId?.let(prioritizedSystemIds::add)
            selectedStargates.asSequence()
                .map { if (it.firstSystemId == selectedSystemId) it.secondSystemId else it.firstSystemId }
                .sorted()
                .forEach(prioritizedSystemIds::add)
            (selectedAnsiblex.asSequence().map { connection ->
                if (connection.firstSystemId == selectedSystemId) connection.secondSystemId else connection.firstSystemId
            } + selectedWormholes.asSequence().map { connection ->
                if (connection.firstSystemId == selectedSystemId) connection.secondSystemId else connection.firstSystemId
            })
                .sorted()
                .forEach(prioritizedSystemIds::add)
            prioritizedSystemIds += routeSystemIds

            return MapVisualEmphasis(
                prioritizedSystemIds = prioritizedSystemIds.toList(),
                focusedSystemIds = prioritizedSystemIds.toSet(),
                selectedSystemId = selectedSystemId,
                selectedStargateConnections = selectedStargates,
                selectedAnsiblexConnectionIds = selectedAnsiblex.mapTo(linkedSetOf(), AnsiblexConnection::id),
                selectedWormholeConnectionIds = selectedWormholes.mapTo(linkedSetOf(), WormholeConnection::id),
                activeRouteCount = routeCount,
            )
        }
    }
}

internal const val ROUTE_FOCUS_BACKGROUND_SYSTEM_ALPHA = 0.32f
internal const val ROUTE_FOCUS_BACKGROUND_SYSTEM_LABEL_ALPHA = 0.28f
internal const val ROUTE_FOCUS_BACKGROUND_HIERARCHY_LABEL_ALPHA = 0.25f
internal const val ROUTE_FOCUS_BACKGROUND_CONNECTION_ALPHA = 0.28f
