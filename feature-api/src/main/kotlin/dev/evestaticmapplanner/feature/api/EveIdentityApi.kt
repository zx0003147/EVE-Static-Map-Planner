package dev.evestaticmapplanner.feature.api

import java.time.Instant
import java.util.Collections

class EveCharacterIdentitySnapshot(
    val id: Long,
    val name: String,
) {
    init {
        require(id > 0) { "Character ID must be positive" }
        EveIdentityValidation.text("Character name", name, 128)
    }
}

class EveCorporationIdentitySnapshot(
    val id: Long,
    val name: String,
    val ticker: String,
) {
    init {
        require(id > 0) { "Corporation ID must be positive" }
        EveIdentityValidation.text("Corporation name", name, 128)
        EveIdentityValidation.text("Corporation ticker", ticker, 32)
    }
}

class EveAllianceIdentitySnapshot(
    val id: Long,
    val name: String,
    val ticker: String,
) {
    init {
        require(id > 0) { "Alliance ID must be positive" }
        EveIdentityValidation.text("Alliance name", name, 128)
        EveIdentityValidation.text("Alliance ticker", ticker, 32)
    }
}

/** Credential-free identity data. Access and refresh tokens must never cross this boundary. */
class EveIdentitySnapshot(
    val character: EveCharacterIdentitySnapshot,
    val corporation: EveCorporationIdentitySnapshot,
    val alliance: EveAllianceIdentitySnapshot?,
    val fetchedAt: Instant,
)

class EveIdentityProviderSnapshot(
    identities: List<EveIdentitySnapshot>,
    val refreshing: Boolean = false,
    val errorMessage: String? = null,
) {
    val identities: List<EveIdentitySnapshot> = Collections.unmodifiableList(ArrayList(identities))

    init {
        require(this.identities.map { it.character.id }.distinct().size == this.identities.size) {
            "EVE identity character IDs must be unique"
        }
        EveIdentityValidation.optionalText("EVE identity error", errorMessage, 240)
    }
}

/** Pack-owned in-memory source. [snapshot] must never perform network I/O. */
interface EveIdentityProvider {
    fun snapshot(): EveIdentityProviderSnapshot

    fun requestRefresh(characterId: Long? = null): Boolean = false
}

interface EveIdentityCapability : FeatureCapability {
    fun register(provider: EveIdentityProvider): EveIdentityRegistration
}

interface EveIdentityRegistration : AutoCloseable {
    fun requestRefresh()

    override fun close()
}

private object EveIdentityValidation {
    fun text(label: String, value: String, maximumLength: Int) {
        require(value.isNotBlank() && value == value.trim()) { "$label must be non-blank and trimmed" }
        require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters" }
        require(value.none(Char::isISOControl)) { "$label must not contain control characters" }
    }

    fun optionalText(label: String, value: String?, maximumLength: Int) {
        if (value != null) text(label, value, maximumLength)
    }
}
