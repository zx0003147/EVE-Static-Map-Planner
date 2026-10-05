package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.core.identity.CurrentIdentityContext
import dev.evestaticmapplanner.core.sovereignty.AllianceCapitalRecord
import dev.evestaticmapplanner.core.sovereignty.AllianceCapitalSnapshot
import dev.evestaticmapplanner.core.sovereignty.AllianceCapitalStatus
import dev.evestaticmapplanner.core.sovereignty.CurrentAllianceCapitalResolver
import dev.evestaticmapplanner.core.sovereignty.CurrentAllianceCapitalState
import dev.evestaticmapplanner.core.sovereignty.SovereigntyFreshness
import dev.evestaticmapplanner.feature.api.AllianceCapitalCapability
import dev.evestaticmapplanner.feature.api.AllianceCapitalProvider
import dev.evestaticmapplanner.feature.api.AllianceCapitalRegistration
import dev.evestaticmapplanner.feature.api.AllianceCapitalSnapshotDto
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.SovereigntyFreshnessDto
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AllianceCapitalHostState(
    val snapshot: AllianceCapitalSnapshot = AllianceCapitalSnapshot.unavailable(CAPABILITY_UNAVAILABLE),
    val providerAvailable: Boolean = false,
    val currentAlliance: CurrentAllianceCapitalState = CurrentAllianceCapitalState(),
)

/** Host-owned Alliance Capital context. Providers publish only validated in-memory snapshots. */
class AllianceCapitalHost(
    private val failureSink: (packId: PackId, operation: String, error: Throwable) -> Unit = { _, _, _ -> },
) : AutoCloseable {
    private val providers = linkedMapOf<PackId, HostedProvider>()
    private val mutableState = MutableStateFlow(AllianceCapitalHostState())
    private var currentIdentity: CurrentIdentityContext? = null
    private var closed = false

    val state: StateFlow<AllianceCapitalHostState> = mutableState.asStateFlow()

    fun snapshot(): AllianceCapitalSnapshot = state.value.snapshot

    fun getCapital(allianceId: Long): AllianceCapitalRecord = state.value.snapshot.getCapital(allianceId)

    @Synchronized
    fun updateCurrentIdentity(identity: CurrentIdentityContext?) {
        currentIdentity = identity
        publish()
    }

    internal fun scopedCapability(packId: PackId) = ScopedAllianceCapitalCapability(packId, this)

    @Synchronized
    internal fun register(packId: PackId, provider: AllianceCapitalProvider): AllianceCapitalRegistration {
        check(!closed) { "Alliance Capital Host is closed" }
        require(packId !in providers) { "An Alliance Capital provider is already registered for $packId" }
        val initial = provider.snapshot().toCoreSnapshot()
        providers[packId] = HostedProvider(provider).also { it.accept(initial) }
        publish()
        return HostAllianceCapitalRegistration({ refresh(packId) }, { unregister(packId) })
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
                hosted.fail(error.message ?: "Alliance Capital provider snapshot failed")
                failureSink(packId, "snapshot", error)
            }
        publish()
    }

    private fun unregister(packId: PackId) = synchronized(this) {
        if (providers.remove(packId) != null) publish()
    }

    private fun publish() {
        val selected = providers.values.lastOrNull()?.current
            ?: AllianceCapitalSnapshot.unavailable(CAPABILITY_UNAVAILABLE)
        mutableState.value = AllianceCapitalHostState(
            snapshot = selected,
            providerAvailable = providers.isNotEmpty(),
            currentAlliance = CurrentAllianceCapitalResolver.resolve(currentIdentity, selected),
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        providers.clear()
        publish()
    }

    private class HostedProvider(val provider: AllianceCapitalProvider) {
        var lastGood: AllianceCapitalSnapshot? = null
        var current: AllianceCapitalSnapshot = AllianceCapitalSnapshot.unavailable()

        fun accept(snapshot: AllianceCapitalSnapshot) {
            current = when (snapshot.freshness) {
                SovereigntyFreshness.AVAILABLE -> snapshot.also { lastGood = it }
                SovereigntyFreshness.STALE -> snapshot.also { lastGood = it }
                SovereigntyFreshness.UNAVAILABLE -> lastGood?.asStale(snapshot.errorMessage) ?: snapshot
            }
        }

        fun fail(message: String) {
            current = lastGood?.asStale(message) ?: AllianceCapitalSnapshot.unavailable(message)
        }
    }
}

internal class ScopedAllianceCapitalCapability(
    private val packId: PackId,
    private val host: AllianceCapitalHost,
) : AllianceCapitalCapability, AutoCloseable {
    private val registrations = linkedSetOf<AllianceCapitalRegistration>()
    private var closed = false

    @Synchronized
    override fun register(provider: AllianceCapitalProvider): AllianceCapitalRegistration {
        check(!closed) { "Alliance Capital capability is closed for Feature Pack $packId" }
        lateinit var scoped: AllianceCapitalRegistration
        val delegate = host.register(packId, provider)
        scoped = ScopedAllianceCapitalRegistration(delegate) {
            synchronized(this) { registrations.remove(scoped) }
        }
        registrations += scoped
        return scoped
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        registrations.toList().asReversed().forEach(AllianceCapitalRegistration::close)
        registrations.clear()
    }
}

private class ScopedAllianceCapitalRegistration(
    private val delegate: AllianceCapitalRegistration,
    private val onClose: () -> Unit,
) : AllianceCapitalRegistration {
    private val closed = AtomicBoolean(false)
    override fun requestRefresh() { if (!closed.get()) delegate.requestRefresh() }
    override fun close() { if (closed.compareAndSet(false, true)) { delegate.close(); onClose() } }
}

private class HostAllianceCapitalRegistration(
    private val refresh: () -> Unit,
    private val unregister: () -> Unit,
) : AllianceCapitalRegistration {
    private val closed = AtomicBoolean(false)
    override fun requestRefresh() { if (!closed.get()) refresh() }
    override fun close() { if (closed.compareAndSet(false, true)) unregister() }
}

private fun AllianceCapitalSnapshotDto.toCoreSnapshot(): AllianceCapitalSnapshot {
    require(records.size <= MAX_PROVIDER_RECORDS) { "Alliance Capital provider exceeded Host entry limit" }
    val observedAtEpochMillis = observedAt?.toEpochMilli()
    val coreFreshness = freshness.toAllianceCapitalCore()
    val resolved = records.groupBy { it.allianceId }.mapValues { (allianceId, candidates) ->
        val systemIds = candidates.map { it.capitalSystemId }.distinct().sorted()
        if (systemIds.size == 1) {
            AllianceCapitalRecord(
                allianceId = allianceId,
                capitalSystemId = systemIds.single(),
                status = AllianceCapitalStatus.AVAILABLE,
                observedAtEpochMillis = observedAtEpochMillis,
                source = source,
                freshness = coreFreshness,
            )
        } else {
            AllianceCapitalRecord(
                allianceId = allianceId,
                status = AllianceCapitalStatus.AMBIGUOUS,
                conflictingSystemIds = systemIds,
                observedAtEpochMillis = observedAtEpochMillis,
                source = source,
                freshness = coreFreshness,
                diagnostic = "Alliance ID $allianceId has multiple capital systems: ${systemIds.joinToString()}",
            )
        }
    }
    return AllianceCapitalSnapshot(
        recordsByAllianceId = resolved,
        observedAtEpochMillis = observedAtEpochMillis,
        source = source,
        freshness = coreFreshness,
        errorMessage = errorMessage,
    )
}

private fun AllianceCapitalSnapshot.asStale(errorMessage: String?) = copy(
    recordsByAllianceId = recordsByAllianceId.mapValues { (_, record) ->
        record.copy(freshness = SovereigntyFreshness.STALE)
    },
    freshness = SovereigntyFreshness.STALE,
    errorMessage = errorMessage,
)

private fun SovereigntyFreshnessDto.toAllianceCapitalCore() = when (this) {
    SovereigntyFreshnessDto.AVAILABLE -> SovereigntyFreshness.AVAILABLE
    SovereigntyFreshnessDto.STALE -> SovereigntyFreshness.STALE
    SovereigntyFreshnessDto.UNAVAILABLE -> SovereigntyFreshness.UNAVAILABLE
}

private const val MAX_PROVIDER_RECORDS = 10_000
private const val CAPABILITY_UNAVAILABLE = "Alliance Capital capability is unavailable"
