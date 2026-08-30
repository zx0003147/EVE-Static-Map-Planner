package dev.evestaticmapplanner

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal class ApplicationShutdownCoordinator(
    private val shutdownLocalhostMcp: suspend () -> Unit,
    private val shutdownAiControl: suspend () -> Unit,
    private val resourceClosers: List<() -> Unit>,
    private val closeDiagnostics: () -> Unit,
    private val exitApplication: () -> Unit,
    private val warningSink: (String, Throwable) -> Unit,
) {
    private val mutation = Mutex()
    private val resourcesClosed = AtomicBoolean(false)
    private var shutdownComplete = false

    suspend fun shutdown() = withContext(NonCancellable) {
        mutation.withLock {
            if (shutdownComplete) return@withLock
            bestEffortSuspend("Localhost MCP") { shutdownLocalhostMcp() }
            bestEffortSuspend("AI Control") { shutdownAiControl() }
            closeOwnedResources()
            bestEffort("diagnostics") { closeDiagnostics() }
            shutdownComplete = true
            exitApplication()
        }
    }

    fun closeOwnedResources() {
        if (!resourcesClosed.compareAndSet(false, true)) return
        resourceClosers.forEachIndexed { index, close ->
            bestEffort("application resource ${index + 1}", close)
        }
    }

    private fun bestEffort(label: String, block: () -> Unit) {
        runCatching(block).onFailure { warningSink("Application shutdown failed: $label", it) }
    }

    private suspend fun bestEffortSuspend(label: String, block: suspend () -> Unit) {
        runCatching { block() }.onFailure { warningSink("Application shutdown failed: $label", it) }
    }
}
