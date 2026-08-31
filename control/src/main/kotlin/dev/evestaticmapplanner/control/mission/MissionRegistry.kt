package dev.evestaticmapplanner.control.mission

import dev.evestaticmapplanner.control.ControlErrorCode
import dev.evestaticmapplanner.control.ControlLimits
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteResult
import java.time.Instant
import java.util.UUID

class MissionRegistry(
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val missions = linkedMapOf<MissionId, Mission>()

    @Synchronized
    fun begin(title: String, viewId: String = "view-1"): Mission {
        if (missions.size >= ControlLimits.MAX_ACTIVE_MISSIONS) {
            fail(ControlErrorCode.MISSION_LIMIT_EXCEEDED, "The active mission limit has been reached")
        }
        val id = MissionId(newId())
        val mission = Mission(id, title, now(), 1, emptyList(), emptyList(), emptyList(), emptySet(), viewId)
        missions[id] = mission
        return mission
    }

    @Synchronized
    fun active(): List<Mission> = missions.values.toList()

    @Synchronized
    fun active(viewId: String): List<Mission> = missions.values.filter { it.viewId == viewId }

    @Synchronized
    fun get(missionId: MissionId): Mission = missions[missionId]
        ?: fail(ControlErrorCode.MISSION_NOT_FOUND, "Mission was not found")

    @Synchronized
    fun addNormalRoute(missionId: MissionId, route: RouteResult): MissionRoute.Normal {
        val mission = get(missionId)
        ensureRouteCapacity(mission)
        val owned = MissionRoute.Normal(missionId, MissionRouteId(newId()), route)
        replace(mission.copy(routes = mission.routes + owned).revised())
        return owned
    }

    @Synchronized
    fun addCapitalRoute(missionId: MissionId, route: CapitalRouteResult): MissionRoute.Capital {
        val mission = get(missionId)
        ensureRouteCapacity(mission)
        val owned = MissionRoute.Capital(missionId, MissionRouteId(newId()), route)
        replace(mission.copy(routes = mission.routes + owned).revised())
        return owned
    }

    @Synchronized
    fun removeRoute(missionId: MissionId, routeId: MissionRouteId): Mission {
        val mission = get(missionId)
        requireOwnedObject(missionId) { it.routes.any { route -> route.routeId == routeId } }
        return replace(mission.copy(routes = mission.routes.filterNot { it.routeId == routeId }).revised())
    }

    @Synchronized
    fun clearRoutes(missionId: MissionId): Mission {
        val mission = get(missionId)
        return if (mission.routes.isEmpty()) mission else replace(mission.copy(routes = emptyList()).revised())
    }

    @Synchronized
    fun invalidateNormalRoutesUsingWormholes(connectionIds: Set<String>): Int {
        if (connectionIds.isEmpty()) return 0

        var invalidatedRouteCount = 0
        val replacements = missions.values.mapNotNull { mission ->
            val retainedRoutes = mission.routes.filterNot { route ->
                val invalidated = route is MissionRoute.Normal && route.route.edges.any { edge ->
                    edge.type == RouteEdgeType.WORMHOLE && edge.connectionId.value in connectionIds
                }
                if (invalidated) invalidatedRouteCount++
                invalidated
            }
            if (retainedRoutes.size == mission.routes.size) null else mission.copy(routes = retainedRoutes).revised()
        }
        replacements.forEach(::replace)
        return invalidatedRouteCount
    }

    @Synchronized
    fun addJumpRange(
        missionId: MissionId,
        originSystemId: Int,
        profile: JumpProfile,
        reachableSystemIds: Set<Int>,
        label: String?,
    ): MissionJumpRange {
        val mission = get(missionId)
        if (mission.jumpRanges.size >= ControlLimits.MAX_JUMP_RANGES_PER_MISSION) {
            fail(ControlErrorCode.MISSION_LIMIT_EXCEEDED, "The mission jump range limit has been reached")
        }
        val owned = MissionJumpRange(
            missionId,
            MissionJumpRangeId(newId()),
            originSystemId,
            profile,
            reachableSystemIds.toSet(),
            label,
        )
        replace(mission.copy(jumpRanges = mission.jumpRanges + owned).revised())
        return owned
    }

    @Synchronized
    fun removeJumpRange(missionId: MissionId, jumpRangeId: MissionJumpRangeId): Mission {
        val mission = get(missionId)
        requireOwnedObject(missionId) { it.jumpRanges.any { range -> range.jumpRangeId == jumpRangeId } }
        return replace(mission.copy(jumpRanges = mission.jumpRanges.filterNot { it.jumpRangeId == jumpRangeId }).revised())
    }

    @Synchronized
    fun clearJumpRanges(missionId: MissionId): Mission {
        val mission = get(missionId)
        return if (mission.jumpRanges.isEmpty()) mission else replace(mission.copy(jumpRanges = emptyList()).revised())
    }

    @Synchronized
    fun addMarker(
        missionId: MissionId,
        systemId: Int,
        role: MissionMarkerRole,
        label: String?,
        notes: String?,
        colorOverride: MarkerColor?,
    ): MissionMarker {
        val mission = get(missionId)
        if (mission.markers.size >= ControlLimits.MAX_MARKERS_PER_MISSION) {
            fail(ControlErrorCode.MISSION_LIMIT_EXCEEDED, "The mission marker limit has been reached")
        }
        val owned = MissionMarker(missionId, MissionMarkerId(newId()), systemId, role, label, notes, colorOverride)
        replace(mission.copy(markers = mission.markers + owned).revised())
        return owned
    }

    @Synchronized
    fun removeMarker(missionId: MissionId, markerId: MissionMarkerId): Mission {
        val mission = get(missionId)
        requireOwnedObject(missionId) { it.markers.any { marker -> marker.markerId == markerId } }
        return replace(mission.copy(markers = mission.markers.filterNot { it.markerId == markerId }).revised())
    }

    @Synchronized
    fun clearMarkers(missionId: MissionId): Mission {
        val mission = get(missionId)
        return if (mission.markers.isEmpty()) mission else replace(mission.copy(markers = emptyList()).revised())
    }

    @Synchronized
    fun clearMission(missionId: MissionId): Mission {
        val mission = get(missionId)
        missions.remove(missionId)
        return mission
    }

    private fun ensureRouteCapacity(mission: Mission) {
        if (mission.routes.size >= ControlLimits.MAX_ROUTES_PER_MISSION) {
            fail(ControlErrorCode.MISSION_LIMIT_EXCEEDED, "The mission route limit has been reached")
        }
    }

    private fun requireOwnedObject(
        missionId: MissionId,
        contains: (Mission) -> Boolean,
    ) {
        if (!contains(get(missionId))) {
            // Do not scan other Missions: cross-Mission and unknown IDs are intentionally indistinguishable.
            fail(ControlErrorCode.OBJECT_NOT_FOUND, "Mission object was not found")
        }
    }

    private fun Mission.revised(): Mission {
        val references = buildSet {
            routes.forEach { addAll(it.systemIds) }
            jumpRanges.forEach { add(it.originSystemId) }
            markers.forEach { add(it.systemId) }
        }
        if (references.size > ControlLimits.MAX_REFERENCED_SYSTEMS_PER_MISSION) {
            fail(ControlErrorCode.MISSION_LIMIT_EXCEEDED, "The mission referenced-system limit has been reached")
        }
        return copy(revision = revision + 1, referencedSystemIds = references)
    }

    private fun replace(mission: Mission): Mission {
        missions[mission.missionId] = mission
        return mission
    }

    @Synchronized
    fun clearView(viewId: String) {
        missions.entries.removeIf { it.value.viewId == viewId }
    }

    @Synchronized
    fun retainViews(viewIds: Set<String>) {
        missions.entries.removeIf { it.value.viewId !in viewIds }
    }
}

class MissionRegistryFailure(
    val code: ControlErrorCode,
    override val message: String,
) : RuntimeException(message)

private fun fail(code: ControlErrorCode, message: String): Nothing = throw MissionRegistryFailure(code, message)
