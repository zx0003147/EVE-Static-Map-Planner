package dev.evestaticmapplanner.feature.api

import java.nio.file.Path

/**
 * A validated portable path relative to one Pack-owned storage area.
 *
 * Only `/` separators and ASCII alphanumeric, `.`, `_`, and `-` filename
 * characters are accepted. Empty segments, `.`, `..`, absolute paths, drive
 * paths, backslashes, and Windows device names are rejected.
 */
class PackRelativePath(val value: String) {
    init {
        require(value.length in 1..512) { "Pack-relative path must contain between 1 and 512 characters" }
        require(!value.startsWith('/')) { "Pack-relative path must not be absolute" }
        require('\\' !in value && ':' !in value) { "Pack-relative path must use portable relative syntax" }

        val segments = value.split('/')
        require(segments.all { it.isNotEmpty() && SEGMENT_SYNTAX.matches(it) }) {
            "Pack-relative path contains an invalid segment"
        }
        require(segments.none { it == "." || it == ".." }) { "Pack-relative path must not traverse directories" }
        require(segments.none(::isWindowsDeviceName)) { "Pack-relative path uses a reserved Windows device name" }
    }

    fun toPath(): Path = Path.of(value)

    override fun equals(other: Any?): Boolean = other is PackRelativePath && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    private fun isWindowsDeviceName(segment: String): Boolean =
        segment.substringBefore('.').lowercase() in WINDOWS_DEVICE_NAMES

    private companion object {
        val SEGMENT_SYNTAX = Regex("[0-9A-Za-z](?:[0-9A-Za-z._-]{0,126}[0-9A-Za-z])?")
        val WINDOWS_DEVICE_NAMES = buildSet {
            addAll(listOf("con", "prn", "aux", "nul"))
            (1..9).forEach { number ->
                add("com$number")
                add("lpt$number")
            }
        }
    }
}

/**
 * Resolves paths inside the data, config, and cache roots assigned to one Pack.
 * Implementations must not expose Core roots and must add link/reparse-point
 * containment checks before performing filesystem operations.
 */
interface PackStorage {
    fun dataPath(relativePath: PackRelativePath): Path

    fun configPath(relativePath: PackRelativePath): Path

    fun cachePath(relativePath: PackRelativePath): Path
}
