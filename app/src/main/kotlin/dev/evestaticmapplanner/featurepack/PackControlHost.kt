package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.PackControlActionResult
import dev.evestaticmapplanner.feature.api.PackControlActionStatus
import dev.evestaticmapplanner.feature.api.PackControlCapability
import dev.evestaticmapplanner.feature.api.PackControlProvider
import dev.evestaticmapplanner.feature.api.PackControlRegistration
import dev.evestaticmapplanner.feature.api.PackControlSeverity
import dev.evestaticmapplanner.feature.api.PackControlSnapshot
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

internal data class PackControlActionKey(val packId: PackId, val actionId: String)

internal data class PackControlActionUiState(
    val key: PackControlActionKey,
    val label: String,
    val description: String?,
    val enabled: Boolean,
)

internal data class PackControlUiState(
    val packId: PackId,
    val primaryText: String,
    val secondaryText: String?,
    val severity: PackControlSeverity,
    val actions: List<PackControlActionUiState>,
    val busyActionId: String? = null,
    val lastStatus: PackControlActionStatus? = null,
    val lastMessage: String? = null,
)

/** Generic Host boundary for Pack status snapshots and bounded background action invocation. */
class PackControlHost(
    private val failureSink: (packId: PackId, actionId: String?, operation: String, error: Throwable) -> Unit =
        { _, _, _, _ -> },
) : AutoCloseable {
    private val providers = linkedMapOf<PackId, HostedProvider>()
    private val mutableState = MutableStateFlow<List<PackControlUiState>>(emptyList())
    private val executor = ThreadPoolExecutor(
        0,
        MAX_ACTION_THREADS,
        THREAD_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_PENDING_ACTIONS),
        { runnable -> Thread(runnable, "feature-pack-control").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private var closed = false

    internal val state: StateFlow<List<PackControlUiState>> = mutableState.asStateFlow()

    internal fun scopedCapability(packId: PackId): ScopedPackControlCapability =
        ScopedPackControlCapability(packId, this)

    @Synchronized
    internal fun register(packId: PackId, provider: PackControlProvider): PackControlRegistration {
        check(!closed) { "Pack Control Host is closed" }
        require(packId !in providers) { "Feature Pack already registered a Pack Controls provider: $packId" }
        val hosted = HostedProvider(packId, provider, safeSnapshot(packId, provider))
        providers[packId] = hosted
        publishState()
        return HostPackControlRegistration(
            requestRefresh = { refresh(packId) },
            unregister = { unregister(packId) },
        )
    }

    internal fun invoke(key: PackControlActionKey): Boolean {
        synchronized(this) {
            val hosted = providers[key.packId] ?: return false
            val action = hosted.snapshot.actions.firstOrNull { it.id == key.actionId } ?: return false
            if (hosted.closed || hosted.invocation != null || !action.enabled) return false
            hosted.lastStatus = null
            hosted.lastMessage = null
            val invocation = ActionInvocation(key.actionId)
            hosted.invocation = invocation
            publishState()
            try {
                invocation.future = executor.submit { execute(hosted, invocation) }
            } catch (error: RejectedExecutionException) {
                hosted.invocation = null
                hosted.lastStatus = PackControlActionStatus.FAILED
                hosted.lastMessage = GENERIC_ACTION_FAILURE_MESSAGE
                publishState()
                reportFailure(hosted.packId, key.actionId, "schedule", error)
                return false
            }
            return true
        }
    }

    @Synchronized
    private fun refresh(packId: PackId) {
        val hosted = providers[packId] ?: return
        if (hosted.closed) return
        hosted.snapshot = safeSnapshot(packId, hosted.provider)
        publishState()
    }

    private fun execute(hosted: HostedProvider, invocation: ActionInvocation) {
        invocation.started.set(true)
        val shouldRun = synchronized(this) {
            !hosted.closed && providers[hosted.packId] === hosted && hosted.invocation === invocation
        }
        if (!shouldRun) {
            invocation.completed.countDown()
            return
        }
        val result = try {
            checkNotNull(hosted.provider.invoke(invocation.actionId)) {
                "Pack Control provider returned a null action result"
            }
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            reportFailure(hosted.packId, invocation.actionId, "invoke", error)
            PackControlActionResult(PackControlActionStatus.FAILED, GENERIC_ACTION_FAILURE_MESSAGE)
        }
        synchronized(this) {
            if (!hosted.closed && providers[hosted.packId] === hosted && hosted.invocation === invocation) {
                hosted.invocation = null
                hosted.lastStatus = result.status
                hosted.lastMessage = result.message
                hosted.snapshot = safeSnapshot(hosted.packId, hosted.provider)
                publishState()
            }
        }
        invocation.completed.countDown()
    }

    private fun unregister(packId: PackId) {
        val invocation = synchronized(this) {
            val hosted = providers.remove(packId) ?: return
            hosted.closed = true
            publishState()
            hosted.invocation.also { hosted.invocation = null }
        }
        cancelAndAwait(packId, invocation)
    }

    private fun cancelAndAwait(packId: PackId, invocation: ActionInvocation?) {
        if (invocation == null) return
        val cancelled = invocation.future?.cancel(true) == true
        if (cancelled && !invocation.started.get()) invocation.completed.countDown()
        if (!invocation.completed.await(CALLBACK_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            reportFailure(
                packId,
                invocation.actionId,
                "close",
                IllegalStateException("Pack Control callback did not stop within the bounded close timeout"),
            )
        }
    }

    private fun safeSnapshot(packId: PackId, provider: PackControlProvider): PackControlSnapshot = try {
        checkNotNull(provider.snapshot()) { "Pack Control provider returned a null snapshot" }
    } catch (error: Throwable) {
        rethrowIfFatal(error)
        reportFailure(packId, null, "snapshot", error)
        PackControlSnapshot(
            primaryText = "Feature Pack controls unavailable",
            secondaryText = "This Pack could not provide its current status.",
            severity = PackControlSeverity.ERROR,
            actions = emptyList(),
        )
    }

    private fun publishState() {
        mutableState.value = providers.values.map { hosted ->
            val busyActionId = hosted.invocation?.actionId
            PackControlUiState(
                packId = hosted.packId,
                primaryText = hosted.snapshot.primaryText,
                secondaryText = hosted.snapshot.secondaryText,
                severity = hosted.snapshot.severity,
                actions = hosted.snapshot.actions.map { action ->
                    PackControlActionUiState(
                        key = PackControlActionKey(hosted.packId, action.id),
                        label = action.label,
                        description = action.description,
                        enabled = action.enabled && busyActionId == null,
                    )
                },
                busyActionId = busyActionId,
                lastStatus = hosted.lastStatus,
                lastMessage = hosted.lastMessage,
            )
        }.sortedBy { it.packId.value }
    }

    private fun reportFailure(packId: PackId, actionId: String?, operation: String, error: Throwable) {
        runCatching { failureSink(packId, actionId, operation, error) }
    }

    override fun close() {
        val packIds = synchronized(this) {
            if (closed) return
            closed = true
            providers.keys.toList()
        }
        packIds.asReversed().forEach(::unregister)
        executor.shutdownNow()
        if (!executor.awaitTermination(CALLBACK_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            reportFailure(
                PackId("host.runtime"),
                null,
                "shutdown",
                IllegalStateException("Pack Control executor did not stop within the bounded shutdown timeout"),
            )
        }
    }

    private class HostedProvider(
        val packId: PackId,
        val provider: PackControlProvider,
        var snapshot: PackControlSnapshot,
    ) {
        var invocation: ActionInvocation? = null
        var lastStatus: PackControlActionStatus? = null
        var lastMessage: String? = null
        var closed = false
    }

    private class ActionInvocation(val actionId: String) {
        val started = AtomicBoolean(false)
        val completed = CountDownLatch(1)
        @Volatile var future: Future<*>? = null
    }

    private companion object {
        const val MAX_ACTION_THREADS = 2
        const val MAX_PENDING_ACTIONS = 16
        const val THREAD_KEEP_ALIVE_SECONDS = 15L
        const val CALLBACK_CLOSE_TIMEOUT_MILLIS = 750L
        const val GENERIC_ACTION_FAILURE_MESSAGE = "Feature Pack action failed"
    }
}

internal class ScopedPackControlCapability(
    private val packId: PackId,
    private val host: PackControlHost,
) : PackControlCapability, AutoCloseable {
    private var registration: PackControlRegistration? = null
    private var closed = false

    @Synchronized
    override fun register(provider: PackControlProvider): PackControlRegistration {
        check(!closed) { "Pack Control capability is closed for Feature Pack $packId" }
        check(registration == null) { "Feature Pack already registered a Pack Controls provider: $packId" }
        lateinit var scoped: PackControlRegistration
        val delegate = host.register(packId, provider)
        scoped = HostPackControlRegistration(
            requestRefresh = delegate::requestRefresh,
            unregister = {
                delegate.close()
                synchronized(this) { if (registration === scoped) registration = null }
            },
        )
        registration = scoped
        return scoped
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        registration?.close()
        registration = null
    }
}

private class HostPackControlRegistration(
    private val requestRefresh: () -> Unit,
    private val unregister: () -> Unit,
) : PackControlRegistration {
    private val closed = AtomicBoolean(false)

    override fun requestRefresh() {
        if (!closed.get()) requestRefresh.invoke()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) unregister()
    }
}
