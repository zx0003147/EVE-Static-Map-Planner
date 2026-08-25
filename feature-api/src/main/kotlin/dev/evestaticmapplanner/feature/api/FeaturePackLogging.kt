package dev.evestaticmapplanner.feature.api

/** Stable log levels understood by the Feature Pack host. */
enum class FeaturePackLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/** Narrow logging bridge that does not expose the host logging backend. */
interface FeaturePackLogger {
    fun log(level: FeaturePackLogLevel, message: String, cause: Throwable?)
}
