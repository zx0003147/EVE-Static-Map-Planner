package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.RouteActionCapability
import dev.evestaticmapplanner.feature.api.RouteActionContext
import dev.evestaticmapplanner.feature.api.RouteActionDescriptor
import dev.evestaticmapplanner.feature.api.RouteActionProvider
import dev.evestaticmapplanner.feature.api.RouteActionRegistration
import dev.evestaticmapplanner.feature.api.RouteActionResult
import dev.evestaticmapplanner.feature.api.RouteActionStatus
import dev.evestaticmapplanner.feature.api.RouteActionTargetId
import dev.evestaticmapplanner.feature.api.RouteActionTargetSnapshot
import dev.evestaticmapplanner.feature.api.RouteKind
import dev.evestaticmapplanner.feature.api.RouteSnapshot
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

internal data class RouteActionKey(val packId: PackId, val actionId: String)

internal data class RouteActionUiState(
    val key: RouteActionKey,
    val label: String,
    val description: String?,
    val supportedRouteKinds: Set<RouteKind>,
    val busy: Boolean = false,
    val lastStatus: RouteActionStatus? = null,
    val lastMessage: String? = null,
    val targetSelector: RouteActionTargetSnapshot? = null,
) {
    val enabled: Boolean get() = !busy && (targetSelector == null || targetSelector.options.any { it.available })
}

/** Host-private registry, execution boundary, and presentation state for Pack Route Actions. */
class RouteActionHost(
    private val failureSink: (packId: PackId, actionId: String, operation: String, error: Throwable) -> Unit =
        { _, _, _, _ -> },
) : AutoCloseable {
    private val actions = linkedMapOf<RouteActionKey, HostedAction>()
    private val mutableState = MutableStateFlow<List<RouteActionUiState>>(emptyList())
    private val executor = ThreadPoolExecutor(
        0,
        MAX_ACTION_THREADS,
        THREAD_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_PENDING_ACTIONS),
        { runnable -> Thread(runnable, "feature-route-action").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private var closed = false

    internal val state: StateFlow<List<RouteActionUiState>> = mutableState.asStateFlow()

    internal fun scopedCapability(packId: PackId): ScopedRouteActionCapability =
        ScopedRouteActionCapability(packId, this)

    @Synchronized
    internal fun register(packId: PackId, provider: RouteActionProvider): RouteActionRegistration {
        check(!closed) { "Route Action Host is closed" }
        val descriptor = provider.descriptor()
        val targetSelector = provider.targets().also { validateTargetSelector(descriptor, it) }
        val key = RouteActionKey(packId, descriptor.id)
        require(key !in actions) { "Route Action ID is already registered for Feature Pack $packId: ${descriptor.id}" }
        actions[key] = HostedAction(key, descriptor, provider, targetSelector)
        publishState()
        return HostRouteActionRegistration(
            refresh = { requestTargetRefresh(key) },
            unregister = { unregister(key) },
        )
    }

    /** Captures [snapshot] before scheduling and never executes Pack code on the caller thread. */
    internal fun invoke(
        key: RouteActionKey,
        snapshot: RouteSnapshot,
        targetId: RouteActionTargetId? = null,
    ): Boolean {
        synchronized(this) {
            val hosted = actions[key] ?: return false
            if (hosted.closed || hosted.busy || snapshot.kind !in hosted.descriptor.supportedRouteKinds) return false
            if (!isValidTarget(hosted, targetId)) return false
            hosted.busy = true
            hosted.lastStatus = null
            hosted.lastMessage = null
            publishState()
            val invocation = ActionInvocation()
            hosted.invocation = invocation
            try {
                invocation.future = executor.submit { execute(hosted, snapshot, targetId, invocation) }
            } catch (error: RejectedExecutionException) {
                hosted.invocation = null
                hosted.busy = false
                hosted.lastStatus = RouteActionStatus.FAILED
                hosted.lastMessage = GENERIC_FAILURE_MESSAGE
                publishState()
                reportFailure(hosted, "schedule", error)
                return false
            }
            return true
        }
    }

    private fun execute(
        hosted: HostedAction,
        snapshot: RouteSnapshot,
        targetId: RouteActionTargetId?,
        invocation: ActionInvocation,
    ) {
        invocation.started.set(true)
        val shouldRun = synchronized(this) {
            !hosted.closed && actions[hosted.key] === hosted && hosted.invocation === invocation
        }
        if (!shouldRun) {
            invocation.completed.countDown()
            return
        }

        val result = try {
            checkNotNull(hosted.provider.execute(RouteActionContext(snapshot, targetId))) {
                "Route Action provider returned a null result"
            }
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            reportFailure(hosted, "execute", error)
            RouteActionResult(RouteActionStatus.FAILED, GENERIC_FAILURE_MESSAGE)
        }
        synchronized(this) {
            if (!hosted.closed && actions[hosted.key] === hosted && hosted.invocation === invocation) {
                hosted.invocation = null
                hosted.busy = false
                hosted.lastStatus = result.status
                hosted.lastMessage = result.message
                publishState()
            }
        }
        invocation.completed.countDown()
    }

    private fun requestTargetRefresh(key: RouteActionKey) {
        val hosted = synchronized(this) {
            val current = actions[key] ?: return
            if (current.closed || current.targetRefreshBusy) return
            current.targetRefreshBusy = true
            current
        }
        try {
            executor.submit { refreshTargets(hosted) }
        } catch (error: RejectedExecutionException) {
            synchronized(this) { hosted.targetRefreshBusy = false }
            reportFailure(hosted, "refresh-targets-schedule", error)
        }
    }

    private fun refreshTargets(hosted: HostedAction) {
        val refreshed = runCatching { hosted.provider.targets().also { validateTargetSelector(hosted.descriptor, it) } }
        synchronized(this) {
            hosted.targetRefreshBusy = false
            if (!hosted.closed && actions[hosted.key] === hosted) {
                refreshed.onSuccess { hosted.targetSelector = it }
                publishState()
            }
        }
        refreshed.exceptionOrNull()?.let { reportFailure(hosted, "refresh-targets", it) }
    }

    private fun unregister(key: RouteActionKey) {
        val invocation = synchronized(this) {
            val hosted = actions.remove(key) ?: return
            hosted.closed = true
            hosted.busy = false
            publishState()
            hosted.invocation.also { hosted.invocation = null }
        }
        cancelAndAwait(invocation, key)
    }

    private fun cancelAndAwait(invocation: ActionInvocation?, key: RouteActionKey) {
        if (invocation == null) return
        val cancelled = invocation.future?.cancel(true) == true
        if (cancelled && !invocation.started.get()) invocation.completed.countDown()
        if (!invocation.completed.await(CALLBACK_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            reportFailure(
                key.packId,
                key.actionId,
                "close",
                IllegalStateException("Route Action callback did not stop within the bounded close timeout"),
            )
        }
    }

    private fun publishState() {
        mutableState.value = actions.values.map { hosted ->
            RouteActionUiState(
                key = hosted.key,
                label = hosted.descriptor.label,
                description = hosted.descriptor.description,
                supportedRouteKinds = hosted.descriptor.supportedRouteKinds,
                targetSelector = hosted.targetSelector,
                busy = hosted.busy,
                lastStatus = hosted.lastStatus,
                lastMessage = hosted.lastMessage,
            )
        }.sortedWith(compareBy({ it.key.packId.value }, { it.key.actionId }))
    }

    private fun reportFailure(hosted: HostedAction, operation: String, error: Throwable) =
        reportFailure(hosted.key.packId, hosted.key.actionId, operation, error)

    private fun reportFailure(packId: PackId, actionId: String, operation: String, error: Throwable) {
        runCatching { failureSink(packId, actionId, operation, error) }
    }

    override fun close() {
        val keys = synchronized(this) {
            if (closed) return
            closed = true
            actions.keys.toList()
        }
        keys.asReversed().forEach(::unregister)
        executor.shutdownNow()
        if (!executor.awaitTermination(CALLBACK_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            reportFailure(
                PackId("host.runtime"),
                "route-action",
                "shutdown",
                IllegalStateException("Route Action executor did not stop within the bounded shutdown timeout"),
            )
        }
    }

    private class HostedAction(
        val key: RouteActionKey,
        val descriptor: RouteActionDescriptor,
        val provider: RouteActionProvider,
        var targetSelector: RouteActionTargetSnapshot?,
    ) {
        var busy = false
        var lastStatus: RouteActionStatus? = null
        var lastMessage: String? = null
        var invocation: ActionInvocation? = null
        var closed = false
        var targetRefreshBusy = false
    }

    private class ActionInvocation {
        val started = AtomicBoolean(false)
        val completed = CountDownLatch(1)
        @Volatile var future: Future<*>? = null
    }

    private fun validateTargetSelector(
        descriptor: RouteActionDescriptor,
        snapshot: RouteActionTargetSnapshot?,
    ) {
        require(descriptor.targetSelectorId == snapshot?.selectorId) {
            "Route Action target snapshot must match its descriptor selector ID"
        }
    }

    private fun isValidTarget(hosted: HostedAction, targetId: RouteActionTargetId?): Boolean {
        val selector = hosted.targetSelector ?: return targetId == null && hosted.descriptor.targetSelectorId == null
        val selected = targetId ?: return false
        return selector.options.any { it.id == selected && it.available }
    }

    private companion object {
        const val MAX_ACTION_THREADS = 2
        const val MAX_PENDING_ACTIONS = 16
        const val THREAD_KEEP_ALIVE_SECONDS = 15L
        const val CALLBACK_CLOSE_TIMEOUT_MILLIS = 750L
        const val GENERIC_FAILURE_MESSAGE = "Feature Pack action failed"
    }
}

internal class ScopedRouteActionCapability(
    private val packId: PackId,
    private val host: RouteActionHost,
) : RouteActionCapability, AutoCloseable {
    private val registrations = linkedSetOf<RouteActionRegistration>()
    private var closed = false

    @Synchronized
    override fun register(provider: RouteActionProvider): RouteActionRegistration {
        check(!closed) { "Route Action capability is closed for Feature Pack $packId" }
        lateinit var scoped: RouteActionRegistration
        val delegate = host.register(packId, provider)
        scoped = HostRouteActionRegistration(
            refresh = delegate::requestTargetRefresh,
            unregister = {
                delegate.close()
                synchronized(this) { registrations.remove(scoped) }
            },
        )
        registrations += scoped
        return scoped
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        registrations.toList().asReversed().forEach(RouteActionRegistration::close)
        registrations.clear()
    }
}

private class HostRouteActionRegistration(
    private val refresh: () -> Unit,
    private val unregister: () -> Unit,
) : RouteActionRegistration {
    private val closed = AtomicBoolean(false)

    override fun requestTargetRefresh() {
        if (!closed.get()) refresh()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) unregister()
    }
}
