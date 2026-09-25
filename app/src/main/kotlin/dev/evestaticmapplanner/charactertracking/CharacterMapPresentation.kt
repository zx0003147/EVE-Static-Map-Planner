package dev.evestaticmapplanner.charactertracking

import dev.evestaticmapplanner.feature.api.TrackedCharacterLocationStatus
import dev.evestaticmapplanner.feature.api.TrackedCharacterSnapshot
import dev.evestaticmapplanner.ui.CharacterPortraitStack
import dev.evestaticmapplanner.ui.presentCharacterPortraits

internal data class CharacterMapCharacter(
    val snapshot: TrackedCharacterSnapshot,
    val portrait: CharacterPortraitStack,
    val isCurrentIdentity: Boolean,
    val isForegroundCharacter: Boolean,
) {
    val characterId: Long get() = snapshot.characterId
    val characterName: String get() = snapshot.characterName
    val systemId: Int? get() = snapshot.solarSystemId
    val locationStatus: TrackedCharacterLocationStatus get() = snapshot.locationStatus
}

internal data class CharacterSystemMarker(
    val systemId: Int,
    val characters: List<CharacterMapCharacter>,
    val portraits: CharacterPortraitStack,
) {
    val containsCurrentIdentity: Boolean = characters.any(CharacterMapCharacter::isCurrentIdentity)
    val containsForegroundCharacter: Boolean = characters.any(CharacterMapCharacter::isForegroundCharacter)
    val hasDegradedLocation: Boolean = characters.any {
        it.locationStatus == TrackedCharacterLocationStatus.DEGRADED
    }
    val hasStaleLocation: Boolean = characters.any {
        it.locationStatus == TrackedCharacterLocationStatus.STALE
    }
    val allLocationsStale: Boolean = characters.all {
        it.locationStatus == TrackedCharacterLocationStatus.STALE
    }
}

internal data class CharacterMapPresentation(
    val characters: List<CharacterMapCharacter>,
    val markers: List<CharacterSystemMarker>,
) {
    companion object {
        val Empty = CharacterMapPresentation(emptyList(), emptyList())
    }
}

internal object CharacterMapPresentationBuilder {
    fun build(
        snapshots: List<TrackedCharacterSnapshot>,
        currentIdentityCharacterId: Long?,
        foregroundCharacterId: Long? = null,
    ): CharacterMapPresentation {
        val orderedSnapshots = snapshots.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, TrackedCharacterSnapshot::characterName)
                .thenBy(TrackedCharacterSnapshot::characterId),
        )
        val characters = orderedSnapshots.map { snapshot ->
            CharacterMapCharacter(
                snapshot = snapshot,
                portrait = presentCharacterPortraits(listOf(snapshot)),
                isCurrentIdentity = snapshot.characterId == currentIdentityCharacterId,
                isForegroundCharacter = snapshot.characterId == foregroundCharacterId,
            )
        }
        val markers = characters.asSequence()
            .filter { character ->
                character.snapshot.trackingEnabled &&
                    character.systemId != null &&
                    character.locationStatus != TrackedCharacterLocationStatus.UNKNOWN
            }
            .groupBy { character -> requireNotNull(character.systemId) }
            .toSortedMap()
            .map { (systemId, groupedCharacters) ->
                CharacterSystemMarker(
                    systemId = systemId,
                    characters = groupedCharacters,
                    portraits = presentCharacterPortraits(groupedCharacters.map(CharacterMapCharacter::snapshot)),
                )
            }
        return CharacterMapPresentation(characters, markers)
    }
}
