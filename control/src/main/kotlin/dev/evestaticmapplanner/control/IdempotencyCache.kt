package dev.evestaticmapplanner.control

import java.time.Duration
import java.time.Instant

internal sealed interface IdempotencyLookup {
    data object Miss : IdempotencyLookup
    data class Hit(val result: ControlResult<*>) : IdempotencyLookup
    data object Conflict : IdempotencyLookup
}

internal class IdempotencyCache(
    private val now: () -> Instant = Instant::now,
    private val ttl: Duration = Duration.ofMinutes(10),
    private val maxEntries: Int = 1024,
) {
    private val entries = linkedMapOf<CacheKey, CacheEntry>()

    @Synchronized
    fun lookup(operation: String, key: String, canonicalInput: Any): IdempotencyLookup {
        prune()
        val cacheKey = CacheKey(operation, key)
        val entry = entries[cacheKey] ?: return IdempotencyLookup.Miss
        return if (entry.canonicalInput == canonicalInput) {
            IdempotencyLookup.Hit(entry.result)
        } else {
            IdempotencyLookup.Conflict
        }
    }

    @Synchronized
    fun put(operation: String, key: String, canonicalInput: Any, result: ControlResult<*>) {
        prune()
        val cacheKey = CacheKey(operation, key)
        entries.remove(cacheKey)
        entries[cacheKey] = CacheEntry(canonicalInput, result, now())
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    private fun prune() {
        val cutoff = now().minus(ttl)
        entries.entries.removeIf { it.value.createdAt.isBefore(cutoff) }
    }

    private data class CacheKey(val operation: String, val idempotencyKey: String)
    private data class CacheEntry(val canonicalInput: Any, val result: ControlResult<*>, val createdAt: Instant)
}
