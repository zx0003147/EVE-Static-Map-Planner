package dev.evestaticmapplanner.sde.update

import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException

data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long?)

data class DownloadedArchive(
    val path: Path,
    val sourceUri: URI,
    val size: Long,
    val sha256: String,
    val downloadedAt: Instant,
)

data class DiskSpaceCheck(val usableBytes: Long, val requiredBytes: Long) {
    val sufficient: Boolean get() = usableBytes >= requiredBytes
}

class DiskSpacePreflight(
    private val usableSpace: (Path) -> Long = { Files.getFileStore(it).usableSpace },
    private val baselineRequiredBytes: Long = 512L * 1024 * 1024,
) {
    fun check(directory: Path, archiveBytes: Long? = null): DiskSpaceCheck {
        Files.createDirectories(directory)
        val required = maxOf(baselineRequiredBytes, (archiveBytes ?: 0L) + 256L * 1024 * 1024)
        return DiskSpaceCheck(usableSpace(directory), required)
    }
}

class SdeArchiveDownloader(
    private val transport: SdeHttpTransport,
    private val paths: ManagedStaticDataPaths,
    private val diskSpacePreflight: DiskSpacePreflight = DiskSpacePreflight(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun download(
        sourceUri: URI,
        build: Long,
        onProgress: (DownloadProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): DownloadedArchive {
        paths.initialize()
        val initialSpace = diskSpacePreflight.check(paths.downloadsDirectory)
        check(initialSpace.sufficient) {
            "Insufficient disk space before download: usable=${initialSpace.usableBytes}, required=${initialSpace.requiredBytes}"
        }
        val target = paths.downloadsDirectory.resolve("eve-online-static-data-$build-jsonl.zip")
        val part = target.resolveSibling("${target.fileName}.part")
        Files.deleteIfExists(part)
        try {
            transport.execute(SdeHttpRequest(sourceUri, timeout = Duration.ofMinutes(30))).use { response ->
                if (response.statusCode !in 200..299) throw IOException("SDE archive request failed with HTTP ${response.statusCode}")
                val total = response.firstHeader("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
                val responseSpace = diskSpacePreflight.check(paths.downloadsDirectory, total)
                check(responseSpace.sufficient) {
                    "Insufficient disk space for archive: usable=${responseSpace.usableBytes}, required=${responseSpace.requiredBytes}"
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                DigestOutputStream(
                    Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    digest,
                ).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw CancellationException("SDE download cancelled")
                        val count = response.body.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(DownloadProgress(downloaded, total))
                    }
                }
                if (total != null && downloaded != total) {
                    throw IOException("SDE archive download was truncated: expected=$total, actual=$downloaded")
                }
                movePublished(part, target)
                return DownloadedArchive(
                    target,
                    sourceUri,
                    downloaded,
                    digest.digest().joinToString("") { "%02x".format(it) },
                    Instant.now(clock),
                )
            }
        } catch (error: Throwable) {
            Files.deleteIfExists(part)
            throw error
        }
    }
}
