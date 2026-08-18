package dev.evestaticmapplanner.sde.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

@Serializable
enum class ActivationPhase {
    PREPARED,
    BACKUP_READY,
    SWAP_INTENT,
    ACTIVE_RETIRED,
    NEW_INSTALLED,
    ACTIVATION_VALID,
    ROLLBACK_INTENT,
    ROLLBACK_VALID,
}

@Serializable
data class ActivationJournalEntry(
    val journalVersion: Int = 1,
    val transactionId: String,
    val sequence: Int,
    val phase: ActivationPhase,
    val oldBuild: Long? = null,
    val oldSha256: String? = null,
    val newBuild: Long,
    val newSha256: String,
    val stagingId: String,
    val backupFileName: String? = null,
    val recordedAt: String,
) {
    init {
        require(journalVersion == 1)
        require(sequence >= 0)
        require(newBuild > 0)
        require(newSha256.matches(ManagedStaticDataPaths.HEX_64))
        require(oldSha256 == null || oldSha256.matches(ManagedStaticDataPaths.HEX_64))
    }
}

class ActivationJournalStore(
    private val paths: ManagedStaticDataPaths,
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true },
) {
    fun append(entry: ActivationJournalEntry) {
        val root = paths.activationRoot(entry.transactionId)
        Files.createDirectories(root)
        val file = root.resolve("%03d-%s.json".format(entry.sequence, entry.phase.name.lowercase()))
        FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            channel.write(java.nio.ByteBuffer.wrap(json.encodeToString(entry).toByteArray(StandardCharsets.UTF_8)))
            channel.force(true)
        }
    }

    fun incompleteTransactions(): List<ActivationJournalEntry> {
        if (!Files.isDirectory(paths.activationDirectory)) return emptyList()
        return Files.list(paths.activationDirectory).use { directories ->
            directories.filter(Files::isDirectory).toList().mapNotNull(::latestValid)
        }
    }

    fun latest(transactionId: String): ActivationJournalEntry? = latestValid(paths.activationRoot(transactionId))

    fun delete(transactionId: String) {
        val root = paths.activationRoot(transactionId)
        if (!Files.exists(root)) return
        Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun latestValid(root: java.nio.file.Path): ActivationJournalEntry? {
        if (!Files.isDirectory(root)) return null
        return Files.list(root).use { files ->
            val journalFiles = files.filter {
                Files.isRegularFile(it) && it.fileName.toString().endsWith(".json")
            }.toList()
            val valid = journalFiles.mapNotNull { file ->
                runCatching { json.decodeFromString<ActivationJournalEntry>(Files.readString(file)) }.getOrNull()
            }
            check(journalFiles.isEmpty() || valid.isNotEmpty()) { "Activation journal is corrupt: $root" }
            valid.maxByOrNull(ActivationJournalEntry::sequence)
        }
    }
}
