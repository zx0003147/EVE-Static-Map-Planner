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

enum class EveIdentitySelectionReason {
    RESTORE_PERSISTED,
    USER_SELECT,
    AUTO_SELECT_SINGLE_STABLE,
    PROVIDER_REFRESH_RETAINED,
    SELECTED_UNAVAILABLE,
}

enum class EveIdentitySelectionAvailability {
    UNSELECTED,
    AVAILABLE,
    LOADING,
    UNAVAILABLE,
}

data class EveIdentitySelectionDiagnostic(
    val oldCharacterId: Long?,
    val newCharacterId: Long?,
    val reason: EveIdentitySelectionReason,
    val selectedCharacterId: Long?,
    val availableCharacterIds: List<Long>,
    val refreshing: Boolean,
    val availability: EveIdentitySelectionAvailability,
    val providerRevision: Long,
    val source: String,
    val packId: PackId? = null,
) {
    val selectionChanged: Boolean get() = oldCharacterId != newCharacterId
}

data class EveIdentityHostState(
    val selection: CurrentIdentityState = CurrentIdentityState(),
    val providerAvailable: Boolean = false,
    val refreshing: Boolean = false,
    val errors: List<String> = emptyList(),
    val providerRevision: Long = 0,
    val selectionRevision: Long = 0,
    val lastSelectionReason: EveIdentitySelectionReason? = null,
) {
    val identities: List<EveIdentity> get() = selection.identities
    val selectedCharacterId: Long? get() = selection.selectedCharacterId
    val currentIdentity: EveIdentity? get() = selection.currentIdentity
    val currentAllianceId: Long? get() = selection.currentAllianceId
    val selectionAvailability: EveIdentitySelectionAvailability
        get() = when {
            selectedCharacterId == null -> EveIdentitySelectionAvailability.UNSELECTED
            currentIdentity != null -> EveIdentitySelectionAvailability.AVAILABLE
            refreshing -> EveIdentitySelectionAvailability.LOADING
            else -> EveIdentitySelectionAvailability.UNAVAILABLE
        }
}

/** Aggregates credential-free identities and owns the application-wide current identity choice. */
class EveIdentityHost(
    private val failureSink: (packId: PackId, operation: String, error: Throwable) -> Unit = { _, _, _ -> },
    private val diagnosticSink: (EveIdentitySelectionDiagnostic) -> Unit = {},
) : AutoCloseable {
    private val providers = linkedMapOf<PackId, HostedProvider>()
    private val mutableState = MutableStateFlow(EveIdentityHostState())
    private var providerRevision = 0L
    private var initialProviderRegistrationComplete = false
    private var initialSelectionResolved = false
    private var closed = false

    val state: StateFlow<EveIdentityHostState> = mutableState.asStateFlow()

    internal fun scopedCapability(packId: PackId) = ScopedEveIdentityCapability(packId, this)

    @Synchronized
    internal fun register(packId: PackId, provider: EveIdentityProvider): EveIdentityRegistration {
        check(!closed) { "EVE Identity Host is closed" }
        require(packId !in providers) { "An EVE identity provider is already registered for $packId" }
        providers[packId] = HostedProvider(provider, validated(provider.snapshot()))
        providerRevision++
        publish(source = "provider-register", packId = packId)
        return HostEveIdentityRegistration({ refresh(packId) }, { unregister(packId) })
    }

    @Synchronized
    fun select(characterId: Long): Boolean {
        val current = mutableState.value.selection
        if (current.identities.none { it.character.id == characterId }) return false
        initialSelectionResolved = true
        updateSelection(
            selection = CurrentIdentitySelection.select(current, characterId),
            reason = EveIdentitySelectionReason.USER_SELECT,
            source = "user",
        )
        return true
    }

    /** Restores the persisted choice even when its Pack has not published that identity yet. */
    @Synchronized
    fun restorePreferredCharacterId(characterId: Long?) {
        require(characterId == null || characterId > 0) { "Character ID must be positive" }
        initialSelectionResolved = characterId != null
        updateSelection(
            selection = CurrentIdentitySelection.reconcile(mutableState.value.identities, characterId),
            reason = EveIdentitySelectionReason.RESTORE_PERSISTED,
            source = "preferences",
        )
    }

    /** Marks the point after every startup Pack has had a chance to register its provider. */
    @Synchronized
    internal fun completeInitialProviderRegistration() {
        if (initialProviderRegistrationComplete) return
        initialProviderRegistrationComplete = true
        publish(source = "runtime-initialization")
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
            .onSuccess {
                hosted.lastGood = it
                providerRevision++
                publish(source = "provider-refresh", packId = packId)
            }
            .onFailure { failureSink(packId, "snapshot", it) }
    }

    private fun unregister(packId: PackId) = synchronized(this) {
        if (providers.remove(packId) != null) {
            providerRevision++
            publish(source = "provider-unregister", packId = packId)
        }
    }

    private fun publish(source: String, packId: PackId? = null) {
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
        val previous = mutableState.value
        val refreshing = snapshots.any(EveIdentityProviderSnapshot::refreshing)
        var selection = CurrentIdentitySelection.reconcile(identities, previous.selectedCharacterId)
        var selectionReason: EveIdentitySelectionReason? = null
        if (
            initialProviderRegistrationComplete &&
            !initialSelectionResolved &&
            selection.selectedCharacterId == null &&
            !refreshing &&
            identities.isNotEmpty()
        ) {
            initialSelectionResolved = true
            if (identities.size == 1) {
                selection = CurrentIdentitySelection.select(selection, identities.single().character.id)
                selectionReason = EveIdentitySelectionReason.AUTO_SELECT_SINGLE_STABLE
            }
        }
        val selectedChanged = previous.selectedCharacterId != selection.selectedCharacterId
        val next = EveIdentityHostState(
            selection = selection,
            providerAvailable = providers.isNotEmpty(),
            refreshing = refreshing,
            errors = snapshots.mapNotNull(EveIdentityProviderSnapshot::errorMessage).distinct(),
            providerRevision = providerRevision,
            selectionRevision = previous.selectionRevision + if (selectedChanged) 1 else 0,
            lastSelectionReason = selectionReason ?: previous.lastSelectionReason,
        )
        mutableState.value = next
        if (selectedChanged) {
            emitDiagnostic(previous, next, checkNotNull(selectionReason), source, packId)
        } else if (
            previous.selection != next.selection ||
            previous.refreshing != next.refreshing ||
            previous.providerAvailable != next.providerAvailable
        ) {
            val reason = if (next.selectedCharacterId != null && next.currentIdentity == null) {
                EveIdentitySelectionReason.SELECTED_UNAVAILABLE
            } else {
                EveIdentitySelectionReason.PROVIDER_REFRESH_RETAINED
            }
            emitDiagnostic(previous, next, reason, source, packId)
        }
    }

    private fun updateSelection(
        selection: CurrentIdentityState,
        reason: EveIdentitySelectionReason,
        source: String,
    ) {
        val previous = mutableState.value
        if (previous.selectedCharacterId == selection.selectedCharacterId) return
        val next = previous.copy(
            selection = selection,
            selectionRevision = previous.selectionRevision + 1,
            lastSelectionReason = reason,
        )
        mutableState.value = next
        emitDiagnostic(previous, next, reason, source, packId = null)
    }

    private fun emitDiagnostic(
        previous: EveIdentityHostState,
        next: EveIdentityHostState,
        reason: EveIdentitySelectionReason,
        source: String,
        packId: PackId?,
    ) {
        runCatching {
            diagnosticSink(
                EveIdentitySelectionDiagnostic(
                    oldCharacterId = previous.selectedCharacterId,
                    newCharacterId = next.selectedCharacterId,
                    reason = reason,
                    selectedCharacterId = next.selectedCharacterId,
                    availableCharacterIds = next.identities.map { it.character.id },
                    refreshing = next.refreshing,
                    availability = next.selectionAvailability,
                    providerRevision = next.providerRevision,
                    source = source,
                    packId = packId,
                ),
            )
        }
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
        providerRevision++
        publish(source = "host-close")
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
