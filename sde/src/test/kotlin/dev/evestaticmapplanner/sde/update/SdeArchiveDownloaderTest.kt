package dev.evestaticmapplanner.sde.update

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SdeArchiveDownloaderTest {
    private val uri = URI.create("https://example.test/fixed.zip")

    @Test
    fun `streams known content length and reports progress`() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val paths = ManagedStaticDataPaths(createTempDirectory("download"))
        val progress = mutableListOf<DownloadProgress>()
        val archive = downloader(paths, bytes, bytes.size.toLong()).download(uri, 123, progress::add)

        assertEquals(bytes.size.toLong(), archive.size)
        assertEquals(bytes.toList(), java.nio.file.Files.readAllBytes(archive.path).toList())
        assertEquals(bytes.size.toLong(), progress.last().downloadedBytes)
    }

    @Test
    fun `unknown content length downloads without percentage total`() {
        val paths = ManagedStaticDataPaths(createTempDirectory("download"))
        val progress = mutableListOf<DownloadProgress>()
        downloader(paths, "zip".toByteArray(), null).download(uri, 123, progress::add)
        assertNull(progress.last().totalBytes)
    }

    @Test
    fun `truncated response fails and part is deleted`() {
        val paths = ManagedStaticDataPaths(createTempDirectory("download"))
        assertFailsWith<IOException> {
            downloader(paths, "short".toByteArray(), 100).download(uri, 123)
        }
        assertFalse(java.nio.file.Files.exists(paths.downloadsDirectory.resolve("eve-online-static-data-123-jsonl.zip.part")))
    }

    @Test
    fun `cancel deletes part and does not publish archive`() {
        val paths = ManagedStaticDataPaths(createTempDirectory("download"))
        var calls = 0
        assertFailsWith<java.util.concurrent.CancellationException> {
            downloader(paths, ByteArray(200_000), null).download(uri, 123, isCancelled = { ++calls > 1 })
        }
        assertFalse(java.nio.file.Files.exists(paths.downloadsDirectory.resolve("eve-online-static-data-123-jsonl.zip")))
    }

    @Test
    fun `disk preflight and I O failure never touch active database`() {
        val paths = ManagedStaticDataPaths(createTempDirectory("download"))
        UpdateTestFixtures.installOld(paths, UpdateTestFixtures.OLD_BUILD)
        val transport = SdeHttpTransport {
            SdeHttpResponse(200, emptyMap(), object : InputStream() {
                var count = 0
                override fun read(): Int = if (count++ < 10) 1 else throw IOException("injected read failure")
            })
        }
        val failingIo = SdeArchiveDownloader(transport, paths, DiskSpacePreflight({ Long.MAX_VALUE }))
        assertFailsWith<IOException> { failingIo.download(uri, 123) }
        UpdateTestFixtures.assertBuild(paths.activeDatabase, UpdateTestFixtures.OLD_BUILD)

        val noSpace = SdeArchiveDownloader(transport, paths, DiskSpacePreflight({ 0 }))
        assertFailsWith<IllegalStateException> { noSpace.download(uri, 123) }
        UpdateTestFixtures.assertBuild(paths.activeDatabase, UpdateTestFixtures.OLD_BUILD)
    }

    @Test
    fun `HTTP errors do not publish archive`() {
        listOf(404, 500).forEach { status ->
            val paths = ManagedStaticDataPaths(createTempDirectory("download-$status"))
            val transport = SdeHttpTransport { SdeHttpResponse(status, emptyMap(), ByteArrayInputStream(byteArrayOf())) }
            assertFailsWith<IOException> {
                SdeArchiveDownloader(transport, paths, DiskSpacePreflight({ Long.MAX_VALUE })).download(uri, status.toLong())
            }
        }
    }

    private fun downloader(paths: ManagedStaticDataPaths, bytes: ByteArray, contentLength: Long?) =
        SdeArchiveDownloader(
            transport = SdeHttpTransport {
                SdeHttpResponse(
                    200,
                    contentLength?.let { mapOf("Content-Length" to listOf(it.toString())) } ?: emptyMap(),
                    ByteArrayInputStream(bytes),
                )
            },
            paths = paths,
            diskSpacePreflight = DiskSpacePreflight({ Long.MAX_VALUE }),
        )
}
