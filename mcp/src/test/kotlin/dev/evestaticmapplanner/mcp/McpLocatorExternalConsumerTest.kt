package dev.evestaticmapplanner.mcp

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class McpLocatorExternalConsumerTest {
    @Test
    fun `external consumer launches exact twenty two tool MCP directly from generated locator`() = runBlocking {
        val imageProperty = System.getProperty(PORTABLE_IMAGE_PROPERTY)
        assumeTrue(imageProperty != null, "Runs only against the final extracted Portable image")
        val applicationImage = Path.of(imageProperty).toAbsolutePath().normalize()
        val guiLauncher = applicationImage.resolve("EVE Static Map Planner.exe")
        assertTrue(Files.isRegularFile(guiLauncher), "Portable GUI launcher is missing")
        val localAppData = createTempDirectory("mcp-locator-consumer-localappdata-")
        val workingDirectory = createTempDirectory("mcp-locator-unrelated-working-directory-")
        val locator = localAppData.resolve("EVE Static Map Planner/integration/mcp.json")

        val gui = portableProcess(guiLauncher, localAppData, workingDirectory)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!Files.isRegularFile(locator) && gui.isAlive && System.nanoTime() < deadline) {
                Thread.sleep(50)
            }
            assertTrue(Files.isRegularFile(locator), "Portable GUI did not publish the MCP locator")
        } finally {
            if (gui.isAlive) gui.destroyForcibly()
            gui.waitFor(10, TimeUnit.SECONDS)
        }

        val document = Json.parseToJsonElement(Files.readString(locator)).jsonObject
        assertEquals(setOf("schemaVersion", "appVersion", "transport", "command"), document.keys)
        assertEquals(1, document.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("0.6.0", document.getValue("appVersion").jsonPrimitive.content)
        assertEquals("stdio", document.getValue("transport").jsonPrimitive.content)
        val command = Path.of(document.getValue("command").jsonPrimitive.content).toAbsolutePath().normalize()
        assertEquals(applicationImage.resolve("eve-map-mcp.exe"), command)
        assertTrue(Files.isRegularFile(command), "Locator command is not a regular file")

        KotlinLoggingConfiguration.logStartupMessage = false
        val mcp = portableProcess(command, localAppData, workingDirectory)
        val client = Client(Implementation("mcp-locator-external-consumer", "1.0"))
        try {
            client.connect(
                StdioClientTransport(
                    mcp.inputStream.asSource().buffered(),
                    mcp.outputStream.asSink().buffered(),
                    mcp.errorStream.asSource().buffered(),
                ),
            )
            assertEquals(28, client.listTools().tools.size)
        } finally {
            runCatching { client.close() }
            runCatching { mcp.outputStream.close() }
        }
        assertTrue(mcp.waitFor(10, TimeUnit.SECONDS), "Locator-launched MCP did not exit after stdin EOF")
        assertEquals(0, mcp.exitValue())
    }

    private fun portableProcess(executable: Path, localAppData: Path, workingDirectory: Path): Process =
        ProcessBuilder(executable.toString())
            .directory(workingDirectory.toFile())
            .apply {
                environment()["LOCALAPPDATA"] = localAppData.toString()
                environment().remove("JAVA_HOME")
                environment().remove("JDK_HOME")
                environment().remove("GRADLE_HOME")
                val pathKey = environment().keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "Path"
                environment()[pathKey] = environment()[pathKey].orEmpty()
                    .split(';')
                    .filterNot { entry ->
                        entry.contains("java", ignoreCase = true) || entry.contains("gradle", ignoreCase = true)
                    }
                    .joinToString(";")
            }
            .start()

    private companion object {
        const val PORTABLE_IMAGE_PROPERTY = "eve.mcp.locator.portable.image"
    }
}
