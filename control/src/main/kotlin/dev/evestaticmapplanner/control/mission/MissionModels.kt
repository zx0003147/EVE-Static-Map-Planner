package dev.evestaticmapplanner.control.mission

import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.core.route.NavigationIntent
import java.time.Instant

@JvmInline value class MissionId(val value: String)
@JvmInline value class MissionRouteId(val value: String)
@JvmInline value class MissionJumpRangeId(val value: String)
@JvmInline value class MissionMarkerId(val value: String)

enum class MissionMarkerRole(val defaultColor: MarkerColor) {
    RALLY(MarkerColor.GREEN),
    DESTINATION(MarkerColor.RED),
    DANGER(MarkerColor.ORANGE),
    BACKUP(MarkerColor.PURPLE),
    WAYPOINT(MarkerColor.BLUE),
    INFO(MarkerColor.WHITE),
}

sealed interface MissionRoute {
    val missionId: MissionId
    val routeId: MissionRouteId
    val systemIds: List<Int>

    data class Normal(
        override val missionId: MissionId,
        override val routeId: MissionRouteId,
        val route: RouteResult,
        val navigationIntent: NavigationIntent = NavigationIntent(
            route.startSystemId,
            destinationSystemId = route.destinationSystemId,
        ),
    ) : MissionRoute {
        override val systemIds: List<Int> get() = route.systems
        val waypointSystemIds: List<Int> get() = navigationIntent.waypointSystemIds
    }

    data class Capital(
        override val missionId: MissionId,
        override val routeId: MissionRouteId,
        val route: CapitalRouteResult,
        val navigationIntent: NavigationIntent = NavigationIntent(
            route.startSystemId,
            destinationSystemId = route.destinationSystemId,
        ),
    ) : MissionRoute {
        override val systemIds: List<Int> get() = route.systems
        val waypointSystemIds: List<Int> get() = navigationIntent.waypointSystemIds
    }
}

data class MissionJumpRange(
    val missionId: MissionId,
    val jumpRangeId: MissionJumpRangeId,
    val originSystemId: Int,
    val profile: JumpProfile,
    val reachableSystemIds: Set<Int>,
    val label: String?,
)

data class MissionMarker(
    val missionId: MissionId,
    val markerId: MissionMarkerId,
    val systemId: Int,
    val role: MissionMarkerRole,
    val label: String?,
    val notes: String?,
    val colorOverride: MarkerColor?,
) {
    val color: MarkerColor get() = colorOverride ?: role.defaultColor
}

data class Mission(
    val missionId: MissionId,
    val title: String,
    val createdAt: Instant,
    val revision: Long,
    val routes: List<MissionRoute>,
    val jumpRanges: List<MissionJumpRange>,
    val markers: List<MissionMarker>,
    val referencedSystemIds: Set<Int>,
    val viewId: String = "view-1",
) {
    /**
     * Systems needed to frame Mission overlays. Generated jump-range coverage is visual-only:
     * it is deliberately excluded from referencedSystemIds and its resource limit.
     */
    val visualFitSystemIds: Set<Int>
        get() = buildSet {
            addAll(referencedSystemIds)
            jumpRanges.forEach { addAll(it.reachableSystemIds) }
        }
}
