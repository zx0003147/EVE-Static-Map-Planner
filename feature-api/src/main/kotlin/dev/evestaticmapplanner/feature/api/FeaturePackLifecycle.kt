package dev.evestaticmapplanner.feature.api

/**
 * The single implementation entrypoint a future Pack loader will discover.
 *
 * FP-1 defines this contract only. Discovery and class loading belong to FP-2.
 */
interface FeaturePackEntrypoint {
    fun descriptor(): FeaturePackDescriptor

    @Throws(FeaturePackStartupException::class)
    fun start(context: FeaturePackContext): FeaturePackSession
}

/** A host-owned view of the small set of capabilities available to one Pack. */
interface FeaturePackContext {
    fun hostInfo(): FeaturePackHostInfo

    fun storage(): PackStorage

    fun logger(): FeaturePackLogger
}

/** The lifecycle handle returned by a successfully started Pack. */
interface FeaturePackSession : AutoCloseable {
    override fun close()
}

/** A Pack may throw this to report a clean, attributable startup failure. */
class FeaturePackStartupException : Exception {
    constructor(message: String) : super(message)

    constructor(message: String, cause: Throwable) : super(message, cause)
}
