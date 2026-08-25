package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.SystemInfoProvider
import dev.evestaticmapplanner.feature.api.SystemInfoProviderDescriptor
import dev.evestaticmapplanner.feature.api.SystemInfoRegistration
import dev.evestaticmapplanner.feature.api.SystemInfoRegistry
import dev.evestaticmapplanner.feature.api.SystemInfoSection
import dev.evestaticmapplanner.feature.api.SystemInfoState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application-owned aggregation boundary between Feature Pack providers and System Info presentation.
 *
 * Providers are queried synchronously only after selection, registration, removal, or explicit
 * provider invalidation. One provider failure is reported and omitted without affecting the others.
 */
class SystemInfoHost(
    private val failureSink: (providerId: String, error: Throwable) -> Unit = { _, _ -> },
) {
    private val providers = linkedMapOf<ProviderKey, HostedProvider>()
    private val mutableState = MutableStateFlow(SystemInfoState(null, emptyList()))
    val state: StateFlow<SystemInfoState> = mutableState.asStateFlow()

    internal fun scopedRegistry(packId: PackId): ScopedSystemInfoRegistry =
        ScopedSystemInfoRegistry(packId, this)

    @Synchronized
    fun request(systemId: Int?): SystemInfoState {
        require(systemId == null || systemId > 0) { "Selected System Info system ID must be positive" }
        return rebuildState(systemId)
    }

    @Synchronized
    fun refresh(): SystemInfoState = rebuildState(mutableState.value.systemId)

    @Synchronized
    internal fun register(packId: PackId, provider: SystemInfoProvider): SystemInfoRegistration {
        val descriptor = provider.descriptor()
        require(providers.values.none { it.descriptor.id == descriptor.id }) {
            "System Info provider ID is already registered: ${descriptor.id}"
        }
        val key = ProviderKey(packId, descriptor.id)
        providers[key] = HostedProvider(descriptor, provider)
        rebuildState(mutableState.value.systemId)
        return HostSystemInfoRegistration(
            refresh = ::refresh,
            unregister = { unregister(key) },
        )
    }

    @Synchronized
    private fun unregister(key: ProviderKey) {
        if (providers.remove(key) != null) rebuildState(mutableState.value.systemId)
    }

    private fun rebuildState(systemId: Int?): SystemInfoState {
        if (systemId == null) return SystemInfoState(null, emptyList()).also { mutableState.value = it }

        val sections = providers.values.flatMap { hosted ->
            val snapshot = try {
                hosted.provider.provide(systemId)
            } catch (error: Throwable) {
                rethrowIfFatal(error)
                runCatching { failureSink(hosted.descriptor.id, error) }
                return@flatMap emptyList()
            }
            if (snapshot.systemId != systemId) {
                val error = IllegalArgumentException(
                    "System Info provider ${hosted.descriptor.id} returned system ${snapshot.systemId} " +
                        "for requested system $systemId",
                )
                runCatching { failureSink(hosted.descriptor.id, error) }
                return@flatMap emptyList()
            }
            snapshot.sections.map { section -> HostedSection(hosted.descriptor, section) }
        }.sortedWith(
            compareByDescending<HostedSection> { it.provider.priority }
                .thenByDescending { it.section.priority }
                .thenBy { it.provider.id }
                .thenBy { it.section.sectionId },
        ).map(HostedSection::section)

        return SystemInfoState(systemId, sections).also { mutableState.value = it }
    }

    private data class ProviderKey(val packId: PackId, val providerId: String)

    private data class HostedProvider(
        val descriptor: SystemInfoProviderDescriptor,
        val provider: SystemInfoProvider,
    )

    private data class HostedSection(
        val provider: SystemInfoProviderDescriptor,
        val section: SystemInfoSection,
    )
}

internal class ScopedSystemInfoRegistry(
    private val packId: PackId,
    private val host: SystemInfoHost,
) : SystemInfoRegistry, AutoCloseable {
    private val registrations = linkedSetOf<SystemInfoRegistration>()
    private var closed = false

    @Synchronized
    override fun register(provider: SystemInfoProvider): SystemInfoRegistration {
        check(!closed) { "System Info registry is closed for Feature Pack $packId" }
        val hostRegistration = host.register(packId, provider)
        registrations += hostRegistration
        return ScopedSystemInfoRegistration(hostRegistration) {
            synchronized(this) { registrations.remove(hostRegistration) }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        registrations.toList().asReversed().forEach(SystemInfoRegistration::close)
        registrations.clear()
    }
}

private class ScopedSystemInfoRegistration(
    private val delegate: SystemInfoRegistration,
    private val onClose: () -> Unit,
) : SystemInfoRegistration {
    private val closed = AtomicBoolean(false)

    override fun refresh() {
        if (!closed.get()) delegate.refresh()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            delegate.close()
            onClose()
        }
    }
}

private class HostSystemInfoRegistration(
    private val refresh: () -> Unit,
    private val unregister: () -> Unit,
) : SystemInfoRegistration {
    private val closed = AtomicBoolean(false)

    override fun refresh() {
        if (!closed.get()) refresh.invoke()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) unregister()
    }
}
