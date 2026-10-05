package dev.evestaticmapplanner.core.route

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.wormhole.WormholeConnection

fun buildDesktopRouteGraph(
    staticMapData: StaticMapData,
    ansiblexConnections: List<AnsiblexConnection> = emptyList(),
    wormholeConnections: List<WormholeConnection> = emptyList(),
    ansiblexDestinationAllowed: ((Int) -> Boolean)? = null,
): RouteGraph = RouteGraphBuilder.build(
    staticMapData,
    ansiblexConnections.flatMap { it.toRouteLinks(ansiblexDestinationAllowed) } +
        wormholeConnections.map(WormholeConnection::toRouteLink),
)

fun AnsiblexConnection.toRouteLinks(destinationAllowed: ((Int) -> Boolean)? = null): List<RouteLink> {
    val allows: (Int) -> Boolean = destinationAllowed ?: { true }
    return buildList {
        if (direction != AnsiblexDirection.SECOND_TO_FIRST && allows(secondSystemId)) {
            add(toRouteLink(RouteLinkDirection.FIRST_TO_SECOND))
        }
        if (direction != AnsiblexDirection.FIRST_TO_SECOND && allows(firstSystemId)) {
            add(toRouteLink(RouteLinkDirection.SECOND_TO_FIRST))
        }
    }
}

private fun AnsiblexConnection.toRouteLink(direction: RouteLinkDirection): RouteLink = RouteLink(
    connectionId = RouteConnectionId("ansiblex:$id"),
    firstSystemId = firstSystemId,
    secondSystemId = secondSystemId,
    direction = direction,
    type = RouteEdgeType.ANSIBLEX,
    enabled = enabled,
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

    fun build(
        connections: List<AnsiblexConnection>,
        destinationAllowed: (Int) -> Boolean,
    ): List<RouteEdge> = RouteLinkEdgeBuilder.build(connections.flatMap { it.toRouteLinks(destinationAllowed) })
}
