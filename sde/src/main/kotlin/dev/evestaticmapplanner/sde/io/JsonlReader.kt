package dev.evestaticmapplanner.sde.io

import dev.evestaticmapplanner.data.db.SourceFileAudit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest

data class ParsedJsonlFile<T>(
    val records: List<T>,
    val audit: SourceFileAudit,
)

class SdeParseException(
    val sourceFile: Path,
    val lineNumber: Int,
    cause: Throwable,
) : IllegalArgumentException(
    "Failed to parse ${sourceFile.fileName} line $lineNumber: ${cause.message}",
    cause,
)

class JsonlReader(
    @PublishedApi internal val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = true
    },
) {
    inline fun <reified T> read(path: Path): ParsedJsonlFile<T> =
        read(path) { line -> json.decodeFromString<T>(line) }

    fun <T> read(path: Path, decode: (String) -> T): ParsedJsonlFile<T> {
        val digest = MessageDigest.getInstance("SHA-256")
        val records = mutableListOf<T>()
        var lineNumber = 0

        DigestInputStream(Files.newInputStream(path), digest).use { digestStream ->
            BufferedReader(InputStreamReader(digestStream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    lineNumber++
                    if (line.isBlank()) continue
                    try {
                        records += decode(line)
                    } catch (error: SerializationException) {
                        throw SdeParseException(path, lineNumber, error)
                    } catch (error: IllegalArgumentException) {
                        throw SdeParseException(path, lineNumber, error)
                    }
                }
            }
        }

        val sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        return ParsedJsonlFile(
            records = records,
            audit = SourceFileAudit(path.fileName.toString(), sha256, records.size),
        )
    }
}
