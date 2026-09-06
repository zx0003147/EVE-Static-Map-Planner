package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.CharacterTrackingCapability
import dev.evestaticmapplanner.feature.api.CharacterTrackingProvider
import dev.evestaticmapplanner.feature.api.CharacterTrackingPriority
import dev.evestaticmapplanner.feature.api.CharacterTrackingRegistration
import dev.evestaticmapplanner.feature.api.CharacterTrackingSnapshot
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.TrackedCharacterSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Aggregates credential-free character snapshots published by Feature Packs. */
class CharacterTrackingHost(
    private val failureSink: (packId: PackId, operation: String, error: Throwable) -> Unit = { _, _, _ -> },
) : AutoCloseable {
    private val providers = linkedMapOf<PackId, HostedProvider>()
    private val mutableState = MutableStateFlow<List<TrackedCharacterSnapshot>>(emptyList())
    private val mutableAvailability = MutableStateFlow(false)
    private var closed = false

    val state: StateFlow<List<TrackedCharacterSnapshot>> = mutableState.asStateFlow()
    /** Independent from character count: true only while a Pack has a live typed provider registration. */
    val availability: StateFlow<Boolean> = mutableAvailability.asStateFlow()

    internal fun scopedCapability(packId: PackId) = ScopedCharacterTrackingCapability(packId, this)

    @Synchronized
    internal fun register(packId: PackId, provider: CharacterTrackingProvider): CharacterTrackingRegistration {
        check(!closed) { "Character Tracking Host is closed" }
        require(packId !in providers) { "A character tracking provider is already registered for $packId" }
        providers[packId] = HostedProvider(provider, validated(provider.snapshot()))
        publish()
        return HostCharacterTrackingRegistration({ refresh(packId) }, { unregister(packId) })
    }

    @Synchronized
    fun provider(packId: PackId): CharacterTrackingProvider? = providers[packId]?.provider

    /** Applies a scheduling hint without exposing which Pack owns the character. */
    @Synchronized
    fun setPriority(characterId: Long, priority: CharacterTrackingPriority): Boolean = providers
        .map { (packId, hosted) ->
            runCatching { hosted.provider.setPriority(characterId, priority) }
                .onFailure { failureSink(packId, "setPriority", it) }
                .getOrDefault(false)
        }
        .any { it }

    /** Requests Pack-owned refresh scheduling; this method never performs ESI I/O itself. */
    @Synchronized
    fun requestRefresh(characterId: Long): Boolean = providers
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
        mutableAvailability.value = providers.isNotEmpty()
        mutableState.value = providers.values.flatMap { it.lastGood.characters }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, TrackedCharacterSnapshot::characterName)
                .thenBy(TrackedCharacterSnapshot::characterId))
    }

    private fun validated(snapshot: CharacterTrackingSnapshot) = snapshot.also {
        require(it.characters.all { character -> character.characterName.length <= 128 }) {
            "Character name exceeds Host display limit"
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
        val provider: CharacterTrackingProvider,
        var lastGood: CharacterTrackingSnapshot,
    )
}

internal class ScopedCharacterTrackingCapability(
    private val packId: PackId,
    private val host: CharacterTrackingHost,
) : CharacterTrackingCapability, AutoCloseable {
    private val registrations = linkedSetOf<CharacterTrackingRegistration>()
    private var closed = false

    @Synchronized
    override fun register(provider: CharacterTrackingProvider): CharacterTrackingRegistration {
        check(!closed) { "Character tracking capability is closed for Feature Pack $packId" }
        lateinit var scoped: CharacterTrackingRegistration
        val delegate = host.register(packId, provider)
        scoped = ScopedCharacterTrackingRegistration(delegate) {
            synchronized(this) { registrations.remove(scoped) }
        }
        registrations += scoped
        return scoped
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        registrations.toList().asReversed().forEach(CharacterTrackingRegistration::close)
        registrations.clear()
    }
}

private class ScopedCharacterTrackingRegistration(
    private val delegate: CharacterTrackingRegistration,
    private val onClose: () -> Unit,
) : CharacterTrackingRegistration {
    private val closed = AtomicBoolean(false)
    override fun requestRefresh() { if (!closed.get()) delegate.requestRefresh() }
    override fun close() {
        if (closed.compareAndSet(false, true)) { delegate.close(); onClose() }
    }
}

private class HostCharacterTrackingRegistration(
    private val refresh: () -> Unit,
    private val unregister: () -> Unit,
) : CharacterTrackingRegistration {
    private val closed = AtomicBoolean(false)
    override fun requestRefresh() { if (!closed.get()) refresh() }
    override fun close() { if (closed.compareAndSet(false, true)) unregister() }
}
