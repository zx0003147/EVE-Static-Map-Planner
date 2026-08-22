package dev.evestaticmapplanner.control.transport

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class LocalControlSessionCredentials internal constructor(secret: ByteArray) {
    private val secretBytes = secret.copyOf()

    @Volatile
    private var active = true

    init {
        require(secretBytes.size == SECRET_SIZE_BYTES)
    }

    internal fun authorizationHeaderValue(): String {
        check(active) { "Local control session credentials are no longer active" }
        return "Bearer ${Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes)}"
    }

    internal fun authenticate(headerValues: List<String>?): Boolean {
        if (!active || headerValues?.size != 1) return false
        val match = BEARER_PATTERN.matchEntire(headerValues.single()) ?: return false
        val candidate = runCatching { Base64.getUrlDecoder().decode(match.groupValues[1]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(secretBytes, candidate)
    }

    internal fun invalidate() {
        active = false
        secretBytes.fill(0)
    }

    internal companion object {
        const val SECRET_SIZE_BYTES = 32
        private val BEARER_PATTERN = Regex("^Bearer ([A-Za-z0-9_-]+)$", RegexOption.IGNORE_CASE)

        fun generate(random: SecureRandom): LocalControlSessionCredentials {
            val secret = ByteArray(SECRET_SIZE_BYTES)
            random.nextBytes(secret)
            return LocalControlSessionCredentials(secret).also { secret.fill(0) }
        }
    }
}
