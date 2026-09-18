package dev.evestaticmapplanner.platform.windows.shortcut

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import dev.evestaticmapplanner.shortcut.GlobalPushToTalkService
import dev.evestaticmapplanner.shortcut.KeyboardShortcut
import dev.evestaticmapplanner.shortcut.PushToTalkActivationResult
import dev.evestaticmapplanner.shortcut.PushToTalkChordStateMachine
import dev.evestaticmapplanner.shortcut.PushToTalkListener
import dev.evestaticmapplanner.shortcut.PushToTalkTransition
import dev.evestaticmapplanner.shortcut.ShortcutKey
import dev.evestaticmapplanner.shortcut.ShortcutKeyAction
import dev.evestaticmapplanner.shortcut.ShortcutModifier
import dev.evestaticmapplanner.shortcut.ShortcutVirtualKeyMapping
import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class WindowsKeyboardEvent(
    val virtualKey: Int,
    val action: ShortcutKeyAction,
)

internal interface WindowsKeyboardHookApi {
    fun currentThreadId(): Int
    fun prepareMessageQueue()
    fun install(onEvent: (WindowsKeyboardEvent) -> Unit)
    fun getMessage(): Int
    fun postQuit(threadId: Int)
    fun uninstall()
}

internal class JnaWindowsKeyboardHookApi : WindowsKeyboardHookApi {
    private var hook: WinUser.HHOOK? = null
    private var callback: WinUser.LowLevelKeyboardProc? = null
    private val message = WinUser.MSG()

    override fun currentThreadId(): Int = Kernel32.INSTANCE.GetCurrentThreadId()

    override fun prepareMessageQueue() {
        User32.INSTANCE.PeekMessage(message, null, 0, 0, PM_NOREMOVE)
    }

    override fun install(onEvent: (WindowsKeyboardEvent) -> Unit) {
        check(hook == null) { "Windows keyboard hook is already installed" }
        val installedHook = AtomicReference<WinUser.HHOOK?>()
        val installedCallback = WinUser.LowLevelKeyboardProc { code, messageParam, info ->
            dispatchWindowsKeyboardHookEvent(
                code = code,
                message = messageParam.toInt(),
                virtualKey = info.vkCode,
                onEvent = onEvent,
            ) {
                User32.INSTANCE.CallNextHookEx(
                    installedHook.get(),
                    code,
                    messageParam,
                    WinDef.LPARAM(Pointer.nativeValue(info.pointer)),
                )
            }
        }
        val installed = User32.INSTANCE.SetWindowsHookEx(
            WinUser.WH_KEYBOARD_LL,
            installedCallback,
            Kernel32.INSTANCE.GetModuleHandle(null),
            0,
        ) ?: error("SetWindowsHookEx failed with Win32 error ${Native.getLastError()}")
        installedHook.set(installed)
        callback = installedCallback
        hook = installed
    }

    override fun getMessage(): Int = User32.INSTANCE.GetMessage(message, null, 0, 0)

    override fun postQuit(threadId: Int) {
        User32.INSTANCE.PostThreadMessage(
            threadId,
            WinUser.WM_QUIT,
            WinDef.WPARAM(0),
            WinDef.LPARAM(0),
        )
    }

    override fun uninstall() {
        hook?.let(User32.INSTANCE::UnhookWindowsHookEx)
        hook = null
        callback = null
    }

    private companion object {
        const val PM_NOREMOVE = 0x0000
    }
}

internal fun <T> dispatchWindowsKeyboardHookEvent(
    code: Int,
    message: Int,
    virtualKey: Int,
    onEvent: (WindowsKeyboardEvent) -> Unit,
    callNext: () -> T,
): T {
    if (code >= 0) {
        val action = when (message) {
            WinUser.WM_KEYDOWN, WinUser.WM_SYSKEYDOWN -> ShortcutKeyAction.DOWN
            WinUser.WM_KEYUP, WinUser.WM_SYSKEYUP -> ShortcutKeyAction.UP
            else -> null
        }
        if (action != null) runCatching { onEvent(WindowsKeyboardEvent(virtualKey, action)) }
    }
    return callNext()
}

internal object WindowsShortcutVirtualKeyMapping : ShortcutVirtualKeyMapping {
    override fun key(key: ShortcutKey): Int = when (key) {
        in ShortcutKey.A..ShortcutKey.Z -> VK_A + (key.ordinal - ShortcutKey.A.ordinal)
        in ShortcutKey.DIGIT_0..ShortcutKey.DIGIT_9 -> VK_0 + (key.ordinal - ShortcutKey.DIGIT_0.ordinal)
        in ShortcutKey.F1..ShortcutKey.F24 -> VK_F1 + (key.ordinal - ShortcutKey.F1.ordinal)
        ShortcutKey.SPACE -> VK_SPACE
        ShortcutKey.TAB -> VK_TAB
        ShortcutKey.ENTER -> VK_RETURN
        ShortcutKey.BACKSPACE -> VK_BACK
        ShortcutKey.DELETE -> VK_DELETE
        ShortcutKey.INSERT -> VK_INSERT
        ShortcutKey.ARROW_UP -> VK_UP
        ShortcutKey.ARROW_DOWN -> VK_DOWN
        ShortcutKey.ARROW_LEFT -> VK_LEFT
        ShortcutKey.ARROW_RIGHT -> VK_RIGHT
        ShortcutKey.HOME -> VK_HOME
        ShortcutKey.END -> VK_END
        ShortcutKey.PAGE_UP -> VK_PRIOR
        ShortcutKey.PAGE_DOWN -> VK_NEXT
        else -> error("Unsupported keyboard shortcut key: $key")
    }

    override fun modifier(virtualKey: Int): ShortcutModifier? = when (virtualKey) {
        VK_CONTROL, VK_LCONTROL, VK_RCONTROL -> ShortcutModifier.CTRL
        VK_MENU, VK_LMENU, VK_RMENU -> ShortcutModifier.ALT
        VK_SHIFT, VK_LSHIFT, VK_RSHIFT -> ShortcutModifier.SHIFT
        VK_LWIN, VK_RWIN -> ShortcutModifier.META
        else -> null
    }

    private const val VK_BACK = 0x08
    private const val VK_TAB = 0x09
    private const val VK_RETURN = 0x0D
    private const val VK_SHIFT = 0x10
    private const val VK_CONTROL = 0x11
    private const val VK_MENU = 0x12
    private const val VK_SPACE = 0x20
    private const val VK_PRIOR = 0x21
    private const val VK_NEXT = 0x22
    private const val VK_END = 0x23
    private const val VK_HOME = 0x24
    private const val VK_LEFT = 0x25
    private const val VK_UP = 0x26
    private const val VK_RIGHT = 0x27
    private const val VK_DOWN = 0x28
    private const val VK_INSERT = 0x2D
    private const val VK_DELETE = 0x2E
    private const val VK_0 = 0x30
    private const val VK_A = 0x41
    private const val VK_LWIN = 0x5B
    private const val VK_RWIN = 0x5C
    private const val VK_F1 = 0x70
    private const val VK_LSHIFT = 0xA0
    private const val VK_RSHIFT = 0xA1
    private const val VK_LCONTROL = 0xA2
    private const val VK_RCONTROL = 0xA3
    private const val VK_LMENU = 0xA4
    private const val VK_RMENU = 0xA5
}

internal class WindowsGlobalPushToTalkService(
    private val apiFactory: () -> WindowsKeyboardHookApi = ::JnaWindowsKeyboardHookApi,
    private val eventDispatcher: (() -> Unit) -> Unit = { action -> EventQueue.invokeLater(action) },
    private val diagnosticSink: (String, Throwable?) -> Unit = { _, _ -> },
    private val platformIsWindows: () -> Boolean = Platform::isWindows,
) : GlobalPushToTalkService {
    private val lifecycleLock = Any()
    private val eventLock = Any()
    private val activationCounter = AtomicLong()
    private var messageThread: Thread? = null
    private var hookApi: WindowsKeyboardHookApi? = null
    private var listener: PushToTalkListener? = null
    private var stateMachine: PushToTalkChordStateMachine? = null
    private var activeActivation = 0L

    @Volatile
    private var messageThreadId = 0

    override fun activate(
        shortcut: KeyboardShortcut,
        listener: PushToTalkListener,
    ): PushToTalkActivationResult = synchronized(lifecycleLock) {
        deactivateLocked()
        if (!platformIsWindows()) return@synchronized PushToTalkActivationResult.Unsupported

        val activation = activationCounter.incrementAndGet()
        val api = apiFactory()
        val started = CountDownLatch(1)
        val startupFailure = AtomicReference<Throwable?>()
        synchronized(eventLock) {
            this.listener = listener
            stateMachine = PushToTalkChordStateMachine(shortcut, WindowsShortcutVirtualKeyMapping)
            activeActivation = activation
        }
        val thread = Thread(
            { runMessageLoop(api, activation, started, startupFailure) },
            "global-push-to-talk-hook",
        ).apply { isDaemon = true }
        hookApi = api
        messageThread = thread
        thread.start()
        if (!started.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            val failure = IllegalStateException("Timed out while installing the Push-to-Talk keyboard hook")
            diagnosticSink("hook installation failed", failure)
            deactivateLocked()
            return@synchronized PushToTalkActivationResult.Failed(failure)
        }
        startupFailure.get()?.let { failure ->
            diagnosticSink("hook installation failed", failure)
            deactivateLocked()
            return@synchronized PushToTalkActivationResult.Failed(failure)
        }
        diagnosticSink("PTT listener activated", null)
        PushToTalkActivationResult.Active
    }

    private fun runMessageLoop(
        api: WindowsKeyboardHookApi,
        activation: Long,
        started: CountDownLatch,
        startupFailure: AtomicReference<Throwable?>,
    ) {
        var startupComplete = false
        try {
            messageThreadId = api.currentThreadId()
            api.prepareMessageQueue()
            api.install { event -> handleKeyboardEvent(activation, event) }
            startupComplete = true
            started.countDown()
            while (true) {
                when (api.getMessage()) {
                    -1 -> error("GetMessage failed with Win32 error ${Native.getLastError()}")
                    0 -> break
                }
            }
        } catch (failure: Throwable) {
            if (startupComplete) {
                dispatchIfCurrent(activation) { it.onUnavailable(failure) }
                diagnosticSink("hook installation failed", failure)
            } else {
                startupFailure.compareAndSet(null, failure)
            }
            started.countDown()
        } finally {
            api.uninstall()
            messageThreadId = 0
        }
    }

    private fun handleKeyboardEvent(activation: Long, event: WindowsKeyboardEvent) {
        val transition = synchronized(eventLock) {
            if (activeActivation != activation) null else stateMachine?.handle(event.virtualKey, event.action)
        } ?: return
        dispatchIfCurrent(activation) { current ->
            when (transition) {
                PushToTalkTransition.PRESSED -> {
                    diagnosticSink("PTT pressed", null)
                    current.onPressed()
                }
                PushToTalkTransition.RELEASED -> {
                    diagnosticSink("PTT released", null)
                    current.onReleased()
                }
            }
        }
    }

    private fun dispatchIfCurrent(activation: Long, action: (PushToTalkListener) -> Unit) {
        eventDispatcher {
            val current = synchronized(eventLock) {
                listener.takeIf { activeActivation == activation }
            }
            current?.let(action)
        }
    }

    override fun deactivate() = synchronized(lifecycleLock) { deactivateLocked() }

    private fun deactivateLocked() {
        val oldListener: PushToTalkListener?
        val wasPressed: Boolean
        synchronized(eventLock) {
            oldListener = listener
            wasPressed = stateMachine?.reset() == true
            listener = null
            stateMachine = null
            activeActivation = activationCounter.incrementAndGet()
        }
        if (wasPressed && oldListener != null) eventDispatcher { oldListener.onCancelled() }

        val thread = messageThread
        val api = hookApi
        messageThread = null
        hookApi = null
        val threadId = messageThreadId
        if (threadId != 0) api?.postQuit(threadId)
        thread?.join(STOP_TIMEOUT_MILLIS)
        if (thread?.isAlive == true) {
            diagnosticSink("hook installation failed", IllegalStateException("Push-to-Talk hook thread did not stop"))
        } else if (thread != null) {
            diagnosticSink("PTT listener deactivated", null)
        }
    }

    override fun close() = deactivate()

    private companion object {
        const val START_TIMEOUT_SECONDS = 5L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
