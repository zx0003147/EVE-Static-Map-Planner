package dev.evestaticmapplanner.shortcut

sealed interface PushToTalkActivationResult {
    data object Active : PushToTalkActivationResult
    data object Unsupported : PushToTalkActivationResult
    data class Failed(val failure: Throwable) : PushToTalkActivationResult
}

interface PushToTalkListener {
    fun onPressed()
    fun onReleased()
    fun onCancelled()
    fun onUnavailable(failure: Throwable)
}

interface GlobalPushToTalkService : AutoCloseable {
    fun activate(shortcut: KeyboardShortcut, listener: PushToTalkListener): PushToTalkActivationResult
    fun deactivate()
}

class UnsupportedGlobalPushToTalkService : GlobalPushToTalkService {
    override fun activate(
        shortcut: KeyboardShortcut,
        listener: PushToTalkListener,
    ): PushToTalkActivationResult = PushToTalkActivationResult.Unsupported

    override fun deactivate() = Unit
    override fun close() = Unit
}
