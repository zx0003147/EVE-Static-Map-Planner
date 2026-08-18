package dev.evestaticmapplanner.sde.update

import java.nio.file.Files
import java.nio.file.Path

class ManagedStaticDataPaths(root: Path) {
    val root: Path = root.toAbsolutePath().normalize()
    val dataDirectory: Path = this.root.resolve("data")
    val activeDatabase: Path = dataDirectory.resolve("static.db")
    val updatesDirectory: Path = this.root.resolve("updates")
    val cacheDirectory: Path = updatesDirectory.resolve("cache")
    val downloadsDirectory: Path = updatesDirectory.resolve("downloads")
    val stagingDirectory: Path = updatesDirectory.resolve("staging")
    val pendingDirectory: Path = updatesDirectory.resolve("pending")
    val pendingManifest: Path = pendingDirectory.resolve("pending-update.json")
    val activationDirectory: Path = updatesDirectory.resolve("activation")
    val backupsDirectory: Path = updatesDirectory.resolve("backups")
    val quarantineDirectory: Path = updatesDirectory.resolve("quarantine")
    val auditDirectory: Path = updatesDirectory.resolve("audit")

    fun initialize() {
        listOf(
            dataDirectory,
            cacheDirectory,
            downloadsDirectory,
            stagingDirectory,
            pendingDirectory,
            activationDirectory,
            backupsDirectory,
            quarantineDirectory,
            auditDirectory,
        ).forEach(Files::createDirectories)
    }

    fun stagingRoot(stagingId: String): Path = safeChild(stagingDirectory, stagingId)

    fun stagedDatabase(stagingId: String): Path = stagingRoot(stagingId).resolve("static.db")

    fun activationRoot(transactionId: String): Path = safeChild(activationDirectory, transactionId)

    fun backupPath(build: Long, hash: String): Path {
        require(hash.matches(HEX_64)) { "Invalid SHA-256" }
        return backupsDirectory.resolve("static-$build-${hash.take(12)}.db")
    }

    fun requireManaged(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.startsWith(root)) { "Path escapes managed static-data root: $path" }
        return normalized
    }

    private fun safeChild(parent: Path, id: String): Path {
        require(id.matches(SAFE_ID)) { "Unsafe managed path identifier: $id" }
        return requireManaged(parent.resolve(id))
    }

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        internal val HEX_64 = Regex("[0-9a-f]{64}")
    }
}
