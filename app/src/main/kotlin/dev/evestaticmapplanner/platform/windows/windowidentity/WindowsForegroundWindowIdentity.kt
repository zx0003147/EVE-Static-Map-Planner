package dev.evestaticmapplanner.platform.windows.windowidentity

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class WindowsForegroundWindowSnapshotReader(
    private val ownProcessId: Long = ProcessHandle.current().pid(),
) {
    fun read(hwndValue: Long): ForegroundWindowSnapshot {
        check(Platform.isWindows()) { "Foreground window identity is only available on Windows" }
        val hwnd = WinDef.HWND(Pointer.createConstant(hwndValue))
        val windowIsValid = User32.INSTANCE.IsWindow(hwnd)
        val processIdReference = IntByReference()
        val threadId = if (windowIsValid) {
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, processIdReference).toLong().takeIf { it != 0L }
        } else {
            null
        }
        val processId = processIdReference.value.toLong().takeIf { it != 0L }
        val title = if (windowIsValid) readTitle(hwnd) else ""
        val className = if (windowIsValid) readClassName(hwnd) else ""
        val processPath = processId?.let(::readProcessPath)
        val processName = processPath?.fileName()
        val processStartTime = processId?.let(::readProcessStartTime)
        val metadata = ForegroundWindowMetadata(
            hwnd = hwndValue,
            windowIsValid = windowIsValid,
            processId = processId,
            threadId = threadId,
            title = title,
            className = className,
            processPath = processPath,
            processName = processName,
            processStartTime = processStartTime,
        )
        val identity = ForegroundWindowClassifier.classify(metadata, ownProcessId)
        return ForegroundWindowSnapshot(
            capturedAt = Instant.now(),
            hwnd = hwndValue,
            processId = processId,
            threadId = threadId,
            title = title,
            className = className,
            processPath = processPath,
            processName = processName,
            processStartTime = processStartTime,
            isOwnProcess = identity.isOwnProcess,
            classification = identity.classification,
            characterName = identity.characterName,
            reason = identity.reason,
        )
    }

    private fun readTitle(hwnd: WinDef.HWND): String {
        val length = User32.INSTANCE.GetWindowTextLength(hwnd)
        val buffer = CharArray((length + 1).coerceAtLeast(MINIMUM_TEXT_BUFFER))
        val copied = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.size)
        return if (copied > 0) Native.toString(buffer) else ""
    }

    private fun readClassName(hwnd: WinDef.HWND): String {
        val buffer = CharArray(CLASS_NAME_BUFFER)
        val copied = User32.INSTANCE.GetClassName(hwnd, buffer, buffer.size)
        return if (copied > 0) Native.toString(buffer) else ""
    }

    private fun readProcessPath(processId: Long): String? {
        val process = Kernel32.INSTANCE.OpenProcess(
            WinNT.PROCESS_QUERY_LIMITED_INFORMATION,
            false,
            processId.toInt(),
        ) ?: return processHandleCommand(processId)
        return try {
            val buffer = CharArray(MAXIMUM_PROCESS_PATH)
            val size = IntByReference(buffer.size)
            if (Kernel32.INSTANCE.QueryFullProcessImageName(process, 0, buffer, size)) {
                String(buffer, 0, size.value)
            } else {
                processHandleCommand(processId)
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(process)
        }
    }

    private fun processHandleCommand(processId: Long): String? = runCatching {
        ProcessHandle.of(processId).orElse(null)?.info()?.command()?.orElse(null)
    }.getOrNull()

    private fun readProcessStartTime(processId: Long): Instant? = runCatching {
        ProcessHandle.of(processId).orElse(null)?.info()?.startInstant()?.orElse(null)
    }.getOrNull()

    private fun String.fileName(): String = replace('/', '\\').substringAfterLast('\\')

    private companion object {
        const val MINIMUM_TEXT_BUFFER = 2
        const val CLASS_NAME_BUFFER = 512
        const val MAXIMUM_PROCESS_PATH = 32_768
    }
}

/**
 * Event-driven foreground monitor. Native callbacks only enqueue a small immutable event; process
 * and window metadata are deliberately resolved by the listener on a separate worker thread.
 */
internal class WindowsForegroundWindowMonitor(
    private val watchdogIntervalSeconds: Long = 2,
) : ForegroundWindowMonitor {
    private val lifecycleLock = Any()
    private val lastObservedHwnd = AtomicLong(0)
    private val hookFailure = AtomicReference<Throwable?>()
    private var eventExecutor: ExecutorService? = null
    private var watchdogExecutor: ScheduledExecutorService? = null
    private var hookThread: Thread? = null
    @Volatile
    private var hookThreadId: Int = 0
    @Volatile
    private var hookHandle: WinNT.HANDLE? = null

    // This field intentionally owns a strong reference for the complete native hook lifetime.
    private val nativeCallback = WinUser.WinEventProc { _, event, hwnd, objectId, childId, _, eventTime ->
        runCatching {
            if (
                event.toInt() == EVENT_SYSTEM_FOREGROUND &&
                objectId.toInt() == OBJID_WINDOW &&
                childId.toInt() == CHILDID_SELF &&
                hwnd != null
            ) {
                enqueue(
                    hwnd = hwnd.value(),
                    source = ForegroundWindowEventSource.WIN_EVENT_HOOK,
                    nativeEventTimeMillis = eventTime.toLong(),
                )
            }
        }.onFailure { failure ->
            hookFailure.compareAndSet(null, failure)
            reportFailure(failure)
        }
    }

    override fun start(
        onForegroundChanged: (ForegroundWindowEvent) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        check(Platform.isWindows()) { "Foreground window monitoring is only available on Windows" }
        synchronized(lifecycleLock) {
            check(hookThread == null) { "Foreground window monitor is already started" }
            hookFailure.set(null)
            val started = CountDownLatch(1)
            val processor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "foreground-window-snapshot-worker").apply { isDaemon = true }
            }
            eventExecutor = processor
            eventConsumer = { event ->
                runCatching { onForegroundChanged(event) }.onFailure(onFailure)
            }
            failureConsumer = onFailure
            val thread = Thread(
                { runMessageLoop(started, onFailure) },
                "foreground-window-win-event-hook",
            ).apply { isDaemon = true }
            hookThread = thread
            thread.start()
            if (!started.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                close()
                error("Timed out while installing the foreground WinEvent hook")
            }
            hookFailure.getAndSet(null)?.let { failure ->
                close()
                throw IllegalStateException("Could not install the foreground WinEvent hook", failure)
            }

            val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "foreground-window-watchdog").apply { isDaemon = true }
            }
            watchdogExecutor = watchdog
            watchdog.scheduleWithFixedDelay(
                { runCatching(::resyncIfNeeded).onFailure(onFailure) },
                watchdogIntervalSeconds,
                watchdogIntervalSeconds,
                TimeUnit.SECONDS,
            )
        }
    }

    @Volatile
    private var eventConsumer: ((ForegroundWindowEvent) -> Unit)? = null
    @Volatile
    private var failureConsumer: ((Throwable) -> Unit)? = null

    private fun runMessageLoop(started: CountDownLatch, onFailure: (Throwable) -> Unit) {
        var installedHook: WinNT.HANDLE? = null
        try {
            hookThreadId = Kernel32.INSTANCE.GetCurrentThreadId()
            // Ensure this native thread owns a message queue before another thread can post WM_QUIT.
            User32.INSTANCE.PeekMessage(WinUser.MSG(), null, 0, 0, PM_NOREMOVE)
            installedHook = User32.INSTANCE.SetWinEventHook(
                EVENT_SYSTEM_FOREGROUND,
                EVENT_SYSTEM_FOREGROUND,
                null,
                nativeCallback,
                0,
                0,
                WINEVENT_OUTOFCONTEXT,
            )
            if (installedHook == null) error("SetWinEventHook returned null")
            hookHandle = installedHook
            started.countDown()
            foregroundHwnd()?.let { enqueue(it, ForegroundWindowEventSource.STARTUP_SYNC) }

            val message = WinUser.MSG()
            while (true) {
                when (User32.INSTANCE.GetMessage(message, null, 0, 0)) {
                    -1 -> error("GetMessage failed")
                    0 -> break
                    else -> {
                        User32.INSTANCE.TranslateMessage(message)
                        User32.INSTANCE.DispatchMessage(message)
                    }
                }
            }
        } catch (failure: Throwable) {
            hookFailure.compareAndSet(null, failure)
            onFailure(failure)
            started.countDown()
        } finally {
            installedHook?.let { User32.INSTANCE.UnhookWinEvent(it) }
            hookHandle = null
            hookThreadId = 0
        }
    }

    private fun resyncIfNeeded() {
        val current = foregroundHwnd() ?: return
        if (current != lastObservedHwnd.get()) {
            enqueue(current, ForegroundWindowEventSource.WATCHDOG_RESYNC)
        }
    }

    private fun foregroundHwnd(): Long? = User32.INSTANCE.GetForegroundWindow()?.value()?.takeIf { it != 0L }

    private fun enqueue(
        hwnd: Long,
        source: ForegroundWindowEventSource,
        nativeEventTimeMillis: Long? = null,
    ) {
        lastObservedHwnd.set(hwnd)
        val event = ForegroundWindowEvent(hwnd, source, nativeEventTimeMillis)
        try {
            eventExecutor?.execute { eventConsumer?.invoke(event) }
        } catch (_: RejectedExecutionException) {
            // Normal during shutdown: callbacks can race with executor termination.
        }
    }

    private fun reportFailure(failure: Throwable) {
        try {
            eventExecutor?.execute { failureConsumer?.invoke(failure) }
        } catch (_: RejectedExecutionException) {
            // Normal during shutdown: callbacks can race with executor termination.
        }
    }

    override fun close() {
        val thread: Thread?
        val processor: ExecutorService?
        val watchdog: ScheduledExecutorService?
        synchronized(lifecycleLock) {
            thread = hookThread
            processor = eventExecutor
            watchdog = watchdogExecutor
            hookThread = null
            eventExecutor = null
            watchdogExecutor = null
            eventConsumer = null
            failureConsumer = null
        }
        watchdog?.shutdownNow()
        val nativeThreadId = hookThreadId
        if (nativeThreadId != 0) {
            User32.INSTANCE.PostThreadMessage(
                nativeThreadId,
                WinUser.WM_QUIT,
                WinDef.WPARAM(0),
                WinDef.LPARAM(0),
            )
        }
        thread?.join(STOP_TIMEOUT_MILLIS)
        processor?.shutdown()
        processor?.awaitTermination(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        if (thread?.isAlive == true) {
            throw IllegalStateException("Foreground WinEvent hook thread did not stop")
        }
    }

    private fun WinDef.HWND.value(): Long = Pointer.nativeValue(pointer)

    private companion object {
        const val EVENT_SYSTEM_FOREGROUND = 0x0003
        const val WINEVENT_OUTOFCONTEXT = 0x0000
        const val OBJID_WINDOW = 0
        const val CHILDID_SELF = 0
        const val PM_NOREMOVE = 0x0000
        const val START_TIMEOUT_SECONDS = 5L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
