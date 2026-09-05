package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.route.CapitalRouteLeg
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteResult

data class Real3DProjectedRouteLeg(
    val edge: RouteEdge,
    val segment: Real3DProjectedSegment,
)

data class Real3DProjectedRoute(
    val route: RouteResult,
    val legs: List<Real3DProjectedRouteLeg>,
    val omittedLegCount: Int,
)

data class Real3DProjectedCapitalRouteLeg(
    val leg: CapitalRouteLeg,
    val segment: Real3DProjectedSegment,
)

data class Real3DProjectedCapitalRoute(
    val route: CapitalRouteResult,
    val legs: List<Real3DProjectedCapitalRouteLeg>,
    val omittedLegCount: Int,
)

/** Projects route endpoints from the same true XYZ geometry used by the 3D base map. */
object Real3DRouteProjector {
    fun project(
        route: RouteResult,
        geometry: Real3DStaticGeometry,
        camera: Real3DCamera,
        viewportSize: MapSize,
    ): Real3DProjectedRoute {
        val projector = Real3DProjector(camera, viewportSize)
        val legs = route.edges.mapNotNull { edge ->
            val first = geometry.nodesById[edge.fromSystemId]?.position ?: return@mapNotNull null
            val second = geometry.nodesById[edge.toSystemId]?.position ?: return@mapNotNull null
            projector.projectSegment(first, second)?.let { Real3DProjectedRouteLeg(edge, it) }
        }
        return Real3DProjectedRoute(route, legs, route.edges.size - legs.size)
    }

    fun project(
        route: CapitalRouteResult,
        geometry: Real3DStaticGeometry,
        camera: Real3DCamera,
        viewportSize: MapSize,
    ): Real3DProjectedCapitalRoute {
        val projector = Real3DProjector(camera, viewportSize)
        val legs = route.legs.mapNotNull { leg ->
            val first = geometry.nodesById[leg.fromSystemId]?.position ?: return@mapNotNull null
            val second = geometry.nodesById[leg.toSystemId]?.position ?: return@mapNotNull null
            projector.projectSegment(first, second)?.let { Real3DProjectedCapitalRouteLeg(leg, it) }
        }
        return Real3DProjectedCapitalRoute(route, legs, route.legs.size - legs.size)
    }
}
