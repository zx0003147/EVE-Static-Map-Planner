package dev.evestaticmapplanner.platform.windows.minimaphud

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import dev.evestaticmapplanner.minimap.MiniMapGlobalHotkey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Thread-owned RegisterHotKey registration with a blocking Win32 message loop (no polling). */
internal class WindowsMiniMapGlobalHotkey : MiniMapGlobalHotkey {
    private val lifecycleLock = Any()
    private var messageThread: Thread? = null

    @Volatile
    private var messageThreadId: Int = 0

    override fun start(onPressed: () -> Unit, onFailure: (Throwable) -> Unit): Result<Unit> = synchronized(lifecycleLock) {
        runCatching {
            check(Platform.isWindows()) { "Global hotkeys are only supported on Windows" }
            check(messageThread == null) { "Mini-map recovery hotkey is already registered" }
            val started = CountDownLatch(1)
            val startupFailure = AtomicReference<Throwable?>()
            val thread = Thread(
                { runMessageLoop(started, startupFailure, onPressed, onFailure) },
                "mini-map-recovery-hotkey",
            ).apply { isDaemon = true }
            messageThread = thread
            thread.start()
            if (!started.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                close()
                error("Timed out while registering the Mini-map recovery hotkey")
            }
            startupFailure.get()?.let { failure ->
                close()
                throw failure
            }
            Unit
        }
    }

    private fun runMessageLoop(
        started: CountDownLatch,
        startupFailure: AtomicReference<Throwable?>,
        onPressed: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        var registered = false
        var startupComplete = false
        try {
            messageThreadId = Kernel32.INSTANCE.GetCurrentThreadId()
            User32.INSTANCE.PeekMessage(WinUser.MSG(), null, 0, 0, PM_NOREMOVE)
            if (!User32.INSTANCE.RegisterHotKey(null, HOTKEY_ID, MODIFIERS, KEY_M)) {
                error("RegisterHotKey failed with Win32 error ${Native.getLastError()}")
            }
            registered = true
            startupComplete = true
            started.countDown()

            val message = WinUser.MSG()
            while (true) {
                when (User32.INSTANCE.GetMessage(message, null, 0, 0)) {
                    -1 -> error("GetMessage failed with Win32 error ${Native.getLastError()}")
                    0 -> break
                    else -> if (message.message == WinUser.WM_HOTKEY && message.wParam.toInt() == HOTKEY_ID) {
                        onPressed()
                    }
                }
            }
        } catch (failure: Throwable) {
            if (startupComplete) onFailure(failure) else startupFailure.compareAndSet(null, failure)
            started.countDown()
        } finally {
            if (registered) User32.INSTANCE.UnregisterHotKey(null, HOTKEY_ID)
            messageThreadId = 0
        }
    }

    override fun close() {
        val thread = synchronized(lifecycleLock) {
            val current = messageThread
            messageThread = null
            current
        }
        val threadId = messageThreadId
        if (threadId != 0) {
            User32.INSTANCE.PostThreadMessage(
                threadId,
                WinUser.WM_QUIT,
                WinDef.WPARAM(0),
                WinDef.LPARAM(0),
            )
        }
        thread?.join(STOP_TIMEOUT_MILLIS)
        check(thread?.isAlive != true) { "Mini-map recovery hotkey thread did not stop" }
    }

    private companion object {
        const val HOTKEY_ID = 0x4556
        const val KEY_M = 0x4D
        const val MODIFIERS = WinUser.MOD_CONTROL or WinUser.MOD_SHIFT
        const val PM_NOREMOVE = 0x0000
        const val START_TIMEOUT_SECONDS = 5L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
