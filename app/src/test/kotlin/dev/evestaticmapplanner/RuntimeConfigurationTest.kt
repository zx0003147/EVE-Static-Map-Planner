package dev.evestaticmapplanner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class RuntimeConfigurationTest {
    @Test
    fun `app depends on control transport and packaged runtime includes http server`() {
        val buildFile = Path.of("build.gradle.kts")
        assertTrue(Files.isRegularFile(buildFile))
        val build = Files.readString(buildFile)

        assertTrue(build.contains("implementation(project(\":control-transport\"))"))
        assertTrue(build.contains("\"jdk.httpserver\""))
    }
}
