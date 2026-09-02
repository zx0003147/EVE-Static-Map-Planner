package dev.evestaticmapplanner.shared.api

sealed interface SharedMapError {
    val message: String
    val requestId: String?

    data class Network(override val message: String = "The Shared Map server could not be reached.") : SharedMapError {
        override val requestId: String? = null
    }

    data class Protocol(override val message: String, override val requestId: String? = null) : SharedMapError
    data class Authentication(override val message: String, override val requestId: String? = null) : SharedMapError
    data class Forbidden(override val message: String, override val requestId: String? = null) : SharedMapError
    data class NotFound(override val message: String, override val requestId: String? = null) : SharedMapError
    data class RateLimited(
        override val message: String,
        override val requestId: String? = null,
        val retryAfterSeconds: Long? = null,
    ) : SharedMapError
    data class Server(override val message: String, override val requestId: String? = null) : SharedMapError
    data class InvalidResponse(override val message: String, override val requestId: String? = null) : SharedMapError
    data class InvalidConfiguration(override val message: String) : SharedMapError {
        override val requestId: String? = null
    }
}

class SharedMapException(val error: SharedMapError) : Exception(error.message) {
    override fun toString(): String = "SharedMapException(${error::class.simpleName}: ${error.message})"
}
