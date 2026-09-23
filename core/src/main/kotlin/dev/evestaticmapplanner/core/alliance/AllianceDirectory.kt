package dev.evestaticmapplanner.core.alliance

import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import kotlin.math.min

data class AllianceReference(
    val allianceId: Long,
    val name: String? = null,
    val ticker: String? = null,
) {
    init {
        require(allianceId > 0) { "Alliance ID must be positive" }
        requireDisplayText("Alliance name", name, 128)
        requireDisplayText("Alliance ticker", ticker, 32)
    }
}

data class AllianceDirectorySourceSnapshot(
    val source: String,
    val alliances: List<AllianceReference>,
    val observedAt: Instant? = null,
    val priority: Int = 0,
) {
    init {
        require(source.isNotBlank() && source == source.trim()) { "Alliance directory source must be canonical" }
        require(alliances.map(AllianceReference::allianceId).distinct().size == alliances.size) {
            "Alliance directory source must not contain duplicate Alliance IDs"
        }
    }
}

data class AllianceDirectoryEntry(
    val alliance: AllianceReference,
    val sources: Set<String>,
    val observedAt: Instant?,
)

data class AllianceDirectorySnapshot(
    val alliancesById: Map<Long, AllianceReference> = emptyMap(),
    val entriesById: Map<Long, AllianceDirectoryEntry> = emptyMap(),
    val observedAt: Instant? = null,
    val diagnostics: List<String> = emptyList(),
) {
    init {
        require(alliancesById.keys == entriesById.keys) { "Alliance directory maps must contain the same IDs" }
        require(alliancesById.all { (id, alliance) -> id == alliance.allianceId }) {
            "Alliance directory key must equal Alliance ID"
        }
    }
}

object AllianceDirectoryMerger {
    fun merge(snapshots: Iterable<AllianceDirectorySourceSnapshot>): AllianceDirectorySnapshot {
        val ordered = snapshots.sortedWith(
            compareByDescending<AllianceDirectorySourceSnapshot> { it.priority }
                .thenByDescending { it.observedAt }
                .thenBy(AllianceDirectorySourceSnapshot::source),
        )
        val contributions = ordered.flatMap { snapshot -> snapshot.alliances.map { snapshot to it } }
            .groupBy { (_, alliance) -> alliance.allianceId }
        val diagnostics = mutableListOf<String>()
        val entries = contributions.mapValues { (allianceId, values) ->
            val nameValues = values.mapNotNull { it.second.name }.distinct()
            val tickerValues = values.mapNotNull { it.second.ticker }.distinct()
            if (nameValues.size > 1) diagnostics += "Alliance $allianceId has conflicting names: ${nameValues.joinToString()}"
            if (tickerValues.size > 1) diagnostics += "Alliance $allianceId has conflicting tickers: ${tickerValues.joinToString()}"
            AllianceDirectoryEntry(
                alliance = AllianceReference(
                    allianceId = allianceId,
                    name = values.firstNotNullOfOrNull { it.second.name },
                    ticker = values.firstNotNullOfOrNull { it.second.ticker },
                ),
                sources = values.mapTo(linkedSetOf()) { it.first.source },
                observedAt = values.mapNotNull { it.first.observedAt }.maxOrNull(),
            )
        }.toSortedMap()
        return AllianceDirectorySnapshot(
            alliancesById = entries.mapValues { it.value.alliance },
            entriesById = entries,
            observedAt = ordered.mapNotNull(AllianceDirectorySourceSnapshot::observedAt).maxOrNull(),
            diagnostics = diagnostics,
        )
    }
}

enum class AllianceSearchMatchKind {
    EXACT,
    TICKER_PREFIX,
    NAME_PREFIX,
    SUBSTRING,
    FUZZY,
}

data class AllianceSearchResult(
    val alliance: AllianceReference,
    val matchKind: AllianceSearchMatchKind,
    internal val distance: Int = 0,
)

class AllianceSearchIndex(snapshot: AllianceDirectorySnapshot) {
    private val indexed = snapshot.alliancesById.values.map(::IndexedAlliance)

    fun exactMatches(query: String): List<AllianceReference> {
        val normalized = normalizeAllianceSearchText(query)
        if (normalized.isEmpty()) return emptyList()
        return indexed.filter { it.normalizedName == normalized || it.normalizedTicker == normalized }
            .map(IndexedAlliance::alliance)
            .sortedWith(allianceDisplayOrder)
    }

    fun search(query: String, limit: Int = 20): List<AllianceSearchResult> {
        require(limit > 0) { "Search result limit must be positive" }
        val normalized = normalizeAllianceSearchText(query)
        if (normalized.isEmpty()) return emptyList()
        return indexed.mapNotNull { value -> value.match(normalized) }
            .sortedWith(
                compareBy<AllianceSearchResult> { it.matchKind.ordinal }
                    .thenBy { it.distance }
                    .thenBy { normalizeAllianceSearchText(it.alliance.ticker.orEmpty()) }
                    .thenBy { normalizeAllianceSearchText(it.alliance.name.orEmpty()) }
                    .thenBy { it.alliance.allianceId },
            )
            .take(limit)
    }

    private data class IndexedAlliance(val alliance: AllianceReference) {
        val normalizedName = normalizeAllianceSearchText(alliance.name.orEmpty())
        val normalizedTicker = normalizeAllianceSearchText(alliance.ticker.orEmpty())
        private val nameWords = normalizedName.split(' ').filter(String::isNotEmpty)

        fun match(query: String): AllianceSearchResult? {
            val kind = when {
                normalizedTicker == query || normalizedName == query -> AllianceSearchMatchKind.EXACT
                normalizedTicker.startsWith(query) -> AllianceSearchMatchKind.TICKER_PREFIX
                normalizedName.startsWith(query) || nameWords.any { it.startsWith(query) } -> AllianceSearchMatchKind.NAME_PREFIX
                query in normalizedTicker || query in normalizedName -> AllianceSearchMatchKind.SUBSTRING
                query.length >= 3 -> {
                    val threshold = min(2, query.length / 3)
                    val distance = sequenceOf(normalizedTicker, normalizedName, *nameWords.toTypedArray())
                        .filter(String::isNotEmpty)
                        .map { boundedLevenshtein(query, it, threshold) }
                        .minOrNull() ?: Int.MAX_VALUE
                    if (distance <= threshold) return AllianceSearchResult(alliance, AllianceSearchMatchKind.FUZZY, distance)
                    null
                }
                else -> null
            }
            return kind?.let { AllianceSearchResult(alliance, it) }
        }
    }
}

fun normalizeAllianceSearchText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .trim()
    .replace(WHITESPACE, " ")
    .lowercase(Locale.ROOT)

private fun boundedLevenshtein(left: String, right: String, limit: Int): Int {
    if (kotlin.math.abs(left.length - right.length) > limit) return limit + 1
    var previous = IntArray(right.length + 1) { it }
    left.forEachIndexed { leftIndex, leftChar ->
        val current = IntArray(right.length + 1)
        current[0] = leftIndex + 1
        var rowMinimum = current[0]
        right.forEachIndexed { rightIndex, rightChar ->
            current[rightIndex + 1] = minOf(
                current[rightIndex] + 1,
                previous[rightIndex + 1] + 1,
                previous[rightIndex] + if (leftChar == rightChar) 0 else 1,
            )
            rowMinimum = min(rowMinimum, current[rightIndex + 1])
        }
        if (rowMinimum > limit) return limit + 1
        previous = current
    }
    return previous[right.length]
}

private fun requireDisplayText(label: String, value: String?, maximumLength: Int) {
    if (value == null) return
    require(value.isNotBlank() && value == value.trim()) { "$label must be non-blank and trimmed" }
    require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters" }
    require(value.none(Char::isISOControl)) { "$label must not contain control characters" }
}

private val allianceDisplayOrder = compareBy<AllianceReference>(
    { normalizeAllianceSearchText(it.ticker.orEmpty()) },
    { normalizeAllianceSearchText(it.name.orEmpty()) },
    AllianceReference::allianceId,
)
private val WHITESPACE = Regex("\\s+")
