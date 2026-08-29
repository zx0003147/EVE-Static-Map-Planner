package dev.evestaticmapplanner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal object McpDiscoveryContract {
    const val SCHEMA_VERSION = 1
    const val TRANSPORT = "stdio"
    const val MCP_EXECUTABLE = "eve-map-mcp.exe"
    const val INTEGRATION_DIRECTORY = "integration"
    const val LOCATOR_FILE = "mcp.json"
    const val JPACKAGE_APP_PATH_PROPERTY = "jpackage.app-path"
    const val MAX_LOCATOR_BYTES = 16 * 1024L
}

internal data class McpDiscoveryDocument(
    val schemaVersion: Int,
    val appVersion: String,
    val transport: String,
    val command: String,
)

internal sealed interface McpDiscoveryMaintenanceResult {
    data object Created : McpDiscoveryMaintenanceResult
    data object Updated : McpDiscoveryMaintenanceResult
    data object RebuiltInvalid : McpDiscoveryMaintenanceResult
    data object Unchanged : McpDiscoveryMaintenanceResult
    data class MissingExecutable(val staleLocatorRemoved: Boolean) : McpDiscoveryMaintenanceResult
    data class UnsupportedNewerSchema(val schemaVersion: Int) : McpDiscoveryMaintenanceResult
}

internal sealed interface McpDiscoveryStartupResult {
    data object NotPackaged : McpDiscoveryStartupResult
    data class Maintained(val result: McpDiscoveryMaintenanceResult) : McpDiscoveryStartupResult
    data class Failed(val cause: Throwable) : McpDiscoveryStartupResult
}

internal class McpDiscoveryLocator(
    applicationRoot: Path,
    private val atomicMove: (Path, Path) -> Unit = ::replaceAtomically,
) {
    private val integrationRoot = applicationRoot.toAbsolutePath().normalize()
        .resolve(McpDiscoveryContract.INTEGRATION_DIRECTORY)
    internal val locatorPath: Path = integrationRoot.resolve(McpDiscoveryContract.LOCATOR_FILE)

    fun maintain(appVersion: String, mcpExecutable: Path): McpDiscoveryMaintenanceResult {
        require(appVersion.isNotBlank()) { "MCP discovery app version must not be blank" }
        val command = mcpExecutable.toAbsolutePath().normalize()
        val existing = readExisting()

        if (!Files.isRegularFile(command, LinkOption.NOFOLLOW_LINKS)) {
            if (existing is ExistingLocator.NewerSchema) {
                return McpDiscoveryMaintenanceResult.UnsupportedNewerSchema(existing.schemaVersion)
            }
            val removed = when (existing) {
                ExistingLocator.Missing -> false
                else -> Files.deleteIfExists(locatorPath)
            }
            return McpDiscoveryMaintenanceResult.MissingExecutable(removed)
        }

        val desired = McpDiscoveryDocument(
            schemaVersion = McpDiscoveryContract.SCHEMA_VERSION,
            appVersion = appVersion,
            transport = McpDiscoveryContract.TRANSPORT,
            command = command.toString(),
        )
        return when (existing) {
            ExistingLocator.Missing -> {
                write(desired)
                McpDiscoveryMaintenanceResult.Created
            }
            ExistingLocator.Invalid -> {
                write(desired)
                McpDiscoveryMaintenanceResult.RebuiltInvalid
            }
            is ExistingLocator.Current -> if (existing.document == desired) {
                McpDiscoveryMaintenanceResult.Unchanged
            } else {
                write(desired)
                McpDiscoveryMaintenanceResult.Updated
            }
            is ExistingLocator.NewerSchema ->
                McpDiscoveryMaintenanceResult.UnsupportedNewerSchema(existing.schemaVersion)
        }
    }

    private fun readExisting(): ExistingLocator {
        if (!Files.exists(locatorPath, LinkOption.NOFOLLOW_LINKS)) return ExistingLocator.Missing
        require(Files.isRegularFile(locatorPath, LinkOption.NOFOLLOW_LINKS)) {
            "MCP discovery locator is not a regular file"
        }
        if (Files.size(locatorPath) !in 1..McpDiscoveryContract.MAX_LOCATOR_BYTES) {
            return ExistingLocator.Invalid
        }
        val root = runCatching {
            Json.parseToJsonElement(Files.readString(locatorPath, Charsets.UTF_8)) as? JsonObject
        }.getOrNull() ?: return ExistingLocator.Invalid
        val schemaVersion = runCatching { root["schemaVersion"]?.jsonPrimitive?.intOrNull }.getOrNull()
            ?: return ExistingLocator.Invalid
        if (schemaVersion > McpDiscoveryContract.SCHEMA_VERSION) {
            return ExistingLocator.NewerSchema(schemaVersion)
        }
        if (schemaVersion != McpDiscoveryContract.SCHEMA_VERSION || root.keys != CONTRACT_FIELDS) {
            return ExistingLocator.Invalid
        }
        val document = runCatching {
            McpDiscoveryDocument(
                schemaVersion = schemaVersion,
                appVersion = root.getValue("appVersion").jsonPrimitive.contentOrNull.orEmpty(),
                transport = root.getValue("transport").jsonPrimitive.contentOrNull.orEmpty(),
                command = root.getValue("command").jsonPrimitive.contentOrNull.orEmpty(),
            )
        }.getOrNull() ?: return ExistingLocator.Invalid
        val commandPath = runCatching { Path.of(document.command) }.getOrNull()
        if (
            document.appVersion.isBlank() ||
            document.transport != McpDiscoveryContract.TRANSPORT ||
            commandPath == null ||
            !commandPath.isAbsolute
        ) {
            return ExistingLocator.Invalid
        }
        return ExistingLocator.Current(document.copy(command = commandPath.normalize().toString()))
    }

    private fun write(document: McpDiscoveryDocument) {
        Files.createDirectories(integrationRoot)
        require(Files.isDirectory(integrationRoot, LinkOption.NOFOLLOW_LINKS)) {
            "MCP discovery integration path is not a directory"
        }
        if (Files.exists(locatorPath, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(locatorPath, LinkOption.NOFOLLOW_LINKS)) {
                "MCP discovery locator is not a regular file"
            }
        }
        val bytes = (buildJsonObject {
            put("schemaVersion", document.schemaVersion)
            put("appVersion", document.appVersion)
            put("transport", document.transport)
            put("command", document.command)
        }.toString() + System.lineSeparator()).toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= McpDiscoveryContract.MAX_LOCATOR_BYTES)

        val temporary = createTemporarySibling()
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
            ).use { output ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) output.write(buffer)
                output.force(true)
            }
            check(Files.size(temporary) == bytes.size.toLong()) { "MCP discovery locator write was incomplete" }
            atomicMove(temporary, locatorPath)
            check(Files.isRegularFile(locatorPath, LinkOption.NOFOLLOW_LINKS)) {
                "MCP discovery locator publication did not create a regular file"
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun createTemporarySibling(): Path {
        val pid = ProcessHandle.current().pid()
        repeat(10) { attempt ->
            val candidate = integrationRoot.resolve("mcp-$pid-${System.nanoTime()}-$attempt.tmp")
            try {
                return Files.createFile(candidate)
            } catch (_: FileAlreadyExistsException) {
                // Try another process-local unique name without requiring SecureRandom during early startup.
            }
        }
        error("Could not allocate an MCP discovery locator temporary sibling")
    }

    private sealed interface ExistingLocator {
        data object Missing : ExistingLocator
        data object Invalid : ExistingLocator
        data class Current(val document: McpDiscoveryDocument) : ExistingLocator
        data class NewerSchema(val schemaVersion: Int) : ExistingLocator
    }

    private companion object {
        val CONTRACT_FIELDS = setOf("schemaVersion", "appVersion", "transport", "command")
    }
}

internal object CurrentApplicationImage {
    fun resolve(jpackageAppPath: String?): Path? {
        val value = jpackageAppPath?.takeIf(String::isNotBlank) ?: return null
        val launcher = Path.of(value)
        require(launcher.isAbsolute) { "jpackage.app-path must be absolute" }
        return requireNotNull(launcher.normalize().parent) { "jpackage.app-path has no parent directory" }
    }
}

internal object McpDiscoveryStartup {
    fun maintain(
        applicationRoot: Path = ApplicationDirectories.root(),
        appVersion: String = ApplicationBuildInfo.current.appVersion,
        jpackageAppPath: String? = System.getProperty(McpDiscoveryContract.JPACKAGE_APP_PATH_PROPERTY),
        locatorFactory: (Path) -> McpDiscoveryLocator = ::McpDiscoveryLocator,
        infoSink: (String) -> Unit = AppDiagnostics::info,
        warningSink: (String, Throwable?) -> Unit = AppDiagnostics::warning,
    ): McpDiscoveryStartupResult {
        val applicationImage = try {
            CurrentApplicationImage.resolve(jpackageAppPath)
        } catch (failure: Throwable) {
            warningSink("MCP discovery locator could not determine the packaged application image", failure)
            return McpDiscoveryStartupResult.Failed(failure)
        } ?: return McpDiscoveryStartupResult.NotPackaged

        return try {
            val result = locatorFactory(applicationRoot).maintain(
                appVersion = appVersion,
                mcpExecutable = applicationImage.resolve(McpDiscoveryContract.MCP_EXECUTABLE),
            )
            when (result) {
                McpDiscoveryMaintenanceResult.Created -> infoSink("MCP discovery locator created")
                McpDiscoveryMaintenanceResult.Updated -> infoSink("MCP discovery locator updated")
                McpDiscoveryMaintenanceResult.RebuiltInvalid ->
                    warningSink("MCP discovery locator rebuilt after invalid existing content", null)
                McpDiscoveryMaintenanceResult.Unchanged -> Unit
                is McpDiscoveryMaintenanceResult.MissingExecutable ->
                    warningSink("MCP discovery unavailable: MCP executable missing", null)
                is McpDiscoveryMaintenanceResult.UnsupportedNewerSchema -> warningSink(
                    "MCP discovery locator schema ${result.schemaVersion} is newer than supported schema " +
                        McpDiscoveryContract.SCHEMA_VERSION + "; existing locator preserved",
                    null,
                )
            }
            McpDiscoveryStartupResult.Maintained(result)
        } catch (failure: Throwable) {
            warningSink("MCP discovery locator could not be updated", failure)
            McpDiscoveryStartupResult.Failed(failure)
        }
    }
}

private fun replaceAtomically(source: Path, target: Path) {
    try {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (failure: AtomicMoveNotSupportedException) {
        throw IllegalStateException("Atomic MCP discovery locator replacement is unavailable", failure)
    }
}
