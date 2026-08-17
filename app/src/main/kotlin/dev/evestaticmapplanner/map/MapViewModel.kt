package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapProjectionId
import dev.evestaticmapplanner.core.map.MapSceneCache
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.MapViewport
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.core.model.SolarSystemDetails
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.repository.UniverseRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

class MapViewModel(
    private val staticMapRepository: StaticMapRepository,
    private val universeRepository: UniverseRepository,
    private val focusSystemName: String?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sceneDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val startedAtNanos = clockNanos()
    private val mutableState = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = mutableState.asStateFlow()

    private var sceneCache: MapSceneCache? = null
    private val detailsCache = mutableMapOf<Int, SolarSystemDetails>()
    private var pendingFocusSystemId: Int? = null
    private var sceneBuildJob: Job? = null

    init {
        load()
    }

    private fun load() {
        scope.launch {
            try {
                val loadStarted = clockNanos()
                val data = withContext(ioDispatcher) { staticMapRepository.load() }
                val loadMillis = elapsedMillis(loadStarted)
                val cache = MapSceneCache(data)
                sceneCache = cache
                val (scene, buildMillis) = buildScene(cache, MapProjectionId.OFFICIAL_2D)
                val focusId = focusSystemName?.let { requested ->
                    data.systems.singleOrNull { it.name.equals(requested, ignoreCase = true) }?.id
                        ?: throw IllegalArgumentException("Solar system not found: $requested")
                }
                pendingFocusSystemId = focusId
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        scene = scene,
                        selectedSystemId = focusId,
                        performance = it.performance.copy(
                            dataLoadMillis = loadMillis,
                            sceneBuildMillis = mapOf(MapProjectionId.OFFICIAL_2D to buildMillis),
                            sceneBuildCount = 1,
                        ),
                    )
                }
                println("MAP_DATA_LOAD ms=${formatMillis(loadMillis)}")
                println("MAP_SCENE projection=${scene.projectionId} buildMs=${formatMillis(buildMillis)}")
                println("MAP_COUNTS systems=${scene.nodes.size} edges=${scene.edges.size}")
                if (focusId != null) loadDetails(focusId)
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(isLoading = false, error = error.message ?: error::class.simpleName ?: "Unknown error")
                }
            }
        }
    }

    fun onCanvasSizeChanged(size: MapSize) {
        if (size.isEmpty) return
        mutableState.update { current ->
            val scene = current.scene ?: return@update current.copy(canvasSize = size)
            var viewport = current.viewports[current.projectionId]
                ?: MapViewport.fit(scene.defaultFitBounds, size)
            val focusId = pendingFocusSystemId
            if (focusId != null) {
                scene.nodesById[focusId]?.let { node ->
                    viewport = viewport.copy(
                        center = node.position,
                        zoom = (viewport.zoom * FOCUS_ZOOM_MULTIPLIER).coerceAtMost(MAX_ZOOM),
                    )
                }
                pendingFocusSystemId = null
            }
            current.copy(
                canvasSize = size,
                viewports = current.viewports + (current.projectionId to viewport),
            )
        }
    }

    fun switchProjection(projectionId: MapProjectionId) {
        val current = mutableState.value
        if (projectionId == current.projectionId || sceneBuildJob?.isActive == true) return
        val cache = sceneCache ?: return
        sceneBuildJob = scope.launch {
            try {
                val wasCached = projectionId in cache.cachedProjectionIds()
                val (scene, buildMillis) = buildScene(cache, projectionId)
                mutableState.update { state ->
                    val viewport = state.viewports[projectionId]
                        ?: state.canvasSize.takeUnless(MapSize::isEmpty)?.let {
                            MapViewport.fit(scene.defaultFitBounds, it)
                        }
                    state.copy(
                        projectionId = projectionId,
                        scene = scene,
                        hoveredSystemId = null,
                        contextMenu = null,
                        viewports = if (viewport != null) state.viewports + (projectionId to viewport) else state.viewports,
                        performance = if (wasCached) {
                            state.performance
                        } else {
                            state.performance.copy(
                                sceneBuildMillis = state.performance.sceneBuildMillis + (projectionId to buildMillis),
                                sceneBuildCount = state.performance.sceneBuildCount + 1,
                            )
                        },
                    )
                }
                if (!wasCached) {
                    println("MAP_SCENE projection=${scene.projectionId} buildMs=${formatMillis(buildMillis)}")
                    println("MAP_COUNTS systems=${scene.nodes.size} edges=${scene.edges.size}")
                }
            } catch (error: Throwable) {
                mutableState.update { it.copy(error = error.message ?: "Unable to build map scene") }
            }
        }
    }

    fun fitMap() {
        mutableState.update { current ->
            val scene = current.scene ?: return@update current
            if (current.canvasSize.isEmpty) return@update current
            current.copy(
                viewports = current.viewports + (
                    current.projectionId to MapViewport.fit(scene.defaultFitBounds, current.canvasSize)
                ),
            )
        }
    }

    fun zoomAt(screenPosition: MapPoint, scrollDelta: Double) {
        mutableState.update { current ->
            val viewport = current.viewport ?: return@update current
            if (current.canvasSize.isEmpty) return@update current
            val transform = MapTransform(viewport, current.canvasSize)
            val factor = ZOOM_BASE.pow(-scrollDelta)
            val zoomed = transform.zoomAt(screenPosition, factor, MIN_ZOOM, MAX_ZOOM)
            current.copy(viewports = current.viewports + (current.projectionId to zoomed), contextMenu = null)
        }
    }

    fun panBy(screenDelta: MapPoint) {
        mutableState.update { current ->
            val viewport = current.viewport ?: return@update current
            current.copy(
                viewports = current.viewports + (current.projectionId to viewport.panBy(screenDelta)),
                hoveredSystemId = null,
                contextMenu = null,
            )
        }
    }

    fun hoverAt(screenPosition: MapPoint) {
        mutableState.update { current ->
            val hit = hitTest(current, screenPosition, HOVER_RADIUS_PX)
            if (hit == current.hoveredSystemId) current else current.copy(hoveredSystemId = hit)
        }
    }

    fun clearHover() {
        mutableState.update { if (it.hoveredSystemId == null) it else it.copy(hoveredSystemId = null) }
    }

    fun selectAt(screenPosition: MapPoint) {
        val systemId = hitTest(mutableState.value, screenPosition, SELECT_RADIUS_PX)
        selectSystem(systemId)
    }

    fun openContextMenuAt(screenPosition: MapPoint) {
        mutableState.update { current ->
            val systemId = hitTest(current, screenPosition, SELECT_RADIUS_PX)
            current.copy(
                contextMenu = systemId?.let { MapContextMenuState(it, screenPosition) },
            )
        }
    }

    fun selectContextMenuSystem() {
        val systemId = mutableState.value.contextMenu?.systemId ?: return
        selectSystem(systemId)
    }

    fun dismissContextMenu() {
        mutableState.update { if (it.contextMenu == null) it else it.copy(contextMenu = null) }
    }

    fun onFirstMapDisplayed() {
        mutableState.update { current ->
            if (current.performance.firstMapDisplayMillis != null) return@update current
            val millis = elapsedMillis(startedAtNanos)
            println("MAP_FIRST_DISPLAY elapsedMs=${formatMillis(millis)}")
            current.copy(performance = current.performance.copy(firstMapDisplayMillis = millis))
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun selectSystem(systemId: Int?) {
        mutableState.update {
            it.copy(
                selectedSystemId = systemId,
                selectedSystemDetails = null,
                contextMenu = null,
            )
        }
        if (systemId != null) loadDetails(systemId)
    }

    private fun loadDetails(systemId: Int) {
        detailsCache[systemId]?.let { details ->
            mutableState.update { current ->
                if (current.selectedSystemId == systemId) current.copy(selectedSystemDetails = details) else current
            }
            return
        }
        scope.launch {
            val details = withContext(ioDispatcher) { universeRepository.getSystemDetails(systemId) }
            if (details != null) detailsCache[systemId] = details
            mutableState.update { current ->
                if (current.selectedSystemId == systemId) current.copy(selectedSystemDetails = details) else current
            }
            if (details != null && focusSystemName?.equals(details.system.name, ignoreCase = true) == true) {
                println("MAP_FOCUS name=${details.system.name} id=${details.system.id}")
                println("MAP_FOCUS_REGION region=${details.region.name}")
                println("MAP_FOCUS_CONSTELLATION name=${details.constellation.name}")
                println("MAP_FOCUS_INFO security=${formatSecurity(details.system.securityStatus)}")
                println("MAP_FOCUS_INFO stargates=${details.stargateCount}")
            }
        }
    }

    private fun hitTest(state: MapUiState, screenPosition: MapPoint, radiusPx: Double): Int? {
        val scene = state.scene ?: return null
        val viewport = state.viewport ?: return null
        if (state.canvasSize.isEmpty) return null
        val transform = MapTransform(viewport, state.canvasSize)
        return scene.spatialIndex.nearest(
            point = transform.screenToWorld(screenPosition),
            maxDistance = radiusPx / viewport.zoom,
        )
    }

    private suspend fun buildScene(
        cache: MapSceneCache,
        projectionId: MapProjectionId,
    ): Pair<ProjectedMapScene, Double> {
        val started = clockNanos()
        val scene = withContext(sceneDispatcher) { cache.get(projectionId) }
        return scene to elapsedMillis(started)
    }

    private fun elapsedMillis(started: Long): Double = (clockNanos() - started) / 1_000_000.0

    private fun formatMillis(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value)

    private fun formatSecurity(value: Double): String = "%.6f".format(java.util.Locale.ROOT, value)
}

private const val HOVER_RADIUS_PX = 10.0
private const val SELECT_RADIUS_PX = 12.0
private const val ZOOM_BASE = 1.2
private const val MIN_ZOOM = 0.01
private const val MAX_ZOOM = 250.0
private const val FOCUS_ZOOM_MULTIPLIER = 8.0
