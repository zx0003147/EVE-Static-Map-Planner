package dev.evestaticmapplanner.platform.windows.shortcut

import com.sun.jna.Platform
import dev.evestaticmapplanner.shortcut.KeyboardShortcut
import dev.evestaticmapplanner.shortcut.PushToTalkActivationResult
import dev.evestaticmapplanner.shortcut.PushToTalkListener
import dev.evestaticmapplanner.shortcut.ShortcutKey
import kotlin.test.Test
import kotlin.test.assertEquals

/** Opt-in real Win32 smoke; normal unit/CI runs never install a system hook. */
class WindowsGlobalPushToTalkNativeSmokeTest {
    @Test
    fun `real low-level hook installs and uninstalls`() {
        if (!Platform.isWindows() || System.getenv(NATIVE_SMOKE_ENVIRONMENT) != "true") return
        val service = WindowsGlobalPushToTalkService()
        try {
            assertEquals(
                PushToTalkActivationResult.Active,
                service.activate(KeyboardShortcut(ShortcutKey.F9), NoOpListener),
            )
        } finally {
            service.close()
        }
    }

    private object NoOpListener : PushToTalkListener {
        override fun onPressed() = Unit
        override fun onReleased() = Unit
        override fun onCancelled() = Unit
        override fun onUnavailable(failure: Throwable) = Unit
    }

    private companion object {
        const val NATIVE_SMOKE_ENVIRONMENT = "EVE_PTT_NATIVE_SMOKE"
    }
}
