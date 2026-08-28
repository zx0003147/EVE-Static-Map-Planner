package dev.evestaticmapplanner.map

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PresentationEmblemDiskCacheTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `default cache directory stays under application cache instead of data`() {
        val localAppData = temporaryDirectory.resolve("LocalAppData")

        val resolved = defaultPresentationEmblemCacheDirectory(
            environment = mapOf("LOCALAPPDATA" to localAppData.toString()),
            osName = "Windows 11",
            userHome = temporaryDirectory.resolve("home"),
        )

        assertEquals(
            localAppData.resolve("EVE Static Map Planner/cache/alliance-logos").toAbsolutePath().normalize(),
            resolved,
        )
        assertTrue(!resolved.toString().contains("${java.io.File.separator}data${java.io.File.separator}"))
    }

    @Test
    fun `cache miss downloads publishes and becomes ready`() = runTest {
        var downloads = 0
        val loader = loader(
            downloader = PresentationEmblemDownloader {
                downloads++
                VALID_BYTES
            },
        )
        val repository = PresentationEmblemAssetRepository(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            loader = loader,
        )

        val request = repository.request(REFERENCE)
        runCurrent()

        assertEquals("valid-logo", assertIs<PresentationEmblemAssetState.Ready<String>>(request.await()).asset)
        assertEquals(1, downloads)
        assertContentEquals(VALID_BYTES, Files.readAllBytes(loader.cachePath(REFERENCE)))
        assertTrue(loader.cachePath(REFERENCE).fileName.toString().matches(Regex("[0-9a-f]{64}\\.png")))
    }

    @Test
    fun `valid cache loads without network access`() = runTest {
        var downloads = 0
        val loader = loader(
            downloader = PresentationEmblemDownloader {
                downloads++
                error("Network must not be used")
            },
        )
        Files.createDirectories(temporaryDirectory)
        Files.write(loader.cachePath(REFERENCE), VALID_BYTES)

        assertEquals("valid-logo", loader.load(REFERENCE))
        assertEquals(0, downloads)
    }

    @Test
    fun `corrupt cache is discarded and replaced by a fresh download`() = runTest {
        var downloads = 0
        val warnings = mutableListOf<String>()
        val loader = loader(
            downloader = PresentationEmblemDownloader {
                downloads++
                FRESH_BYTES
            },
            warningSink = { message, _ -> warnings += message },
        )
        Files.createDirectories(temporaryDirectory)
        Files.write(loader.cachePath(REFERENCE), CORRUPT_BYTES)

        assertEquals("fresh-logo", loader.load(REFERENCE))
        assertEquals(1, downloads)
        assertContentEquals(FRESH_BYTES, Files.readAllBytes(loader.cachePath(REFERENCE)))
        assertTrue(warnings.any { it.contains("cache is invalid") })
    }

    @Test
    fun `download failure retries three times with bounded backoff`() = runTest {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val warnings = mutableListOf<String>()
        val loader = loader(
            downloader = PresentationEmblemDownloader {
                attempts++
                throw IOException("offline")
            },
            warningSink = { message, _ -> warnings += message },
            delayBeforeRetry = delays::add,
        )

        val failure = try {
            loader.load(REFERENCE)
            null
        } catch (error: IOException) {
            error
        }

        assertNotNull(failure)
        assertEquals(EMBLEM_NETWORK_ATTEMPTS, attempts)
        assertEquals(listOf(250L, 500L), delays)
        assertEquals(EMBLEM_NETWORK_ATTEMPTS, warnings.count { it.contains("download failed") })
    }

    @Test
    fun `exhausted retry for one logo does not prevent another logo becoming ready`() = runTest {
        val attemptsByKey = mutableMapOf<String, Int>()
        val loader = loader(
            downloader = PresentationEmblemDownloader { reference ->
                attemptsByKey[reference.key] = attemptsByKey.getOrDefault(reference.key, 0) + 1
                if (reference.key == FAILED_REFERENCE.key) throw IOException("unavailable")
                VALID_BYTES
            },
        )
        val repository = PresentationEmblemAssetRepository(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            loader = loader,
        )

        val failed = repository.request(FAILED_REFERENCE)
        val ready = repository.request(REFERENCE)
        advanceUntilIdle()

        assertIs<PresentationEmblemAssetState.Failed>(failed.await())
        assertEquals("valid-logo", assertIs<PresentationEmblemAssetState.Ready<String>>(ready.await()).asset)
        assertEquals(EMBLEM_NETWORK_ATTEMPTS, attemptsByKey[FAILED_REFERENCE.key])
        assertEquals(1, attemptsByKey[REFERENCE.key])
    }

    @Test
    fun `concurrent requests for the same uncached logo perform one download`() = runTest {
        var downloads = 0
        val releaseDownload = CompletableDeferred<Unit>()
        val loader = loader(
            downloader = PresentationEmblemDownloader {
                downloads++
                releaseDownload.await()
                VALID_BYTES
            },
        )
        val repository = PresentationEmblemAssetRepository(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            loader = loader,
        )

        val first = repository.request(REFERENCE)
        val second = repository.request(REFERENCE)
        assertSame(first, second)
        runCurrent()
        assertEquals(1, downloads)

        releaseDownload.complete(Unit)
        advanceUntilIdle()
        assertIs<PresentationEmblemAssetState.Ready<String>>(first.await())
        assertContentEquals(VALID_BYTES, Files.readAllBytes(loader.cachePath(REFERENCE)))
    }

    @Test
    fun `network concurrency is bounded across different logos`() = runTest {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val started = AtomicInteger()
        val releaseDownloads = CompletableDeferred<Unit>()
        val loader = loader(
            downloader = PresentationEmblemDownloader {
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                started.incrementAndGet()
                try {
                    releaseDownloads.await()
                    VALID_BYTES
                } finally {
                    active.decrementAndGet()
                }
            },
            maxConcurrentDownloads = 4,
        )

        val jobs = (1..10).map { index ->
            async { loader.load(reference(index)) }
        }
        runCurrent()

        assertEquals(4, started.get())
        assertEquals(4, maximumActive.get())
        releaseDownloads.complete(Unit)
        advanceUntilIdle()
        assertEquals(List(10) { "valid-logo" }, jobs.awaitAll())
        assertTrue(maximumActive.get() <= 4)
    }

    @Test
    fun `cache write failure is logged but downloaded logo remains usable`() = runTest {
        val cachePathThatIsAFile = temporaryDirectory.resolve("not-a-directory")
        Files.writeString(cachePathThatIsAFile, "occupied")
        val warnings = mutableListOf<String>()
        val loader = loader(
            cacheDirectory = cachePathThatIsAFile,
            downloader = PresentationEmblemDownloader { VALID_BYTES },
            warningSink = { message, _ -> warnings += message },
        )

        assertEquals("valid-logo", loader.load(REFERENCE))
        assertTrue(warnings.any { it.contains("cache write failed") })
    }

    private fun loader(
        cacheDirectory: Path = temporaryDirectory,
        downloader: PresentationEmblemDownloader,
        warningSink: (String, Throwable?) -> Unit = { _, _ -> },
        maxConcurrentDownloads: Int = EMBLEM_MAX_CONCURRENT_DOWNLOADS,
        delayBeforeRetry: suspend (Long) -> Unit = {},
    ) = DiskCachedPresentationEmblemLoader(
        cacheDirectory = cacheDirectory,
        downloader = downloader,
        decoder = PresentationEmblemDecoder(::decode),
        warningSink = warningSink,
        maxConcurrentDownloads = maxConcurrentDownloads,
        delayBeforeRetry = delayBeforeRetry,
    )

    private fun decode(bytes: ByteArray): String = when {
        bytes.contentEquals(VALID_BYTES) -> "valid-logo"
        bytes.contentEquals(FRESH_BYTES) -> "fresh-logo"
        else -> error("Corrupt image")
    }

    private fun reference(index: Int) = PresentationEmblemReference(
        key = "eve-alliance:$index",
        url = "https://images.evetech.net/alliances/$index/logo?size=256",
    )

    private companion object {
        val VALID_BYTES = "valid".encodeToByteArray()
        val FRESH_BYTES = "fresh".encodeToByteArray()
        val CORRUPT_BYTES = "corrupt".encodeToByteArray()
        val REFERENCE = PresentationEmblemReference(
            key = "eve-alliance:42",
            url = "https://images.evetech.net/alliances/42/logo?size=256",
        )
        val FAILED_REFERENCE = PresentationEmblemReference(
            key = "eve-alliance:404",
            url = "https://images.evetech.net/alliances/404/logo?size=256",
        )
    }
}
