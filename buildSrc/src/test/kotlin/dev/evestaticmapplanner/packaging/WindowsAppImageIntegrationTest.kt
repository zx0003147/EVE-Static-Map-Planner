package dev.evestaticmapplanner.packaging

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsAppImageIntegrationTest {
    @Test
    fun `MCP config has an isolated deterministic production classpath`() {
        val config = WindowsAppImageIntegration.mcpLauncherConfig(
            appVersion = "0.2.0",
            mcpJarNames = listOf("slf4j-nop.jar", "mcp-0.2.0.jar", "control-transport-0.2.0.jar"),
        )

        assertEquals(WindowsAppImageIntegration.MCP_MAIN_CLASS, WindowsAppImageIntegration.launcherMainClass(config))
        assertEquals(
            listOf(
                "\$APPDIR\\mcp\\mcp-0.2.0.jar",
                "\$APPDIR\\mcp\\control-transport-0.2.0.jar",
                "\$APPDIR\\mcp\\slf4j-nop.jar",
            ),
            WindowsAppImageIntegration.launcherClasspath(config),
        )
        assertFalse(config.contains("compose", ignoreCase = true))
        assertFalse(config.contains("sqlite", ignoreCase = true))
    }

    @Test
    fun `MCP config requires the versioned main jar`() {
        assertFailsWith<IllegalStateException> {
            WindowsAppImageIntegration.mcpLauncherConfig("0.2.0", listOf("mcp-0.1.2.jar"))
        }
    }

    @Test
    fun `Compose main jar is read from the launcher config`() {
        val config = """
            [Application]
            app.classpath=${'$'}APPDIR\app-0.2.0-a1b2c3.jar
            app.mainclass=dev.evestaticmapplanner.MainKt
            app.classpath=${'$'}APPDIR\compose-desktop.jar
        """.trimIndent()

        assertEquals("app-0.2.0-a1b2c3.jar", WindowsAppImageIntegration.mainJarFromComposeConfig(config))
    }

    @Test
    fun `jimage output is reduced to module names`() {
        val modules = WindowsAppImageIntegration.parseJimageModules(
            """
                jimage: runtime/lib/modules
                Module: java.base
                    java/lang/Object.class
                Module: java.net.http
                    java/net/http/HttpClient.class
                Module: jdk.httpserver
                    com/sun/net/httpserver/HttpServer.class
            """.trimIndent(),
        )

        assertEquals(setOf("java.base", "java.net.http", "jdk.httpserver"), modules)
    }

    @Test
    fun `PE subsystem reader distinguishes GUI and console launchers`() {
        val gui = Files.createTempFile("gui-launcher-", ".exe")
        val console = Files.createTempFile("console-launcher-", ".exe")
        try {
            writePeStub(gui, WindowsAppImageIntegration.WINDOWS_GUI_SUBSYSTEM)
            writePeStub(console, WindowsAppImageIntegration.WINDOWS_CONSOLE_SUBSYSTEM)

            assertEquals(2, WindowsAppImageIntegration.peSubsystem(gui))
            assertEquals(3, WindowsAppImageIntegration.peSubsystem(console))
        } finally {
            Files.deleteIfExists(gui)
            Files.deleteIfExists(console)
        }
    }

    @Test
    fun `forbidden package names include AI secrets and persistent state`() {
        val forbidden = WindowsAppImageIntegration.forbiddenPackagedFileNames
        assertTrue(forbidden.containsAll(setOf(
            "active-instance.json",
            "active.lock",
            "session.key",
            "settings.properties",
            "static.db",
            "user.db",
        )))
    }

    @Test
    fun `PATH component GUID is stable in the installer namespace`() {
        val namespace = java.util.UUID.fromString("502B9850-A5B0-4922-BB20-AC7FEBA590DC")
        assertEquals(
            WindowsAppImageIntegration.PATH_COMPONENT_GUID,
            JpackageComponentGuidNamespace.formatMsiGuid(
                JpackageComponentGuidNamespace.uuidV5(namespace, "installer-component-v1:per-user-path"),
            ),
        )
    }

    private fun writePeStub(path: java.nio.file.Path, subsystem: Int) {
        val bytes = ByteArray(512)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val peOffset = 0x80
        buffer.putInt(0x3c, peOffset)
        bytes[peOffset] = 'P'.code.toByte()
        bytes[peOffset + 1] = 'E'.code.toByte()
        buffer.putShort(peOffset + 24, 0x20b.toShort())
        buffer.putShort(peOffset + 24 + 0x44, subsystem.toShort())
        Files.write(path, bytes)
    }
}
