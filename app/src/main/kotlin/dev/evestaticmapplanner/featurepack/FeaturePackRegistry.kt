package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.FeatureApiVersion
import dev.evestaticmapplanner.feature.api.FeatureApiVersions
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.PackVersion
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.jar.JarFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

enum class FeaturePackInstallationState {
    INSTALLED,
    INCOMPATIBLE,
    MISSING_JAR,
    INVALID_PACK,
}

data class RegisteredFeaturePack(
    val packId: PackId,
    val displayName: String,
    val version: PackVersion?,
    val publisher: String?,
    val requiredFeatureApiVersion: FeatureApiVersion?,
    val path: Path,
    val jar: Path,
    val enabled: Boolean,
    val installationState: FeaturePackInstallationState,
    val lastError: String?,
)

data class FeaturePackRegistrySnapshot(
    val packs: List<RegisteredFeaturePack>,
    val failures: List<FeaturePackFailure>,
)

data class StoredFeaturePackState(
    val enabled: Boolean = false,
    val lastError: String? = null,
)

interface FeaturePackManagerStateStore {
    fun load(): Map<PackId, StoredFeaturePackState>

    fun save(states: Map<PackId, StoredFeaturePackState>)
}

class PropertiesFeaturePackManagerStateStore(
    val path: Path,
    private val warningSink: (String, Throwable?) -> Unit = { _, _ -> },
) : FeaturePackManagerStateStore {
    override fun load(): Map<PackId, StoredFeaturePackState> {
        if (!path.isRegularFile()) return emptyMap()
        val properties = try {
            Properties().also { values -> Files.newInputStream(path).use(values::load) }
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            warningSink("Feature Pack manager state could not be read", error)
            return emptyMap()
        }
        if (properties.getProperty(VERSION_KEY) != STORAGE_VERSION) {
            warningSink("Feature Pack manager state has an unsupported version", null)
            return emptyMap()
        }
        return properties.stringPropertyNames()
            .asSequence()
            .filter { it.startsWith(PACK_PREFIX) && it.endsWith(ENABLED_SUFFIX) }
            .mapNotNull { key ->
                val rawId = key.removePrefix(PACK_PREFIX).removeSuffix(ENABLED_SUFFIX)
                val packId = runCatching { PackId(rawId) }.getOrNull() ?: return@mapNotNull null
                val enabled = properties.getProperty(key).toBooleanStrictOrNull() ?: false
                val lastError = properties.getProperty("$PACK_PREFIX$rawId$ERROR_SUFFIX")?.takeIf(String::isNotBlank)
                packId to StoredFeaturePackState(enabled, lastError)
            }
            .toMap()
    }

    @Synchronized
    override fun save(states: Map<PackId, StoredFeaturePackState>) {
        if (states.isEmpty()) {
            Files.deleteIfExists(path)
            return
        }
        val normalizedPath = path.toAbsolutePath().normalize()
        val parent = requireNotNull(normalizedPath.parent) { "Feature Pack state path requires a parent: $path" }
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "feature-pack-manager-", ".tmp")
        try {
            val properties = Properties().apply {
                setProperty(VERSION_KEY, STORAGE_VERSION)
                states.toSortedMap(compareBy(PackId::value)).forEach { (packId, state) ->
                    setProperty("$PACK_PREFIX${packId.value}$ENABLED_SUFFIX", state.enabled.toString())
                    state.lastError?.takeIf(String::isNotBlank)?.let {
                        setProperty("$PACK_PREFIX${packId.value}$ERROR_SUFFIX", it)
                    }
                }
            }
            Files.newOutputStream(temporary).use { properties.store(it, "EVE Static Map Planner Feature Packs") }
            try {
                Files.move(temporary, normalizedPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, normalizedPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val STORAGE_VERSION = "1"
        const val VERSION_KEY = "state.version"
        const val PACK_PREFIX = "pack."
        const val ENABLED_SUFFIX = ".enabled"
        const val ERROR_SUFFIX = ".lastError"
    }
}

interface FeaturePackRegistry {
    fun discover(): FeaturePackRegistrySnapshot

    fun updateState(packId: PackId, state: StoredFeaturePackState)

    fun forget(packId: PackId)
}

class LocalFeaturePackRegistry(
    private val packRoot: Path,
    private val stateStore: FeaturePackManagerStateStore,
) : FeaturePackRegistry {
    override fun discover(): FeaturePackRegistrySnapshot {
        val normalizedRoot = packRoot.toAbsolutePath().normalize()
        if (!Files.exists(normalizedRoot)) return FeaturePackRegistrySnapshot(emptyList(), emptyList())
        if (!normalizedRoot.isDirectory()) {
            return FeaturePackRegistrySnapshot(
                emptyList(),
                listOf(FeaturePackFailure(
                    FeaturePackFailureKind.INVALID_DISCOVERY_PATH,
                    "Feature Pack root is not a directory: $normalizedRoot",
                )),
            )
        }
        val stored = stateStore.load()
        val packs = mutableListOf<RegisteredFeaturePack>()
        val failures = mutableListOf<FeaturePackFailure>()
        try {
            Files.list(normalizedRoot).use { children ->
                children.filter(Path::isDirectory).sorted().forEach { directory ->
                    val directoryName = directory.fileName.toString()
                    val packId = runCatching { PackId(directoryName) }.getOrElse { error ->
                        failures += FeaturePackFailure(
                            FeaturePackFailureKind.INVALID_PACK_DIRECTORY,
                            "Feature Pack directory name is not a valid Pack ID: $directory",
                            error,
                        )
                        return@forEach
                    }
                    val jar = directory.resolve(PACK_JAR_NAME)
                    val saved = stored[packId] ?: StoredFeaturePackState()
                    if (!jar.isRegularFile()) {
                        val message = "Feature Pack directory does not contain $PACK_JAR_NAME: $directory"
                        packs += fallback(packId, directory, jar, saved, FeaturePackInstallationState.MISSING_JAR, message)
                        failures += FeaturePackFailure(FeaturePackFailureKind.MISSING_JAR, message)
                        return@forEach
                    }
                    try {
                        val metadata = FeaturePackJarManifest.read(jar)
                        require(metadata.packId == packId) {
                            "Manifest Pack ID ${metadata.packId.value} does not match directory $directoryName"
                        }
                        val incompatibility = FeaturePackCompatibilityPolicy.incompatibilityMessage(
                            metadata.requiredFeatureApiVersion,
                        )
                        packs += RegisteredFeaturePack(
                            packId = packId,
                            displayName = metadata.displayName,
                            version = metadata.version,
                            publisher = metadata.publisher,
                            requiredFeatureApiVersion = metadata.requiredFeatureApiVersion,
                            path = directory,
                            jar = jar,
                            enabled = saved.enabled,
                            installationState = if (incompatibility == null) {
                                FeaturePackInstallationState.INSTALLED
                            } else {
                                FeaturePackInstallationState.INCOMPATIBLE
                            },
                            lastError = incompatibility ?: saved.lastError,
                        )
                        if (incompatibility != null) {
                            failures += FeaturePackFailure(
                                FeaturePackFailureKind.INCOMPATIBLE_FEATURE_API,
                                incompatibility,
                            )
                        }
                    } catch (error: Throwable) {
                        rethrowIfFatal(error)
                        val message = "Feature Pack metadata is invalid for $jar: ${error.message ?: error::class.simpleName}"
                        packs += fallback(packId, directory, jar, saved, FeaturePackInstallationState.INVALID_PACK, message)
                        failures += FeaturePackFailure(FeaturePackFailureKind.INVALID_DESCRIPTOR, message, error)
                    }
                }
            }
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            failures += FeaturePackFailure(
                FeaturePackFailureKind.DISCOVERY_FAILED,
                "Could not inspect Feature Pack root: $normalizedRoot",
                error,
            )
        }
        return FeaturePackRegistrySnapshot(packs.sortedBy { it.displayName.lowercase() }, failures)
    }

    override fun updateState(packId: PackId, state: StoredFeaturePackState) {
        stateStore.save(stateStore.load().toMutableMap().apply { put(packId, state) })
    }

    override fun forget(packId: PackId) {
        stateStore.save(stateStore.load().toMutableMap().apply { remove(packId) })
    }

    private fun fallback(
        packId: PackId,
        directory: Path,
        jar: Path,
        saved: StoredFeaturePackState,
        installationState: FeaturePackInstallationState,
        error: String,
    ) = RegisteredFeaturePack(
        packId = packId,
        displayName = packId.value,
        version = null,
        publisher = null,
        requiredFeatureApiVersion = null,
        path = directory,
        jar = jar,
        enabled = saved.enabled,
        installationState = installationState,
        lastError = error,
    )
}

data class FeaturePackManifestMetadata(
    val packId: PackId,
    val displayName: String,
    val version: PackVersion,
    val publisher: String,
    val requiredFeatureApiVersion: FeatureApiVersion,
)

object FeaturePackJarManifest {
    const val PACK_ID = "EVE-Feature-Pack-Id"
    const val DISPLAY_NAME = "EVE-Feature-Pack-Name"
    const val VERSION = "EVE-Feature-Pack-Version"
    const val PUBLISHER = "EVE-Feature-Pack-Publisher"
    const val FEATURE_API_VERSION = "EVE-Feature-API-Version"

    fun read(jar: Path): FeaturePackManifestMetadata = JarFile(jar.toFile()).use { file ->
        val attributes = requireNotNull(file.manifest) { "JAR manifest is missing" }.mainAttributes
        FeaturePackManifestMetadata(
            packId = PackId(requireAttribute(attributes.getValue(PACK_ID), PACK_ID)),
            displayName = validatedLabel(requireAttribute(attributes.getValue(DISPLAY_NAME), DISPLAY_NAME), DISPLAY_NAME),
            version = PackVersion(requireAttribute(attributes.getValue(VERSION), VERSION)),
            publisher = validatedLabel(requireAttribute(attributes.getValue(PUBLISHER), PUBLISHER), PUBLISHER),
            requiredFeatureApiVersion = parseFeatureApiVersion(
                requireAttribute(attributes.getValue(FEATURE_API_VERSION), FEATURE_API_VERSION),
            ),
        )
    }

    private fun requireAttribute(value: String?, name: String): String =
        requireNotNull(value) { "Manifest attribute $name is missing" }

    private fun validatedLabel(value: String, name: String): String {
        require(value.isNotBlank() && value == value.trim() && value.length <= 100 && value.none(Char::isISOControl)) {
            "Manifest attribute $name is invalid"
        }
        return value
    }

    private fun parseFeatureApiVersion(value: String): FeatureApiVersion {
        require(POSITIVE_DECIMAL_INTEGER.matches(value)) {
            "Manifest attribute $FEATURE_API_VERSION must be a canonical positive decimal integer"
        }
        val parsed = requireNotNull(value.toIntOrNull()) {
            "Manifest attribute $FEATURE_API_VERSION is outside the supported integer range"
        }
        return FeatureApiVersion(parsed.toString(), FeatureApiVersions.current().frozen)
    }

    private val POSITIVE_DECIMAL_INTEGER = Regex("[1-9][0-9]*")
}

internal object FeaturePackCompatibilityPolicy {
    fun incompatibilityMessage(required: FeatureApiVersion): String? {
        val provided = FeatureApiVersions.current()
        return if (required == provided) {
            null
        } else {
            "Feature Pack requires Feature API ${required.identifier}. " +
                "This version of EVE Static Map Planner provides Feature API ${provided.identifier}."
        }
    }
}

private const val PACK_JAR_NAME = "pack.jar"
