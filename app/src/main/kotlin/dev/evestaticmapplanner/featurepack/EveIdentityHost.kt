package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.core.identity.CurrentIdentitySelection
import dev.evestaticmapplanner.core.identity.CurrentIdentityState
import dev.evestaticmapplanner.core.identity.EveAllianceIdentity
import dev.evestaticmapplanner.core.identity.EveCharacterIdentity
import dev.evestaticmapplanner.core.identity.EveCorporationIdentity
import dev.evestaticmapplanner.core.identity.EveIdentity
import dev.evestaticmapplanner.feature.api.EveIdentityCapability
import dev.evestaticmapplanner.feature.api.EveIdentityProvider
import dev.evestaticmapplanner.feature.api.EveIdentityProviderSnapshot
import dev.evestaticmapplanner.feature.api.EveIdentityRegistration
import dev.evestaticmapplanner.feature.api.PackId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EveIdentityHostState(
    val selection: CurrentIdentityState = CurrentIdentityState(),
    val providerAvailable: Boolean = false,
    val refreshing: Boolean = false,
    val errors: List<String> = emptyList(),
) {
    val identities: List<EveIdentity> get() = selection.identities
    val selectedCharacterId: Long? get() = selection.selectedCharacterId
    val currentIdentity: EveIdentity? get() = selection.currentIdentity
    val currentAllianceId: Long? get() = selection.currentAllianceId
}

/** Aggregates credential-free identities and owns the application-wide current identity choice. */
class EveIdentityHost(
    private val failureSink: (packId: PackId, operation: String, error: Throwable) -> Unit = { _, _, _ -> },
) : AutoCloseable {
    private val providers = linkedMapOf<PackId, HostedProvider>()
    private val mutableState = MutableStateFlow(EveIdentityHostState())
    private var preferredCharacterId: Long? = null
    private var closed = false

    val state: StateFlow<EveIdentityHostState> = mutableState.asStateFlow()

    internal fun scopedCapability(packId: PackId) = ScopedEveIdentityCapability(packId, this)

    @Synchronized
    internal fun register(packId: PackId, provider: EveIdentityProvider): EveIdentityRegistration {
        check(!closed) { "EVE Identity Host is closed" }
        require(packId !in providers) { "An EVE identity provider is already registered for $packId" }
        providers[packId] = HostedProvider(provider, validated(provider.snapshot()))
        publish()
        return HostEveIdentityRegistration({ refresh(packId) }, { unregister(packId) })
    }

    @Synchronized
    fun select(characterId: Long): Boolean {
        val current = mutableState.value.selection
        if (current.identities.none { it.character.id == characterId }) return false
        preferredCharacterId = characterId
        mutableState.value = mutableState.value.copy(
            selection = CurrentIdentitySelection.select(current, characterId),
        )
        return true
    }

    /** Remembers a persisted choice before its Pack has necessarily published identity data. */
    @Synchronized
    fun restorePreferredCharacterId(characterId: Long?) {
        require(characterId == null || characterId > 0) { "Character ID must be positive" }
        preferredCharacterId = characterId
        publish()
    }

    @Synchronized
    fun requestRefresh(characterId: Long? = null): Boolean = providers
        .map { (packId, hosted) ->
            runCatching { hosted.provider.requestRefresh(characterId) }
                .onFailure { failureSink(packId, "requestRefresh", it) }
                .getOrDefault(false)
        }
        .any { it }

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
        val snapshots = providers.values.map(HostedProvider::lastGood)
        val identities = snapshots
            .flatMap(EveIdentityProviderSnapshot::identities)
            .map { snapshot ->
                EveIdentity(
                    character = EveCharacterIdentity(snapshot.character.id, snapshot.character.name),
                    corporation = EveCorporationIdentity(
                        snapshot.corporation.id,
                        snapshot.corporation.name,
                        snapshot.corporation.ticker,
                    ),
                    alliance = snapshot.alliance?.let {
                        EveAllianceIdentity(it.id, it.name, it.ticker)
                    },
                    fetchedAtEpochMillis = snapshot.fetchedAt.toEpochMilli(),
                )
            }
            .groupBy { it.character.id }
            .values
            .map { values -> values.maxBy(EveIdentity::fetchedAtEpochMillis) }
        val currentSelection = mutableState.value.selection
        mutableState.value = EveIdentityHostState(
            selection = CurrentIdentitySelection.reconcile(
                identities,
                currentSelection.selectedCharacterId,
                preferredCharacterId,
            ),
            providerAvailable = providers.isNotEmpty(),
            refreshing = snapshots.any(EveIdentityProviderSnapshot::refreshing),
            errors = snapshots.mapNotNull(EveIdentityProviderSnapshot::errorMessage).distinct(),
        )
    }

    private fun validated(snapshot: EveIdentityProviderSnapshot): EveIdentityProviderSnapshot = snapshot.also {
        require(it.identities.all { identity -> identity.character.name.length <= 128 }) {
            "EVE identity character name exceeds Host display limit"
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        providers.clear()
        publish()
    }

    private data class HostedProvider(
        val provider: EveIdentityProvider,
        var lastGood: EveIdentityProviderSnapshot,
    )
}

internal class ScopedEveIdentityCapability(
    private val packId: PackId,
    private val host: EveIdentityHost,
) : EveIdentityCapability, AutoCloseable {
    private val registrations = linkedSetOf<EveIdentityRegistration>()
    private var closed = false

    @Synchronized
    override fun register(provider: EveIdentityProvider): EveIdentityRegistration {
        check(!closed) { "EVE identity capability is closed for Feature Pack $packId" }
        lateinit var scoped: EveIdentityRegistration
        val delegate = host.register(packId, provider)
        scoped = ScopedEveIdentityRegistration(delegate) {
            synchronized(this) { registrations.remove(scoped) }
        }
        registrations += scoped
        return scoped
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        registrations.toList().asReversed().forEach(EveIdentityRegistration::close)
        registrations.clear()
    }
}

private class ScopedEveIdentityRegistration(
    private val delegate: EveIdentityRegistration,
    private val onClose: () -> Unit,
) : EveIdentityRegistration {
    private val closed = AtomicBoolean(false)
    override fun requestRefresh() { if (!closed.get()) delegate.requestRefresh() }
    override fun close() {
        if (closed.compareAndSet(false, true)) { delegate.close(); onClose() }
    }
}

private class HostEveIdentityRegistration(
    private val refresh: () -> Unit,
    private val unregister: () -> Unit,
) : EveIdentityRegistration {
    private val closed = AtomicBoolean(false)
    override fun requestRefresh() { if (!closed.get()) refresh() }
    override fun close() { if (closed.compareAndSet(false, true)) unregister() }
}
