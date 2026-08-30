package dev.evestaticmapplanner

import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.Stargate
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.data.db.SourceFileAudit
import dev.evestaticmapplanner.data.db.StaticDatabaseBuildSession
import dev.evestaticmapplanner.data.db.StaticDatabaseSchema
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue

@OptIn(ExperimentalMcpApi::class)
class PortableHttpMcpInstalledImageTest {
    @Test
    fun `portable image serves HTTP MCP after move with a new profile`() = runBlocking {
        val imageProperty = System.getProperty(PORTABLE_IMAGE_PROPERTY)
        assumeTrue(imageProperty != null, "Runs only against the final extracted Portable image")
        val sourceImage = Path.of(imageProperty).toAbsolutePath().normalize()
        val root = createTempDirectory("portable-http-mcp-")
        val imageA = root.resolve("Portable Path A/EVE Static Map Planner")
        copyTree(sourceImage, imageA)
        val staticDatabase = root.resolve("fixture/static.db")
        buildHttpFixtureDatabase(staticDatabase)

        runPortableAcceptance(imageA, root.resolve("Profile A"), staticDatabase)
        waitForPortRelease()

        val imageB = root.resolve("Moved Portable Path B/EVE Static Map Planner")
        Files.createDirectories(imageB.parent)
        Files.move(imageA, imageB, StandardCopyOption.ATOMIC_MOVE)
        runPortableAcceptance(imageB, root.resolve("Fresh Profile B"), staticDatabase)
        waitForPortRelease()
    }

    private suspend fun runPortableAcceptance(image: Path, localAppData: Path, staticDatabase: Path) {
        val launcher = image.resolve("EVE Static Map Planner.exe")
        assertTrue(Files.isRegularFile(launcher), "Portable GUI launcher is missing: $launcher")
        val settings = localAppData.resolve("EVE Static Map Planner/settings.properties")
        Files.createDirectories(settings.parent)
        Files.writeString(settings, "settings.version=1\naiControl.enabled=true\n")
        val userDatabase = localAppData.resolve("test-data/user.db")
        Files.createDirectories(userDatabase.parent)
        val process = ProcessBuilder(
            launcher.toString(),
            "--database",
            staticDatabase.toString(),
            "--user-database",
            userDatabase.toString(),
        )
            .directory(image.parent.toFile())
            .redirectOutput(localAppData.resolve("portable-stdout.txt").also { Files.createDirectories(it.parent) }.toFile())
            .redirectError(localAppData.resolve("portable-stderr.txt").toFile())
            .apply {
                environment()["LOCALAPPDATA"] = localAppData.toString()
                environment().remove("JAVA_HOME")
                environment().remove("JDK_HOME")
                environment().remove("GRADLE_HOME")
            }
            .start()
        try {
            waitForListener(process)
            val http = HttpClient(CIO) { install(SSE) }
            val client = Client(Implementation("portable-http-acceptance", "1.0"))
            try {
                client.connect(StreamableHttpClientTransport(http, ENDPOINT))
                assertEquals(28, client.listTools().tools.size)
                val search = client.callTool("search_system", mapOf("query" to "Jita"))
                assertFalse(search.isError == true)
                val systems = search.structuredContent?.get("systems") as JsonArray
                val first = systems.single() as JsonObject
                assertEquals("Jita", (first.getValue("canonicalName") as JsonPrimitive).content)
            } finally {
                runCatching { client.close() }
                http.close()
            }
        } finally {
            stopProcessTree(process, "Portable GUI")
        }
    }

    private suspend fun waitForListener(process: Process) {
        repeat(600) {
            check(process.isAlive) { "Portable GUI exited before HTTP MCP became available" }
            if (canConnect()) return
            delay(50)
        }
        error("Portable HTTP MCP did not bind $ENDPOINT within 30 seconds")
    }

    private suspend fun waitForPortRelease() {
        repeat(200) {
            val available = runCatching {
                ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1")).use { true }
            }.getOrDefault(false)
            if (available) return
            delay(50)
        }
        error("Portable HTTP MCP did not release 127.0.0.1:$PORT")
    }

    private fun canConnect(): Boolean = runCatching {
        Socket("127.0.0.1", PORT).use { true }
    }.getOrDefault(false)

    private companion object {
        const val PORTABLE_IMAGE_PROPERTY = "eve.http.mcp.portable.image"
        const val PORT = 27892
        const val ENDPOINT = "http://127.0.0.1:27892/mcp"
    }
}

@OptIn(ExperimentalMcpApi::class)
class PortableHttpMcpResourceTest {
    @Test
    fun `portable HTTP MCP resource envelope is measured in three stable states`() = runBlocking {
        val imageProperty = System.getProperty(IMAGE_PROPERTY)
        val reportProperty = System.getProperty(REPORT_PROPERTY)
        assumeTrue(imageProperty != null && reportProperty != null, "Runs only in the resource acceptance task")
        val root = createTempDirectory("portable-http-resource-")
        val image = root.resolve("Resource QA/EVE Static Map Planner")
        copyTree(Path.of(imageProperty).toAbsolutePath().normalize(), image)
        val staticDatabase = root.resolve("fixture/static.db")
        buildHttpFixtureDatabase(staticDatabase)
        val samples = mutableListOf<ResourceSample>()

        ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1")).use {
            val baseline = startMap(image, root.resolve("Profile A"), staticDatabase)
            try {
                waitForLog(root.resolve("Profile A"), baseline, "Localhost MCP unavailable")
                samples += sample("A_PORT_OCCUPIED_BASELINE", baseline)
            } finally {
                stopMap(baseline)
            }
        }

        val idle = startMap(image, root.resolve("Profile B"), staticDatabase)
        try {
            waitForPort(idle)
            samples += sample("B_HTTP_IDLE", idle)
        } finally {
            stopMap(idle)
        }

        val connected = startMap(image, root.resolve("Profile C"), staticDatabase)
        val http = HttpClient(CIO) { install(SSE) }
        val client = Client(Implementation("portable-resource-client", "1.0"))
        try {
            waitForPort(connected)
            client.connect(StreamableHttpClientTransport(http, ENDPOINT))
            assertEquals(28, client.listTools().tools.size)
            samples += sample("C_ONE_CLIENT_CONNECTED", connected)
        } finally {
            runCatching { client.close() }
            http.close()
            stopMap(connected)
        }

        val baseline = samples.first()
        val idleSample = samples[1]
        val connectedSample = samples[2]
        val report = buildString {
            appendLine("EVE Static Map Planner 1.0.0 localhost HTTP MCP resource acceptance")
            appendLine("stable_window_seconds=5")
            samples.forEach { appendLine(it.encode()) }
            appendLine("http_idle_working_set_delta=${idleSample.workingSetBytes - baseline.workingSetBytes}")
            appendLine("http_idle_private_bytes_delta=${idleSample.privateBytes - baseline.privateBytes}")
            appendLine("http_idle_thread_delta=${idleSample.threads - baseline.threads}")
            appendLine("http_idle_handle_delta=${idleSample.handles - baseline.handles}")
            appendLine("one_client_working_set_delta_from_idle=${connectedSample.workingSetBytes - idleSample.workingSetBytes}")
            appendLine("one_client_thread_delta_from_idle=${connectedSample.threads - idleSample.threads}")
            appendLine("extra_child_processes=${samples.maxOf(ResourceSample::childProcesses)}")
        }
        val reportPath = Path.of(reportProperty)
        Files.createDirectories(reportPath.parent)
        Files.writeString(reportPath, report)
        println(report)
    }

    private fun startMap(image: Path, profile: Path, staticDatabase: Path): Process {
        val settings = profile.resolve("EVE Static Map Planner/settings.properties")
        Files.createDirectories(settings.parent)
        Files.writeString(settings, "settings.version=1\naiControl.enabled=true\n")
        val userDatabase = profile.resolve("data/user.db")
        Files.createDirectories(userDatabase.parent)
        return ProcessBuilder(
            image.resolve("EVE Static Map Planner.exe").toString(),
            "--database",
            staticDatabase.toString(),
            "--user-database",
            userDatabase.toString(),
        )
            .directory(image.parent.toFile())
            .redirectOutput(profile.resolve("stdout.txt").toFile())
            .redirectError(profile.resolve("stderr.txt").toFile())
            .apply {
                environment()["LOCALAPPDATA"] = profile.toString()
                listOf("JAVA_HOME", "JDK_HOME", "GRADLE_HOME", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")
                    .forEach(environment()::remove)
            }
            .start()
    }

    private suspend fun waitForPort(process: Process) {
        repeat(600) {
            check(process.isAlive) { "Portable Map exited before HTTP startup" }
            if (runCatching { Socket("127.0.0.1", PORT).use { true } }.getOrDefault(false)) return
            delay(50)
        }
        error("HTTP MCP did not bind within 30 seconds")
    }

    private suspend fun waitForLog(profile: Path, process: Process, expected: String) {
        val log = profile.resolve("EVE Static Map Planner/logs/app-0.log")
        repeat(600) {
            check(process.isAlive) { "Portable Map exited before baseline startup" }
            if (Files.isRegularFile(log) && Files.readString(log).contains(expected)) return
            delay(50)
        }
        error("Expected baseline log was not written: $expected")
    }

    private suspend fun sample(label: String, process: Process): ResourceSample {
        val target = process.toHandle().descendants().findFirst().orElse(process.toHandle())
        val before = target.info().totalCpuDuration().orElse(Duration.ZERO)
        delay(5_000)
        val after = target.info().totalCpuDuration().orElse(Duration.ZERO)
        val powershell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        val command = "${'$'}p=Get-Process -Id ${target.pid()}; " +
            "Write-Output \"${'$'}(${'$'}p.WorkingSet64)|${'$'}(${'$'}p.PrivateMemorySize64)|" +
            "${'$'}(${'$'}p.Threads.Count)|${'$'}(${'$'}p.HandleCount)\""
        val encodedCommand = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_16LE))
        val metricsProcess = ProcessBuilder(powershell.toString(), "-NoProfile", "-EncodedCommand", encodedCommand)
            .redirectErrorStream(true)
            .start()
        val output = metricsProcess.inputStream.bufferedReader().use { it.readText() }.trim()
        check(metricsProcess.waitFor(10, TimeUnit.SECONDS) && metricsProcess.exitValue() == 0) {
            "Could not sample Map process: $output"
        }
        val metricsLine = output.lineSequence().firstOrNull { it.matches(Regex("\\d+\\|\\d+\\|\\d+\\|\\d+")) }
            ?: error("PowerShell resource output did not contain metrics: $output")
        val values = metricsLine.split('|').map(String::toLong)
        return ResourceSample(
            label = label,
            workingSetBytes = values[0],
            privateBytes = values[1],
            cpuMillisOverFiveSeconds = (after - before).toMillis(),
            threads = values[2].toInt(),
            handles = values[3].toInt(),
            childProcesses = target.descendants().count(),
        )
    }

    private fun stopMap(process: Process) {
        stopProcessTree(process, "Portable Map")
    }

    private companion object {
        const val IMAGE_PROPERTY = "eve.http.mcp.resource.portable.image"
        const val REPORT_PROPERTY = "eve.http.mcp.resource.report"
        const val PORT = 27892
        const val ENDPOINT = "http://127.0.0.1:27892/mcp"
    }
}

private fun stopProcessTree(process: Process, label: String) {
    val descendants = process.toHandle().descendants().toList().asReversed()
    descendants.forEach { handle -> if (handle.isAlive) handle.destroyForcibly() }
    if (process.isAlive) process.destroyForcibly()
    assertTrue(process.waitFor(15, TimeUnit.SECONDS), "$label launcher did not exit")

    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
    while (descendants.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
        descendants.forEach { handle -> if (handle.isAlive) handle.destroyForcibly() }
        Thread.sleep(50)
    }
    val survivingPids = descendants.filter(ProcessHandle::isAlive).map(ProcessHandle::pid)
    assertTrue(survivingPids.isEmpty(), "$label child processes did not exit: $survivingPids")
}

private data class ResourceSample(
    val label: String,
    val workingSetBytes: Long,
    val privateBytes: Long,
    val cpuMillisOverFiveSeconds: Long,
    val threads: Int,
    val handles: Int,
    val childProcesses: Long,
) {
    fun encode(): String = listOf(
        "case=$label",
        "working_set_bytes=$workingSetBytes",
        "private_bytes=$privateBytes",
        "cpu_ms_over_5s=$cpuMillisOverFiveSeconds",
        "threads=$threads",
        "handles=$handles",
        "child_processes=$childProcesses",
    ).joinToString(" ")
}

private fun copyTree(source: Path, destination: Path) {
    Files.walk(source).use { paths ->
        paths.forEach { path ->
            val target = destination.resolve(source.relativize(path).toString())
            if (Files.isDirectory(path)) {
                Files.createDirectories(target)
            } else {
                Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }
}

private fun buildHttpFixtureDatabase(path: Path) {
    Files.createDirectories(path.parent)
    StaticDatabaseBuildSession.create(path).use { database ->
        val origin = UniversePosition(0.0, 0.0, 0.0)
        database.insert(Region(10000002, "The Forge", origin, null))
        database.insert(Constellation(20000020, 10000002, "Kimotoro", origin, null))
        database.insert(
            SolarSystem(
                30000142,
                20000020,
                10000002,
                "Jita",
                0.9,
                null,
                origin,
                SchematicPosition(1.0e15, 1.0e15),
                1.0,
                null,
                null,
                null,
            ),
        )
        database.insert(
            SolarSystem(
                30000144,
                20000020,
                10000002,
                "Perimeter",
                1.0,
                null,
                origin,
                SchematicPosition(2.0e15, 1.0e15),
                1.0,
                null,
                null,
                null,
            ),
        )
        database.insert(Stargate(50000001, 30000142, 30000144, 50000002, 1, origin))
        database.insert(Stargate(50000002, 30000144, 30000142, 50000001, 1, origin))
        database.insert(SourceFileAudit("portable-http-fixture", "b".repeat(64), 2))
        mapOf(
            "schema_version" to StaticDatabaseSchema.VERSION.toString(),
            "sde_build" to "20260830",
            "generated_at" to "2026-08-30T00:00:00Z",
            "source_format" to "jsonl",
            "generator_version" to "portable-http-test",
        ).forEach(database::putMetadata)
        database.validationReport()
        database.commit()
    }
}
