package dev.evestaticmapplanner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class RuntimeConfigurationTest {
    @Test
    fun `shared packaged runtime includes desktop and MCP network modules`() {
        val buildFile = Path.of("build.gradle.kts")
        assertTrue(Files.isRegularFile(buildFile))
        val build = Files.readString(buildFile)

        assertTrue(build.contains("implementation(project(\":control-transport\"))"))
        assertTrue(build.contains("\"java.net.http\""))
        assertTrue(build.contains("\"jdk.httpserver\""))
        assertTrue(build.contains("createIntegratedDistributable"))
        assertTrue(build.contains("WindowsAppImageIntegration.audit"))
        assertTrue(build.contains("\":mcp:installedImageTest\""))
    }
}
