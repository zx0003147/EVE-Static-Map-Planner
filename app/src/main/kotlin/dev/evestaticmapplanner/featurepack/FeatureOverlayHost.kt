package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.OverlayEntry
import dev.evestaticmapplanner.feature.api.OverlayLayer
import dev.evestaticmapplanner.feature.api.OverlayLayerState
import dev.evestaticmapplanner.feature.api.OverlayProvider
import dev.evestaticmapplanner.feature.api.OverlayProviderDescriptor
import dev.evestaticmapplanner.feature.api.OverlayRegistration
import dev.evestaticmapplanner.feature.api.OverlayRegistry
import dev.evestaticmapplanner.feature.api.OverlayState
import dev.evestaticmapplanner.feature.api.PackId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application-owned aggregation boundary between external providers and map presentation.
 *
 * Provider methods are called only when registration changes or [refresh] is requested.
 * There is no polling loop, background thread, network access, or database access.
 */
class FeatureOverlayHost {
    private val providers = linkedMapOf<ProviderKey, HostedProvider>()
    private val mutableState = MutableStateFlow(OverlayState(emptyList()))
    val state: StateFlow<OverlayState> = mutableState.asStateFlow()

    internal fun scopedRegistry(packId: PackId): ScopedOverlayRegistry = ScopedOverlayRegistry(packId, this)

    @Synchronized
    fun refresh(): OverlayState = rebuildState()

    @Synchronized
    internal fun register(packId: PackId, provider: OverlayProvider): OverlayRegistration {
        val descriptor = provider.descriptor()
        require(providers.values.none { it.descriptor.id == descriptor.id }) {
            "Overlay provider ID is already registered: ${descriptor.id}"
        }
        val layers = provider.layers().toList()
        require(layers.isNotEmpty()) { "Overlay provider must declare at least one layer: ${descriptor.id}" }
        require(layers.map(OverlayLayer::id).distinct().size == layers.size) {
            "Overlay provider declares duplicate layer IDs: ${descriptor.id}"
        }

        val key = ProviderKey(packId, descriptor.id)
        val hosted = HostedProvider(descriptor, layers, provider)
        validateEntries(hosted, provider.snapshot().entries)
        providers[key] = hosted
        rebuildState()
        return HostOverlayRegistration { unregister(key) }
    }

    @Synchronized
    private fun unregister(key: ProviderKey) {
        if (providers.remove(key) != null) rebuildState()
    }

    private fun rebuildState(): OverlayState {
        val combinedLayers = providers.values.flatMap { hosted ->
            val entries = hosted.provider.snapshot().entries
            validateEntries(hosted, entries)
            hosted.layers.map { layer ->
                OverlayLayerState(
                    provider = hosted.descriptor,
                    layer = layer,
                    entries = entries.filter { it.layerId == layer.id },
                )
            }
        }.sortedWith(compareBy<OverlayLayerState> { it.layer.priority }
            .thenBy { it.provider.id }
            .thenBy { it.layer.id })
        return OverlayState(combinedLayers).also { mutableState.value = it }
    }

    private fun validateEntries(hosted: HostedProvider, entries: List<OverlayEntry>) {
        val layerIds = hosted.layers.mapTo(hashSetOf(), OverlayLayer::id)
        require(entries.all { it.layerId in layerIds }) {
            "Overlay provider ${hosted.descriptor.id} returned an entry for an undeclared layer"
        }
        require(entries.map { it.layerId to it.systemId }.distinct().size == entries.size) {
            "Overlay provider ${hosted.descriptor.id} returned duplicate system entries for a layer"
        }
    }

    private data class ProviderKey(val packId: PackId, val providerId: String)

    private data class HostedProvider(
        val descriptor: OverlayProviderDescriptor,
        val layers: List<OverlayLayer>,
        val provider: OverlayProvider,
    )
}

internal class ScopedOverlayRegistry(
    private val packId: PackId,
    private val host: FeatureOverlayHost,
) : OverlayRegistry, AutoCloseable {
    private val registrations = linkedSetOf<OverlayRegistration>()
    private var closed = false

    @Synchronized
    override fun register(provider: OverlayProvider): OverlayRegistration {
        check(!closed) { "Overlay registry is closed for Feature Pack $packId" }
        val hostRegistration = host.register(packId, provider)
        val scopedRegistration = HostOverlayRegistration {
            hostRegistration.close()
            synchronized(this) { registrations.removeIf { it === hostRegistration } }
        }
        registrations += hostRegistration
        return scopedRegistration
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        registrations.toList().asReversed().forEach(OverlayRegistration::close)
        registrations.clear()
    }
}

private class HostOverlayRegistration(
    private val unregister: () -> Unit,
) : OverlayRegistration {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) unregister()
    }
}
