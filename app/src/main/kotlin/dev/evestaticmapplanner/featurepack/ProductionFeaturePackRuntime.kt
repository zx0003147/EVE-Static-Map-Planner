package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.AppDiagnostics
import dev.evestaticmapplanner.ApplicationBuildInfo
import dev.evestaticmapplanner.ApplicationDirectories
import dev.evestaticmapplanner.feature.api.CoreVersion
import dev.evestaticmapplanner.feature.api.FeatureApiVersions
import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.FeaturePackDescriptor
import dev.evestaticmapplanner.feature.api.FeaturePackHostInfo
import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.FeaturePackLogger
import dev.evestaticmapplanner.feature.api.HostPlatform
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.PackRelativePath
import dev.evestaticmapplanner.feature.api.PackStorage
import dev.evestaticmapplanner.feature.api.OverlayRegistry
import dev.evestaticmapplanner.feature.api.SystemInfoRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

object ProductionFeaturePackDirectories {
    const val DIRECTORY_NAME = "feature-packs"

    fun resolve(
        environment: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name"),
        userHome: Path = Path.of(System.getProperty("user.home")),
    ): Path = ApplicationDirectories.root(environment, osName, userHome)
        .resolve(DIRECTORY_NAME)
        .toAbsolutePath()
        .normalize()
}

data class ProductionFeaturePackStartReport(
    val packRoot: Path,
    val candidates: List<LocalFeaturePackCandidate>,
    val loadedPackIds: List<PackId>,
    val failures: List<FeaturePackFailure>,
)

data class ProductionFeaturePackCloseReport(
    val failures: List<FeaturePackFailure>,
)

/** Application-owned production coordinator around the FP-2A isolated host. */
class ProductionFeaturePackRuntime private constructor(
    val startReport: ProductionFeaturePackStartReport,
    val manager: FeaturePackManager,
    val overlayHost: FeatureOverlayHost,
    val systemInfoHost: SystemInfoHost,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun closeSafely(): ProductionFeaturePackCloseReport {
        if (!closed.compareAndSet(false, true)) return ProductionFeaturePackCloseReport(emptyList())
        val failures = manager.closeSafely()
        return ProductionFeaturePackCloseReport(failures)
    }

    override fun close() {
        closeSafely()
    }

    companion object {
        fun start(
            packRoot: Path = ProductionFeaturePackDirectories.resolve(),
            applicationRoot: Path = packRoot.toAbsolutePath().normalize().parent,
            eventSink: (String) -> Unit = {},
            host: LocalFeaturePackHost = LocalFeaturePackHost(),
        ): ProductionFeaturePackRuntime {
            val normalizedRoot = packRoot.toAbsolutePath().normalize()
            val normalizedApplicationRoot = applicationRoot.toAbsolutePath().normalize()
            val overlayHost = FeatureOverlayHost()
            val systemInfoHost = SystemInfoHost { providerId, error ->
                AppDiagnostics.warning("System Info provider failed: $providerId", error)
            }
            val stateStore = PropertiesFeaturePackManagerStateStore(
                normalizedApplicationRoot.resolve("feature-pack-manager.properties"),
                AppDiagnostics::warning,
            )
            val registry = LocalFeaturePackRegistry(normalizedRoot, stateStore)
            val manager = FeaturePackManager(
                packRoot = normalizedRoot,
                registry = registry,
                contextFactory = productionContextFactory(
                    normalizedApplicationRoot,
                    eventSink,
                    overlayHost,
                    systemInfoHost,
                ),
                host = host,
            )
            if (!Files.exists(normalizedRoot) || stateStore.load().values.none(StoredFeaturePackState::enabled)) {
                return ProductionFeaturePackRuntime(
                    ProductionFeaturePackStartReport(normalizedRoot, emptyList(), emptyList(), emptyList()),
                    manager,
                    overlayHost,
                    systemInfoHost,
                )
            }

            val startup = manager.startEnabledPacks()
            return ProductionFeaturePackRuntime(
                ProductionFeaturePackStartReport(
                    packRoot = normalizedRoot,
                    candidates = startup.snapshot.packs
                        .filter { it.installationState == FeaturePackInstallationState.INSTALLED }
                        .map { LocalFeaturePackCandidate(it.path, it.jar) },
                    loadedPackIds = startup.loadedPackIds,
                    failures = startup.failures,
                ),
                manager,
                overlayHost,
                systemInfoHost,
            )
        }

        private fun productionContextFactory(
            applicationRoot: Path,
            eventSink: (String) -> Unit,
            overlayHost: FeatureOverlayHost,
            systemInfoHost: SystemInfoHost,
        ) = FeaturePackContextFactory { descriptor ->
            ProductionFeaturePackContext(
                applicationRoot.toAbsolutePath().normalize(),
                descriptor,
                eventSink,
                overlayHost.scopedRegistry(descriptor.packId),
                systemInfoHost.scopedRegistry(descriptor.packId),
            )
        }
    }
}

private class ProductionFeaturePackContext(
    applicationRoot: Path,
    descriptor: FeaturePackDescriptor,
    eventSink: (String) -> Unit,
    private val overlayRegistry: ScopedOverlayRegistry,
    private val systemInfoRegistry: ScopedSystemInfoRegistry,
) : FeaturePackContext, FeaturePackContextLifecycle {
    private val storage = ProductionPackStorage(
        applicationRoot.resolve("feature-pack-storage").resolve(descriptor.packId.value),
    )
    private val logger = object : FeaturePackLogger {
        override fun log(level: FeaturePackLogLevel, message: String, cause: Throwable?) {
            val event = "${level.name}:${descriptor.packId.value}:$message"
            eventSink(event)
            when (level) {
                FeaturePackLogLevel.DEBUG, FeaturePackLogLevel.INFO -> AppDiagnostics.info("Feature Pack: $event")
                FeaturePackLogLevel.WARN -> AppDiagnostics.warning("Feature Pack: $event", cause)
                FeaturePackLogLevel.ERROR -> AppDiagnostics.warning("Feature Pack error: $event", cause)
            }
        }
    }

    override fun hostInfo() = FeaturePackHostInfo(
        coreVersion = parseCoreVersion(ApplicationBuildInfo.current.appVersion),
        featureApiVersion = FeatureApiVersions.current(),
        platform = HostPlatform(platformName(), platformArchitecture()),
    )

    override fun storage(): PackStorage = storage

    override fun logger(): FeaturePackLogger = logger

    override fun overlays(): OverlayRegistry = overlayRegistry

    override fun systemInfo(): SystemInfoRegistry = systemInfoRegistry

    override fun closeHostResources() {
        systemInfoRegistry.close()
        overlayRegistry.close()
    }
}

private class ProductionPackStorage(root: Path) : PackStorage {
    private val normalizedRoot = root.toAbsolutePath().normalize()

    override fun dataPath(relativePath: PackRelativePath): Path = resolve("data", relativePath)

    override fun configPath(relativePath: PackRelativePath): Path = resolve("config", relativePath)

    override fun cachePath(relativePath: PackRelativePath): Path = resolve("cache", relativePath)

    private fun resolve(area: String, relativePath: PackRelativePath): Path {
        val areaRoot = normalizedRoot.resolve(area)
        val resolved = areaRoot.resolve(relativePath.toPath()).normalize()
        check(resolved.startsWith(areaRoot)) { "Feature Pack storage path escaped its assigned area" }
        return resolved
    }
}

private fun parseCoreVersion(version: String): CoreVersion {
    val components = version.substringBefore('-').split('.')
    return CoreVersion(
        components.getOrNull(0)?.toIntOrNull() ?: 0,
        components.getOrNull(1)?.toIntOrNull() ?: 0,
        components.getOrNull(2)?.toIntOrNull() ?: 0,
    )
}

private fun platformName(): String = when {
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows"
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos"
    else -> "linux"
}

private fun platformArchitecture(): String = when (System.getProperty("os.arch").lowercase()) {
    "amd64", "x86_64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> "unknown"
}
