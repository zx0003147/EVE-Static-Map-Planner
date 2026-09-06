package dev.evestaticmapplanner.platform.windows.windowidentity

import kotlin.test.Test
import kotlin.test.assertEquals

class ForegroundWindowMonitorTest {
    @Test
    fun `consumer logic can be tested without Win32`() {
        val events = mutableListOf<ForegroundWindowEvent>()
        val monitor: ForegroundWindowMonitor = FakeForegroundWindowMonitor(
            ForegroundWindowEvent(0x1111, ForegroundWindowEventSource.STARTUP_SYNC),
            ForegroundWindowEvent(0x2222, ForegroundWindowEventSource.WIN_EVENT_HOOK, 123),
        )

        monitor.use { it.start(events::add) }

        assertEquals(listOf(0x1111L, 0x2222L), events.map(ForegroundWindowEvent::hwnd))
    }

    private class FakeForegroundWindowMonitor(
        private vararg val events: ForegroundWindowEvent,
    ) : ForegroundWindowMonitor {
        override fun start(
            onForegroundChanged: (ForegroundWindowEvent) -> Unit,
            onFailure: (Throwable) -> Unit,
        ) {
            events.forEach(onForegroundChanged)
        }

        override fun close() = Unit
    }
}
