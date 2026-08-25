package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapBounds
import dev.evestaticmapplanner.core.map.MapProjectionId
import dev.evestaticmapplanner.core.map.MapSceneCache
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.MapViewport
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.core.model.SolarSystemDetails
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.repository.UniverseRepository
import dev.evestaticmapplanner.preferences.AppPreferences
import dev.evestaticmapplanner.preferences.AiControlPreferences
import dev.evestaticmapplanner.preferences.DefaultPreferencesStore
import dev.evestaticmapplanner.preferences.MapDisplayPreferences
import dev.evestaticmapplanner.preferences.MarkerPreferences
import dev.evestaticmapplanner.preferences.OverlayVisibilityPreferences
import dev.evestaticmapplanner.preferences.PreferencesStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val preferencesStore: PreferencesStore = DefaultPreferencesStore,
) {
    private val startedAtNanos = clockNanos()
    private val mutableState = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = mutableState.asStateFlow()

    private var sceneCache: MapSceneCache? = null
    private var systemNamesById: Map<Int, String> = emptyMap()
    private val detailsCache = mutableMapOf<Int, SolarSystemDetails>()
    private var pendingFocusSystemId: Int? = null
    private var sceneBuildJob: Job? = null
    private var settingsSaveJob: Job? = null
    private val preferencesMutation = Mutex()

    init {
        load()
    }

    private fun load() {
        scope.launch {
            try {
                val loadStarted = clockNanos()
                val (data, appPreferences) = withContext(ioDispatcher) {
                    staticMapRepository.load() to preferencesStore.load()
                }
                val loadMillis = elapsedMillis(loadStarted)
                val cache = MapSceneCache(data)
                sceneCache = cache
                systemNamesById = data.systems.associate { it.id to it.name }
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
                        appPreferences = appPreferences,
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
                    viewport = focusedViewport(viewport, node.position, current.appPreferences.mapDisplay)
                }
                pendingFocusSystemId = null
            }
            current.copy(
                canvasSize = size,
                viewports = current.viewports + (current.projectionId to viewport),
                semanticLabelModes = current.semanticLabelModes + (
                    current.projectionId to nextSemanticMode(current, current.projectionId, viewport.zoom)
                ),
            )
        }
    }

    fun switchProjection(projectionId: MapProjectionId) {
        switchProjection(projectionId, focusSystemId = null, focusNotice = null)
    }

    private fun switchProjection(
        projectionId: MapProjectionId,
        focusSystemId: Int?,
        focusNotice: String?,
    ) {
        val current = mutableState.value
        if (projectionId == current.projectionId || sceneBuildJob?.isActive == true) return
        val cache = sceneCache ?: return
        sceneBuildJob = scope.launch {
            try {
                val wasCached = projectionId in cache.cachedProjectionIds()
                val (scene, buildMillis) = buildScene(cache, projectionId)
                mutableState.update { state ->
                    var viewport = state.viewports[projectionId]
                        ?: state.canvasSize.takeUnless(MapSize::isEmpty)?.let {
                            MapViewport.fit(scene.defaultFitBounds, it)
                        }
                    if (viewport != null && focusSystemId != null) {
                        scene.nodesById[focusSystemId]?.let { node ->
                            viewport = focusedViewport(
                                checkNotNull(viewport),
                                node.position,
                                state.appPreferences.mapDisplay,
                            )
                        }
                    }
                    state.copy(
                        projectionId = projectionId,
                        scene = scene,
                        hoveredSystemId = null,
                        contextMenu = null,
                        focusNotice = focusNotice,
                        viewports = if (viewport != null) state.viewports + (projectionId to viewport) else state.viewports,
                        semanticLabelModes = if (viewport != null) {
                            state.semanticLabelModes + (
                                projectionId to nextSemanticMode(state, projectionId, viewport.zoom)
                            )
                        } else {
                            state.semanticLabelModes
                        },
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
            val viewport = MapViewport.fit(scene.defaultFitBounds, current.canvasSize)
            current.copy(
                viewports = current.viewports + (current.projectionId to viewport),
                semanticLabelModes = current.semanticLabelModes + (
                    current.projectionId to SemanticZoomPolicy.initialMode(
                        viewport.zoom,
                        current.appPreferences.mapDisplay,
                    )
                ),
            )
        }
    }

    fun updateMapDisplayPreferences(preferences: MapDisplayPreferences) {
        mutableState.update { current ->
            current.copy(
                appPreferences = current.appPreferences.copy(mapDisplay = preferences),
                semanticLabelModes = current.viewports.mapValues { (_, viewport) ->
                    SemanticZoomPolicy.initialMode(viewport.zoom, preferences)
                },
            )
        }
        schedulePreferencesSave()
    }

    fun resetMapDisplayPreferences() {
        val defaults = MapDisplayPreferences.Defaults
        mutableState.update { current ->
            current.copy(
                appPreferences = current.appPreferences.copy(mapDisplay = defaults),
                semanticLabelModes = current.viewports.mapValues { (_, viewport) ->
                    SemanticZoomPolicy.initialMode(viewport.zoom, defaults)
                },
            )
        }
        schedulePreferencesSave()
    }

    fun updateMarkerPreferences(preferences: MarkerPreferences) {
        mutableState.update { current ->
            current.copy(appPreferences = current.appPreferences.copy(marker = preferences))
        }
        schedulePreferencesSave()
    }

    fun resetMarkerPreferences() {
        mutableState.update { current ->
            current.copy(appPreferences = current.appPreferences.copy(marker = MarkerPreferences.Defaults))
        }
        schedulePreferencesSave()
    }

    fun updateOverlayVisibilityPreferences(preferences: OverlayVisibilityPreferences) {
        mutableState.update { current ->
            current.copy(appPreferences = current.appPreferences.copy(overlayVisibility = preferences))
        }
        schedulePreferencesSave()
    }

    fun resetOverlayVisibilityPreferences() {
        updateOverlayVisibilityPreferences(OverlayVisibilityPreferences.Defaults)
    }

    suspend fun updateAiControlPreferences(preferences: AiControlPreferences): Result<Unit> = withContext(NonCancellable) {
        preferencesMutation.withLock {
            settingsSaveJob?.cancel()
            val next = mutableState.value.appPreferences.copy(aiControl = preferences)
            val saved = withContext(ioDispatcher) { runCatching { preferencesStore.save(next) } }
            if (saved.isSuccess) {
                mutableState.update { current ->
                    current.copy(appPreferences = current.appPreferences.copy(aiControl = preferences))
                }
            }
            saved
        }
    }

    suspend fun resetAllPreferences(): Result<Unit> = withContext(NonCancellable) {
        preferencesMutation.withLock {
            settingsSaveJob?.cancel()
            val defaults = AppPreferences.Defaults
            val saved = withContext(ioDispatcher) { runCatching { preferencesStore.save(defaults) } }
            if (saved.isSuccess) {
                mutableState.update { current ->
                    current.copy(
                        appPreferences = defaults,
                        semanticLabelModes = current.viewports.mapValues { (_, viewport) ->
                            SemanticZoomPolicy.initialMode(viewport.zoom, defaults.mapDisplay)
                        },
                    )
                }
            }
            saved
        }
    }

    fun zoomAt(screenPosition: MapPoint, scrollDelta: Double) {
        mutableState.update { current ->
            val viewport = current.viewport ?: return@update current
            if (current.canvasSize.isEmpty) return@update current
            val transform = MapTransform(viewport, current.canvasSize)
            val factor = ZOOM_BASE.pow(-scrollDelta)
            val zoomed = transform.zoomAt(screenPosition, factor, MIN_ZOOM, MAX_ZOOM)
            current.copy(
                viewports = current.viewports + (current.projectionId to zoomed),
                semanticLabelModes = current.semanticLabelModes + (
                    current.projectionId to nextSemanticMode(current, current.projectionId, zoomed.zoom)
                ),
                contextMenu = null,
            )
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

    fun selectSystemById(systemId: Int) {
        selectSystem(systemId)
    }

    fun selectAndFocusSystem(systemId: Int) {
        sceneBuildJob?.cancel()
        sceneBuildJob = null
        selectSystem(systemId)
        val current = mutableState.value
        val scene = current.scene ?: return
        val node = scene.nodesById[systemId]
        if (node != null) {
            mutableState.update { state ->
                val activeNode = state.scene?.nodesById?.get(systemId) ?: return@update state
                val viewport = state.viewport ?: return@update state
                val focused = focusedViewport(viewport, activeNode.position, state.appPreferences.mapDisplay)
                state.copy(
                    viewports = state.viewports + (state.projectionId to focused),
                    semanticLabelModes = state.semanticLabelModes + (
                        state.projectionId to nextSemanticMode(state, state.projectionId, focused.zoom)
                    ),
                    hoveredSystemId = null,
                    contextMenu = null,
                    focusNotice = null,
                )
            }
            return
        }
        if (current.projectionId == MapProjectionId.OFFICIAL_2D && systemId in scene.omittedSystemIds) {
            val systemName = systemNamesById[systemId] ?: systemId.toString()
            switchProjection(
                projectionId = MapProjectionId.REAL_XZ,
                focusSystemId = systemId,
                focusNotice = "$systemName is unavailable in Official 2D; switched to Real X-Z.",
            )
        }
    }

    /** Completion-aware control adapter entry; the existing search-focus behavior remains unchanged. */
    suspend fun focusSystemForControl(systemId: Int): Boolean {
        if (systemId !in systemNamesById || mutableState.value.scene == null || mutableState.value.canvasSize.isEmpty) {
            return false
        }
        selectAndFocusSystem(systemId)
        sceneBuildJob?.join()
        val current = mutableState.value
        val node = current.scene?.nodesById?.get(systemId) ?: return false
        return current.selectedSystemId == systemId && current.viewport?.center == node.position
    }

    /** Fits caller-provided Mission visual systems and falls back from Official 2D when necessary. */
    suspend fun fitSystemsForControl(systemIds: Set<Int>): Boolean {
        if (systemIds.isEmpty() || !systemNamesById.keys.containsAll(systemIds)) return false
        var current = mutableState.value
        if (current.scene == null || current.canvasSize.isEmpty) return false
        if (current.projectionId == MapProjectionId.OFFICIAL_2D && systemIds.any { it in current.scene.omittedSystemIds }) {
            sceneBuildJob?.cancel()
            sceneBuildJob = null
            switchProjection(MapProjectionId.REAL_XZ, focusSystemId = null, focusNotice = null)
            sceneBuildJob?.join()
            current = mutableState.value
        }
        val scene = current.scene ?: return false
        val points = systemIds.map { scene.nodesById[it]?.position ?: return false }
        val viewport = MapViewport.fit(MapBounds.fromPoints(points), current.canvasSize)
        mutableState.update { state ->
            state.copy(
                viewports = state.viewports + (state.projectionId to viewport),
                semanticLabelModes = state.semanticLabelModes + (
                    state.projectionId to SemanticZoomPolicy.initialMode(viewport.zoom, state.appPreferences.mapDisplay)
                ),
                hoveredSystemId = null,
                contextMenu = null,
                focusNotice = null,
            )
        }
        return true
    }

    fun openContextMenuAt(screenPosition: MapPoint) {
        mutableState.update { current ->
            val systemId = hitTest(current, screenPosition, SELECT_RADIUS_PX)
            current.copy(
                contextMenu = systemId?.let { MapContextMenuState(it, screenPosition) },
            )
        }
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
        settingsSaveJob?.cancel()
        runBlocking(NonCancellable) {
            preferencesMutation.withLock {
                withContext(ioDispatcher) {
                    runCatching { preferencesStore.save(mutableState.value.appPreferences) }
                }
            }
        }
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

    private fun nextSemanticMode(
        state: MapUiState,
        projectionId: MapProjectionId,
        zoom: Double,
    ): SemanticLabelMode = state.semanticLabelModes[projectionId]?.let { current ->
        SemanticZoomPolicy.transition(current, zoom, state.appPreferences.mapDisplay)
    } ?: SemanticZoomPolicy.initialMode(zoom, state.appPreferences.mapDisplay)

    private fun schedulePreferencesSave() {
        settingsSaveJob?.cancel()
        settingsSaveJob = scope.launch {
            delay(SETTINGS_SAVE_DEBOUNCE_MILLIS)
            preferencesMutation.withLock {
                withContext(ioDispatcher) {
                    runCatching { preferencesStore.save(mutableState.value.appPreferences) }
                }
            }
        }
    }

    private fun formatMillis(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value)

    private fun formatSecurity(value: Double): String = "%.6f".format(java.util.Locale.ROOT, value)

    private fun focusedViewport(
        viewport: MapViewport,
        target: MapPoint,
        preferences: MapDisplayPreferences,
    ): MapViewport {
        val zoom = if (viewport.zoom >= preferences.systemZoomThreshold) {
            viewport.zoom
        } else {
            (preferences.systemZoomThreshold * SEARCH_FOCUS_ZOOM_MARGIN).coerceIn(MIN_ZOOM, MAX_ZOOM)
        }
        return MapViewport(center = target, zoom = zoom)
    }
}

private const val HOVER_RADIUS_PX = 10.0
private const val SELECT_RADIUS_PX = 12.0
private const val ZOOM_BASE = 1.2
private const val MIN_ZOOM = 0.01
private const val MAX_ZOOM = 250.0
private const val SEARCH_FOCUS_ZOOM_MARGIN = 1.05
private const val SETTINGS_SAVE_DEBOUNCE_MILLIS = 150L
