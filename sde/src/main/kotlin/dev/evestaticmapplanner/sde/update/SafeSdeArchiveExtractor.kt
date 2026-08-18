package dev.evestaticmapplanner.sde.update

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class ExtractedSdeSources(
    val directory: Path,
    val extractedBytes: Long,
)

class SdeArchiveSecurityException(message: String) : IOException(message)

class SafeSdeArchiveExtractor(
    private val maxEntryCount: Int = 10_000,
    private val maxRequiredEntryBytes: Long = 256L * 1024 * 1024,
    private val maxTotalRequiredBytes: Long = 512L * 1024 * 1024,
) {
    fun extract(archive: Path, outputDirectory: Path): ExtractedSdeSources {
        require(Files.isRegularFile(archive)) { "SDE archive does not exist: $archive" }
        require(!Files.exists(outputDirectory)) { "Extraction output already exists: $outputDirectory" }
        Files.createDirectories(outputDirectory)
        try {
            ZipFile(archive.toFile()).use { zip ->
                val entries = zip.entries().asSequence().toList()
                if (entries.size > maxEntryCount) throw SdeArchiveSecurityException("ZIP has too many entries: ${entries.size}")
                entries.forEach { validateEntryName(it.name) }
                val required = REQUIRED_NAMES.associateWith { name ->
                    entries.filter { !it.isDirectory && it.name.substringAfterLast('/') == name }.also { matches ->
                        if (matches.size != 1) {
                            throw SdeArchiveSecurityException("Expected exactly one $name in ZIP, found ${matches.size}")
                        }
                    }.single()
                }
                val declaredTotal = required.values.sumOf { entry ->
                    if (entry.size > maxRequiredEntryBytes) {
                        throw SdeArchiveSecurityException("Required ZIP entry is too large: ${entry.name} (${entry.size})")
                    }
                    entry.size.coerceAtLeast(0)
                }
                if (declaredTotal > maxTotalRequiredBytes) {
                    throw SdeArchiveSecurityException("Required ZIP entries exceed extraction size limit")
                }

                var total = 0L
                required.forEach { (name, entry) ->
                    val target = outputDirectory.resolve(name)
                    val part = target.resolveSibling("$name.part")
                    val crc = CRC32()
                    var entryBytes = 0L
                    zip.getInputStream(entry).use { input ->
                        Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                entryBytes += count
                                total += count
                                if (entryBytes > maxRequiredEntryBytes || total > maxTotalRequiredBytes) {
                                    throw SdeArchiveSecurityException("ZIP extraction size limit exceeded")
                                }
                                crc.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    if (entry.size >= 0 && entryBytes != entry.size) {
                        throw SdeArchiveSecurityException("ZIP entry size mismatch for $name")
                    }
                    if (entry.crc < 0 || crc.value != entry.crc) {
                        throw SdeArchiveSecurityException("ZIP entry CRC mismatch for $name")
                    }
                    movePublished(part, target)
                }
                return ExtractedSdeSources(outputDirectory, total)
            }
        } catch (error: Throwable) {
            deleteDirectory(outputDirectory)
            throw error
        }
    }

    private fun validateEntryName(name: String) {
        if (name.isBlank() || '\u0000' in name || '\\' in name) {
            throw SdeArchiveSecurityException("Unsafe ZIP entry name")
        }
        if (name.startsWith('/') || name.startsWith("//") || DRIVE_PREFIX.containsMatchIn(name)) {
            throw SdeArchiveSecurityException("Absolute ZIP entry is not allowed: $name")
        }
        val segments = name.split('/')
        if (segments.any { it == ".." || it == "." }) {
            throw SdeArchiveSecurityException("ZIP traversal entry is not allowed: $name")
        }
    }

    private fun deleteDirectory(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    companion object {
        val REQUIRED_NAMES = setOf(
            "mapRegions.jsonl",
            "mapConstellations.jsonl",
            "mapSolarSystems.jsonl",
            "mapStargates.jsonl",
        )
        private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
    }
}
