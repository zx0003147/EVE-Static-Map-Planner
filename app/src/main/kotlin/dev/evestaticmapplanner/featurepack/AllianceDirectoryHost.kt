package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.core.alliance.AllianceDirectoryMerger
import dev.evestaticmapplanner.core.alliance.AllianceDirectorySnapshot
import dev.evestaticmapplanner.core.alliance.AllianceDirectorySourceSnapshot
import dev.evestaticmapplanner.core.alliance.AllianceReference
import dev.evestaticmapplanner.core.identity.EveIdentity
import dev.evestaticmapplanner.feature.api.AllianceDirectoryCapability
import dev.evestaticmapplanner.feature.api.AllianceDirectoryProvider
import dev.evestaticmapplanner.feature.api.AllianceDirectoryProviderSnapshot
import dev.evestaticmapplanner.feature.api.AllianceDirectoryRegistration
import dev.evestaticmapplanner.feature.api.PackId
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AllianceDirectoryHostState(
    val snapshot: AllianceDirectorySnapshot = AllianceDirectorySnapshot(),
    val providerAvailable: Boolean = false,
    val errors: List<String> = emptyList(),
)

/** Host-owned aggregation point. All entity joins are keyed exclusively by Alliance ID. */
class AllianceDirectoryHost(
    private val failureSink: (packId: PackId, operation: String, error: Throwable) -> Unit = { _, _, _ -> },
) : AutoCloseable {
    private val providers = linkedMapOf<PackId, HostedProvider>()
    private val publicMetadata = linkedMapOf<Long, AllianceReference>()
    private val mutableState = MutableStateFlow(AllianceDirectoryHostState())
    private var currentIdentity: EveIdentity? = null
    private var closed = false

    val state: StateFlow<AllianceDirectoryHostState> = mutableState.asStateFlow()

    internal fun scopedCapability(packId: PackId) = ScopedAllianceDirectoryCapability(packId, this)

    @Synchronized
    internal fun register(packId: PackId, provider: AllianceDirectoryProvider): AllianceDirectoryRegistration {
        check(!closed) { "Alliance Directory Host is closed" }
        require(packId !in providers) { "An Alliance Directory provider is already registered for $packId" }
        providers[packId] = HostedProvider(provider, validated(provider.snapshot()))
        publish()
        return HostAllianceDirectoryRegistration({ refresh(packId) }, { unregister(packId) })
    }

    @Synchronized
    fun updateCurrentIdentity(identity: EveIdentity?) {
        currentIdentity = identity
        publish()
    }

    @Synchronized
    fun addVerifiedPublicMetadata(alliance: AllianceReference) {
        publicMetadata[alliance.allianceId] = alliance
        publish()
    }

    @Synchronized
    fun requestRefresh(): Boolean = providers.map { (packId, hosted) ->
        runCatching { hosted.provider.requestRefresh() }
            .onFailure { failureSink(packId, "requestRefresh", it) }
            .getOrDefault(false)
    }.any { it }

    private fun refresh(packId: PackId) = synchronized(this) {
        val hosted = providers[packId] ?: return@synchronized
        runCatching { validated(hosted.provider.snapshot()) }
            .onSuccess { hosted.lastGood = it; publish() }
            .onFailure { failureSink(packId, "snapshot", it) }
    }

    private fun unregister(packId: PackId) = synchronized(this) {
        if (providers.remove(packId) != null) publish()
    }

    private fun publish() {
        val providerSources = providers.map { (packId, hosted) ->
            hosted.lastGood.toCoreSource("feature-pack:${packId.value}", priority = 100)
        }
        val identitySource = currentIdentity?.alliance?.let { alliance ->
            AllianceDirectorySourceSnapshot(
                source = "current-esi-identity",
                alliances = listOf(AllianceReference(alliance.id, alliance.name, alliance.ticker)),
                observedAt = Instant.ofEpochMilli(currentIdentity!!.fetchedAtEpochMillis),
                priority = 200,
            )
        }
        val metadataSource = publicMetadata.values.takeIf(Collection<AllianceReference>::isNotEmpty)?.let {
            AllianceDirectorySourceSnapshot(
                source = "public-esi-alliance-detail",
                alliances = it.toList(),
                observedAt = Instant.now(),
                priority = 300,
            )
        }
        val sources = buildList {
            addAll(providerSources)
            identitySource?.let(::add)
            metadataSource?.let(::add)
        }
        mutableState.value = AllianceDirectoryHostState(
            snapshot = AllianceDirectoryMerger.merge(sources),
            providerAvailable = providers.isNotEmpty(),
            errors = providers.values.mapNotNull { it.lastGood.errorMessage }.distinct(),
        )
    }

    private fun validated(snapshot: AllianceDirectoryProviderSnapshot): AllianceDirectoryProviderSnapshot = snapshot.also {
        require(it.alliances.size <= MAX_PROVIDER_ALLIANCES) { "Alliance Directory provider exceeded Host entry limit" }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        providers.clear()
        publicMetadata.clear()
        currentIdentity = null
        publish()
    }

    private data class HostedProvider(
        val provider: AllianceDirectoryProvider,
        var lastGood: AllianceDirectoryProviderSnapshot,
    )
}

internal class ScopedAllianceDirectoryCapability(
    private val packId: PackId,
    private val host: AllianceDirectoryHost,
) : AllianceDirectoryCapability, AutoCloseable {
    private val registrations = linkedSetOf<AllianceDirectoryRegistration>()
    private var closed = false

    @Synchronized
    override fun register(provider: AllianceDirectoryProvider): AllianceDirectoryRegistration {
        check(!closed) { "Alliance Directory capability is closed for Feature Pack $packId" }
        lateinit var scoped: AllianceDirectoryRegistration
        val delegate = host.register(packId, provider)
        scoped = ScopedAllianceDirectoryRegistration(delegate) {
            synchronized(this) { registrations.remove(scoped) }
        }
        registrations += scoped
        return scoped
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        registrations.toList().asReversed().forEach(AllianceDirectoryRegistration::close)
        registrations.clear()
    }
}

private class ScopedAllianceDirectoryRegistration(
    private val delegate: AllianceDirectoryRegistration,
    private val onClose: () -> Unit,
) : AllianceDirectoryRegistration {
    private val closed = AtomicBoolean(false)
    override fun requestRefresh() { if (!closed.get()) delegate.requestRefresh() }
    override fun close() { if (closed.compareAndSet(false, true)) { delegate.close(); onClose() } }
}

private class HostAllianceDirectoryRegistration(
    private val refresh: () -> Unit,
    private val unregister: () -> Unit,
) : AllianceDirectoryRegistration {
    private val closed = AtomicBoolean(false)
    override fun requestRefresh() { if (!closed.get()) refresh() }
    override fun close() { if (closed.compareAndSet(false, true)) unregister() }
}

private fun AllianceDirectoryProviderSnapshot.toCoreSource(
    source: String,
    priority: Int,
) = AllianceDirectorySourceSnapshot(
    source = source,
    alliances = alliances.map { AllianceReference(it.allianceId, it.name, it.ticker) },
    observedAt = observedAt,
    priority = priority,
)

private const val MAX_PROVIDER_ALLIANCES = 10_000
