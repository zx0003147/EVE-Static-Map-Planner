package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapProjectionId
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapViewport
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.core.model.SolarSystemDetails
import dev.evestaticmapplanner.preferences.AppPreferences

data class MapContextMenuState(
    val systemId: Int,
    val screenPosition: MapPoint,
)

data class MapPerformanceState(
    val dataLoadMillis: Double? = null,
    val sceneBuildMillis: Map<MapProjectionId, Double> = emptyMap(),
    val sceneBuildCount: Int = 0,
    val firstMapDisplayMillis: Double? = null,
)

data class MapUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val projectionId: MapProjectionId = MapProjectionId.OFFICIAL_2D,
    val scene: ProjectedMapScene? = null,
    val canvasSize: MapSize = MapSize(0.0, 0.0),
    val viewports: Map<MapProjectionId, MapViewport> = emptyMap(),
    val semanticLabelModes: Map<MapProjectionId, SemanticLabelMode> = emptyMap(),
    val appPreferences: AppPreferences = AppPreferences.Defaults,
    val hoveredSystemId: Int? = null,
    val selectedSystemId: Int? = null,
    val selectedSystemDetails: SolarSystemDetails? = null,
    val contextMenu: MapContextMenuState? = null,
    val focusNotice: String? = null,
    val performance: MapPerformanceState = MapPerformanceState(),
) {
    val viewport: MapViewport? get() = viewports[projectionId]
    val semanticLabelMode: SemanticLabelMode
        get() = semanticLabelModes[projectionId]
            ?: viewport?.zoom?.let { SemanticZoomPolicy.initialMode(it, appPreferences.mapDisplay) }
            ?: SemanticLabelMode.REGION_ONLY
}
