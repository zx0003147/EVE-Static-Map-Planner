package dev.evestaticmapplanner.sde.update

import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SdeUpdateServiceTest {
    @Test
    fun `check compares install update up to date and local newer`() = runTest {
        val cases = listOf(
            null to SdeUpdateComparison.INSTALL_AVAILABLE,
            100L to SdeUpdateComparison.UPDATE_AVAILABLE,
            200L to SdeUpdateComparison.UP_TO_DATE,
            300L to SdeUpdateComparison.LOCAL_NEWER,
        )
        cases.forEach { (current, expected) ->
            val root = createTempDirectory("service-check")
            val paths = ManagedStaticDataPaths(root)
            if (current != null) UpdateTestFixtures.installOld(paths, current)
            val transport = ByteQueueTransport(
                ByteResponse(200, latestBody(200).toByteArray()),
            )
            val service = service(paths, transport, this)
            assertTrue(service.checkForUpdates())
            assertFalse(service.checkForUpdates(), "one updater job must run at a time")
            advanceUntilIdle()
            assertEquals(expected, service.state.value.comparison)
            assertEquals(SdeUpdaterPhase.IDLE, service.state.value.phase)
        }
    }

    @Test
    fun `first install service prepares validates activates and reports success`() = runTest {
        val root = createTempDirectory("service-install")
        val fixtureZip = createFixtureZip(root)
        val bytes = Files.readAllBytes(fixtureZip)
        val paths = ManagedStaticDataPaths(root.resolve("managed"))
        var activated: Long? = null
        val transport = ByteQueueTransport(
            ByteResponse(200, latestBody(200).toByteArray()),
            ByteResponse(200, bytes, mapOf("Content-Length" to listOf(bytes.size.toString()))),
        )
        val service = service(paths, transport, this) { activated = it }

        service.checkForUpdates()
        advanceUntilIdle()
        assertTrue(service.downloadAndPrepare())
        advanceUntilIdle()

        assertEquals(SdeUpdaterPhase.SUCCEEDED, service.state.value.phase)
        assertEquals(200, activated)
        UpdateTestFixtures.assertBuild(paths.activeDatabase, 200)
    }

    @Test
    fun `normal update prepares pending and never hot swaps active database`() = runTest {
        val root = createTempDirectory("service-update")
        val fixtureZip = createFixtureZip(root)
        val bytes = Files.readAllBytes(fixtureZip)
        val paths = ManagedStaticDataPaths(root.resolve("managed"))
        UpdateTestFixtures.installOld(paths, 100)
        val transport = ByteQueueTransport(
            ByteResponse(200, latestBody(200).toByteArray()),
            ByteResponse(200, bytes, mapOf("Content-Length" to listOf(bytes.size.toString()))),
        )
        val service = service(paths, transport, this)

        service.checkForUpdates()
        advanceUntilIdle()
        service.downloadAndPrepare()
        advanceUntilIdle()

        assertEquals(SdeUpdaterPhase.RESTART_REQUIRED, service.state.value.phase)
        assertEquals(200, service.state.value.pendingBuild)
        UpdateTestFixtures.assertBuild(paths.activeDatabase, 100)
    }

    private fun service(
        paths: ManagedStaticDataPaths,
        transport: SdeHttpTransport,
        scope: TestScope,
        onActivated: (Long) -> Unit = {},
    ): SdeUpdateService {
        val pending = PendingUpdateStore(paths)
        val client = SdeUpdateClient(
            transport,
            LatestBuildCacheStore(paths),
            latestUri = URI.create("https://example.test/latest.jsonl"),
        )
        return SdeUpdateService(
            paths,
            client,
            SdeArchiveDownloader(transport, paths, DiskSpacePreflight({ Long.MAX_VALUE })),
            SdeCandidatePreparer(paths, stagingIdGenerator = { "service" }),
            PendingUpdateActivator(paths),
            pending,
            scope,
            ioDispatcher = Dispatchers.Unconfined,
            onFirstInstallActivated = onActivated,
        )
    }

    private fun createFixtureZip(root: java.nio.file.Path) = SafeSdeArchiveExtractorTest().zip(
        root.resolve("fixture.zip"),
        SafeSdeArchiveExtractorTest().validEntries(),
    )

    private fun latestBody(build: Long) = """{"_key":"sde","buildNumber":$build}"""
}

private data class ByteResponse(
    val status: Int,
    val body: ByteArray,
    val headers: Map<String, List<String>> = emptyMap(),
)

private class ByteQueueTransport(vararg values: ByteResponse) : SdeHttpTransport {
    private val responses = ArrayDeque(values.toList())
    override fun execute(request: SdeHttpRequest): SdeHttpResponse {
        val value = responses.removeFirstOrNull() ?: error("Unexpected HTTP request: ${request.uri}")
        return SdeHttpResponse(value.status, value.headers, ByteArrayInputStream(value.body))
    }
}
