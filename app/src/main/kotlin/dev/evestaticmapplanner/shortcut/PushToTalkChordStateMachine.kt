package dev.evestaticmapplanner.shortcut

internal enum class ShortcutKeyAction { DOWN, UP }

internal enum class PushToTalkTransition { PRESSED, RELEASED }

/** Pure key-edge state machine. It never consumes, logs, or stores unrelated keystrokes. */
internal class PushToTalkChordStateMachine(
    private val shortcut: KeyboardShortcut,
    private val virtualKeyMapping: ShortcutVirtualKeyMapping,
) {
    private val pressedModifierKeys = mutableSetOf<Int>()
    private var mainKeyPressed = false
    private var active = false
    private var blockedUntilReleased = false

    fun handle(virtualKey: Int, action: ShortcutKeyAction): PushToTalkTransition? {
        val modifier = virtualKeyMapping.modifier(virtualKey)
        val isMainKey = virtualKey == virtualKeyMapping.key(shortcut.key)
        if (modifier == null && !isMainKey) return null

        return when (action) {
            ShortcutKeyAction.DOWN -> handleDown(virtualKey, modifier, isMainKey)
            ShortcutKeyAction.UP -> handleUp(virtualKey, modifier, isMainKey)
        }
    }

    fun reset(): Boolean {
        val wasActive = active
        pressedModifierKeys.clear()
        mainKeyPressed = false
        active = false
        blockedUntilReleased = false
        return wasActive
    }

    private fun handleDown(
        virtualKey: Int,
        modifier: ShortcutModifier?,
        isMainKey: Boolean,
    ): PushToTalkTransition? {
        if (modifier != null) {
            pressedModifierKeys += virtualKey
            return null
        }
        if (!isMainKey || mainKeyPressed) return null
        mainKeyPressed = true
        if (blockedUntilReleased) return null
        if (!requiredModifiersPressed()) {
            blockedUntilReleased = true
            return null
        }
        active = true
        return PushToTalkTransition.PRESSED
    }

    private fun handleUp(
        virtualKey: Int,
        modifier: ShortcutModifier?,
        isMainKey: Boolean,
    ): PushToTalkTransition? {
        if (modifier != null) pressedModifierKeys -= virtualKey
        if (isMainKey) mainKeyPressed = false

        val releasedRequiredModifier = modifier != null && modifier in shortcut.modifiers &&
            pressedModifierKeys.none { virtualKeyMapping.modifier(it) == modifier }
        val shouldRelease = active && (isMainKey || releasedRequiredModifier)
        if (shouldRelease) {
            active = false
        }
        if (blockedUntilReleased && !mainKeyPressed && shortcut.modifiers.none(::isModifierPressed)) {
            blockedUntilReleased = false
        }
        return if (shouldRelease) PushToTalkTransition.RELEASED else null
    }

    private fun requiredModifiersPressed(): Boolean = shortcut.modifiers.all(::isModifierPressed)

    private fun isModifierPressed(modifier: ShortcutModifier): Boolean =
        pressedModifierKeys.any { virtualKeyMapping.modifier(it) == modifier }
}

internal interface ShortcutVirtualKeyMapping {
    fun key(key: ShortcutKey): Int
    fun modifier(virtualKey: Int): ShortcutModifier?
}
