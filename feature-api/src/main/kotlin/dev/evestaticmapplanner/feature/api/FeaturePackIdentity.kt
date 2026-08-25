package dev.evestaticmapplanner.feature.api

/**
 * Canonical, portable Pack identifier.
 *
 * Syntax: 1..64 lowercase ASCII letters or digits, optionally followed by groups
 * separated by one `.`, `_`, or `-`. Separators cannot be repeated or appear at
 * either end. Windows device names are rejected.
 */
class PackId(val value: String) {
    init {
        require(value.length in 1..64) { "Pack ID must contain between 1 and 64 characters" }
        require(SYNTAX.matches(value)) {
            "Pack ID must be lowercase ASCII alphanumeric groups separated by '.', '_', or '-'"
        }
        val windowsStem = value.substringBefore('.')
        require(windowsStem !in WINDOWS_DEVICE_NAMES) { "Pack ID uses a reserved Windows device name" }
    }

    override fun equals(other: Any?): Boolean = other is PackId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    private companion object {
        val SYNTAX = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
        val WINDOWS_DEVICE_NAMES = buildSet {
            addAll(listOf("con", "prn", "aux", "nul"))
            (1..9).forEach { number ->
                add("com$number")
                add("lpt$number")
            }
        }
    }
}

/** Opaque Pack release identifier used for display and manifest identity. */
class PackVersion(val value: String) {
    init {
        require(value.length in 1..64) { "Pack version must contain between 1 and 64 characters" }
        require(SYNTAX.matches(value)) {
            "Pack version must be a portable ASCII version token"
        }
    }

    override fun equals(other: Any?): Boolean = other is PackVersion && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    private companion object {
        val SYNTAX = Regex("[0-9A-Za-z]+(?:[._+-][0-9A-Za-z]+)*")
    }
}

/** Minimal identity metadata available before a Pack is started. */
class FeaturePackDescriptor(
    val packId: PackId,
    val displayName: String,
    val packVersion: PackVersion,
    val publisher: String,
) {
    init {
        validateLabel("Display name", displayName, 100)
        validateLabel("Publisher", publisher, 100)
    }

    private fun validateLabel(label: String, value: String, maximumLength: Int) {
        require(value.isNotBlank()) { "$label must not be blank" }
        require(value == value.trim()) { "$label must not have leading or trailing whitespace" }
        require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters" }
        require(value.none(Char::isISOControl)) { "$label must not contain control characters" }
    }
}
