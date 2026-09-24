package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.core.sovereignty.SovereigntyFreshness
import dev.evestaticmapplanner.core.sovereignty.SovereigntySnapshot
import dev.evestaticmapplanner.core.sovereignty.SovereigntyStatus
import dev.evestaticmapplanner.core.sovereignty.SystemOwnerKind
import dev.evestaticmapplanner.core.sovereignty.SystemOwnership
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.SovereigntyCapability
import dev.evestaticmapplanner.feature.api.SovereigntyFreshnessDto
import dev.evestaticmapplanner.feature.api.SovereigntyOwnerKindDto
import dev.evestaticmapplanner.feature.api.SovereigntyProvider
import dev.evestaticmapplanner.feature.api.SovereigntyRegistration
import dev.evestaticmapplanner.feature.api.SovereigntySnapshotDto
import dev.evestaticmapplanner.feature.api.SovereigntyStatusDto
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SovereigntyHostState(
    val snapshot: SovereigntySnapshot = SovereigntySnapshot.unavailable(),
    val providerAvailable: Boolean = false,
)

/** Host-owned system-ownership context. Provider data never becomes an HTTP or ESI dependency of Core. */
class SovereigntyHost(
    private val failureSink: (packId: PackId, operation: String, error: Throwable) -> Unit = { _, _, _ -> },
) : AutoCloseable {
    private val providers = linkedMapOf<PackId, HostedProvider>()
    private val mutableState = MutableStateFlow(SovereigntyHostState())
    private var closed = false

    val state: StateFlow<SovereigntyHostState> = mutableState.asStateFlow()

    fun snapshot(): SovereigntySnapshot = state.value.snapshot

    fun getOwnership(systemId: Int): SystemOwnership? = state.value.snapshot.getOwnership(systemId)

    internal fun scopedCapability(packId: PackId) = ScopedSovereigntyCapability(packId, this)

    @Synchronized
    internal fun register(packId: PackId, provider: SovereigntyProvider): SovereigntyRegistration {
        check(!closed) { "Sovereignty Host is closed" }
        require(packId !in providers) { "A Sovereignty provider is already registered for $packId" }
        val initial = provider.snapshot().toCoreSnapshot()
        providers[packId] = HostedProvider(provider).also { it.accept(initial) }
        publish()
        return HostSovereigntyRegistration({ refresh(packId) }, { unregister(packId) })
    }

    @Synchronized
    fun requestRefresh(): Boolean = providers.map { (packId, hosted) ->
        runCatching { hosted.provider.requestRefresh() }
            .onFailure { failureSink(packId, "requestRefresh", it) }
            .getOrDefault(false)
    }.any { it }

    private fun refresh(packId: PackId) = synchronized(this) {
        val hosted = providers[packId] ?: return@synchronized
        runCatching { hosted.provider.snapshot().toCoreSnapshot() }
            .onSuccess(hosted::accept)
            .onFailure { error ->
                hosted.fail(error.message ?: "Sovereignty provider snapshot failed")
                failureSink(packId, "snapshot", error)
            }
        publish()
    }

    private fun unregister(packId: PackId) = synchronized(this) {
        if (providers.remove(packId) != null) publish()
    }

    private fun publish() {
        val selected = providers.values.lastOrNull()?.current ?: SovereigntySnapshot.unavailable()
        mutableState.value = SovereigntyHostState(
            snapshot = selected,
            providerAvailable = providers.isNotEmpty(),
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        providers.clear()
        publish()
    }

    private class HostedProvider(val provider: SovereigntyProvider) {
        var lastGood: SovereigntySnapshot? = null
        var current: SovereigntySnapshot = SovereigntySnapshot.unavailable()

        fun accept(snapshot: SovereigntySnapshot) {
            current = when (snapshot.freshness) {
                SovereigntyFreshness.AVAILABLE -> snapshot.also { lastGood = it }
                SovereigntyFreshness.STALE -> snapshot.also { if (it.systemsById.isNotEmpty()) lastGood = it }
                SovereigntyFreshness.UNAVAILABLE -> lastGood?.asStale(snapshot.errorMessage) ?: snapshot
            }
        }

        fun fail(message: String) {
            current = lastGood?.asStale(message) ?: SovereigntySnapshot.unavailable(message)
        }
    }
}

internal class ScopedSovereigntyCapability(
    private val packId: PackId,
    private val host: SovereigntyHost,
) : SovereigntyCapability, AutoCloseable {
    private val registrations = linkedSetOf<SovereigntyRegistration>()
    private var closed = false

    @Synchronized
    override fun register(provider: SovereigntyProvider): SovereigntyRegistration {
        check(!closed) { "Sovereignty capability is closed for Feature Pack $packId" }
        lateinit var scoped: SovereigntyRegistration
        val delegate = host.register(packId, provider)
        scoped = ScopedSovereigntyRegistration(delegate) {
            synchronized(this) { registrations.remove(scoped) }
        }
        registrations += scoped
        return scoped
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        registrations.toList().asReversed().forEach(SovereigntyRegistration::close)
        registrations.clear()
    }
}

private class ScopedSovereigntyRegistration(
    private val delegate: SovereigntyRegistration,
    private val onClose: () -> Unit,
) : SovereigntyRegistration {
    private val closed = AtomicBoolean(false)
    override fun requestRefresh() { if (!closed.get()) delegate.requestRefresh() }
    override fun close() { if (closed.compareAndSet(false, true)) { delegate.close(); onClose() } }
}

private class HostSovereigntyRegistration(
    private val refresh: () -> Unit,
    private val unregister: () -> Unit,
) : SovereigntyRegistration {
    private val closed = AtomicBoolean(false)
    override fun requestRefresh() { if (!closed.get()) refresh() }
    override fun close() { if (closed.compareAndSet(false, true)) unregister() }
}

private fun SovereigntySnapshotDto.toCoreSnapshot(): SovereigntySnapshot {
    require(systems.size <= MAX_PROVIDER_SYSTEMS) { "Sovereignty provider exceeded Host entry limit" }
    val observedAtEpochMillis = observedAt?.toEpochMilli()
    val coreFreshness = freshness.toCore()
    val ownership = systems.map { value ->
        SystemOwnership(
            systemId = value.systemId,
            ownerKind = value.ownerKind.toCore(),
            allianceId = value.allianceId,
            allianceName = value.allianceName,
            corporationId = value.corporationId,
            corporationName = value.corporationName,
            factionId = value.factionId,
            factionName = value.factionName,
            sovereigntyStatus = value.sovereigntyStatus.toCore(),
            observedAtEpochMillis = observedAtEpochMillis,
            source = source,
            freshness = coreFreshness,
        )
    }
    return SovereigntySnapshot(
        systemsById = ownership.associateBy(SystemOwnership::systemId),
        observedAtEpochMillis = observedAtEpochMillis,
        source = source,
        freshness = coreFreshness,
        errorMessage = errorMessage,
    )
}

private fun SovereigntySnapshot.asStale(errorMessage: String?) = copy(
    systemsById = systemsById.mapValues { (_, ownership) -> ownership.copy(freshness = SovereigntyFreshness.STALE) },
    freshness = SovereigntyFreshness.STALE,
    errorMessage = errorMessage,
)

private fun SovereigntyOwnerKindDto.toCore() = when (this) {
    SovereigntyOwnerKindDto.ALLIANCE -> SystemOwnerKind.ALLIANCE
    SovereigntyOwnerKindDto.CORPORATION -> SystemOwnerKind.CORPORATION
    SovereigntyOwnerKindDto.FACTION -> SystemOwnerKind.FACTION
    SovereigntyOwnerKindDto.UNCLAIMED -> SystemOwnerKind.UNCLAIMED
    SovereigntyOwnerKindDto.UNKNOWN -> SystemOwnerKind.UNKNOWN
}

private fun SovereigntyStatusDto.toCore() = when (this) {
    SovereigntyStatusDto.CLAIMED -> SovereigntyStatus.CLAIMED
    SovereigntyStatusDto.UNCLAIMED -> SovereigntyStatus.UNCLAIMED
    SovereigntyStatusDto.UNKNOWN -> SovereigntyStatus.UNKNOWN
}

private fun SovereigntyFreshnessDto.toCore() = when (this) {
    SovereigntyFreshnessDto.AVAILABLE -> SovereigntyFreshness.AVAILABLE
    SovereigntyFreshnessDto.STALE -> SovereigntyFreshness.STALE
    SovereigntyFreshnessDto.UNAVAILABLE -> SovereigntyFreshness.UNAVAILABLE
}

private const val MAX_PROVIDER_SYSTEMS = 10_000
