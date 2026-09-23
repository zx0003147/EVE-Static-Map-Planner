package dev.evestaticmapplanner.core.identity

data class EveCharacterIdentity(
    val id: Long,
    val name: String,
) {
    init {
        require(id > 0) { "Character ID must be positive" }
        requireValidIdentityText("Character name", name, MAX_NAME_LENGTH)
    }
}

data class EveCorporationIdentity(
    val id: Long,
    val name: String,
    val ticker: String,
) {
    init {
        require(id > 0) { "Corporation ID must be positive" }
        requireValidIdentityText("Corporation name", name, MAX_NAME_LENGTH)
        requireValidIdentityText("Corporation ticker", ticker, MAX_TICKER_LENGTH)
    }
}

data class EveAllianceIdentity(
    val id: Long,
    val name: String,
    val ticker: String,
) {
    init {
        require(id > 0) { "Alliance ID must be positive" }
        requireValidIdentityText("Alliance name", name, MAX_NAME_LENGTH)
        requireValidIdentityText("Alliance ticker", ticker, MAX_TICKER_LENGTH)
    }
}

/** Credential-free EVE identity resolved from public ESI character, corporation and alliance data. */
data class EveIdentity(
    val character: EveCharacterIdentity,
    val corporation: EveCorporationIdentity,
    val alliance: EveAllianceIdentity?,
    val fetchedAtEpochMillis: Long,
) {
    init {
        require(fetchedAtEpochMillis >= 0) { "Identity fetch time must not be negative" }
    }

    /** Phase 1 stores alliance identifiers canonically as upper-case text (normally the alliance ticker). */
    val currentAllianceIdentifier: String?
        get() = alliance?.ticker?.uppercase()
}

data class CurrentIdentityState(
    val identities: List<EveIdentity> = emptyList(),
    val selectedCharacterId: Long? = null,
) {
    init {
        require(identities.map { it.character.id }.distinct().size == identities.size) {
            "EVE identity character IDs must be unique"
        }
        require(selectedCharacterId == null || identities.any { it.character.id == selectedCharacterId }) {
            "Selected EVE identity must exist in the identity list"
        }
    }

    val currentIdentity: EveIdentity?
        get() = selectedCharacterId?.let { id -> identities.firstOrNull { it.character.id == id } }
}

/** Pure reconciliation rules for retaining or choosing the current identity as ESI data changes. */
object CurrentIdentitySelection {
    fun reconcile(
        identities: List<EveIdentity>,
        selectedCharacterId: Long?,
        preferredCharacterId: Long? = null,
    ): CurrentIdentityState {
        val ordered = identities
            .distinctBy { it.character.id }
            .sortedWith(compareBy<EveIdentity> { it.character.name.lowercase() }.thenBy { it.character.id })
        val availableIds = ordered.mapTo(hashSetOf()) { it.character.id }
        val selected = when {
            preferredCharacterId in availableIds -> preferredCharacterId
            selectedCharacterId in availableIds -> selectedCharacterId
            ordered.size == 1 -> ordered.single().character.id
            else -> null
        }
        return CurrentIdentityState(ordered, selected)
    }

    fun select(state: CurrentIdentityState, characterId: Long): CurrentIdentityState {
        require(characterId > 0) { "Character ID must be positive" }
        require(state.identities.any { it.character.id == characterId }) {
            "Selected EVE identity is unavailable"
        }
        return state.copy(selectedCharacterId = characterId)
    }
}

private fun requireValidIdentityText(label: String, value: String, maximumLength: Int) {
    require(value.isNotBlank() && value == value.trim()) { "$label must be non-blank and trimmed" }
    require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters" }
    require(value.none(Char::isISOControl)) { "$label must not contain control characters" }
}

private const val MAX_NAME_LENGTH = 128
private const val MAX_TICKER_LENGTH = 32
