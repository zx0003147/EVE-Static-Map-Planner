package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.control.mission.MissionRoute
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.feature.api.RouteIdentity
import dev.evestaticmapplanner.feature.api.RouteKind
import dev.evestaticmapplanner.feature.api.RouteSegment
import dev.evestaticmapplanner.feature.api.RouteSegmentKind
import dev.evestaticmapplanner.feature.api.RouteSnapshot
import java.util.UUID

/** Host-private DTO adapter. Core route types remain unaware of Feature API. */
internal object RouteSnapshotAdapters {
    fun normal(route: RouteResult, identity: RouteIdentity, kind: RouteKind = RouteKind.NORMAL) = RouteSnapshot(
        identity = identity,
        kind = kind,
        sourceSystemId = route.startSystemId,
        destinationSystemId = route.destinationSystemId,
        orderedSystemIds = route.systems,
        orderedSegments = route.edges.map { edge ->
            RouteSegment(
                fromSystemId = edge.fromSystemId,
                toSystemId = edge.toSystemId,
                kind = when (edge.type) {
                    RouteEdgeType.STARGATE -> RouteSegmentKind.STARGATE
                    RouteEdgeType.ANSIBLEX -> RouteSegmentKind.ANSIBLEX
                    RouteEdgeType.WORMHOLE -> error(
                        "Feature API 2.0.0 RouteSnapshot does not support Wormhole route segments",
                    )
                },
                distanceLy = null,
            )
        },
    )

    fun capital(
        route: CapitalRouteResult,
        identity: RouteIdentity,
        kind: RouteKind = RouteKind.CAPITAL,
    ) = RouteSnapshot(
        identity = identity,
        kind = kind,
        sourceSystemId = route.startSystemId,
        destinationSystemId = route.destinationSystemId,
        orderedSystemIds = route.systems,
        orderedSegments = route.legs.map { leg ->
            RouteSegment(
                fromSystemId = leg.fromSystemId,
                toSystemId = leg.toSystemId,
                kind = RouteSegmentKind.CAPITAL_JUMP,
                distanceLy = leg.distanceLy,
            )
        },
    )
}

/**
 * Maintains opaque identities independently of Compose and the Core models. Equal active route
 * values retain identity across recomposition; a changed route receives a new identity.
 */
internal class InteractiveRouteSnapshotAdapter(
    private val identityFactory: () -> RouteIdentity = { RouteIdentity(UUID.randomUUID().toString()) },
) {
    private var normalRoute: RouteResult? = null
    private var normalSnapshot: RouteSnapshot? = null
    private var capitalRoute: CapitalRouteResult? = null
    private var capitalSnapshot: RouteSnapshot? = null
    private val missionSnapshots = linkedMapOf<MissionRouteKey, Pair<Any, RouteSnapshot>>()

    @Synchronized
    fun normal(route: RouteResult?): RouteSnapshot? {
        if (route == null) {
            normalRoute = null
            normalSnapshot = null
            return null
        }
        if (route != normalRoute) {
            normalRoute = route
            normalSnapshot = RouteSnapshotAdapters.normal(route, identityFactory())
        }
        return normalSnapshot
    }

    @Synchronized
    fun capital(route: CapitalRouteResult?): RouteSnapshot? {
        if (route == null) {
            capitalRoute = null
            capitalSnapshot = null
            return null
        }
        if (route != capitalRoute) {
            capitalRoute = route
            capitalSnapshot = RouteSnapshotAdapters.capital(route, identityFactory())
        }
        return capitalSnapshot
    }

    @Synchronized
    fun mission(route: MissionRoute): RouteSnapshot {
        val key = MissionRouteKey(route.missionId.value, route.routeId.value)
        val coreRoute: Any = when (route) {
            is MissionRoute.Normal -> route.route
            is MissionRoute.Capital -> route.route
        }
        missionSnapshots[key]?.takeIf { it.first == coreRoute }?.let { return it.second }
        val identity = identityFactory()
        val snapshot = when (route) {
            is MissionRoute.Normal -> RouteSnapshotAdapters.normal(route.route, identity, RouteKind.MISSION_NORMAL)
            is MissionRoute.Capital -> RouteSnapshotAdapters.capital(route.route, identity, RouteKind.MISSION_CAPITAL)
        }
        missionSnapshots[key] = coreRoute to snapshot
        return snapshot
    }

    private data class MissionRouteKey(val missionId: String, val routeId: String)
}
