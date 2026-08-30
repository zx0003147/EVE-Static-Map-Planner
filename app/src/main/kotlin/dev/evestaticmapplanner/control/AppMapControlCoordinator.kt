package dev.evestaticmapplanner.control

import kotlinx.coroutines.CoroutineScope
import dev.evestaticmapplanner.control.mission.InMemoryOnlyMissionRepository
import dev.evestaticmapplanner.control.mission.MissionRegistry
import dev.evestaticmapplanner.control.mission.MissionRepository

class AppMapControlCoordinator(
    systemReadPort: SystemReadPort,
    routePlanningPort: RoutePlanningPort,
    jumpPlanningPort: JumpPlanningPort,
    viewportControlPort: ViewportControlPort,
    missionRenderStatePort: MissionRenderStatePort,
    savedMarkerControlPort: SavedMarkerControlPort,
    planningViewControlPort: PlanningViewControlPort = SinglePlanningViewControlPort,
    missionRepository: MissionRepository = InMemoryOnlyMissionRepository,
    scope: CoroutineScope,
    private val service: DefaultMapControlService = DefaultMapControlService(
        systemReadPort = systemReadPort,
        routePlanningPort = routePlanningPort,
        jumpPlanningPort = jumpPlanningPort,
        viewportControlPort = viewportControlPort,
        missionRenderStatePort = missionRenderStatePort,
        savedMarkerControlPort = savedMarkerControlPort,
        planningViewControlPort = planningViewControlPort,
        registry = MissionRegistry(repository = missionRepository),
        scope = scope,
    ),
) : MapControlService by service, AutoCloseable {
    override fun close() = service.close()
}
