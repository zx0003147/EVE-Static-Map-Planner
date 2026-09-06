package dev.evestaticmapplanner.minimap

import dev.evestaticmapplanner.preferences.MiniMapInteractionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiniMapHudControllerTest {
    @Test
    fun `successful hotkey registration enables lock and dispatches recovery toggle`() {
        val hotkey = FakeHotkey()
        var toggles = 0
        val controller = MiniMapHudController(hotkey)

        controller.start { toggles++ }
        hotkey.press()

        assertEquals(MiniMapRecoveryHotkeyStatus.REGISTERED, controller.state.value.hotkeyStatus)
        assertTrue(controller.state.value.canLock)
        assertEquals(MiniMapInteractionMode.HUD_LOCKED, controller.safeMode(MiniMapInteractionMode.HUD_LOCKED))
        assertEquals(1, toggles)
        controller.close()
        assertEquals(1, hotkey.closeCount)
        assertEquals(MiniMapRecoveryHotkeyStatus.CLOSED, controller.state.value.hotkeyStatus)
    }

    @Test
    fun `registration failure disables locked mode and exposes a diagnostic`() {
        val hotkey = FakeHotkey(startFailure = IllegalStateException("already in use"))
        val controller = MiniMapHudController(hotkey)

        controller.start {}

        assertEquals(MiniMapRecoveryHotkeyStatus.FAILED, controller.state.value.hotkeyStatus)
        assertFalse(controller.state.value.canLock)
        assertEquals(MiniMapInteractionMode.INTERACTIVE, controller.safeMode(MiniMapInteractionMode.HUD_LOCKED))
        assertTrue(controller.state.value.diagnostic.orEmpty().contains("already in use"))
        controller.close()
        assertEquals(1, hotkey.closeCount)
    }

    @Test
    fun `runtime hotkey failure revokes locking and cleanup remains deterministic`() {
        val hotkey = FakeHotkey()
        val controller = MiniMapHudController(hotkey)
        controller.start {}

        hotkey.fail(IllegalStateException("message loop ended"))

        assertEquals(MiniMapRecoveryHotkeyStatus.FAILED, controller.state.value.hotkeyStatus)
        assertEquals(MiniMapInteractionMode.INTERACTIVE, controller.safeMode(MiniMapInteractionMode.HUD_LOCKED))
        controller.close()
        assertEquals(1, hotkey.closeCount)
    }

    @Test
    fun `capability lifecycle unregisters the hotkey on unload and starts a replacement on reload`() {
        val loadedHotkey = FakeHotkey()
        val loadedController = MiniMapHudController(loadedHotkey)
        loadedController.start {}

        loadedController.close()

        val reloadedHotkey = FakeHotkey()
        val reloadedController = MiniMapHudController(reloadedHotkey)
        reloadedController.start {}

        assertEquals(1, loadedHotkey.startCount)
        assertEquals(1, loadedHotkey.closeCount)
        assertEquals(1, reloadedHotkey.startCount)
        assertEquals(MiniMapRecoveryHotkeyStatus.REGISTERED, reloadedController.state.value.hotkeyStatus)
        reloadedController.close()
    }

    private class FakeHotkey(
        private val startFailure: Throwable? = null,
    ) : MiniMapGlobalHotkey {
        private var pressed: (() -> Unit)? = null
        private var failed: ((Throwable) -> Unit)? = null
        var closeCount = 0
            private set
        var startCount = 0
            private set

        override fun start(onPressed: () -> Unit, onFailure: (Throwable) -> Unit): Result<Unit> {
            startCount++
            pressed = onPressed
            failed = onFailure
            return startFailure?.let { Result.failure(it) } ?: Result.success(Unit)
        }

        fun press() = checkNotNull(pressed).invoke()
        fun fail(failure: Throwable) = checkNotNull(failed).invoke(failure)
        override fun close() { closeCount++ }
    }
}
