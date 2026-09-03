package dev.evestaticmapplanner.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import dev.evestaticmapplanner.control.mission.MissionRegistry

class AppMapControlCoordinator(
    systemReadPort: SystemReadPort,
    routePlanningPort: RoutePlanningPort,
    jumpPlanningPort: JumpPlanningPort,
    viewportControlPort: ViewportControlPort,
    missionRenderStatePort: MissionRenderStatePort,
    savedMarkerControlPort: SavedMarkerControlPort,
    wormholeControlPort: WormholeControlPort,
    missionNavigationActionPort: MissionNavigationActionPort = UnavailableMissionNavigationActionPort,
    wormholeConnectionIds: Flow<Set<String>>? = null,
    planningViewControlPort: PlanningViewControlPort = SinglePlanningViewControlPort,
    scope: CoroutineScope,
    private val service: DefaultMapControlService = DefaultMapControlService(
        systemReadPort = systemReadPort,
        routePlanningPort = routePlanningPort,
        jumpPlanningPort = jumpPlanningPort,
        viewportControlPort = viewportControlPort,
        missionRenderStatePort = missionRenderStatePort,
        savedMarkerControlPort = savedMarkerControlPort,
        planningViewControlPort = planningViewControlPort,
        wormholeControlPort = wormholeControlPort,
        missionNavigationActionPort = missionNavigationActionPort,
        wormholeConnectionIds = wormholeConnectionIds,
        registry = MissionRegistry(),
        scope = scope,
    ),
) : MapControlService by service, AutoCloseable {
    override fun close() = service.close()
}
