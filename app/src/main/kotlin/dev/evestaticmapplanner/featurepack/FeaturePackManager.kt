package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.PackId
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FeaturePackRuntimeState {
    DISABLED,
    ENABLED,
}

data class FeaturePackManagerItem(
    val pack: RegisteredFeaturePack,
    val runtimeState: FeaturePackRuntimeState,
)

data class FeaturePackManagerState(
    val initialized: Boolean = false,
    val packs: List<FeaturePackManagerItem> = emptyList(),
    val discoveryErrors: List<String> = emptyList(),
)

data class FeaturePackManagerStartResult(
    val snapshot: FeaturePackRegistrySnapshot,
    val loadedPackIds: List<PackId>,
    val failures: List<FeaturePackFailure>,
)

class FeaturePackManager(
    private val packRoot: Path,
    private val registry: FeaturePackRegistry,
    private val contextFactory: FeaturePackContextFactory,
    private val host: LocalFeaturePackHost = LocalFeaturePackHost(),
) : AutoCloseable {
    private val activePacks = linkedMapOf<PackId, LoadedFeaturePack>()
    private val mutableState = MutableStateFlow(FeaturePackManagerState())
    val state: StateFlow<FeaturePackManagerState> = mutableState.asStateFlow()

    @Synchronized
    fun refresh(): FeaturePackRegistrySnapshot {
        val snapshot = registry.discover()
        publish(snapshot)
        return snapshot
    }

    @Synchronized
    fun startEnabledPacks(): FeaturePackManagerStartResult {
        val snapshot = refresh()
        val failures = snapshot.failures.toMutableList()
        snapshot.packs.filter(RegisteredFeaturePack::enabled).forEach { pack ->
            if (pack.installationState != FeaturePackInstallationState.INSTALLED) {
                val failure = FeaturePackFailure(
                    if (pack.installationState == FeaturePackInstallationState.INCOMPATIBLE) {
                        FeaturePackFailureKind.INCOMPATIBLE_FEATURE_API
                    } else {
                        FeaturePackFailureKind.INVALID_DESCRIPTOR
                    },
                    pack.lastError ?: "Enabled Feature Pack is not installed correctly: ${pack.packId.value}",
                )
                failures += failure
                remember(pack.packId, true, failure.message)
            } else {
                enableLoaded(pack)?.let(failures::add)
            }
        }
        publish(registry.discover())
        return FeaturePackManagerStartResult(snapshot, activePacks.keys.toList(), failures.distinctBy { it.kind to it.message })
    }

    @Synchronized
    fun setEnabled(packId: PackId, enabled: Boolean): Result<Unit> = runCatching {
        val snapshot = ensureInitialized()
        val pack = snapshot.packs.firstOrNull { it.packId == packId }
            ?: error("Feature Pack is not installed: ${packId.value}")
        if (enabled) {
            require(pack.installationState == FeaturePackInstallationState.INSTALLED) {
                pack.lastError ?: "Feature Pack is not installed correctly"
            }
            remember(packId, true, null)
            enableLoaded(pack)?.let { failure -> throw FeaturePackOperationException(failure) }
        } else {
            val closeFailure = closeLoaded(packId)
            remember(packId, false, closeFailure?.message)
            closeFailure?.let { throw FeaturePackOperationException(it) }
        }
        Unit
    }.also { publish(registry.discover()) }

    @Synchronized
    fun remove(packId: PackId): Result<Unit> = runCatching {
        val snapshot = ensureInitialized()
        val pack = snapshot.packs.firstOrNull { it.packId == packId }
            ?: error("Feature Pack is not installed: ${packId.value}")
        closeLoaded(packId)?.let {
            remember(packId, false, it.message)
            throw FeaturePackOperationException(it)
        }
        val normalizedRoot = packRoot.toAbsolutePath().normalize()
        val target = pack.path.toAbsolutePath().normalize()
        require(target.parent == normalizedRoot) { "Refusing to remove a path outside the Feature Pack root" }
        if (Files.exists(target)) deleteTreeWithoutFollowingLinks(target)
        registry.forget(packId)
    }.onFailure { error ->
        if (error !is FeaturePackOperationException) {
            remember(packId, false, error.message ?: "Feature Pack removal failed")
        }
    }.also { publish(registry.discover()) }

    @Synchronized
    fun closeSafely(): List<FeaturePackFailure> {
        val failures = activePacks.keys.toList().asReversed().mapNotNull { packId ->
            closeLoaded(packId)?.also { remember(packId, true, it.message) }
        }
        if (mutableState.value.initialized) publish(registry.discover())
        return failures
    }

    override fun close() {
        closeSafely()
    }

    private fun ensureInitialized(): FeaturePackRegistrySnapshot =
        if (mutableState.value.initialized) registry.discover() else refresh()

    private fun enableLoaded(pack: RegisteredFeaturePack): FeaturePackFailure? {
        if (activePacks.containsKey(pack.packId)) return null
        return when (val result = host.load(LocalFeaturePackCandidate(pack.path, pack.jar), contextFactory)) {
            is FeaturePackLoadResult.Failed -> result.failure.also { remember(pack.packId, true, it.message) }
            is FeaturePackLoadResult.Loaded -> {
                val descriptor = result.pack.descriptor
                if (
                    descriptor.packId != pack.packId ||
                    descriptor.displayName != pack.displayName ||
                    descriptor.packVersion != pack.version ||
                    descriptor.publisher != pack.publisher
                ) {
                    result.pack.closeSafely()
                    FeaturePackFailure(
                        FeaturePackFailureKind.DESCRIPTOR_MISMATCH,
                        "Feature Pack runtime descriptor does not match its manifest: ${pack.packId.value}",
                    ).also { remember(pack.packId, true, it.message) }
                } else {
                    activePacks[pack.packId] = result.pack
                    remember(pack.packId, true, null)
                    null
                }
            }
        }
    }

    private fun closeLoaded(packId: PackId): FeaturePackFailure? {
        val loaded = activePacks.remove(packId) ?: return null
        return when (val result = loaded.closeSafely()) {
            FeaturePackCloseResult.Closed -> null
            is FeaturePackCloseResult.Failed -> result.failure
        }
    }

    private fun remember(packId: PackId, enabled: Boolean, lastError: String?) {
        registry.updateState(packId, StoredFeaturePackState(enabled, lastError))
    }

    private fun publish(snapshot: FeaturePackRegistrySnapshot) {
        mutableState.value = FeaturePackManagerState(
            initialized = true,
            packs = snapshot.packs.map { pack ->
                FeaturePackManagerItem(
                    pack = pack,
                    runtimeState = if (activePacks.containsKey(pack.packId)) {
                        FeaturePackRuntimeState.ENABLED
                    } else {
                        FeaturePackRuntimeState.DISABLED
                    },
                )
            },
            discoveryErrors = snapshot.failures
                .filter { failure -> snapshot.packs.none { it.lastError == failure.message } }
                .map(FeaturePackFailure::message),
        )
    }
}

class FeaturePackManagerViewModel(
    private val manager: FeaturePackManager,
    private val packControlHost: PackControlHost? = null,
) {
    val state: StateFlow<FeaturePackManagerState> = manager.state
    internal val controlsState: StateFlow<List<PackControlUiState>> =
        packControlHost?.state ?: MutableStateFlow(emptyList())

    fun refresh() = manager.refresh()

    fun setEnabled(packId: PackId, enabled: Boolean): Result<Unit> = manager.setEnabled(packId, enabled)

    fun remove(packId: PackId): Result<Unit> = manager.remove(packId)

    internal fun invokeControl(key: PackControlActionKey): Boolean = packControlHost?.invoke(key) == true
}

private class FeaturePackOperationException(val failure: FeaturePackFailure) : Exception(failure.message, failure.cause)

private fun deleteTreeWithoutFollowingLinks(target: Path) {
    Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
            if (error != null) throw error
            Files.delete(directory)
            return FileVisitResult.CONTINUE
        }
    })
}
