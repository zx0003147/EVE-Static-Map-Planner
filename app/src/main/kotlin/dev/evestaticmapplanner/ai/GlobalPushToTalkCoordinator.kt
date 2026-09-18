package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.shortcut.GlobalPushToTalkService
import dev.evestaticmapplanner.shortcut.KeyboardShortcut
import dev.evestaticmapplanner.shortcut.PushToTalkActivationResult
import dev.evestaticmapplanner.shortcut.PushToTalkListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class GlobalPushToTalkStatus { INACTIVE, ACTIVE, FAILED, UNSUPPORTED }

internal data class GlobalPushToTalkState(
    val status: GlobalPushToTalkStatus = GlobalPushToTalkStatus.INACTIVE,
    val diagnostic: String? = null,
)

/** Owns the application-side PTT cycle. Native input remains isolated in [GlobalPushToTalkService]. */
internal class GlobalPushToTalkCoordinator(
    private val service: GlobalPushToTalkService,
    private val voiceController: VoiceController,
) : AutoCloseable {
    private val lock = Any()
    private val mutableState = MutableStateFlow(GlobalPushToTalkState())
    private var binding: Binding? = null
    private var bindingNumber = 0L
    private var suspendedForCapture = false
    private var activeSession: PushToTalkSession? = null
    private var ignoredPhysicalCycle = false
    val state: StateFlow<GlobalPushToTalkState> = mutableState.asStateFlow()

    fun bind(
        generation: String,
        shortcut: KeyboardShortcut?,
        transcriptConsumer: (String, Boolean) -> Unit,
    ) = synchronized(lock) {
        deactivateLocked(cancelRecording = true)
        binding = Binding(++bindingNumber, generation, shortcut, transcriptConsumer)
        activateLocked()
    }

    fun unbind(generation: String) = synchronized(lock) {
        if (binding?.generation != generation) return@synchronized
        binding = null
        deactivateLocked(cancelRecording = true)
    }

    fun suspendForShortcutCapture() = synchronized(lock) {
        suspendedForCapture = true
        deactivateLocked(cancelRecording = true)
    }

    fun resumeAfterShortcutCapture() = synchronized(lock) {
        suspendedForCapture = false
        activateLocked()
    }

    private fun activateLocked() {
        val current = binding
        if (suspendedForCapture || current?.shortcut == null) {
            mutableState.value = GlobalPushToTalkState()
            return
        }
        val generation = current.generation
        val bindingId = current.id
        val result = service.activate(current.shortcut, object : PushToTalkListener {
            override fun onPressed() = handlePressed(bindingId, generation)
            override fun onReleased() = handleReleased(bindingId, generation)
            override fun onCancelled() = handleCancelled(bindingId, generation)
            override fun onUnavailable(failure: Throwable) = handleUnavailable(bindingId, generation, failure)
        })
        mutableState.value = when (result) {
            PushToTalkActivationResult.Active -> GlobalPushToTalkState(GlobalPushToTalkStatus.ACTIVE)
            PushToTalkActivationResult.Unsupported -> GlobalPushToTalkState(GlobalPushToTalkStatus.UNSUPPORTED)
            is PushToTalkActivationResult.Failed -> GlobalPushToTalkState(
                GlobalPushToTalkStatus.FAILED,
                result.failure.message,
            )
        }
    }

    private fun handlePressed(bindingId: Long, generation: String) = synchronized(lock) {
        val current = binding ?: return@synchronized
        if (current.id != bindingId || current.generation != generation) return@synchronized
        if (suspendedForCapture || activeSession != null || ignoredPhysicalCycle) return@synchronized
        val session = voiceController.beginPushToTalk { transcript, autoSend ->
            val target = synchronized(lock) {
                binding?.takeIf { it.id == bindingId && it.generation == generation }
            }
            target?.transcriptConsumer?.invoke(transcript, autoSend)
        }
        if (session == null) {
            ignoredPhysicalCycle = true
        } else {
            activeSession = session
        }
    }

    private fun handleReleased(bindingId: Long, generation: String) = synchronized(lock) {
        if (binding?.id != bindingId || binding?.generation != generation) return@synchronized
        val session = activeSession
        activeSession = null
        if (ignoredPhysicalCycle) {
            ignoredPhysicalCycle = false
            return@synchronized
        }
        session?.let(voiceController::endPushToTalk)
    }

    private fun handleCancelled(bindingId: Long, generation: String) = synchronized(lock) {
        if (binding?.id == bindingId && binding?.generation == generation) cancelActiveSessionLocked()
    }

    private fun handleUnavailable(bindingId: Long, generation: String, failure: Throwable) = synchronized(lock) {
        if (binding?.id == bindingId && binding?.generation == generation) {
            cancelActiveSessionLocked()
            mutableState.value = GlobalPushToTalkState(GlobalPushToTalkStatus.FAILED, failure.message)
        }
    }

    private fun deactivateLocked(cancelRecording: Boolean) {
        service.deactivate()
        if (cancelRecording) cancelActiveSessionLocked()
        ignoredPhysicalCycle = false
        mutableState.value = GlobalPushToTalkState()
    }

    private fun cancelActiveSessionLocked() {
        activeSession?.let(voiceController::cancelPushToTalk)
        activeSession = null
    }

    override fun close() = synchronized(lock) {
        binding = null
        suspendedForCapture = true
        deactivateLocked(cancelRecording = true)
        service.close()
    }

    private data class Binding(
        val id: Long,
        val generation: String,
        val shortcut: KeyboardShortcut?,
        val transcriptConsumer: (String, Boolean) -> Unit,
    )
}
