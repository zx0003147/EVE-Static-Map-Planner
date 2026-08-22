package dev.evestaticmapplanner.control.transport

internal class TokenBucketRateLimiter(
    private val refillPerSecond: Double,
    private val capacity: Double,
    private val nanoTime: () -> Long,
) {
    private var tokens = capacity
    private var lastRefillNanos = nanoTime()

    init {
        require(refillPerSecond > 0.0)
        require(capacity >= 1.0)
    }

    @Synchronized
    fun tryAcquire(): Boolean {
        refill()
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }

    private fun refill() {
        val now = nanoTime()
        val elapsed = (now - lastRefillNanos).coerceAtLeast(0L)
        if (elapsed > 0L) {
            tokens = (tokens + elapsed / 1_000_000_000.0 * refillPerSecond).coerceAtMost(capacity)
            lastRefillNanos = now
        }
    }
}

internal class LocalControlRateLimiters(nanoTime: () -> Long) {
    private val allRequests = TokenBucketRateLimiter(20.0, 40.0, nanoTime)
    private val mutations = TokenBucketRateLimiter(10.0, 20.0, nanoTime)

    fun tryAcquire(mutation: Boolean): Boolean =
        allRequests.tryAcquire() && (!mutation || mutations.tryAcquire())
}
