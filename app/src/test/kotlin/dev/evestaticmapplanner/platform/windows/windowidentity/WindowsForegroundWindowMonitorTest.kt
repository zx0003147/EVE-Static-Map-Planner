package dev.evestaticmapplanner.platform.windows.windowidentity

import com.sun.jna.Platform
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsForegroundWindowMonitorTest {
    @Test
    fun `real monitor starts reads initial foreground snapshot and unhooks`() {
        if (!Platform.isWindows()) return
        val event = AtomicReference<ForegroundWindowEvent?>()
        val received = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        WindowsForegroundWindowMonitor(watchdogIntervalSeconds = 1).use { monitor ->
            monitor.start(
                onForegroundChanged = {
                    event.compareAndSet(null, it)
                    received.countDown()
                },
                onFailure = { failure.compareAndSet(null, it) },
            )
            assertTrue(received.await(5, TimeUnit.SECONDS), "Startup foreground synchronization was not delivered")
            assertTrue(event.get()?.hwnd != 0L)
            val snapshot = WindowsForegroundWindowSnapshotReader().read(assertNotNull(event.get()).hwnd)
            assertTrue(snapshot.processId != null, "Foreground PID was unavailable")
            assertTrue(snapshot.threadId != null, "Foreground thread ID was unavailable")
            assertTrue(snapshot.processPath != null, "Foreground executable path was unavailable")
        }

        failure.get()?.let { throw AssertionError("Native foreground monitor reported a failure", it) }
    }

    @Test
    fun `real monitor classifies a foreground window owned by this process as non EVE`() {
        if (!Platform.isWindows() || GraphicsEnvironment.isHeadless()) return
        val ownProcessId = ProcessHandle.current().pid()
        val observed = AtomicReference<ForegroundWindowSnapshot?>()
        val received = CountDownLatch(1)
        val frame = AtomicReference<JFrame?>()

        WindowsForegroundWindowMonitor(watchdogIntervalSeconds = 1).use { monitor ->
            monitor.start(
                onForegroundChanged = { event ->
                    val snapshot = WindowsForegroundWindowSnapshotReader(ownProcessId).read(event.hwnd)
                    if (snapshot.processId == ownProcessId) {
                        observed.compareAndSet(null, snapshot)
                        received.countDown()
                    }
                },
            )
            try {
                EventQueue.invokeAndWait {
                    frame.set(
                        JFrame("Window Identity Own-Process Probe").apply {
                            setSize(360, 120)
                            setLocationRelativeTo(null)
                            isAlwaysOnTop = true
                            isVisible = true
                            toFront()
                            requestFocus()
                        },
                    )
                }
                assertTrue(received.await(10, TimeUnit.SECONDS), "Own-process foreground event was not delivered")
            } finally {
                EventQueue.invokeAndWait { frame.get()?.dispose() }
            }
        }

        val snapshot = assertNotNull(observed.get())
        assertTrue(snapshot.isOwnProcess)
        assertEquals(ForegroundWindowClassification.NON_EVE, snapshot.classification)
    }
}
