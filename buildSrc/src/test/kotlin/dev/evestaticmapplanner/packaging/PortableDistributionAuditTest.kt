package dev.evestaticmapplanner.packaging

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PortableDistributionAuditTest {
    private val mcpJars = setOf("mcp-0.6.0.jar", "control-0.6.0.jar")

    @Test
    fun `archive name contains version and Windows architecture`() {
        assertEquals(
            "EVE-Static-Map-Planner-0.6.0-Windows-x64.zip",
            PortableDistributionAudit.archiveFileName("0.6.0"),
        )
    }

    @Test
    fun `valid portable distribution has one root and complete runtime`() {
        val zip = createPortableZip()
        try {
            val result = PortableDistributionAudit.audit(zip, "0.6.0", mcpJars)
            assertEquals(mcpJars, result.mcpJarNames)
            assertTrue(result.fileCount >= 10)
            assertTrue(result.uncompressedSize > 0)
            assertEquals(64, result.sha256.length)
        } finally {
            Files.deleteIfExists(zip)
        }
    }

    @Test
    fun `source and credential files are rejected`() {
        for (forbidden in listOf("src/Main.kt", "refresh-token.dpapi", "qa/session.json")) {
            val zip = createPortableZip(forbidden)
            try {
                assertFailsWith<IllegalArgumentException> {
                    PortableDistributionAudit.audit(zip, "0.6.0", mcpJars)
                }
            } finally {
                Files.deleteIfExists(zip)
            }
        }
    }

    private fun createPortableZip(extraPath: String? = null): Path {
        val zip = Files.createTempDirectory("portable-audit-")
            .resolve(PortableDistributionAudit.archiveFileName("0.6.0"))
        val root = "${PortableDistributionAudit.ROOT_DIRECTORY}/"
        val files = linkedMapOf(
            "${WindowsAppImageIntegration.MAIN_LAUNCHER}.exe" to "gui",
            "${WindowsAppImageIntegration.MCP_LAUNCHER}.exe" to "mcp",
            "${WindowsAppImageIntegration.STABLE_MCP_LAUNCHER}.exe" to "stable",
            "runtime/lib/modules" to "modules",
            "app/app-0.6.0-hash.jar" to "app",
            "app/feature-api-2.0.0-hash.jar" to "api",
            "app/skiko-awt-runtime-windows-x64-1.jar" to "skiko",
            "app/mcp/mcp-0.6.0.jar" to "mcp-main",
            "app/mcp/control-0.6.0.jar" to "control",
            "app/${WindowsAppImageIntegration.MAIN_LAUNCHER}.cfg" to "config",
        )
        extraPath?.let { files[it] = "forbidden" }
        ZipOutputStream(Files.newOutputStream(zip)).use { output ->
            for ((relativePath, content) in files) {
                output.putNextEntry(ZipEntry(root + relativePath))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return zip
    }
}
