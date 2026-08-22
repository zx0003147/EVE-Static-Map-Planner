package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.control.mission.Mission
import dev.evestaticmapplanner.control.mission.MissionJumpRange
import dev.evestaticmapplanner.control.mission.MissionMarker
import dev.evestaticmapplanner.control.mission.MissionRoute
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class MissionMapUiState(
    val missions: List<Mission> = emptyList(),
    val normalRoutes: List<MissionRoute.Normal> = emptyList(),
    val capitalRoutes: List<MissionRoute.Capital> = emptyList(),
    val jumpRanges: List<MissionJumpRange> = emptyList(),
    val markers: List<MissionMarker> = emptyList(),
)

class MissionMapStateStore(
    private val commitDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : MissionRenderStatePort {
    private val mutableState = MutableStateFlow(MissionMapUiState())
    val state: StateFlow<MissionMapUiState> = mutableState.asStateFlow()

    override suspend fun publish(missions: List<Mission>) = withContext(commitDispatcher) {
        val routes = missions.flatMap(Mission::routes)
        mutableState.value = MissionMapUiState(
            missions = missions,
            normalRoutes = routes.filterIsInstance<MissionRoute.Normal>(),
            capitalRoutes = routes.filterIsInstance<MissionRoute.Capital>(),
            jumpRanges = missions.flatMap(Mission::jumpRanges),
            markers = missions.flatMap(Mission::markers),
        )
    }
}
