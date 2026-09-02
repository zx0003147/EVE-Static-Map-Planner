package dev.evestaticmapplanner.shared.api

import java.net.URI
import java.util.Locale

@JvmInline
value class SharedServerUrl private constructor(val origin: String) {
    fun endpoint(path: String): String {
        require(path.startsWith('/') && !path.startsWith("//")) { "Endpoint path must be absolute" }
        return origin + path
    }

    override fun toString(): String = origin

    companion object {
        fun parse(raw: String): SharedServerUrl {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty() && trimmed.length <= MAX_URL_LENGTH) { "Server URL is invalid" }
            val uri = runCatching { URI(trimmed) }.getOrElse { throw IllegalArgumentException("Server URL is invalid") }
            require(uri.isAbsolute && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Server URL must contain only an origin"
            }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "Server URL must not contain a path" }
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: throw IllegalArgumentException("Server URL is invalid")
            val host = uri.host?.lowercase(Locale.ROOT) ?: throw IllegalArgumentException("Server URL host is invalid")
            require(scheme == "https" || scheme == "http") { "Server URL must use HTTPS" }
            if (scheme == "http") {
                require(host == "localhost" || host == "127.0.0.1") {
                    "Plain HTTP is allowed only for localhost development"
                }
            }
            val port = uri.port
            require(port == -1 || port in 1..65535) { "Server URL port is invalid" }
            val canonicalPort = when {
                port == -1 -> ""
                scheme == "https" && port == 443 -> ""
                scheme == "http" && port == 80 -> ""
                else -> ":$port"
            }
            return SharedServerUrl("$scheme://$host$canonicalPort")
        }
    }
}

private const val MAX_URL_LENGTH = 2_048
