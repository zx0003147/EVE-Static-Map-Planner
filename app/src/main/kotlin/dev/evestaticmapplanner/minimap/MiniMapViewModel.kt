package dev.evestaticmapplanner.minimap

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.map.HopNeighborhoodExtractor
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.MapViewport
import dev.evestaticmapplanner.core.map.MiniMapSlice
import dev.evestaticmapplanner.core.map.MiniMapSliceEdge
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.core.map.ProjectedCapitalRouteOverlayBuilder
import dev.evestaticmapplanner.core.map.ProjectedRouteLeg
import dev.evestaticmapplanner.core.map.ProjectedRouteOverlayBuilder
import dev.evestaticmapplanner.core.route.AnsiblexRouteEdgeBuilder
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.control.mission.MissionRoute
import dev.evestaticmapplanner.feature.api.TrackedCharacterLocationStatus
import dev.evestaticmapplanner.feature.api.TrackedCharacterSnapshot
import dev.evestaticmapplanner.preferences.MiniMapFollowMode
import dev.evestaticmapplanner.preferences.MiniMapPreferences
import dev.evestaticmapplanner.core.route.RouteEdgeType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MiniMapCharacterGroup(
    val systemId: Int,
    val characters: List<TrackedCharacterSnapshot>,
    val containsFollowedCharacter: Boolean,
)

data class MiniMapUiState(
    val preferences: MiniMapPreferences = MiniMapPreferences.Defaults,
    val characters: List<TrackedCharacterSnapshot> = emptyList(),
    val followedCharacterId: Long? = null,
    val followedSystemName: String? = null,
    val slice: MiniMapSlice? = null,
    val ansiblexVisualEdges: List<MiniMapSliceEdge> = emptyList(),
    val activeRoute: RouteResult? = null,
    val visibleRouteLegs: List<ProjectedRouteLeg> = emptyList(),
    internal val visibleRouteOverlays: List<MiniMapRouteOverlay> = emptyList(),
    val characterGroups: List<MiniMapCharacterGroup> = emptyList(),
    val canvasSize: MapSize = MapSize(0.0, 0.0),
    val viewport: MapViewport? = null,
    val diagnostic: String? = null,
) {
    val followedCharacter: TrackedCharacterSnapshot?
        get() = characters.firstOrNull { it.characterId == followedCharacterId }
}

/** Mini-map state owns only a slice and viewport; the full scene remains shared with the main map. */
class MiniMapViewModel(
    initialPreferences: MiniMapPreferences = MiniMapPreferences.Defaults,
    private val persistPreferences: (MiniMapPreferences) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(MiniMapUiState(preferences = initialPreferences))
    private var scene: ProjectedMapScene? = null
    private var ansiblexConnections: List<AnsiblexConnection> = emptyList()
    private var missionNormalRoutes: List<MissionRoute.Normal> = emptyList()
    private var missionCapitalRoutes: List<MissionRoute.Capital> = emptyList()
    private var automaticCharacterId: Long? = null

    val state: StateFlow<MiniMapUiState> = mutableState.asStateFlow()

    fun updateScene(scene: ProjectedMapScene?) {
        if (this.scene === scene) return
        this.scene = scene
        rebuild(fit = true)
    }

    fun updateCharacters(characters: List<TrackedCharacterSnapshot>) {
        mutableState.update { it.copy(characters = characters) }
        rebuild(fit = false)
    }

    /** Receives the already-loaded Route state; no repository or database is opened here. */
    fun updateAnsiblexConnections(connections: List<AnsiblexConnection>) {
        if (ansiblexConnections == connections) return
        ansiblexConnections = connections
        rebuild(fit = false)
    }

    /** Displays the main planner's already-calculated route; no route is calculated here. */
    fun updateActiveRoute(route: RouteResult?) {
        if (mutableState.value.activeRoute === route) return
        mutableState.update { it.copy(activeRoute = route) }
        rebuild(fit = false)
    }

    /** Displays the Mission store's authoritative route results; no route is calculated here. */
    fun updateMissionRoutes(
        normalRoutes: List<MissionRoute.Normal>,
        capitalRoutes: List<MissionRoute.Capital>,
    ) {
        if (missionNormalRoutes == normalRoutes && missionCapitalRoutes == capitalRoutes) return
        missionNormalRoutes = normalRoutes.toList()
        missionCapitalRoutes = capitalRoutes.toList()
        rebuild(fit = false)
    }

    fun setAutomaticCharacter(characterId: Long?) {
        automaticCharacterId = characterId
        rebuild(fit = true)
    }

    fun setPinnedCharacter(characterId: Long) {
        val next = mutableState.value.preferences.copy(
            followMode = MiniMapFollowMode.PINNED,
            pinnedCharacterId = characterId,
        )
        updatePreferences(next, fit = true)
    }

    fun setFollowMode(mode: MiniMapFollowMode) {
        updatePreferences(mutableState.value.preferences.copy(followMode = mode), fit = true)
    }

    fun setEnabled(enabled: Boolean) {
        updatePreferences(mutableState.value.preferences.copy(enabled = enabled), fit = false)
    }

    fun setRange(hops: Int) {
        require(hops in 1..5)
        updatePreferences(mutableState.value.preferences.copy(stargateHops = hops), fit = true)
    }

    fun updatePreferences(preferences: MiniMapPreferences, fit: Boolean = false) {
        mutableState.update { it.copy(preferences = preferences) }
        persistPreferences(preferences)
        rebuild(fit)
    }

    fun restorePreferences(preferences: MiniMapPreferences) {
        if (mutableState.value.preferences == preferences) return
        mutableState.update { it.copy(preferences = preferences) }
        rebuild(fit = true)
    }

    fun updateCanvasSize(size: MapSize) {
        if (size == mutableState.value.canvasSize) return
        mutableState.update { current ->
            val viewport = current.slice?.let { slice ->
                if (size.isEmpty) null else MapViewport.fit(slice.localBounds, size, MINI_MAP_PADDING)
            }
            current.copy(canvasSize = size, viewport = viewport)
        }
    }

    fun panBy(screenDelta: MapPoint) {
        mutableState.update { current -> current.copy(viewport = current.viewport?.panBy(screenDelta)) }
    }

    fun zoomAt(screenPosition: MapPoint, scrollDelta: Double) {
        mutableState.update { current ->
            val viewport = current.viewport ?: return@update current
            if (current.canvasSize.isEmpty) return@update current
            val transform = MapTransform(viewport, current.canvasSize)
            current.copy(viewport = transform.zoomAt(screenPosition, ZOOM_BASE.pow(scrollDelta), MIN_ZOOM, MAX_ZOOM))
        }
    }

    private fun rebuild(fit: Boolean) {
        mutableState.update { current ->
            val followedId = when (current.preferences.followMode) {
                MiniMapFollowMode.PINNED -> current.preferences.pinnedCharacterId
                MiniMapFollowMode.AUTO -> automaticCharacterId
            }
            val followed = current.characters.firstOrNull { it.characterId == followedId }
            val followedSystemId = followed?.solarSystemId
            val currentScene = scene
            val followedSystemName = followedSystemId?.let { currentScene?.nodesById?.get(it)?.system?.name }
            val slice = when {
                followed == null -> null
                !followed.trackingEnabled -> null
                followedSystemId == null -> null
                currentScene?.nodesById?.containsKey(followedSystemId) != true -> null
                else -> {
                    val includeAnsiblex = current.preferences.includeAnsiblexEdges
                    HopNeighborhoodExtractor.extract(
                        currentScene,
                        followedSystemId,
                        current.preferences.stargateHops,
                        allowedEdgeTypes = if (includeAnsiblex) {
                            setOf(RouteEdgeType.STARGATE, RouteEdgeType.ANSIBLEX)
                        } else {
                            setOf(RouteEdgeType.STARGATE)
                        },
                        additionalEdges = if (includeAnsiblex) {
                            AnsiblexRouteEdgeBuilder.build(ansiblexConnections)
                        } else {
                            emptyList()
                        },
                    )
                }
            }
            val groups = slice?.let { miniSlice ->
                current.characters.asSequence()
                    .filter { it.trackingEnabled && it.solarSystemId in miniSlice.includedSystemIds }
                    .filter { it.locationStatus != TrackedCharacterLocationStatus.UNKNOWN }
                    .groupBy { checkNotNull(it.solarSystemId) }
                    .map { (systemId, atSystem) ->
                        MiniMapCharacterGroup(
                            systemId,
                            atSystem.sortedWith(
                                compareBy(String.CASE_INSENSITIVE_ORDER, TrackedCharacterSnapshot::characterName),
                            ),
                            atSystem.any { it.characterId == followedId },
                        )
                    }
                    .sortedBy(MiniMapCharacterGroup::systemId)
            }.orEmpty()
            val ansiblexVisualEdges = slice?.edges.orEmpty().filter { it.type == RouteEdgeType.ANSIBLEX }
            val visibleRouteLegs = if (slice != null && currentScene != null && current.activeRoute != null) {
                ProjectedRouteOverlayBuilder.build(current.activeRoute, currentScene).legs
                    .filterVisibleIn(slice.includedSystemIds, { it.edge.fromSystemId }, { it.edge.toSystemId })
            } else {
                emptyList()
            }
            val visibleRouteOverlays = if (slice != null && currentScene != null) {
                buildList {
                    visibleRouteLegs.toMiniMapOverlay(
                        id = USER_NORMAL_ROUTE_OVERLAY_ID,
                        kind = MiniMapRouteKind.USER_NORMAL,
                        styleIndex = 0,
                    )?.let(::add)
                    missionNormalRoutes.forEachIndexed { index, missionRoute ->
                        ProjectedRouteOverlayBuilder.build(missionRoute.route, currentScene).legs
                            .filterVisibleIn(
                                slice.includedSystemIds,
                                { it.edge.fromSystemId },
                                { it.edge.toSystemId },
                            )
                            .toMiniMapOverlay(
                                id = missionRoute.overlayId(),
                                kind = MiniMapRouteKind.MISSION_NORMAL,
                                styleIndex = index,
                            )?.let(::add)
                    }
                    missionCapitalRoutes.forEachIndexed { index, missionRoute ->
                        val segments = ProjectedCapitalRouteOverlayBuilder.build(missionRoute.route, currentScene).legs
                            .filterVisibleIn(
                                slice.includedSystemIds,
                                { it.fromSystemId },
                                { it.toSystemId },
                            )
                            .map { leg ->
                                MiniMapRouteSegment(
                                    fromSystemId = leg.fromSystemId,
                                    toSystemId = leg.toSystemId,
                                    from = leg.from,
                                    to = leg.to,
                                )
                            }
                        if (segments.isNotEmpty()) {
                            add(
                                MiniMapRouteOverlay(
                                    id = missionRoute.overlayId(),
                                    kind = MiniMapRouteKind.MISSION_CAPITAL,
                                    styleIndex = index,
                                    segments = segments,
                                ),
                            )
                        }
                    }
                }
            } else {
                emptyList()
            }
            val viewport = when {
                slice == null || current.canvasSize.isEmpty -> null
                fit || current.slice?.centerSystemId != slice.centerSystemId ||
                    current.slice.maxHops != slice.maxHops ||
                    current.slice.includedSystemIds != slice.includedSystemIds ->
                    MapViewport.fit(slice.localBounds, current.canvasSize, MINI_MAP_PADDING)
                else -> current.viewport
            }
            current.copy(
                followedCharacterId = followedId,
                followedSystemName = followedSystemName,
                slice = slice,
                ansiblexVisualEdges = ansiblexVisualEdges,
                visibleRouteLegs = visibleRouteLegs,
                visibleRouteOverlays = visibleRouteOverlays,
                characterGroups = groups,
                viewport = viewport,
                diagnostic = when {
                    current.characters.isEmpty() ->
                        "No tracked characters\nConnect a character in ESI Pack first."
                    followedId == null -> "Select a character to follow"
                    followed == null -> "Followed character is unavailable"
                    !followed.trackingEnabled -> "Tracking is disabled for ${followed.characterName}"
                    followed.solarSystemId == null -> "Location unavailable for ${followed.characterName}"
                    slice == null -> "Character location is outside the current map projection"
                    else -> null
                },
            )
        }
    }

    private companion object {
        const val USER_NORMAL_ROUTE_OVERLAY_ID = "user:normal"
        const val MINI_MAP_PADDING = 36.0
        const val ZOOM_BASE = 1.12
        const val MIN_ZOOM = 0.00001
        const val MAX_ZOOM = 10_000.0
    }
}

private fun List<ProjectedRouteLeg>.toMiniMapOverlay(
    id: String,
    kind: MiniMapRouteKind,
    styleIndex: Int,
): MiniMapRouteOverlay? {
    if (isEmpty()) return null
    return MiniMapRouteOverlay(
        id = id,
        kind = kind,
        styleIndex = styleIndex,
        segments = map { leg ->
            MiniMapRouteSegment(
                fromSystemId = leg.edge.fromSystemId,
                toSystemId = leg.edge.toSystemId,
                from = leg.from,
                to = leg.to,
                edgeType = leg.edge.type,
            )
        },
    )
}

private fun MissionRoute.overlayId(): String = "mission:${missionId.value}:${routeId.value}"

private inline fun <T> List<T>.filterVisibleIn(
    visibleSystemIds: Set<Int>,
    fromSystemId: (T) -> Int,
    toSystemId: (T) -> Int,
): List<T> = filter { item ->
    fromSystemId(item) in visibleSystemIds && toSystemId(item) in visibleSystemIds
}

private fun Double.pow(exponent: Double): Double = Math.pow(this, exponent)
