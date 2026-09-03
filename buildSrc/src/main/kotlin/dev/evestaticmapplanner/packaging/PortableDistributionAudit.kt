package dev.evestaticmapplanner.packaging

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

data class PortableDistributionAuditResult(
    val fileCount: Int,
    val uncompressedSize: Long,
    val sha256: String,
    val mcpJarNames: Set<String>,
)

object PortableDistributionAudit {
    const val ARCHIVE_PREFIX = "EVE-Static-Map-Planner-"
    const val ARCHIVE_SUFFIX = "-Windows-x64.zip"
    const val ROOT_DIRECTORY = "EVE Static Map Planner"

    private val forbiddenExactFileNames = WindowsAppImageIntegration.forbiddenPackagedFileNames + setOf(
        "refresh-token.dpapi",
        "session.json",
        "character-location.json",
    )
    private val forbiddenPathSegments = setOf(".git", ".gradle", ".sde-work", "src", "qa")
    private val forbiddenSourceSuffixes = setOf(".kt", ".java", ".kts")

    fun archiveFileName(appVersion: String): String {
        require(appVersion.matches(Regex("\\d+\\.\\d+\\.\\d+"))) { "Invalid release version: $appVersion" }
        return "$ARCHIVE_PREFIX$appVersion$ARCHIVE_SUFFIX"
    }

    fun audit(
        zip: Path,
        appVersion: String,
        expectedFeatureApiArtifactVersion: String,
        expectedMcpJarNames: Set<String>,
    ): PortableDistributionAuditResult {
        require(Files.isRegularFile(zip)) { "Missing Portable ZIP: $zip" }
        require(zip.fileName.toString() == archiveFileName(appVersion)) {
            "Portable ZIP name drift: ${zip.fileName}"
        }
        require(expectedMcpJarNames.isNotEmpty()) { "Expected MCP JAR set is empty" }
        require(expectedFeatureApiArtifactVersion.matches(Regex("\\d+\\.\\d+\\.\\d+"))) {
            "Invalid Feature API artifact version: $expectedFeatureApiArtifactVersion"
        }

        val rootPrefix = "$ROOT_DIRECTORY/"
        val entries = ZipFile(zip.toFile()).use { archive ->
            archive.entries().asSequence().filterNot { it.isDirectory }.map { entry ->
                entry.name.replace('\\', '/') to entry.size
            }.toList()
        }
        require(entries.isNotEmpty()) { "Portable ZIP is empty" }
        val paths = entries.map { it.first }
        require(paths.all { it.startsWith(rootPrefix) }) { "Portable ZIP contains files outside $ROOT_DIRECTORY" }
        require(paths.map { it.substringBefore('/') }.toSet() == setOf(ROOT_DIRECTORY)) {
            "Portable ZIP must contain exactly one root directory"
        }

        fun count(relativePath: String): Int = paths.count { it == "$rootPrefix$relativePath" }
        require(count("${WindowsAppImageIntegration.MAIN_LAUNCHER}.exe") == 1) {
            "Portable ZIP must contain exactly one main GUI launcher"
        }
        require(count("${WindowsAppImageIntegration.MCP_LAUNCHER}.exe") == 1)
        require(count("${WindowsAppImageIntegration.STABLE_MCP_LAUNCHER}.exe") == 1)
        require(count("runtime/lib/modules") == 1) { "Portable ZIP is missing runtime/lib/modules" }
        require(entries.single { it.first == "${rootPrefix}runtime/lib/modules" }.second > 0L) {
            "Portable runtime/lib/modules is empty"
        }
        require(paths.count { it.matches(Regex("${Regex.escape(rootPrefix)}app/app-${Regex.escape(appVersion)}-.+\\.jar")) } == 1) {
            "Portable ZIP must contain exactly one versioned main application JAR"
        }
        require(paths.count {
            it.matches(
                Regex(
                    "${Regex.escape(rootPrefix)}app/feature-api-" +
                        "${Regex.escape(expectedFeatureApiArtifactVersion)}-.+\\.jar",
                ),
            )
        } == 1) {
            "Portable ZIP must contain the Feature API $expectedFeatureApiArtifactVersion Host JAR"
        }
        require(paths.any { it.startsWith("${rootPrefix}app/skiko-awt-runtime-windows-x64-") && it.endsWith(".jar") }) {
            "Portable ZIP is missing the Skiko Windows x64 runtime"
        }

        val mcpJarNames = paths.asSequence()
            .filter { it.startsWith("${rootPrefix}app/mcp/") && it.endsWith(".jar", ignoreCase = true) }
            .map { it.substringAfterLast('/') }
            .toSet()
        require(mcpJarNames == expectedMcpJarNames) {
            "Portable MCP JAR set drift: missing=${expectedMcpJarNames - mcpJarNames}, extra=${mcpJarNames - expectedMcpJarNames}"
        }

        val forbiddenPaths = paths.filter { path ->
            val relative = path.removePrefix(rootPrefix)
            val segments = relative.split('/')
            val fileName = segments.last().lowercase()
            segments.any { it.lowercase() in forbiddenPathSegments } ||
                forbiddenSourceSuffixes.any(fileName::endsWith) ||
                fileName in forbiddenExactFileNames ||
                fileName in setOf("build.gradle", "gradlew", "gradlew.bat") ||
                fileName.contains("authorization-code", ignoreCase = true) ||
                fileName.contains("oauth-state", ignoreCase = true)
        }
        require(forbiddenPaths.isEmpty()) { "Portable ZIP contains source, build, user, or QA data: $forbiddenPaths" }
        require(paths.none { it.endsWith("/pack.jar", ignoreCase = true) }) {
            "External Feature Packs must not be bundled in the main Portable ZIP"
        }

        return PortableDistributionAuditResult(
            fileCount = entries.size,
            uncompressedSize = entries.sumOf { (_, size) -> size.coerceAtLeast(0L) },
            sha256 = sha256(zip),
            mcpJarNames = mcpJarNames,
        )
    }

    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
