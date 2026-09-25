package dev.evestaticmapplanner.charactertracking

import dev.evestaticmapplanner.feature.api.CharacterTrackingPriority
import dev.evestaticmapplanner.feature.api.TrackedCharacterAuthorizationState
import dev.evestaticmapplanner.feature.api.TrackedCharacterSnapshot

internal class CharacterTrackingPriorityCoordinator(
    private val setPriority: (Long, CharacterTrackingPriority) -> Boolean,
    private val requestRefresh: (Long) -> Boolean,
) : AutoCloseable {
    private var highPriorityCharacterIds = emptySet<Long>()
    private var foregroundCharacterId: Long? = null

    fun update(
        currentIdentityCharacterId: Long?,
        foregroundCharacterId: Long?,
        characters: List<TrackedCharacterSnapshot>,
    ) {
        val eligibleIds = characters.asSequence()
            .filter { it.trackingEnabled && it.authorizationState == TrackedCharacterAuthorizationState.CONNECTED }
            .map(TrackedCharacterSnapshot::characterId)
            .toSet()
        val nextHighPriorityIds = setOfNotNull(currentIdentityCharacterId, foregroundCharacterId)
            .filterTo(linkedSetOf()) { it in eligibleIds }

        (highPriorityCharacterIds - nextHighPriorityIds).forEach { characterId ->
            setPriority(characterId, CharacterTrackingPriority.NORMAL)
        }
        (nextHighPriorityIds - highPriorityCharacterIds).forEach { characterId ->
            setPriority(characterId, CharacterTrackingPriority.HIGH)
        }
        if (foregroundCharacterId != this.foregroundCharacterId && foregroundCharacterId in eligibleIds) {
            requestRefresh(checkNotNull(foregroundCharacterId))
        }

        highPriorityCharacterIds = nextHighPriorityIds
        this.foregroundCharacterId = foregroundCharacterId
    }

    override fun close() {
        highPriorityCharacterIds.forEach { characterId ->
            setPriority(characterId, CharacterTrackingPriority.NORMAL)
        }
        highPriorityCharacterIds = emptySet()
        foregroundCharacterId = null
    }
}
