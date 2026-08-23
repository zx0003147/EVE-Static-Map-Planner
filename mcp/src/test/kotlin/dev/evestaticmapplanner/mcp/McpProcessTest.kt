package dev.evestaticmapplanner.mcp

import dev.evestaticmapplanner.control.DefaultMapControlService
import dev.evestaticmapplanner.control.JumpPlanningPort
import dev.evestaticmapplanner.control.MissionRenderStatePort
import dev.evestaticmapplanner.control.RoutePlanningPort
import dev.evestaticmapplanner.control.SystemInfoDto
import dev.evestaticmapplanner.control.SystemReadPort
import dev.evestaticmapplanner.control.SystemSummaryDto
import dev.evestaticmapplanner.control.ViewportControlPort
import dev.evestaticmapplanner.control.ViewportOperationOutcome
import dev.evestaticmapplanner.control.transport.LocalControlDiscoveryAcquisition
import dev.evestaticmapplanner.control.transport.LocalControlServer
import dev.evestaticmapplanner.control.transport.SecureLocalControlDiscovery
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class McpProcessTest {
    @Test
    fun `real stdio process has protocol-only stdout and exits on EOF`() = runBlocking {
        KotlinLoggingConfiguration.logStartupMessage = false
        val localAppData = createTempDirectory("mcp-stdio-localappdata-")
        val secretSentinel = "stdio-secret-must-not-leak"
        val process = javaProcess("dev.evestaticmapplanner.mcp.MainKt", localAppData, secretSentinel)
        val stdout = RecordingInputStream(process.inputStream)
        val stderr = RecordingInputStream(process.errorStream)
        val transport = StdioClientTransport(
            stdout.asSource().buffered(),
            process.outputStream.asSink().buffered(),
            stderr.asSource().buffered(),
        )
        val client = Client(Implementation("step-3a-process-test", "1.0"))
        try {
            client.connect(transport)
            assertEquals(20, client.listTools().tools.size)
            val disconnected = client.callTool("search_system", mapOf("query" to "Jita"))
            assertTrue(disconnected.isError == true)
            assertEquals(
                "APP_DISCONNECTED",
                disconnected.structuredContent?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.content,
            )
        } finally {
            runCatching { client.close() }
            runCatching { process.outputStream.close() }
        }

        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "MCP process did not exit after stdin EOF")
        assertEquals(0, process.exitValue())
        val protocolLines = stdout.utf8().lineSequence().filter(String::isNotBlank).toList()
        assertTrue(protocolLines.isNotEmpty())
        protocolLines.forEach { line -> assertTrue(Json.parseToJsonElement(line).jsonObject.isNotEmpty()) }
        assertFalse(stdout.utf8().contains("kotlin-logging:"))
        assertFalse(stderr.utf8().contains(secretSentinel))
    }

    @Test
    fun `MCP handler reaches real local server and map control service`() {
        val localAppData = createTempDirectory("mcp-e2e-localappdata-")
        val process = javaProcess("dev.evestaticmapplanner.mcp.McpEndToEndProbe", localAppData, "e2e-secret")
        assertTrue(process.waitFor(15, TimeUnit.SECONDS), "MCP end-to-end probe did not exit")
        val stdout = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
        val stderr = process.errorStream.readAllBytes().toString(StandardCharsets.UTF_8)
        assertEquals(0, process.exitValue(), stderr)
        assertEquals("E2E_OK", stdout.trim())
        assertFalse(stderr.contains("e2e-secret"))
    }

    @Test
    fun `packaged launcher has pure stdio disconnected behavior and exits on EOF`() = runBlocking {
        KotlinLoggingConfiguration.logStartupMessage = false
        val launcher = packagedLauncherPath()
        assertTrue(launcher.toString().contains(' '), "Acceptance launcher path must exercise spaces")
        val localAppData = createTempDirectory("mcp-launcher-disconnected-")
        val secretSentinel = "launcher-secret-must-not-leak"
        val process = launcherProcess(launcher, localAppData, secretSentinel)
        val stdout = RecordingInputStream(process.inputStream)
        val stderr = RecordingInputStream(process.errorStream)
        val transport = StdioClientTransport(
            stdout.asSource().buffered(),
            process.outputStream.asSink().buffered(),
            stderr.asSource().buffered(),
        )
        val client = Client(Implementation("step-3b-launcher-test", "1.0"))
        try {
            client.connect(transport)
            assertEquals(20, client.listTools().tools.size)
            val disconnected = client.callTool("search_system", mapOf("query" to "Jita"))
            assertTrue(disconnected.isError == true)
            assertEquals(
                "APP_DISCONNECTED",
                disconnected.structuredContent?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.content,
            )
        } finally {
            runCatching { client.close() }
            runCatching { process.outputStream.close() }
        }

        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Packaged MCP launcher did not exit after stdin EOF")
        assertEquals(0, process.exitValue())
        assertProtocolOnly(stdout)
        assertFalse(stderr.utf8().contains(secretSentinel))
        assertFalse(process.isAlive)
    }

    @Test
    fun `stable launcher resolves from PATH outside install directory`() = runBlocking {
        assumeTrue(
            System.getProperty("eve.mcp.stable.launcher.path") != null,
            "Runs only against the final integrated app-image",
        )
        KotlinLoggingConfiguration.logStartupMessage = false
        val installedLauncher = stablePackagedLauncherPath()
        val workingDirectory = createTempDirectory("mcp-path-working-directory-")
        assertFalse(workingDirectory.startsWith(installedLauncher.parent))
        val localAppData = createTempDirectory("mcp-path-disconnected-")
        val secretSentinel = "path-launcher-secret-must-not-leak"
        val process = launcherProcess(
            launcher = Path.of("eve-map-mcp.exe"),
            localAppData = localAppData,
            secretSentinel = secretSentinel,
            workingDirectory = workingDirectory,
        )
        val stdout = RecordingInputStream(process.inputStream)
        val stderr = RecordingInputStream(process.errorStream)
        val transport = StdioClientTransport(
            stdout.asSource().buffered(),
            process.outputStream.asSink().buffered(),
            stderr.asSource().buffered(),
        )
        val client = Client(Implementation("step-4d-path-launcher-test", "1.0"))
        try {
            client.connect(transport)
            assertEquals(20, client.listTools().tools.size)
            val disconnected = client.callTool("search_system", mapOf("query" to "Jita"))
            assertTrue(disconnected.isError == true)
            assertEquals(
                "APP_DISCONNECTED",
                disconnected.structuredContent?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.content,
            )
        } finally {
            runCatching { client.close() }
            runCatching { process.outputStream.close() }
        }

        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "PATH-resolved MCP launcher did not exit after stdin EOF")
        assertEquals(0, process.exitValue())
        assertProtocolOnly(stdout)
        assertFalse(stderr.utf8().contains(secretSentinel))
        assertFalse(process.isAlive)
    }

    @Test
    fun `packaged launcher reaches real local server and mission layer`() = runBlocking {
        KotlinLoggingConfiguration.logStartupMessage = false
        val localAppData = createTempDirectory("mcp-launcher-e2e-")
        val root = localAppData.resolve("EVE Static Map Planner").resolve("control")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = probeService(scope)
        val lease = (SecureLocalControlDiscovery(root).acquire() as LocalControlDiscoveryAcquisition.Acquired).lease
        val localServer = LocalControlServer(service, "different-app-version-is-compatible")
        var process: Process? = null
        var protocolClient: Client? = null
        var stdout: RecordingInputStream? = null
        var stderr: RecordingInputStream? = null
        try {
            localServer.start()
            lease.publish(localServer)
            process = launcherProcess(packagedLauncherPath(), localAppData, "launcher-e2e-secret")
            stdout = RecordingInputStream(process.inputStream)
            stderr = RecordingInputStream(process.errorStream)
            val transport = StdioClientTransport(
                stdout.asSource().buffered(),
                process.outputStream.asSink().buffered(),
                stderr.asSource().buffered(),
            )
            protocolClient = Client(Implementation("step-3b-launcher-e2e", "1.0"))
            protocolClient.connect(transport)

            val search = protocolClient.callTool("search_system", mapOf("query" to "A"))
            assertFalse(search.isError == true)
            val systems = search.structuredContent?.get("systems") as JsonArray
            assertEquals("A", systems.single().jsonObject.getValue("canonicalName").jsonPrimitive.content)

            val begin = protocolClient.callTool("begin_mission", mapOf("title" to "Launcher E2E Mission"))
            assertFalse(begin.isError == true)
            val missionId = begin.structuredContent?.get("missionId")?.jsonPrimitive?.content.orEmpty()
            assertTrue(missionId.isNotBlank())

            val mission = protocolClient.callTool("get_mission", mapOf("missionId" to missionId))
            assertFalse(mission.isError == true)
            assertEquals(missionId, mission.structuredContent?.get("missionId")?.jsonPrimitive?.content)

            val clear = protocolClient.callTool("clear_mission", mapOf("missionId" to missionId))
            assertFalse(clear.isError == true)
        } finally {
            runCatching { protocolClient?.close() }
            runCatching { process?.outputStream?.close() }
            if (process != null) {
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            runCatching { lease.unpublishDescriptor() }
            runCatching { localServer.stop() }
            runCatching { lease.removeSessionKey() }
            runCatching { lease.release() }
            runCatching { service.close() }
            scope.cancel()
        }

        val completedProcess = requireNotNull(process)
        assertEquals(0, completedProcess.exitValue())
        assertFalse(completedProcess.isAlive)
        assertProtocolOnly(requireNotNull(stdout))
        assertFalse(requireNotNull(stderr).utf8().contains("launcher-e2e-secret"))
    }
}

@OptIn(ExperimentalMcpApi::class)
internal object McpEndToEndProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        KotlinLoggingConfiguration.logStartupMessage = false
        val root = Path.of(requireNotNull(System.getenv("LOCALAPPDATA")))
            .resolve("EVE Static Map Planner")
            .resolve("control")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = probeService(scope)
        val lease = (SecureLocalControlDiscovery(root).acquire() as LocalControlDiscoveryAcquisition.Acquired).lease
        val localServer = LocalControlServer(service, "0.1.2")
        localServer.start()
        lease.publish(localServer)
        val mcpClient = LocalMcpMapClient()
        val mcpServer = createMcpServer(mcpClient)
        val transports = ChannelTransport.createLinkedPair()
        val protocolClient = Client(Implementation("step-3a-e2e-test", "1.0"))
        try {
            mcpServer.createSession(transports.serverTransport)
            protocolClient.connect(transports.clientTransport)
            val search = protocolClient.callTool("search_system", mapOf("query" to "A"))
            check(search.isError != true)
            val systems = search.structuredContent?.get("systems") as JsonArray
            check(systems.single().jsonObject.getValue("canonicalName").jsonPrimitive.content == "A")
            val mission = protocolClient.callTool("begin_mission", mapOf("title" to "E2E Mission"))
            check(mission.isError != true)
            check(mission.structuredContent?.get("missionId")?.jsonPrimitive?.content?.isNotBlank() == true)
        } finally {
            runCatching { protocolClient.close() }
            runCatching { mcpServer.close() }
            runCatching { mcpClient.close() }
            runCatching { lease.unpublishDescriptor() }
            runCatching { localServer.stop() }
            runCatching { lease.removeSessionKey() }
            runCatching { lease.release() }
            runCatching { service.close() }
            scope.cancel()
        }
        print("E2E_OK")
    }
}

private fun probeService(scope: CoroutineScope): DefaultMapControlService {
    val system = SystemSummaryDto(1, "A", 1, 1, 0.0)
    return DefaultMapControlService(
        systemReadPort = object : SystemReadPort {
            override suspend fun searchSystems(query: String, limit: Int) = listOf(system)
            override suspend fun getSystemInfo(systemId: Int) =
                if (systemId == 1) SystemInfoDto(system, "R", "C", 0.0, 0.0, 0.0, 0) else null
        },
        routePlanningPort = object : RoutePlanningPort {
            override suspend fun calculateNormalRoute(
                startSystemId: Int,
                destinationSystemId: Int,
                useAnsiblex: Boolean,
            ) = RouteCalculationOutcome.InvalidEndpoint(emptySet(), startSystemId, destinationSystemId)

            override suspend fun calculateCapitalRoute(
                startSystemId: Int,
                destinationSystemId: Int,
                effectiveRangeLy: Double,
            ) = CapitalRouteOutcome.InvalidEndpoint(emptySet())
        },
        jumpPlanningPort = JumpPlanningPort { _, _ -> error("unused") },
        viewportControlPort = object : ViewportControlPort {
            override suspend fun focusSystem(systemId: Int) = ViewportOperationOutcome.COMPLETED
            override suspend fun fitSystems(systemIds: Set<Int>) = ViewportOperationOutcome.COMPLETED
        },
        missionRenderStatePort = MissionRenderStatePort { },
        scope = scope,
    )
}

private fun javaProcess(mainClass: String, localAppData: Path, secretSentinel: String): Process {
    val java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()
    return ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), mainClass)
        .apply {
            environment()["LOCALAPPDATA"] = localAppData.toString()
            environment()["MCP_TEST_SECRET_SENTINEL"] = secretSentinel
        }
        .start()
}

private fun packagedLauncherPath(): Path = Path.of(
    requireNotNull(System.getProperty("eve.mcp.launcher.path")) { "Missing packaged MCP launcher test path" },
).toAbsolutePath().normalize().also { launcher ->
    check(Files.isRegularFile(launcher)) { "Packaged MCP launcher is missing: $launcher" }
}

private fun stablePackagedLauncherPath(): Path = Path.of(
    requireNotNull(System.getProperty("eve.mcp.stable.launcher.path")) {
        "Missing stable packaged MCP launcher test path"
    },
).toAbsolutePath().normalize().also { launcher ->
    check(Files.isRegularFile(launcher)) { "Stable packaged MCP launcher is missing: $launcher" }
    val pathValue = System.getenv().entries.single { it.key.equals("PATH", ignoreCase = true) }.value
    check(pathValue.split(';').any {
        runCatching { Path.of(it).toAbsolutePath().normalize() == launcher.parent }.getOrDefault(false)
    }) { "Stable packaged MCP launcher directory is missing from the test process PATH" }
}

private fun launcherProcess(
    launcher: Path,
    localAppData: Path,
    secretSentinel: String,
    workingDirectory: Path? = null,
): Process =
    ProcessBuilder(launcher.toString())
        .apply {
            workingDirectory?.let { directory(it.toFile()) }
            environment()["LOCALAPPDATA"] = localAppData.toString()
            environment()["MCP_TEST_SECRET_SENTINEL"] = secretSentinel
            environment().remove("JAVA_HOME")
            environment().remove("JDK_HOME")
            environment().remove("GRADLE_HOME")
            environment()["PATH"] = environment()["PATH"].orEmpty()
                .split(';')
                .filterNot { entry ->
                    entry.contains("java", ignoreCase = true) || entry.contains("gradle", ignoreCase = true)
                }
                .joinToString(";")
        }
        .start()

private fun assertProtocolOnly(stdout: RecordingInputStream) {
    val text = stdout.utf8()
    val protocolLines = text.lineSequence().filter(String::isNotBlank).toList()
    assertTrue(protocolLines.isNotEmpty())
    protocolLines.forEach { line -> assertTrue(Json.parseToJsonElement(line).jsonObject.isNotEmpty()) }
    assertFalse(text.contains("kotlin-logging:"))
}

private class RecordingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
    private val bytes = ByteArrayOutputStream()

    @Synchronized
    override fun read(): Int = super.read().also { if (it >= 0) bytes.write(it) }

    @Synchronized
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { count -> if (count > 0) bytes.write(buffer, offset, count) }

    @Synchronized
    fun utf8(): String = bytes.toString(StandardCharsets.UTF_8)
}
