package dev.evestaticmapplanner.packaging

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class WindowsAppImageAudit(
    val launcherNames: Set<String>,
    val runtimeDirectories: List<Path>,
    val runtimeModuleFiles: List<Path>,
    val runtimeModules: Set<String>,
    val mcpClasspath: List<String>,
)

object WindowsAppImageIntegration {
    const val MAIN_LAUNCHER = "EVE Static Map Planner"
    const val MAIN_CLASS = "dev.evestaticmapplanner.MainKt"
    const val MCP_LAUNCHER = "EVE Map MCP Bridge"
    const val STABLE_MCP_LAUNCHER = "eve-map-mcp"
    const val MCP_MAIN_CLASS = "dev.evestaticmapplanner.mcp.MainKt"
    const val WINDOWS_GUI_SUBSYSTEM = 2
    const val WINDOWS_CONSOLE_SUBSYSTEM = 3

    val requiredRuntimeModules = setOf(
        "java.base",
        "java.instrument",
        "java.logging",
        "java.net.http",
        "java.sql",
        "jdk.httpserver",
        "jdk.unsupported",
    )

    val forbiddenPackagedFileNames = setOf(
        "active-instance.json",
        "active.lock",
        "mcp.json",
        "session.key",
        "settings.properties",
        "static.db",
        "user.db",
    )

    fun mainJarFromComposeConfig(config: String): String {
        val classpath = launcherClasspath(config)
        val mainJar = classpath.firstOrNull { entry ->
            entry.substringAfterLast('\\').matches(Regex("app-[^-]+.*\\.jar", RegexOption.IGNORE_CASE))
        } ?: classpath.firstOrNull()
        return requireNotNull(mainJar) { "Compose launcher config has no classpath" }
            .substringAfterLast('\\')
    }

    fun launcherMainClass(config: String): String = config.lineSequence()
        .map(String::trim)
        .singleOrNull { it.startsWith("app.mainclass=") }
        ?.substringAfter('=')
        ?: error("Launcher config must contain exactly one app.mainclass")

    fun launcherClasspath(config: String): List<String> = config.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("app.classpath=") }
        .map { it.substringAfter('=') }
        .toList()

    fun mcpLauncherConfig(appVersion: String, mcpJarNames: Collection<String>): String {
        require(appVersion.isNotBlank())
        val sortedJars = mcpJarNames.distinct().sorted()
        require(sortedJars.isNotEmpty()) { "MCP production classpath is empty" }
        val mainJar = sortedJars.singleOrNull { it == "mcp-$appVersion.jar" }
            ?: error("MCP production classpath must contain exactly mcp-$appVersion.jar")
        return buildString {
            appendLine("[Application]")
            appendLine("app.classpath=\$APPDIR\\mcp\\$mainJar")
            appendLine("app.mainclass=$MCP_MAIN_CLASS")
            for (jar in sortedJars) {
                if (jar != mainJar) appendLine("app.classpath=\$APPDIR\\mcp\\$jar")
            }
            appendLine()
            appendLine("[JavaOptions]")
            appendLine("java-options=-Djpackage.app-version=$appVersion")
        }
    }

    fun peSubsystem(executable: Path): Int {
        require(Files.isRegularFile(executable)) { "Missing Windows launcher: $executable" }
        val bytes = Files.readAllBytes(executable)
        require(bytes.size >= 0x40 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte()) {
            "Not a PE executable: $executable"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val peOffset = buffer.getInt(0x3c)
        require(peOffset >= 0 && peOffset + 24 + 0x46 <= bytes.size) { "Malformed PE header: $executable" }
        require(bytes.copyOfRange(peOffset, peOffset + 4).contentEquals(byteArrayOf('P'.code.toByte(), 'E'.code.toByte(), 0, 0))) {
            "Missing PE signature: $executable"
        }
        return buffer.getShort(peOffset + 24 + 0x44).toInt() and 0xffff
    }

    fun parseJimageModules(output: String): Set<String> = output.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("Module: ") }
        .map { it.substringAfter("Module: ") }
        .toSortedSet()

    fun audit(
        image: Path,
        expectedMcpJarNames: Set<String>,
        jimageExecutable: Path,
    ): WindowsAppImageAudit {
        require(Files.isDirectory(image)) { "Missing integrated application image: $image" }
        val mainExe = image.resolve("$MAIN_LAUNCHER.exe")
        val mcpExe = image.resolve("$MCP_LAUNCHER.exe")
        val stableMcpExe = image.resolve("$STABLE_MCP_LAUNCHER.exe")
        val topLevelLaunchers = Files.list(image).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".exe", ignoreCase = true) }
                .map { it.fileName.toString().removeSuffix(".exe") }
                .toList()
                .toSet()
        }
        require(topLevelLaunchers == setOf(MAIN_LAUNCHER, MCP_LAUNCHER, STABLE_MCP_LAUNCHER)) {
            "Integrated image launcher drift: $topLevelLaunchers"
        }
        require(peSubsystem(mainExe) == WINDOWS_GUI_SUBSYSTEM) { "Main launcher is not a Windows GUI launcher" }
        require(peSubsystem(mcpExe) == WINDOWS_CONSOLE_SUBSYSTEM) { "MCP launcher is not a Windows console launcher" }
        require(peSubsystem(stableMcpExe) == WINDOWS_CONSOLE_SUBSYSTEM) {
            "Stable MCP launcher is not a Windows console launcher"
        }

        val appDirectory = image.resolve("app")
        val mainConfig = Files.readString(appDirectory.resolve("$MAIN_LAUNCHER.cfg"), StandardCharsets.UTF_8)
        val mcpConfig = Files.readString(appDirectory.resolve("$MCP_LAUNCHER.cfg"), StandardCharsets.UTF_8)
        val stableMcpConfig = Files.readString(
            appDirectory.resolve("$STABLE_MCP_LAUNCHER.cfg"),
            StandardCharsets.UTF_8,
        )
        require(launcherMainClass(mainConfig) == MAIN_CLASS) { "Main launcher class changed" }
        require(launcherMainClass(mcpConfig) == MCP_MAIN_CLASS) { "MCP launcher class changed" }
        require(stableMcpConfig == mcpConfig) { "Stable and compatibility MCP launcher configs differ" }
        require(launcherClasspath(mainConfig).none { it.contains("\\mcp\\", ignoreCase = true) }) {
            "Main launcher classpath unexpectedly contains MCP production jars"
        }
        val mcpClasspath = launcherClasspath(mcpConfig)
        require(mcpClasspath.all { it.startsWith("\$APPDIR\\mcp\\") }) {
            "MCP launcher classpath escaped its dedicated mcp directory: $mcpClasspath"
        }
        require(mcpClasspath.map { it.substringAfterLast('\\') }.toSet() == expectedMcpJarNames) {
            "MCP launcher classpath does not match the production runtime jars"
        }
        require(mcpClasspath.none { entry ->
            listOf("compose", "sqlite", "data-", "sde-", "ktor").any { entry.contains(it, ignoreCase = true) }
        }) { "MCP launcher classpath contains a forbidden desktop/data/network dependency: $mcpClasspath" }

        val allPaths = Files.walk(image).use { it.toList() }
        val forbidden = allPaths.filter { path ->
            Files.isRegularFile(path) && path.fileName.toString().lowercase() in forbiddenPackagedFileNames
        }
        require(forbidden.isEmpty()) { "Runtime/user data was packaged: $forbidden" }
        val runtimeDirectories = allPaths.filter {
            Files.isDirectory(it) && it.fileName.toString().equals("runtime", ignoreCase = true)
        }
        require(runtimeDirectories == listOf(image.resolve("runtime"))) {
            "Integrated image must contain one top-level runtime directory: $runtimeDirectories"
        }
        val runtimeModuleFiles = allPaths.filter {
            Files.isRegularFile(it) && it.fileName.toString() == "modules" &&
                it.parent?.fileName?.toString() == "lib"
        }
        require(runtimeModuleFiles == listOf(image.resolve("runtime/lib/modules"))) {
            "Integrated image contains more than one Java runtime module image: $runtimeModuleFiles"
        }
        require(Files.isRegularFile(jimageExecutable)) { "Missing jimage executable: $jimageExecutable" }
        val process = ProcessBuilder(jimageExecutable.toString(), "list", runtimeModuleFiles.single().toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        require(process.waitFor() == 0) { "jimage failed while auditing the shared runtime: $output" }
        val runtimeModules = parseJimageModules(output)
        require(runtimeModules.containsAll(requiredRuntimeModules)) {
            "Shared runtime is missing required modules: ${requiredRuntimeModules - runtimeModules}"
        }

        return WindowsAppImageAudit(
            launcherNames = topLevelLaunchers,
            runtimeDirectories = runtimeDirectories,
            runtimeModuleFiles = runtimeModuleFiles,
            runtimeModules = runtimeModules,
            mcpClasspath = mcpClasspath,
        )
    }
}
