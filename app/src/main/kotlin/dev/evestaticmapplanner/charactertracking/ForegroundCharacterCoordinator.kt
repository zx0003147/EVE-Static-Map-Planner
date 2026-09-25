package dev.evestaticmapplanner.charactertracking

import dev.evestaticmapplanner.feature.api.TrackedCharacterAuthorizationState
import dev.evestaticmapplanner.feature.api.TrackedCharacterSnapshot
import dev.evestaticmapplanner.platform.windows.windowidentity.ForegroundWindowClassification
import dev.evestaticmapplanner.platform.windows.windowidentity.ForegroundWindowEvent
import dev.evestaticmapplanner.platform.windows.windowidentity.ForegroundWindowMonitor
import dev.evestaticmapplanner.platform.windows.windowidentity.ForegroundWindowSnapshot
import dev.evestaticmapplanner.platform.windows.windowidentity.WindowSessionIdentity
import dev.evestaticmapplanner.platform.windows.windowidentity.WindowsForegroundWindowMonitor
import dev.evestaticmapplanner.platform.windows.windowidentity.WindowsForegroundWindowSnapshotReader
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun interface MonotonicClock {
    fun nowNanos(): Long
}

internal data class ForegroundCharacterState(
    val foregroundCharacterId: Long? = null,
    val lastForeground: ForegroundWindowSnapshot? = null,
    val diagnostic: String = "Waiting for a foreground EVE client",
    val antiFlappingActive: Boolean = false,
    val manualBindingCount: Int = 0,
)

internal sealed interface ManualWindowBindingResult {
    data object Bound : ManualWindowBindingResult
    data class Rejected(val reason: String) : ManualWindowBindingResult
}

/** Maps strict foreground-window identity to a temporary foreground tracked character. */
internal class ForegroundCharacterCoordinator(
    private val monitor: ForegroundWindowMonitor = WindowsForegroundWindowMonitor(),
    private val readSnapshot: (Long) -> ForegroundWindowSnapshot = WindowsForegroundWindowSnapshotReader()::read,
    private val clock: MonotonicClock = MonotonicClock(System::nanoTime),
    private val isSessionAlive: (WindowSessionIdentity) -> Boolean = ::isProcessSessionAlive,
    private val runStabilityTimer: Boolean = true,
) : AutoCloseable {
    private val lock = Any()
    private val mutableState = MutableStateFlow(ForegroundCharacterState())
    private var charactersById = emptyMap<Long, TrackedCharacterSnapshot>()
    private var charactersByExactName = emptyMap<String, TrackedCharacterSnapshot>()
    private val manualBindings = mutableMapOf<WindowSessionIdentity, Long>()
    private var lastTransition: AcceptedTransition? = null
    private var pendingReversal: PendingReversal? = null
    private var stabilityTimer: ScheduledExecutorService? = null
    private var started = false

    val state: StateFlow<ForegroundCharacterState> = mutableState.asStateFlow()

    fun start(onFailure: (Throwable) -> Unit = {}) {
        synchronized(lock) {
            check(!started) { "Foreground character coordinator is already started" }
            started = true
        }
        monitor.start(::onForegroundEvent, onFailure)
        if (runStabilityTimer) {
            stabilityTimer = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "foreground-character-stability").apply { isDaemon = true }
            }.also { timer ->
                timer.scheduleWithFixedDelay(
                    { runCatching(::reevaluateStability).onFailure(onFailure) },
                    STABILITY_TICK_MILLIS,
                    STABILITY_TICK_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    fun updateCharacters(characters: List<TrackedCharacterSnapshot>) = synchronized(lock) {
        val eligible = characters.filter(::isEligible)
        charactersById = eligible.associateBy(TrackedCharacterSnapshot::characterId)
        charactersByExactName = eligible.associateBy(TrackedCharacterSnapshot::characterName)
        manualBindings.entries.removeIf { (_, characterId) -> characterId !in charactersById }
        val current = mutableState.value.foregroundCharacterId
        if (current != null && current !in charactersById) {
            lastTransition = null
            pendingReversal = null
            mutableState.value = mutableState.value.copy(
                foregroundCharacterId = null,
                diagnostic = "Previously followed character is no longer authorized for tracking",
                antiFlappingActive = false,
                manualBindingCount = manualBindings.size,
            )
        } else {
            mutableState.value = mutableState.value.copy(manualBindingCount = manualBindings.size)
        }
        mutableState.value.lastForeground?.let(::evaluateSnapshotLocked)
    }

    fun bindCurrentWindow(characterId: Long): ManualWindowBindingResult = synchronized(lock) {
        if (characterId !in charactersById) {
            return@synchronized ManualWindowBindingResult.Rejected("Character is not authorized for tracking")
        }
        val foreground = mutableState.value.lastForeground
            ?: return@synchronized ManualWindowBindingResult.Rejected("No foreground window has been observed")
        if (foreground.classification !in EVE_GAME_CLASSIFICATIONS) {
            return@synchronized ManualWindowBindingResult.Rejected(
                "The foreground window is not a verified EVE game client",
            )
        }
        val session = foreground.sessionIdentity
            ?: return@synchronized ManualWindowBindingResult.Rejected("The EVE client session identity is incomplete")
        removeReusedHandleBindings(session)
        manualBindings[session] = characterId
        acceptCandidateLocked(characterId, "Following manual EVE client session binding", bypassStability = true)
        mutableState.value = mutableState.value.copy(manualBindingCount = manualBindings.size)
        ManualWindowBindingResult.Bound
    }

    internal fun onForegroundEvent(event: ForegroundWindowEvent) {
        val snapshot = readSnapshot(event.hwnd)
        synchronized(lock) { evaluateSnapshotLocked(snapshot) }
    }

    internal fun reevaluateStability() = synchronized(lock) {
        purgeDeadBindings()
        val pending = pendingReversal ?: return@synchronized
        if (clock.nowNanos() >= pending.stableAfterNanos) {
            pendingReversal = null
            acceptCandidateLocked(pending.characterId, pending.diagnostic, bypassStability = true)
        }
    }

    private fun evaluateSnapshotLocked(snapshot: ForegroundWindowSnapshot) {
        purgeDeadBindings()
        snapshot.sessionIdentity?.let(::removeReusedHandleBindings)
        val candidate = when (snapshot.classification) {
            ForegroundWindowClassification.EVE_GAME_CHARACTER,
            ForegroundWindowClassification.EVE_GAME_UNKNOWN_CHARACTER,
            -> {
                val boundId = snapshot.sessionIdentity?.let(manualBindings::get)
                when {
                    boundId != null && boundId in charactersById -> boundId
                    snapshot.classification == ForegroundWindowClassification.EVE_GAME_CHARACTER ->
                        snapshot.characterName?.let(charactersByExactName::get)?.characterId
                    else -> null
                }
            }
            else -> null
        }
        val diagnostic = when {
            candidate != null && snapshot.sessionIdentity?.let(manualBindings::containsKey) == true ->
                "Matched a manual EVE client session binding"
            candidate != null -> "Matched foreground EVE character exactly"
            snapshot.classification == ForegroundWindowClassification.EVE_GAME_CHARACTER ->
                "Foreground EVE character is not authorized for tracking; retaining the previous character"
            snapshot.classification == ForegroundWindowClassification.EVE_GAME_UNKNOWN_CHARACTER ->
                "Foreground EVE client character is unknown; retaining the previous character"
            snapshot.classification == ForegroundWindowClassification.EVE_LAUNCHER ->
                "EVE Launcher is foreground; retaining the previous character"
            snapshot.classification == ForegroundWindowClassification.NON_EVE ->
                "Non-EVE window is foreground; retaining the previous character"
            else -> "Foreground window identity is uncertain; retaining the previous character"
        }
        mutableState.value = mutableState.value.copy(lastForeground = snapshot)
        if (candidate != null) {
            acceptCandidateLocked(candidate, diagnostic)
        } else {
            pendingReversal = null
            mutableState.value = mutableState.value.copy(
                diagnostic = diagnostic,
                antiFlappingActive = false,
                manualBindingCount = manualBindings.size,
            )
        }
    }

    private fun acceptCandidateLocked(characterId: Long, diagnostic: String, bypassStability: Boolean = false) {
        val current = mutableState.value.foregroundCharacterId
        if (current == characterId) {
            pendingReversal = null
            mutableState.value = mutableState.value.copy(
                diagnostic = diagnostic,
                antiFlappingActive = false,
                manualBindingCount = manualBindings.size,
            )
            return
        }
        val now = clock.nowNanos()
        val transition = lastTransition
        val rapidReversal = !bypassStability && transition != null &&
            transition.fromCharacterId == characterId && transition.toCharacterId == current &&
            now - transition.acceptedAtNanos <= REVERSAL_WINDOW_NANOS
        if (rapidReversal) {
            val existing = pendingReversal
            pendingReversal = if (existing?.characterId == characterId) existing else {
                PendingReversal(characterId, now + STABLE_FOR_NANOS, diagnostic)
            }
            mutableState.value = mutableState.value.copy(
                diagnostic = "Rapid client reversal detected; waiting briefly for a stable foreground",
                antiFlappingActive = true,
                manualBindingCount = manualBindings.size,
            )
            return
        }
        pendingReversal = null
        if (current != null) lastTransition = AcceptedTransition(current, characterId, now)
        mutableState.value = mutableState.value.copy(
            foregroundCharacterId = characterId,
            diagnostic = diagnostic,
            antiFlappingActive = false,
            manualBindingCount = manualBindings.size,
        )
    }

    private fun removeReusedHandleBindings(current: WindowSessionIdentity) {
        manualBindings.keys.removeIf { previous -> previous.hwnd == current.hwnd && previous != current }
    }

    private fun purgeDeadBindings() {
        if (manualBindings.keys.removeIf { !isSessionAlive(it) }) {
            mutableState.value = mutableState.value.copy(manualBindingCount = manualBindings.size)
        }
    }

    override fun close() {
        stabilityTimer?.shutdownNow()
        stabilityTimer = null
        monitor.close()
        synchronized(lock) { started = false }
    }

    private data class AcceptedTransition(
        val fromCharacterId: Long,
        val toCharacterId: Long,
        val acceptedAtNanos: Long,
    )

    private data class PendingReversal(
        val characterId: Long,
        val stableAfterNanos: Long,
        val diagnostic: String,
    )

    private companion object {
        val EVE_GAME_CLASSIFICATIONS = setOf(
            ForegroundWindowClassification.EVE_GAME_CHARACTER,
            ForegroundWindowClassification.EVE_GAME_UNKNOWN_CHARACTER,
        )
        const val STABILITY_TICK_MILLIS = 100L
        val STABLE_FOR_NANOS = TimeUnit.MILLISECONDS.toNanos(350)
        val REVERSAL_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(800)

        fun isEligible(character: TrackedCharacterSnapshot): Boolean =
            character.trackingEnabled && character.authorizationState == TrackedCharacterAuthorizationState.CONNECTED

        fun isProcessSessionAlive(session: WindowSessionIdentity): Boolean = runCatching {
            val process = ProcessHandle.of(session.processId).orElse(null) ?: return@runCatching false
            process.isAlive && process.info().startInstant().orElse(null) == session.processStartTime
        }.getOrDefault(false)
    }
}
