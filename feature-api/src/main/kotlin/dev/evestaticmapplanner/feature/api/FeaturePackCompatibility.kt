package dev.evestaticmapplanner.feature.api

/** Simple numeric Core version used only for deterministic minimum-version checks. */
class CoreVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<CoreVersion> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "Core version components must not be negative" }
    }

    override fun compareTo(other: CoreVersion): Int =
        compareValuesBy(this, other, CoreVersion::major, CoreVersion::minor, CoreVersion::patch)

    override fun equals(other: Any?): Boolean =
        other is CoreVersion && major == other.major && minor == other.minor && patch == other.patch

    override fun hashCode(): Int = 31 * (31 * major + minor) + patch

    override fun toString(): String = "$major.$minor.$patch"
}

/**
 * Feature API identity carried across the future Pack class-loader boundary.
 * A non-frozen version is an explicitly unstable development contract.
 */
class FeatureApiVersion(
    val identifier: String,
    val frozen: Boolean,
) {
    init {
        require(identifier.length in 1..64) { "Feature API identifier must contain between 1 and 64 characters" }
        require(IDENTIFIER_SYNTAX.matches(identifier)) { "Feature API identifier is not a portable ASCII token" }
    }

    override fun equals(other: Any?): Boolean =
        other is FeatureApiVersion && identifier == other.identifier && frozen == other.frozen

    override fun hashCode(): Int = 31 * identifier.hashCode() + frozen.hashCode()

    override fun toString(): String = identifier

    private companion object {
        val IDENTIFIER_SYNTAX = Regex("[0-9A-Za-z]+(?:[._-][0-9A-Za-z]+)*")
    }
}

/** Current frozen v1 compatibility identity. Breaking contract changes require a new major API version. */
object FeatureApiVersions {
    fun current(): FeatureApiVersion = FeatureApiVersion("1", true)
}

/** Minimal platform identity; no filesystem roots or environment details are exposed. */
class HostPlatform(
    val operatingSystem: String,
    val architecture: String,
) {
    init {
        requireToken("Operating system", operatingSystem)
        requireToken("Architecture", architecture)
    }

    private fun requireToken(label: String, value: String) {
        require(value.length in 1..32) { "$label must contain between 1 and 32 characters" }
        require(TOKEN_SYNTAX.matches(value)) { "$label must be a portable ASCII token" }
    }

    private companion object {
        val TOKEN_SYNTAX = Regex("[0-9A-Za-z]+(?:[._-][0-9A-Za-z]+)*")
    }
}

/** Narrow compatibility information supplied by Core to a Pack. */
class FeaturePackHostInfo(
    val coreVersion: CoreVersion,
    val featureApiVersion: FeatureApiVersion,
    val platform: HostPlatform,
)

/**
 * Pure manifest compatibility values reserved for the future Pack container.
 * This is an exact API match plus minimum Core check, not a SemVer range solver.
 */
class FeaturePackCompatibility(
    val packFormatVersion: Int,
    val featureApiVersion: FeatureApiVersion,
    val minimumCoreVersion: CoreVersion,
) {
    init {
        require(packFormatVersion > 0) { "Pack format version must be positive" }
    }

    fun isCompatibleWith(hostInfo: FeaturePackHostInfo, supportedPackFormatVersion: Int): Boolean =
        packFormatVersion == supportedPackFormatVersion &&
            featureApiVersion == hostInfo.featureApiVersion &&
            minimumCoreVersion <= hostInfo.coreVersion
}
