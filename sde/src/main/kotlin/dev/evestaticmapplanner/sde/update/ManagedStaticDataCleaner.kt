package dev.evestaticmapplanner.sde.update

import java.nio.file.Files

class ManagedStaticDataCleaner(
    private val paths: ManagedStaticDataPaths,
    private val pendingStore: PendingUpdateStore = PendingUpdateStore(paths),
    private val journalStore: ActivationJournalStore = ActivationJournalStore(paths),
) {
    fun cleanOrphans() {
        paths.initialize()
        val pending = runCatching { pendingStore.read() }.getOrElse { return }
        val journalStagingIds = runCatching { journalStore.incompleteTransactions().mapTo(mutableSetOf()) { it.stagingId } }
            .getOrElse { return }
        val retained = journalStagingIds + listOfNotNull(pending?.stagingId)
        deleteParts(paths.downloadsDirectory)
        deleteParts(paths.pendingDirectory)
        Files.list(paths.stagingDirectory).use { directories ->
            directories.filter(Files::isDirectory).forEach { directory ->
                if (directory.fileName.toString() !in retained) deleteTree(directory)
            }
        }
    }

    private fun deleteParts(directory: java.nio.file.Path) {
        if (!Files.isDirectory(directory)) return
        Files.list(directory).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".part") }
                .forEach(Files::deleteIfExists)
        }
    }

    private fun deleteTree(directory: java.nio.file.Path) {
        paths.requireManaged(directory)
        Files.walk(directory).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
