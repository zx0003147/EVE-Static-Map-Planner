package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapBounds
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.core.wormhole.WormholeConnection

internal data class ProjectedWormholeConnection(
    val connection: WormholeConnection,
    val first: MapPoint,
    val second: MapPoint,
) {
    val bounds: MapBounds = MapBounds.between(first, second)
}

internal data class WormholeMapPresentation(
    val connections: List<ProjectedWormholeConnection>,
    val omittedConnectionCount: Int,
) {
    companion object {
        val Empty = WormholeMapPresentation(emptyList(), 0)
    }
}

internal object WormholeMapPresentationBuilder {
    fun build(
        connections: List<WormholeConnection>,
        scene: ProjectedMapScene,
    ): WormholeMapPresentation {
        if (connections.isEmpty()) return WormholeMapPresentation.Empty
        var omitted = 0
        val projected = connections.sortedBy(WormholeConnection::id).mapNotNull { connection ->
            val first = scene.nodesById[connection.firstSystemId]?.position
            val second = scene.nodesById[connection.secondSystemId]?.position
            if (first == null || second == null) {
                omitted += 1
                null
            } else {
                ProjectedWormholeConnection(connection, first, second)
            }
        }
        return WormholeMapPresentation(projected, omitted)
    }
}

internal fun visibleWormholeConnections(
    presentation: WormholeMapPresentation,
    visibleBounds: MapBounds,
): List<ProjectedWormholeConnection> = presentation.connections.filter { it.bounds.intersects(visibleBounds) }
