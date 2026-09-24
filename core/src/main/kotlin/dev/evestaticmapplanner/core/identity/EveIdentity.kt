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

    val currentAllianceId: Long?
        get() = alliance?.id
}

enum class CurrentIdentitySource {
    ESI,
    MANUAL,
}

/**
 * The identity snapshot used by access-control decisions.
 *
 * Alliance names and tickers are display metadata only. Callers must use [allianceId] for equality.
 */
data class CurrentIdentityContext(
    val source: CurrentIdentitySource,
    val character: EveCharacterIdentity? = null,
    val allianceId: Long? = null,
    val allianceName: String? = null,
    val allianceTicker: String? = null,
) {
    init {
        require(allianceId == null || allianceId > 0) { "Alliance ID must be positive" }
        requireOptionalIdentityText("Alliance name", allianceName, MAX_NAME_LENGTH)
        requireOptionalIdentityText("Alliance ticker", allianceTicker, MAX_TICKER_LENGTH)
        require(source != CurrentIdentitySource.MANUAL || character == null) {
            "Manual alliance simulation cannot claim an ESI character"
        }
    }

    companion object {
        fun esi(identity: EveIdentity): CurrentIdentityContext = CurrentIdentityContext(
            source = CurrentIdentitySource.ESI,
            character = identity.character,
            allianceId = identity.alliance?.id,
            allianceName = identity.alliance?.name,
            allianceTicker = identity.alliance?.ticker,
        )

        fun manual(
            allianceId: Long?,
            allianceName: String? = null,
            allianceTicker: String? = null,
        ): CurrentIdentityContext = CurrentIdentityContext(
            source = CurrentIdentitySource.MANUAL,
            allianceId = allianceId,
            allianceName = allianceName,
            allianceTicker = allianceTicker,
        )
    }
}

fun EveIdentity.toCurrentIdentityContext(): CurrentIdentityContext = CurrentIdentityContext.esi(this)

data class CurrentIdentityState(
    val identities: List<EveIdentity> = emptyList(),
    val selectedCharacterId: Long? = null,
) {
    init {
        require(identities.map { it.character.id }.distinct().size == identities.size) {
            "EVE identity character IDs must be unique"
        }
        require(selectedCharacterId == null || selectedCharacterId > 0) { "Character ID must be positive" }
    }

    val currentIdentity: EveIdentity?
        get() = selectedCharacterId?.let { id -> identities.firstOrNull { it.character.id == id } }

    val currentAllianceId: Long?
        get() = currentIdentity?.currentAllianceId
}

/** Orders refreshed identity data while retaining the authoritative character selection unchanged. */
object CurrentIdentitySelection {
    fun reconcile(
        identities: List<EveIdentity>,
        selectedCharacterId: Long?,
    ): CurrentIdentityState {
        val ordered = identities
            .distinctBy { it.character.id }
            .sortedWith(compareBy<EveIdentity> { it.character.name.lowercase() }.thenBy { it.character.id })
        return CurrentIdentityState(ordered, selectedCharacterId)
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

private fun requireOptionalIdentityText(label: String, value: String?, maximumLength: Int) {
    if (value != null) requireValidIdentityText(label, value, maximumLength)
}

private const val MAX_NAME_LENGTH = 128
private const val MAX_TICKER_LENGTH = 32
