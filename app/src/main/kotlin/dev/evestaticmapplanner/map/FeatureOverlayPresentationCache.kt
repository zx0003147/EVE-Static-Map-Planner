package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.feature.api.OverlayEntryVisibility
import dev.evestaticmapplanner.feature.api.OverlayState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

internal class FeatureOverlayGeometryKey private constructor(
    private val scene: ProjectedMapScene,
    private val overlaySignature: FeatureOverlayGeometrySignature,
) {
    val projectionId = scene.projectionId

    override fun equals(other: Any?): Boolean =
        other is FeatureOverlayGeometryKey && scene === other.scene && overlaySignature == other.overlaySignature

    override fun hashCode(): Int = 31 * System.identityHashCode(scene) + overlaySignature.hashCode()

    companion object {
        fun from(scene: ProjectedMapScene, state: OverlayState) = FeatureOverlayGeometryKey(
            scene = scene,
            overlaySignature = state.geometrySignature(),
        )
    }
}

private data class FeatureOverlayGeometrySignature(
    val layers: List<FeatureOverlayLayerGeometrySignature>,
)

private data class FeatureOverlayLayerGeometrySignature(
    val providerId: String,
    val layerId: String,
    val layerName: String,
    val entries: List<FeatureOverlayEntryGeometrySignature>,
)

private data class FeatureOverlayEntryGeometrySignature(
    val systemId: Int,
    val title: String?,
    val value: String?,
)

private fun OverlayState.geometrySignature(): FeatureOverlayGeometrySignature = FeatureOverlayGeometrySignature(
    layers = layers.mapNotNull { layerState ->
        val visibleEntries = layerState.entries.asSequence()
            .filter { it.visibility == OverlayEntryVisibility.VISIBLE }
            .sortedBy { it.systemId }
            .map { entry -> FeatureOverlayEntryGeometrySignature(entry.systemId, entry.title, entry.value) }
            .toList()
        visibleEntries.takeIf(List<FeatureOverlayEntryGeometrySignature>::isNotEmpty)?.let {
            FeatureOverlayLayerGeometrySignature(
                providerId = layerState.provider.id,
                layerId = layerState.layer.id,
                layerName = layerState.layer.name,
                entries = it,
            )
        }
    },
)

internal class FeatureOverlayPresentationCache(
    private val capacity: Int = FEATURE_OVERLAY_PRESENTATION_CACHE_CAPACITY,
) {
    private val presentations = LinkedHashMap<FeatureOverlayGeometryKey, FeatureOverlayPresentation>(capacity, 0.75f, true)
    private var currentKey: FeatureOverlayGeometryKey? = null

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun request(key: FeatureOverlayGeometryKey): FeatureOverlayPresentation? {
        currentKey = key
        return presentations[key]
    }

    @Synchronized
    fun peek(key: FeatureOverlayGeometryKey): FeatureOverlayPresentation? = presentations[key]

    @Synchronized
    fun complete(
        key: FeatureOverlayGeometryKey,
        presentation: FeatureOverlayPresentation,
    ): Boolean {
        presentations[key] = presentation
        while (presentations.size > capacity) {
            val eldest = presentations.entries.iterator()
            eldest.next()
            eldest.remove()
        }
        return key == currentKey
    }

    @Synchronized
    fun isCurrent(key: FeatureOverlayGeometryKey): Boolean = key == currentKey

    @Synchronized
    fun cachedKeys(): Set<FeatureOverlayGeometryKey> = presentations.keys.toSet()
}

internal sealed interface FeatureOverlayPresentationRequest {
    data class Cached(val presentation: FeatureOverlayPresentation) : FeatureOverlayPresentationRequest
    data class Pending(val result: Deferred<FeatureOverlayPresentation>) : FeatureOverlayPresentationRequest
}

internal class FeatureOverlayPresentationCoordinator(
    private val scope: CoroutineScope,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cache: FeatureOverlayPresentationCache = FeatureOverlayPresentationCache(),
    private val computer: suspend (OverlayState, ProjectedMapScene) -> FeatureOverlayPresentation =
        { state, scene -> FeatureOverlayPresentationBuilder.build(state, scene) },
) {
    private val inFlightLock = Any()
    private val inFlight = linkedMapOf<FeatureOverlayGeometryKey, Deferred<FeatureOverlayPresentation>>()

    fun peek(key: FeatureOverlayGeometryKey): FeatureOverlayPresentation? = cache.peek(key)

    fun request(
        key: FeatureOverlayGeometryKey,
        state: OverlayState,
        scene: ProjectedMapScene,
    ): FeatureOverlayPresentationRequest {
        cache.request(key)?.let { return FeatureOverlayPresentationRequest.Cached(it) }
        val deferred = synchronized(inFlightLock) {
            inFlight[key] ?: scope.async(computationDispatcher, start = CoroutineStart.LAZY) {
                computer(state, scene).also { presentation -> cache.complete(key, presentation) }
            }.also { created ->
                inFlight[key] = created
                created.invokeOnCompletion {
                    synchronized(inFlightLock) { inFlight.remove(key, created) }
                }
                created.start()
            }
        }
        return FeatureOverlayPresentationRequest.Pending(deferred)
    }

    fun isCurrent(key: FeatureOverlayGeometryKey): Boolean = cache.isCurrent(key)
}

internal data class KeyedFeatureOverlayPresentation(
    val key: FeatureOverlayGeometryKey,
    val presentation: FeatureOverlayPresentation,
)

private const val FEATURE_OVERLAY_PRESENTATION_CACHE_CAPACITY = 2
