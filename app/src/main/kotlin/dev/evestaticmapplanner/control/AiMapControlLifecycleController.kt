package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.AppDiagnostics
import dev.evestaticmapplanner.control.transport.ActiveLocalControlDiscovery
import dev.evestaticmapplanner.control.transport.LocalControlAuditEvent
import dev.evestaticmapplanner.control.transport.LocalControlAuditSink
import dev.evestaticmapplanner.control.transport.LocalControlDiscoveryAcquisition
import dev.evestaticmapplanner.control.transport.LocalControlServer
import dev.evestaticmapplanner.control.transport.SecureLocalControlDiscovery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path

sealed interface AiControlStatus {
    data object Disabled : AiControlStatus
    data object Starting : AiControlStatus
    data object Listening : AiControlStatus
    data object AlreadyActive : AiControlStatus
    data class Error(val code: String, val message: String) : AiControlStatus
}

class AppAiControlSession internal constructor(
    val server: LocalControlServer,
    private val clearMissionState: suspend () -> Unit,
    private val closeControlSession: () -> Unit,
) {
    suspend fun clearMissions() = clearMissionState()
    fun closeControl() = closeControlSession()
}

class AiMapControlLifecycleController internal constructor(
    discoveryRoot: Path,
    private val sessionFactory: () -> AppAiControlSession,
    private val discoveryFactory: (Path) -> SecureLocalControlDiscovery = ::SecureLocalControlDiscovery,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: AiControlLifecycleDiagnostics = AppAiControlLifecycleDiagnostics,
    private val startupGate: suspend () -> Unit = {},
) {
    private val discoveryRoot = discoveryRoot.toAbsolutePath().normalize()
    private val mutation = Mutex()
    private val mutableStatus = MutableStateFlow<AiControlStatus>(AiControlStatus.Disabled)
    val status: StateFlow<AiControlStatus> = mutableStatus.asStateFlow()

    private var active: ActiveSession? = null
    private var closed = false

    suspend fun setEnabled(enabled: Boolean) = withContext(ioDispatcher + NonCancellable) {
        mutation.withLock {
            if (closed) return@withLock
            if (enabled) enableLocked() else disableLocked(AiControlStatus.Disabled)
        }
    }

    suspend fun shutdown() = withContext(ioDispatcher + NonCancellable) {
        mutation.withLock {
            if (closed) return@withLock
            closed = true
            disableLocked(AiControlStatus.Disabled)
        }
    }

    private suspend fun enableLocked() {
        if (active != null) {
            mutableStatus.value = AiControlStatus.Listening
            return
        }
        mutableStatus.value = AiControlStatus.Starting
        var lease: ActiveLocalControlDiscovery? = null
        var session: AppAiControlSession? = null
        try {
            when (val acquisition = discoveryFactory(discoveryRoot).acquire()) {
                LocalControlDiscoveryAcquisition.AlreadyActive -> {
                    mutableStatus.value = AiControlStatus.AlreadyActive
                    diagnostics.info("AI Control enable declined because another instance is active")
                    return
                }
                is LocalControlDiscoveryAcquisition.Acquired -> lease = acquisition.lease
            }
            session = sessionFactory()
            session.clearMissions()
            startupGate()
            session.server.start()
            lease.publish(session.server)
            active = ActiveSession(lease, session)
            mutableStatus.value = AiControlStatus.Listening
            diagnostics.info("AI Control is listening on localhost")
        } catch (failure: Throwable) {
            cleanup(lease, session)
            mutableStatus.value = AiControlStatus.Error(
                code = "ENABLE_FAILED",
                message = "AI Map Control could not be enabled securely",
            )
            diagnostics.warning("AI Control enable failed", failure)
        }
    }

    private suspend fun disableLocked(finalStatus: AiControlStatus) {
        val current = active
        active = null
        if (current != null) cleanup(current.discovery, current.session)
        mutableStatus.value = finalStatus
        if (current != null) diagnostics.info("AI Control was disabled")
    }

    private suspend fun cleanup(
        discovery: ActiveLocalControlDiscovery?,
        session: AppAiControlSession?,
    ) {
        fun bestEffort(label: String, block: () -> Unit) {
            runCatching(block).onFailure { diagnostics.warning("AI Control cleanup failed: $label", it) }
        }
        suspend fun bestEffortSuspend(label: String, block: suspend () -> Unit) {
            runCatching { block() }.onFailure { diagnostics.warning("AI Control cleanup failed: $label", it) }
        }

        bestEffort("descriptor unpublish") { discovery?.unpublishDescriptor() }
        bestEffort("server stop") { session?.server?.stop() }
        bestEffort("session key removal") { discovery?.removeSessionKey() }
        bestEffortSuspend("Mission state clear") { session?.clearMissions() }
        bestEffort("Control Session close") { session?.closeControl() }
        bestEffort("active lock release") { discovery?.release() }
    }

    private data class ActiveSession(
        val discovery: ActiveLocalControlDiscovery,
        val session: AppAiControlSession,
    )
}

internal interface AiControlLifecycleDiagnostics {
    fun info(message: String)
    fun warning(message: String, failure: Throwable)
}

private object AppAiControlLifecycleDiagnostics : AiControlLifecycleDiagnostics {
    override fun info(message: String) = AppDiagnostics.info(message)
    override fun warning(message: String, failure: Throwable) {
        val causeTypes = generateSequence(failure) { it.cause }
            .take(4)
            .joinToString(" -> ") { it::class.simpleName ?: "UnknownFailure" }
        AppDiagnostics.warning("$message; causeTypes=$causeTypes")
    }
}

object AppLocalControlAuditSink : LocalControlAuditSink {
    override fun record(event: LocalControlAuditEvent) {
        AppDiagnostics.info(
            "AI_CONTROL_AUDIT timestamp=${event.timestamp} requestId=${event.requestId ?: "-"} " +
                "operation=${event.operation} missionId=${event.missionId ?: "-"} " +
                "httpStatus=${event.httpStatus} result=${event.resultCode} " +
                "durationMs=${event.durationMillis} delivered=${event.responseDelivered}",
        )
    }
}
