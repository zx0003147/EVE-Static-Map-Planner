package dev.evestaticmapplanner.platform.windows.shortcut

import com.sun.jna.platform.win32.WinUser
import dev.evestaticmapplanner.shortcut.KeyboardShortcut
import dev.evestaticmapplanner.shortcut.PushToTalkActivationResult
import dev.evestaticmapplanner.shortcut.PushToTalkListener
import dev.evestaticmapplanner.shortcut.ShortcutKey
import dev.evestaticmapplanner.shortcut.ShortcutKeyAction
import dev.evestaticmapplanner.shortcut.ShortcutModifier
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WindowsGlobalPushToTalkServiceTest {
    @Test
    fun `chord uses main-key edges ignores repeats and releases when a required modifier is released`() {
        val api = FakeWindowsKeyboardHookApi()
        val listener = RecordingListener()
        val service = service(api)
        try {
            assertEquals(
                PushToTalkActivationResult.Active,
                service.activate(
                    KeyboardShortcut(
                        ShortcutKey.SPACE,
                        setOf(ShortcutModifier.CTRL, ShortcutModifier.ALT),
                    ),
                    listener,
                ),
            )

            api.emit(VK_LCONTROL, ShortcutKeyAction.DOWN)
            api.emit(VK_RMENU, ShortcutKeyAction.DOWN)
            api.emit(VK_A, ShortcutKeyAction.DOWN)
            api.emit(VK_A, ShortcutKeyAction.UP)
            api.emit(VK_SPACE, ShortcutKeyAction.DOWN)
            api.emit(VK_SPACE, ShortcutKeyAction.DOWN)
            assertEquals(listOf("pressed"), listener.events)

            api.emit(VK_LCONTROL, ShortcutKeyAction.UP)
            api.emit(VK_SPACE, ShortcutKeyAction.UP)
            api.emit(VK_RMENU, ShortcutKeyAction.UP)
            assertEquals(listOf("pressed", "released"), listener.events)
        } finally {
            service.close()
        }
    }

    @Test
    fun `reactivation replaces the previous hook and repeated deactivation stays safe`() {
        val api = FakeWindowsKeyboardHookApi()
        val service = service(api)
        val first = RecordingListener()
        val second = RecordingListener()

        service.activate(KeyboardShortcut(ShortcutKey.F9), first)
        service.activate(KeyboardShortcut(ShortcutKey.SPACE), second)
        api.emit(VK_SPACE, ShortcutKeyAction.DOWN)
        api.emit(VK_SPACE, ShortcutKeyAction.UP)
        service.deactivate()
        service.deactivate()

        assertTrue(first.events.isEmpty())
        assertEquals(listOf("pressed", "released"), second.events)
        assertEquals(2, api.installCount)
        assertEquals(2, api.uninstallCount)
        assertEquals(2, api.postQuitCount)
    }

    @Test
    fun `main key pressed before modifiers blocks the complete physical cycle`() {
        val api = FakeWindowsKeyboardHookApi()
        val listener = RecordingListener()
        val service = service(api)
        try {
            service.activate(
                KeyboardShortcut(ShortcutKey.SPACE, setOf(ShortcutModifier.CTRL)),
                listener,
            )
            api.emit(VK_SPACE, ShortcutKeyAction.DOWN)
            api.emit(VK_LCONTROL, ShortcutKeyAction.DOWN)
            api.emit(VK_SPACE, ShortcutKeyAction.UP)
            api.emit(VK_LCONTROL, ShortcutKeyAction.UP)
            assertTrue(listener.events.isEmpty())

            api.emit(VK_RCONTROL, ShortcutKeyAction.DOWN)
            api.emit(VK_SPACE, ShortcutKeyAction.DOWN)
            api.emit(VK_SPACE, ShortcutKeyAction.UP)
            api.emit(VK_RCONTROL, ShortcutKeyAction.UP)
            assertEquals(listOf("pressed", "released"), listener.events)
        } finally {
            service.close()
        }
    }

    @Test
    fun `a held modifier can be reused across consecutive main-key cycles`() {
        val api = FakeWindowsKeyboardHookApi()
        val listener = RecordingListener()
        val service = service(api)
        try {
            service.activate(
                KeyboardShortcut(ShortcutKey.SPACE, setOf(ShortcutModifier.CTRL)),
                listener,
            )
            api.emit(VK_LCONTROL, ShortcutKeyAction.DOWN)
            api.emit(VK_SPACE, ShortcutKeyAction.DOWN)
            api.emit(VK_SPACE, ShortcutKeyAction.UP)
            api.emit(VK_SPACE, ShortcutKeyAction.DOWN)
            api.emit(VK_SPACE, ShortcutKeyAction.UP)
            api.emit(VK_LCONTROL, ShortcutKeyAction.UP)

            assertEquals(listOf("pressed", "released", "pressed", "released"), listener.events)
        } finally {
            service.close()
        }
    }

    @Test
    fun `deactivate while held emits cancellation and cleans up exactly once`() {
        val api = FakeWindowsKeyboardHookApi()
        val listener = RecordingListener()
        val service = service(api)

        service.activate(KeyboardShortcut(ShortcutKey.F9), listener)
        api.emit(VK_F9, ShortcutKeyAction.DOWN)
        service.deactivate()
        service.deactivate()

        assertEquals(listOf("pressed", "cancelled"), listener.events)
        assertEquals(1, api.installCount)
        assertEquals(1, api.uninstallCount)
        assertEquals(1, api.postQuitCount)
    }

    @Test
    fun `hook installation failure is returned without crashing`() {
        val service = service(FakeWindowsKeyboardHookApi(installFailure = IllegalStateException("blocked")))

        val result = service.activate(KeyboardShortcut(ShortcutKey.F9), RecordingListener())

        assertIs<PushToTalkActivationResult.Failed>(result)
        service.close()
    }

    @Test
    fun `native callback always calls next hook for handled ignored and negative events`() {
        var forwarded = 0
        val events = mutableListOf<WindowsKeyboardEvent>()
        listOf(
            Triple(0, WinUser.WM_KEYDOWN, VK_F9),
            Triple(0, 0x9999, VK_F9),
            Triple(-1, WinUser.WM_KEYUP, VK_F9),
        ).forEach { (code, message, key) ->
            dispatchWindowsKeyboardHookEvent(code, message, key, events::add) { ++forwarded }
        }

        assertEquals(3, forwarded)
        assertEquals(listOf(WindowsKeyboardEvent(VK_F9, ShortcutKeyAction.DOWN)), events)
    }

    private fun service(api: FakeWindowsKeyboardHookApi) = WindowsGlobalPushToTalkService(
        apiFactory = { api },
        eventDispatcher = { it() },
        platformIsWindows = { true },
    )

    private class RecordingListener : PushToTalkListener {
        val events = mutableListOf<String>()
        override fun onPressed() { events += "pressed" }
        override fun onReleased() { events += "released" }
        override fun onCancelled() { events += "cancelled" }
        override fun onUnavailable(failure: Throwable) { events += "unavailable" }
    }

    private class FakeWindowsKeyboardHookApi(
        private val installFailure: Throwable? = null,
    ) : WindowsKeyboardHookApi {
        private val messages = LinkedBlockingQueue<Int>()
        private var callback: ((WindowsKeyboardEvent) -> Unit)? = null
        var installCount = 0
        var uninstallCount = 0
        var postQuitCount = 0

        override fun currentThreadId() = 42
        override fun prepareMessageQueue() = Unit
        override fun install(onEvent: (WindowsKeyboardEvent) -> Unit) {
            installFailure?.let { throw it }
            installCount++
            callback = onEvent
        }
        override fun getMessage(): Int = messages.take()
        override fun postQuit(threadId: Int) {
            postQuitCount++
            messages.put(0)
        }
        override fun uninstall() {
            if (installCount > uninstallCount) uninstallCount++
            callback = null
        }
        fun emit(virtualKey: Int, action: ShortcutKeyAction) {
            checkNotNull(callback)(WindowsKeyboardEvent(virtualKey, action))
        }
    }

    private companion object {
        const val VK_SPACE = 0x20
        const val VK_A = 0x41
        const val VK_F9 = 0x78
        const val VK_LCONTROL = 0xA2
        const val VK_RCONTROL = 0xA3
        const val VK_RMENU = 0xA5
    }
}
