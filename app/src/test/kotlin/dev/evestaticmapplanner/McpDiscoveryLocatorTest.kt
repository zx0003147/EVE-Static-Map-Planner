package dev.evestaticmapplanner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpDiscoveryLocatorTest {
    @Test
    fun `first maintenance creates schema one locator`() {
        val fixture = fixture("first-create")

        assertEquals(
            McpDiscoveryMaintenanceResult.Created,
            fixture.locator.maintain("0.6.0", fixture.executable),
        )

        val document = readDocument(fixture.locator.locatorPath)
        assertEquals(setOf("schemaVersion", "appVersion", "transport", "command"), document.keys)
        assertEquals(1, document.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("0.6.0", document.getValue("appVersion").jsonPrimitive.content)
        assertEquals("stdio", document.getValue("transport").jsonPrimitive.content)
        assertEquals(fixture.executable.toString(), document.getValue("command").jsonPrimitive.content)
    }

    @Test
    fun `identical locator is not rewritten`() {
        val fixture = fixture("unchanged")
        fixture.locator.maintain("0.6.0", fixture.executable)
        val sentinelTime = FileTime.fromMillis(946_684_800_000)
        Files.setLastModifiedTime(fixture.locator.locatorPath, sentinelTime)
        val locatorThatRejectsWrites = McpDiscoveryLocator(fixture.applicationRoot) { _, _ ->
            error("unchanged locator attempted a write")
        }

        assertEquals(
            McpDiscoveryMaintenanceResult.Unchanged,
            locatorThatRejectsWrites.maintain("0.6.0", fixture.executable),
        )
        assertEquals(sentinelTime, Files.getLastModifiedTime(fixture.locator.locatorPath))
    }

    @Test
    fun `app version change atomically updates the same file`() {
        val fixture = fixture("version-change")
        fixture.locator.maintain("0.6.0", fixture.executable)
        val locatorPath = fixture.locator.locatorPath

        assertEquals(
            McpDiscoveryMaintenanceResult.Updated,
            fixture.locator.maintain("0.6.1", fixture.executable),
        )
        assertEquals(locatorPath, fixture.locator.locatorPath)
        assertEquals("0.6.1", readDocument(locatorPath).getValue("appVersion").jsonPrimitive.content)
    }

    @Test
    fun `moved MCP command updates the same locator`() {
        val fixture = fixture("move-command")
        fixture.locator.maintain("0.6.0", fixture.executable)
        val moved = executable(fixture.root.resolve("Path B (Moved)"))

        assertEquals(
            McpDiscoveryMaintenanceResult.Updated,
            fixture.locator.maintain("0.6.0", moved),
        )
        assertEquals(moved.toString(), readDocument(fixture.locator.locatorPath).getValue("command").jsonPrimitive.content)
    }

    @Test
    fun `corrupt and invalid schema one JSON is rebuilt`() {
        listOf(
            "{not-json}",
            """{"schemaVersion":1,"appVersion":"0.6.0","transport":"http","command":"C:\\\\bad.exe"}""",
            """{"schemaVersion":1,"appVersion":"0.6.0","transport":"stdio","command":"relative.exe","extra":true}""",
        ).forEachIndexed { index, invalid ->
            val fixture = fixture("corrupt-$index")
            Files.createDirectories(fixture.locator.locatorPath.parent)
            Files.writeString(fixture.locator.locatorPath, invalid)

            assertEquals(
                McpDiscoveryMaintenanceResult.RebuiltInvalid,
                fixture.locator.maintain("0.6.0", fixture.executable),
            )
            assertEquals("stdio", readDocument(fixture.locator.locatorPath).getValue("transport").jsonPrimitive.content)
        }
    }

    @Test
    fun `missing executable removes stale supported locator and leaves no invalid publication`() {
        val fixture = fixture("missing-executable")
        fixture.locator.maintain("0.6.0", fixture.executable)
        Files.delete(fixture.executable)

        val result = assertIs<McpDiscoveryMaintenanceResult.MissingExecutable>(
            fixture.locator.maintain("0.6.0", fixture.executable),
        )

        assertTrue(result.staleLocatorRemoved)
        assertFalse(Files.exists(fixture.locator.locatorPath))
    }

    @Test
    fun `higher schema is preserved without overwrite or deletion`() {
        val fixture = fixture("future-schema")
        Files.createDirectories(fixture.locator.locatorPath.parent)
        val future = """{"schemaVersion":2,"futureTransport":{"kind":"stdio"}}"""
        Files.writeString(fixture.locator.locatorPath, future)

        assertEquals(
            McpDiscoveryMaintenanceResult.UnsupportedNewerSchema(2),
            fixture.locator.maintain("0.6.0", fixture.executable),
        )
        assertEquals(future, Files.readString(fixture.locator.locatorPath))

        Files.delete(fixture.executable)
        assertEquals(
            McpDiscoveryMaintenanceResult.UnsupportedNewerSchema(2),
            fixture.locator.maintain("0.6.0", fixture.executable),
        )
        assertEquals(future, Files.readString(fixture.locator.locatorPath))
    }

    @Test
    fun `unicode spaces parentheses and deep command path round trip through JSON`() {
        val root = createTempDirectory("定位器 空格 (QA)")
        val applicationRoot = root.resolve("本地数据").resolve("EVE Static Map Planner")
        val image = root.resolve("很深的目录").resolve("工具 (便携版)").resolve("EVE Static Map Planner")
        val command = executable(image)
        val locator = McpDiscoveryLocator(applicationRoot)

        locator.maintain("0.6.0", command)

        assertEquals(command.toString(), readDocument(locator.locatorPath).getValue("command").jsonPrimitive.content)
    }

    @Test
    fun `atomic writer keeps old complete target until replacement`() {
        val fixture = fixture("atomicity")
        fixture.locator.maintain("0.6.0", fixture.executable)
        val oldText = Files.readString(fixture.locator.locatorPath)
        var moveObserved = false
        val observingLocator = McpDiscoveryLocator(fixture.applicationRoot) { source, target ->
            assertEquals(oldText, Files.readString(target))
            val staged = Files.readString(source)
            assertEquals("0.6.1", (Json.parseToJsonElement(staged) as JsonObject).getValue("appVersion").jsonPrimitive.content)
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            moveObserved = true
        }

        assertEquals(
            McpDiscoveryMaintenanceResult.Updated,
            observingLocator.maintain("0.6.1", fixture.executable),
        )
        assertTrue(moveObserved)
    }

    @Test
    fun `jpackage application image resolution is absolute and independent of working directory`() {
        val image = createTempDirectory("app image with spaces").toAbsolutePath().normalize()
        val launcher = image.resolve("EVE Static Map Planner.exe")

        assertEquals(image, CurrentApplicationImage.resolve(launcher.toString()))
        assertEquals(null, CurrentApplicationImage.resolve(null))
    }

    @Test
    fun `startup publication failure is isolated and reported`() {
        val fixture = fixture("failure-isolation")
        val warnings = mutableListOf<String>()
        val launcher = fixture.executable.parent.resolve("EVE Static Map Planner.exe")
        Files.writeString(launcher, "launcher")

        val result = McpDiscoveryStartup.maintain(
            applicationRoot = fixture.applicationRoot,
            appVersion = "0.6.0",
            jpackageAppPath = launcher.toString(),
            locatorFactory = { root -> McpDiscoveryLocator(root) { _, _ -> error("simulated atomic move failure") } },
            infoSink = {},
            warningSink = { message, _ -> warnings += message },
        )

        assertIs<McpDiscoveryStartupResult.Failed>(result)
        assertTrue(warnings.single().contains("could not be updated"))
    }

    @Test
    fun `missing packaged MCP is a nonfatal startup result and publishes nothing`() {
        val root = createTempDirectory("mcp-locator-startup-missing-")
        val applicationRoot = root.resolve("Local AppData/EVE Static Map Planner")
        val image = root.resolve("Portable without MCP")
        Files.createDirectories(image)
        val launcher = image.resolve("EVE Static Map Planner.exe")
        Files.writeString(launcher, "launcher")
        val warnings = mutableListOf<String>()

        val startup = assertIs<McpDiscoveryStartupResult.Maintained>(
            McpDiscoveryStartup.maintain(
                applicationRoot = applicationRoot,
                appVersion = "0.6.0",
                jpackageAppPath = launcher.toAbsolutePath().toString(),
                infoSink = {},
                warningSink = { message, _ -> warnings += message },
            ),
        )

        assertEquals(McpDiscoveryMaintenanceResult.MissingExecutable(false), startup.result)
        assertFalse(Files.exists(applicationRoot.resolve("integration/mcp.json")))
        assertTrue(warnings.single().contains("executable missing"))
    }

    private fun fixture(name: String): Fixture {
        val root = createTempDirectory("mcp-locator-$name-")
        val applicationRoot = root.resolve("Local AppData").resolve("EVE Static Map Planner")
        val executable = executable(root.resolve("Portable Path A").resolve("EVE Static Map Planner"))
        return Fixture(root, applicationRoot, executable, McpDiscoveryLocator(applicationRoot))
    }

    private fun executable(image: Path): Path {
        Files.createDirectories(image)
        return image.resolve(McpDiscoveryContract.MCP_EXECUTABLE).toAbsolutePath().normalize().also {
            Files.writeString(it, "test executable")
        }
    }

    private fun readDocument(path: Path): JsonObject = Json.parseToJsonElement(Files.readString(path)) as JsonObject

    private data class Fixture(
        val root: Path,
        val applicationRoot: Path,
        val executable: Path,
        val locator: McpDiscoveryLocator,
    )
}
