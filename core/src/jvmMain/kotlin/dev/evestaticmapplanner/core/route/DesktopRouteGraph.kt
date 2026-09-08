package dev.evestaticmapplanner.core.route

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.wormhole.WormholeConnection

fun buildDesktopRouteGraph(
    staticMapData: StaticMapData,
    ansiblexConnections: List<AnsiblexConnection> = emptyList(),
    wormholeConnections: List<WormholeConnection> = emptyList(),
): RouteGraph = RouteGraphBuilder.build(
    staticMapData,
    ansiblexConnections.map(AnsiblexConnection::toRouteLink) +
        wormholeConnections.map(WormholeConnection::toRouteLink),
)

fun AnsiblexConnection.toRouteLink(): RouteLink = RouteLink(
    connectionId = RouteConnectionId("ansiblex:$id"),
    firstSystemId = firstSystemId,
    secondSystemId = secondSystemId,
    direction = when (direction) {
        AnsiblexDirection.BIDIRECTIONAL -> RouteLinkDirection.BIDIRECTIONAL
        AnsiblexDirection.FIRST_TO_SECOND -> RouteLinkDirection.FIRST_TO_SECOND
        AnsiblexDirection.SECOND_TO_FIRST -> RouteLinkDirection.SECOND_TO_FIRST
    },
    type = RouteEdgeType.ANSIBLEX,
    enabled = enabled,
)

fun WormholeConnection.toRouteLink(): RouteLink = RouteLink(
    connectionId = RouteConnectionId(id),
    firstSystemId = firstSystemId,
    secondSystemId = secondSystemId,
    direction = RouteLinkDirection.BIDIRECTIONAL,
    type = RouteEdgeType.WORMHOLE,
)

/** JVM compatibility facade retained for Desktop call sites and plugins. */
object AnsiblexRouteEdgeBuilder {
    fun build(connections: List<AnsiblexConnection>): List<RouteEdge> =
        RouteLinkEdgeBuilder.build(connections.map(AnsiblexConnection::toRouteLink))
}
