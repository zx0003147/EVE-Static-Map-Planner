package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.DynamicOverlayCapability
import dev.evestaticmapplanner.feature.api.DynamicOverlayRegistration
import dev.evestaticmapplanner.feature.api.OverlayEntry
import dev.evestaticmapplanner.feature.api.OverlayLayer
import dev.evestaticmapplanner.feature.api.OverlayLayerState
import dev.evestaticmapplanner.feature.api.OverlayProvider
import dev.evestaticmapplanner.feature.api.OverlayProviderDescriptor
import dev.evestaticmapplanner.feature.api.OverlayRegistration
import dev.evestaticmapplanner.feature.api.OverlayRegistry
import dev.evestaticmapplanner.feature.api.OverlayState
import dev.evestaticmapplanner.feature.api.PackId
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application-owned aggregation boundary between external providers and map presentation.
 *
 * Each provider owns a validated last-good cache. Dynamic refresh is provider-targeted and starts
 * only after a Pack requests it and the Host enables background execution; constructing an empty
 * Host starts no thread and performs no work.
 */
class FeatureOverlayHost(
    backgroundRefreshesEnabled: Boolean = true,
    private val failureSink: (packId: PackId, providerId: String, operation: String, error: Throwable) -> Unit =
        { _, _, _, _ -> },
) : AutoCloseable {
    private val providers = linkedMapOf<ProviderKey, HostedProvider>()
    private val mutableState = MutableStateFlow(OverlayState(emptyList()))
    private val executor = ThreadPoolExecutor(
        0,
        MAX_REFRESH_THREADS,
        THREAD_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_PENDING_REFRESHES),
        { runnable -> Thread(runnable, "feature-overlay-refresh").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private var closed = false
    private var backgroundRefreshesEnabled = backgroundRefreshesEnabled

    val state: StateFlow<OverlayState> = mutableState.asStateFlow()

    internal fun scopedRegistry(packId: PackId): ScopedOverlayRegistry = ScopedOverlayRegistry(packId, this)

    internal fun scopedDynamicCapability(packId: PackId): ScopedDynamicOverlayCapability =
        ScopedDynamicOverlayCapability(packId, this)

    /** Re-publishes the combined state from validated caches without calling any provider. */
    @Synchronized
    fun refresh(): OverlayState = publishCombinedState()

    /** Releases refresh requests queued while the application was waiting for its first base-map frame. */
    @Synchronized
    fun enableBackgroundRefreshes() {
        if (closed || backgroundRefreshesEnabled) return
        backgroundRefreshesEnabled = true
        providers.values
            .filter { it.dynamic && !it.closed && it.refreshState == RefreshState.SCHEDULED && it.invocation == null }
            .forEach(::submitRefresh)
    }

    @Synchronized
    internal fun registerStatic(packId: PackId, provider: OverlayProvider): OverlayRegistration =
        register(packId, provider, dynamic = false)

    @Synchronized
    internal fun registerDynamic(packId: PackId, provider: OverlayProvider): DynamicOverlayRegistration {
        val hosted = registerHosted(packId, provider, dynamic = true)
        return HostDynamicOverlayRegistration(
            requestRefresh = { requestRefresh(hosted.key) },
            unregister = { unregister(hosted.key) },
        )
    }

    private fun register(packId: PackId, provider: OverlayProvider, dynamic: Boolean): OverlayRegistration {
        val hosted = registerHosted(packId, provider, dynamic)
        return HostOverlayRegistration { unregister(hosted.key) }
    }

    private fun registerHosted(packId: PackId, provider: OverlayProvider, dynamic: Boolean): HostedProvider {
        check(!closed) { "Feature Overlay Host is closed" }
        val descriptor = provider.descriptor()
        require(providers.values.none { it.descriptor.id == descriptor.id }) {
            "Overlay provider ID is already registered: ${descriptor.id}"
        }
        val layers = provider.layers().toList()
        require(layers.isNotEmpty()) { "Overlay provider must declare at least one layer: ${descriptor.id}" }
        require(layers.map(OverlayLayer::id).distinct().size == layers.size) {
            "Overlay provider declares duplicate layer IDs: ${descriptor.id}"
        }
        val entries = provider.snapshot().entries.toList()
        val key = ProviderKey(packId, descriptor.id)
        val hosted = HostedProvider(key, descriptor, layers, provider, entries, dynamic)
        validateEntries(hosted, entries)
        providers[key] = hosted
        publishCombinedState()
        return hosted
    }

    private fun requestRefresh(key: ProviderKey) {
        synchronized(this) {
            val hosted = providers[key] ?: return
            if (!hosted.dynamic || hosted.closed) return
            when (hosted.refreshState) {
                RefreshState.IDLE -> {
                    hosted.refreshState = RefreshState.SCHEDULED
                    if (backgroundRefreshesEnabled) submitRefresh(hosted)
                }
                RefreshState.SCHEDULED, RefreshState.RUNNING_DIRTY -> Unit
                RefreshState.RUNNING -> hosted.refreshState = RefreshState.RUNNING_DIRTY
                RefreshState.CLOSED -> Unit
            }
        }
    }

    private fun submitRefresh(hosted: HostedProvider) {
        val invocation = RefreshInvocation()
        hosted.invocation = invocation
        try {
            invocation.future = executor.submit { runRefresh(hosted, invocation) }
        } catch (error: RejectedExecutionException) {
            hosted.invocation = null
            hosted.refreshState = RefreshState.IDLE
            reportFailure(hosted, "schedule-refresh", error)
        }
    }

    private fun runRefresh(hosted: HostedProvider, invocation: RefreshInvocation) {
        invocation.started.set(true)
        val shouldRun = synchronized(this) {
            if (hosted.closed || providers[hosted.key] !== hosted || hosted.invocation !== invocation) {
                false
            } else {
                hosted.refreshState = RefreshState.RUNNING
                true
            }
        }
        if (!shouldRun) {
            invocation.completed.countDown()
            return
        }

        val result = runCatching {
            hosted.provider.snapshot().entries.toList().also { validateEntries(hosted, it) }
        }
        synchronized(this) {
            if (!hosted.closed && providers[hosted.key] === hosted && hosted.invocation === invocation) {
                result.onSuccess { entries ->
                    hosted.lastGoodEntries = entries
                    publishCombinedState()
                }.onFailure { error ->
                    rethrowIfFatal(error)
                    reportFailure(hosted, "refresh", error)
                }
                val dirty = hosted.refreshState == RefreshState.RUNNING_DIRTY
                hosted.invocation = null
                hosted.refreshState = RefreshState.IDLE
                if (dirty) {
                    hosted.refreshState = RefreshState.SCHEDULED
                    submitRefresh(hosted)
                }
            }
        }
        invocation.completed.countDown()
    }

    private fun unregister(key: ProviderKey) {
        val invocation = synchronized(this) {
            val hosted = providers.remove(key) ?: return
            hosted.closed = true
            hosted.refreshState = RefreshState.CLOSED
            publishCombinedState()
            hosted.invocation.also { hosted.invocation = null }
        }
        cancelAndAwait(invocation, key)
    }

    private fun cancelAndAwait(invocation: RefreshInvocation?, key: ProviderKey) {
        if (invocation == null) return
        val cancelled = invocation.future?.cancel(true) == true
        if (cancelled && !invocation.started.get()) invocation.completed.countDown()
        if (!invocation.completed.await(CALLBACK_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            reportFailure(
                key.packId,
                key.providerId,
                "close-refresh",
                IllegalStateException("Overlay refresh did not stop within the bounded close timeout"),
            )
        }
    }

    private fun publishCombinedState(): OverlayState {
        val combinedLayers = providers.values.flatMap { hosted ->
            hosted.layers.map { layer ->
                OverlayLayerState(
                    provider = hosted.descriptor,
                    layer = layer,
                    entries = hosted.lastGoodEntries.filter { it.layerId == layer.id },
                )
            }
        }.sortedWith(
            compareBy<OverlayLayerState> { it.layer.priority }
                .thenBy { it.provider.id }
                .thenBy { it.layer.id },
        )
        return OverlayState(combinedLayers).also { mutableState.value = it }
    }

    private fun validateEntries(hosted: HostedProvider, entries: List<OverlayEntry>) {
        val layerIds = hosted.layers.mapTo(hashSetOf(), OverlayLayer::id)
        require(entries.all { it.layerId in layerIds }) {
            "Overlay provider ${hosted.descriptor.id} returned an entry for an undeclared layer"
        }
        require(entries.map { it.layerId to it.systemId }.distinct().size == entries.size) {
            "Overlay provider ${hosted.descriptor.id} returned duplicate system entries for a layer"
        }
    }

    private fun reportFailure(hosted: HostedProvider, operation: String, error: Throwable) =
        reportFailure(hosted.key.packId, hosted.descriptor.id, operation, error)

    private fun reportFailure(packId: PackId, providerId: String, operation: String, error: Throwable) {
        runCatching { failureSink(packId, providerId, operation, error) }
    }

    override fun close() {
        val keys = synchronized(this) {
            if (closed) return
            closed = true
            providers.keys.toList()
        }
        keys.asReversed().forEach(::unregister)
        executor.shutdownNow()
        if (!executor.awaitTermination(CALLBACK_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            reportFailure(
                PackId("host.runtime"),
                "dynamic-overlay",
                "shutdown",
                IllegalStateException("Feature Overlay executor did not stop within the bounded shutdown timeout"),
            )
        }
    }

    private data class ProviderKey(val packId: PackId, val providerId: String)

    private class HostedProvider(
        val key: ProviderKey,
        val descriptor: OverlayProviderDescriptor,
        val layers: List<OverlayLayer>,
        val provider: OverlayProvider,
        var lastGoodEntries: List<OverlayEntry>,
        val dynamic: Boolean,
    ) {
        var refreshState = RefreshState.IDLE
        var invocation: RefreshInvocation? = null
        var closed = false
    }

    private class RefreshInvocation {
        val started = AtomicBoolean(false)
        val completed = CountDownLatch(1)
        @Volatile var future: Future<*>? = null
    }

    private enum class RefreshState { IDLE, SCHEDULED, RUNNING, RUNNING_DIRTY, CLOSED }

    private companion object {
        const val MAX_REFRESH_THREADS = 2
        const val MAX_PENDING_REFRESHES = 32
        const val THREAD_KEEP_ALIVE_SECONDS = 15L
        const val CALLBACK_CLOSE_TIMEOUT_MILLIS = 750L
    }
}

internal class ScopedOverlayRegistry(
    private val packId: PackId,
    private val host: FeatureOverlayHost,
) : OverlayRegistry, AutoCloseable {
    private val registrations = linkedSetOf<OverlayRegistration>()
    private var closed = false

    @Synchronized
    override fun register(provider: OverlayProvider): OverlayRegistration {
        check(!closed) { "Overlay registry is closed for Feature Pack $packId" }
        lateinit var scoped: OverlayRegistration
        val delegate = host.registerStatic(packId, provider)
        scoped = HostOverlayRegistration {
            delegate.close()
            synchronized(this) { registrations.remove(scoped) }
        }
        registrations += scoped
        return scoped
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        registrations.toList().asReversed().forEach(OverlayRegistration::close)
        registrations.clear()
    }
}

internal class ScopedDynamicOverlayCapability(
    private val packId: PackId,
    private val host: FeatureOverlayHost,
) : DynamicOverlayCapability, AutoCloseable {
    private val registrations = linkedSetOf<DynamicOverlayRegistration>()
    private var closed = false

    @Synchronized
    override fun register(provider: OverlayProvider): DynamicOverlayRegistration {
        check(!closed) { "Dynamic Overlay capability is closed for Feature Pack $packId" }
        lateinit var scoped: DynamicOverlayRegistration
        val delegate = host.registerDynamic(packId, provider)
        scoped = ScopedDynamicOverlayRegistration(delegate) {
            synchronized(this) { registrations.remove(scoped) }
        }
        registrations += scoped
        return scoped
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        registrations.toList().asReversed().forEach(DynamicOverlayRegistration::close)
        registrations.clear()
    }
}

private class ScopedDynamicOverlayRegistration(
    private val delegate: DynamicOverlayRegistration,
    private val onClose: () -> Unit,
) : DynamicOverlayRegistration {
    private val closed = AtomicBoolean(false)

    override fun requestRefresh() {
        if (!closed.get()) delegate.requestRefresh()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            delegate.close()
            onClose()
        }
    }
}

private class HostOverlayRegistration(
    private val unregister: () -> Unit,
) : OverlayRegistration {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) unregister()
    }
}

private class HostDynamicOverlayRegistration(
    private val requestRefresh: () -> Unit,
    private val unregister: () -> Unit,
) : DynamicOverlayRegistration {
    private val closed = AtomicBoolean(false)

    override fun requestRefresh() {
        if (!closed.get()) requestRefresh.invoke()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) unregister()
    }
}
