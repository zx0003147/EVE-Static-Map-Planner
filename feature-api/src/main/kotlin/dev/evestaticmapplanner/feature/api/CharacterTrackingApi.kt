package dev.evestaticmapplanner.feature.api

import java.time.Instant

/** Authorization state safe to expose to the Host; credentials never cross the Pack boundary. */
enum class TrackedCharacterAuthorizationState { CONNECTED, AUTHORIZING, DISCONNECTED, ERROR, UNCONFIGURED }

enum class TrackedCharacterLocationStatus { CURRENT, STALE, DEGRADED, UNKNOWN }

enum class TrackedCharacterOnlineState { ONLINE, OFFLINE, UNKNOWN }

enum class TrackedCharacterErrorCategory {
    AUTHORIZATION, FORBIDDEN, RATE_LIMITED, NETWORK, SERVER, PROTOCOL, UNKNOWN,
}

enum class CharacterTrackingPriority { LOW, NORMAL, HIGH }

/** Display-neutral, credential-free state for one authorized character. */
class TrackedCharacterSnapshot(
    val characterId: Long,
    val characterName: String,
    val authorizationState: TrackedCharacterAuthorizationState,
    val trackingEnabled: Boolean,
    val solarSystemId: Int?,
    val locationStatus: TrackedCharacterLocationStatus,
    val locationObservedAt: Instant?,
    val lastValidatedAt: Instant?,
    val lastChangedAt: Instant?,
    val onlineState: TrackedCharacterOnlineState,
    val retryAfter: Instant?,
    val lastErrorCategory: TrackedCharacterErrorCategory?,
    val portrait: OverlayImage? = null,
) {
    init {
        require(characterId > 0) { "Character ID must be positive" }
        require(characterName.isNotBlank() && characterName == characterName.trim()) {
            "Character name must be non-blank and trimmed"
        }
        require(characterName.none(Char::isISOControl)) { "Character name must not contain control characters" }
        require(solarSystemId == null || solarSystemId > 0) { "Solar system ID must be positive" }
        require((solarSystemId == null) == (locationStatus == TrackedCharacterLocationStatus.UNKNOWN)) {
            "UNKNOWN locations must not expose a solar system, and known locations require a freshness status"
        }
        require(locationObservedAt == null || solarSystemId != null)
        require(lastChangedAt == null || solarSystemId != null)
    }
}

class CharacterTrackingSnapshot(val characters: List<TrackedCharacterSnapshot>) {
    init {
        require(characters.map(TrackedCharacterSnapshot::characterId).distinct().size == characters.size) {
            "Tracked character IDs must be unique"
        }
    }
}

/** Pack-owned in-memory provider. [snapshot] must not perform network I/O. */
interface CharacterTrackingProvider {
    fun snapshot(): CharacterTrackingSnapshot

    fun requestRefresh(characterId: Long? = null): Boolean = false

    /** Scheduling hint only; the Pack retains cooldown, retry, and request ownership. */
    fun setPriority(characterId: Long, priority: CharacterTrackingPriority): Boolean = false

    fun setTrackingEnabled(characterId: Long, enabled: Boolean): Boolean = false
}

interface CharacterTrackingCapability : FeatureCapability {
    fun register(provider: CharacterTrackingProvider): CharacterTrackingRegistration
}

interface CharacterTrackingRegistration : AutoCloseable {
    fun requestRefresh()

    override fun close()
}
