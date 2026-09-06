package dev.evestaticmapplanner.minimap

import dev.evestaticmapplanner.feature.api.TrackedCharacterAuthorizationState
import dev.evestaticmapplanner.feature.api.TrackedCharacterLocationStatus
import dev.evestaticmapplanner.feature.api.TrackedCharacterOnlineState
import dev.evestaticmapplanner.feature.api.TrackedCharacterSnapshot
import dev.evestaticmapplanner.platform.windows.windowidentity.ForegroundWindowClassification
import dev.evestaticmapplanner.platform.windows.windowidentity.ForegroundWindowEvent
import dev.evestaticmapplanner.platform.windows.windowidentity.ForegroundWindowEventSource
import dev.evestaticmapplanner.platform.windows.windowidentity.ForegroundWindowMonitor
import dev.evestaticmapplanner.platform.windows.windowidentity.ForegroundWindowSnapshot
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ForegroundCharacterFollowCoordinatorTest {
    @Test
    fun `known authorized EVE character follows immediately and ordinary A to B is not delayed`() {
        val fixture = fixture()
        fixture.coordinator.updateCharacters(listOf(character(1, "Alpha"), character(2, "Bravo")))
        fixture.start()

        fixture.emit(snapshot(10, "Alpha"))
        assertEquals(1, fixture.coordinator.state.value.followedCharacterId)

        fixture.clock.advanceMillis(2_000)
        fixture.emit(snapshot(20, "Bravo"))
        assertEquals(2, fixture.coordinator.state.value.followedCharacterId)
        assertFalse(fixture.coordinator.state.value.antiFlappingActive)
        fixture.close()
    }

    @Test
    fun `rapid reversal is the only transition held for stability`() {
        val fixture = fixture()
        fixture.coordinator.updateCharacters(listOf(character(1, "Alpha"), character(2, "Bravo")))
        fixture.start()
        fixture.emit(snapshot(10, "Alpha"))
        fixture.emit(snapshot(20, "Bravo"))

        fixture.clock.advanceMillis(100)
        fixture.emit(snapshot(10, "Alpha"))
        assertEquals(2, fixture.coordinator.state.value.followedCharacterId)
        assertTrue(fixture.coordinator.state.value.antiFlappingActive)

        fixture.clock.advanceMillis(349)
        fixture.coordinator.reevaluateStability()
        assertEquals(2, fixture.coordinator.state.value.followedCharacterId)
        fixture.clock.advanceMillis(1)
        fixture.coordinator.reevaluateStability()
        assertEquals(1, fixture.coordinator.state.value.followedCharacterId)
        assertFalse(fixture.coordinator.state.value.antiFlappingActive)
        fixture.close()
    }

    @Test
    fun `non EVE launcher unknown EVE and uncertain windows retain the last character`() {
        val fixture = fixture()
        fixture.coordinator.updateCharacters(listOf(character(1, "Alpha")))
        fixture.start()
        fixture.emit(snapshot(10, "Alpha"))

        listOf(
            ForegroundWindowClassification.NON_EVE,
            ForegroundWindowClassification.EVE_LAUNCHER,
            ForegroundWindowClassification.EVE_GAME_UNKNOWN_CHARACTER,
            ForegroundWindowClassification.UNKNOWN,
        ).forEachIndexed { index, classification ->
            fixture.emit(snapshot(100L + index, null, classification))
            assertEquals(1, fixture.coordinator.state.value.followedCharacterId)
        }
        fixture.close()
    }

    @Test
    fun `focusing the Planner Mini-map retains the AUTO followed character`() {
        val fixture = fixture()
        fixture.coordinator.updateCharacters(listOf(character(1, "Alpha")))
        fixture.start()
        fixture.emit(snapshot(10, "Alpha"))

        fixture.emit(snapshot(99, null, ForegroundWindowClassification.NON_EVE, ownProcess = true))

        assertEquals(1, fixture.coordinator.state.value.followedCharacterId)
        assertTrue(fixture.coordinator.state.value.diagnostic.contains("retaining"))
        fixture.close()
    }

    @Test
    fun `unauthorized exact name is never followed and removal plus reconnect are explicit`() {
        val fixture = fixture()
        fixture.coordinator.updateCharacters(listOf(character(1, "Alpha")))
        fixture.start()
        fixture.emit(snapshot(20, "Bravo"))
        assertEquals(null, fixture.coordinator.state.value.followedCharacterId)

        fixture.emit(snapshot(10, "Alpha"))
        assertEquals(1, fixture.coordinator.state.value.followedCharacterId)
        fixture.coordinator.updateCharacters(emptyList())
        assertEquals(null, fixture.coordinator.state.value.followedCharacterId)

        fixture.coordinator.updateCharacters(listOf(character(1, "Alpha")))
        assertEquals(1, fixture.coordinator.state.value.followedCharacterId)
        fixture.close()
    }

    @Test
    fun `manual binding uses full session identity and HWND reuse invalidates it`() {
        val fixture = fixture()
        fixture.coordinator.updateCharacters(listOf(character(1, "Alpha")))
        fixture.start()
        fixture.emit(snapshot(10, null, ForegroundWindowClassification.EVE_GAME_UNKNOWN_CHARACTER, pid = 50, startSecond = 1))

        assertIs<ManualWindowBindingResult.Bound>(fixture.coordinator.bindCurrentWindow(1))
        assertEquals(1, fixture.coordinator.state.value.followedCharacterId)
        assertEquals(1, fixture.coordinator.state.value.manualBindingCount)

        fixture.emit(snapshot(10, null, ForegroundWindowClassification.EVE_GAME_UNKNOWN_CHARACTER, pid = 51, startSecond = 2))
        assertEquals(1, fixture.coordinator.state.value.followedCharacterId)
        assertEquals(0, fixture.coordinator.state.value.manualBindingCount)
        assertTrue(fixture.coordinator.state.value.diagnostic.contains("unknown"))
        fixture.close()
    }

    @Test
    fun `manual binding rejects non EVE foreground and untracked character`() {
        val fixture = fixture()
        fixture.coordinator.updateCharacters(listOf(character(1, "Alpha")))
        fixture.start()
        fixture.emit(snapshot(30, null, ForegroundWindowClassification.NON_EVE))

        assertIs<ManualWindowBindingResult.Rejected>(fixture.coordinator.bindCurrentWindow(1))
        assertIs<ManualWindowBindingResult.Rejected>(fixture.coordinator.bindCurrentWindow(999))
        fixture.close()
    }

    @Test
    fun `manual binding is discarded when its process session ends`() {
        var alive = true
        val fixture = fixture(isSessionAlive = { alive })
        fixture.coordinator.updateCharacters(listOf(character(1, "Alpha")))
        fixture.start()
        fixture.emit(snapshot(10, null, ForegroundWindowClassification.EVE_GAME_UNKNOWN_CHARACTER))
        assertIs<ManualWindowBindingResult.Bound>(fixture.coordinator.bindCurrentWindow(1))
        assertEquals(1, fixture.coordinator.state.value.manualBindingCount)

        alive = false
        fixture.coordinator.reevaluateStability()
        assertEquals(0, fixture.coordinator.state.value.manualBindingCount)
        fixture.close()
    }

    private fun fixture(isSessionAlive: (dev.evestaticmapplanner.platform.windows.windowidentity.WindowSessionIdentity) -> Boolean = { true }): Fixture {
        val monitor = FakeMonitor()
        val snapshots = mutableMapOf<Long, ForegroundWindowSnapshot>()
        val clock = FakeClock()
        val coordinator = ForegroundCharacterFollowCoordinator(
            monitor = monitor,
            readSnapshot = { snapshots.getValue(it) },
            clock = clock,
            isSessionAlive = isSessionAlive,
            runStabilityTimer = false,
        )
        return Fixture(monitor, snapshots, clock, coordinator)
    }

    private data class Fixture(
        val monitor: FakeMonitor,
        val snapshots: MutableMap<Long, ForegroundWindowSnapshot>,
        val clock: FakeClock,
        val coordinator: ForegroundCharacterFollowCoordinator,
    ) : AutoCloseable {
        fun start() = coordinator.start()
        fun emit(snapshot: ForegroundWindowSnapshot) {
            snapshots[snapshot.hwnd] = snapshot
            monitor.emit(snapshot.hwnd)
        }
        override fun close() = coordinator.close()
    }

    private class FakeMonitor : ForegroundWindowMonitor {
        private var callback: ((ForegroundWindowEvent) -> Unit)? = null
        override fun start(onForegroundChanged: (ForegroundWindowEvent) -> Unit, onFailure: (Throwable) -> Unit) {
            callback = onForegroundChanged
        }
        fun emit(hwnd: Long) = checkNotNull(callback).invoke(
            ForegroundWindowEvent(hwnd, ForegroundWindowEventSource.WIN_EVENT_HOOK),
        )
        override fun close() { callback = null }
    }

    private class FakeClock : MonotonicClock {
        private var now = 0L
        override fun nowNanos(): Long = now
        fun advanceMillis(milliseconds: Long) { now += TimeUnit.MILLISECONDS.toNanos(milliseconds) }
    }

    private fun character(id: Long, name: String) = TrackedCharacterSnapshot(
        id,
        name,
        TrackedCharacterAuthorizationState.CONNECTED,
        true,
        30_000_001,
        TrackedCharacterLocationStatus.CURRENT,
        Instant.EPOCH,
        Instant.EPOCH,
        Instant.EPOCH,
        TrackedCharacterOnlineState.UNKNOWN,
        null,
        null,
    )

    private fun snapshot(
        hwnd: Long,
        characterName: String?,
        classification: ForegroundWindowClassification = ForegroundWindowClassification.EVE_GAME_CHARACTER,
        pid: Long = hwnd + 1_000,
        startSecond: Long = hwnd,
        ownProcess: Boolean = false,
    ) = ForegroundWindowSnapshot(
        capturedAt = Instant.EPOCH,
        hwnd = hwnd,
        processId = pid,
        threadId = pid + 1,
        title = characterName?.let { "EVE - $it" }.orEmpty(),
        className = "trinityWindow",
        processPath = "C:\\EVE\\exefile.exe",
        processName = "exefile.exe",
        processStartTime = Instant.EPOCH.plusSeconds(startSecond),
        isOwnProcess = ownProcess,
        classification = classification,
        characterName = characterName,
        reason = "fixture",
    )
}
