package dev.evestaticmapplanner.core.alliance

enum class AllianceOwnerMatchMethod {
    EXPLICIT_ID,
    EXACT_TEXT,
    USER_CONFIRMED,
}

sealed interface AllianceOwnerResolution {
    val rawText: String

    data class ResolvedExact(
        override val rawText: String,
        val alliance: AllianceReference,
        val method: AllianceOwnerMatchMethod,
    ) : AllianceOwnerResolution

    data class NeedsConfirmation(
        override val rawText: String,
        val candidates: List<AllianceReference>,
    ) : AllianceOwnerResolution

    data class AmbiguousExact(
        override val rawText: String,
        val candidates: List<AllianceReference>,
    ) : AllianceOwnerResolution

    data class Unknown(override val rawText: String) : AllianceOwnerResolution
}

class AllianceOwnerResolver(private val snapshot: AllianceDirectorySnapshot) {
    private val searchIndex = AllianceSearchIndex(snapshot)

    fun resolve(rawText: String): AllianceOwnerResolution {
        val canonical = rawText.trim()
        val explicitId = canonical.toLongOrNull()?.takeIf { it > 0 }
        if (explicitId != null) {
            return AllianceOwnerResolution.ResolvedExact(
                canonical,
                snapshot.alliancesById[explicitId] ?: AllianceReference(explicitId),
                AllianceOwnerMatchMethod.EXPLICIT_ID,
            )
        }
        val exact = searchIndex.exactMatches(canonical)
        if (exact.size == 1) {
            return AllianceOwnerResolution.ResolvedExact(canonical, exact.single(), AllianceOwnerMatchMethod.EXACT_TEXT)
        }
        if (exact.size > 1) return AllianceOwnerResolution.AmbiguousExact(canonical, exact)
        val candidates = searchIndex.search(canonical).map(AllianceSearchResult::alliance)
        return if (candidates.isEmpty()) {
            AllianceOwnerResolution.Unknown(canonical)
        } else {
            AllianceOwnerResolution.NeedsConfirmation(canonical, candidates)
        }
    }
}
