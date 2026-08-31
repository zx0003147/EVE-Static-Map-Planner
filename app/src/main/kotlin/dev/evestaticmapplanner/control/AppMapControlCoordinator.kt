package dev.evestaticmapplanner.control

import kotlinx.coroutines.CoroutineScope
import dev.evestaticmapplanner.control.mission.MissionRegistry

class AppMapControlCoordinator(
    systemReadPort: SystemReadPort,
    routePlanningPort: RoutePlanningPort,
    jumpPlanningPort: JumpPlanningPort,
    viewportControlPort: ViewportControlPort,
    missionRenderStatePort: MissionRenderStatePort,
    savedMarkerControlPort: SavedMarkerControlPort,
    wormholeControlPort: WormholeControlPort,
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
        registry = MissionRegistry(),
        scope = scope,
    ),
) : MapControlService by service, AutoCloseable {
    override fun close() = service.close()
}
