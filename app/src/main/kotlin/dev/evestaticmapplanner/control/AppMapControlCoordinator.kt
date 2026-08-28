package dev.evestaticmapplanner.control

import kotlinx.coroutines.CoroutineScope

class AppMapControlCoordinator(
    systemReadPort: SystemReadPort,
    routePlanningPort: RoutePlanningPort,
    jumpPlanningPort: JumpPlanningPort,
    viewportControlPort: ViewportControlPort,
    missionRenderStatePort: MissionRenderStatePort,
    savedMarkerControlPort: SavedMarkerControlPort,
    scope: CoroutineScope,
    private val service: DefaultMapControlService = DefaultMapControlService(
        systemReadPort = systemReadPort,
        routePlanningPort = routePlanningPort,
        jumpPlanningPort = jumpPlanningPort,
        viewportControlPort = viewportControlPort,
        missionRenderStatePort = missionRenderStatePort,
        savedMarkerControlPort = savedMarkerControlPort,
        scope = scope,
    ),
) : MapControlService by service, AutoCloseable {
    override fun close() = service.close()
}
