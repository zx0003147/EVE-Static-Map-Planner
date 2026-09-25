package dev.evestaticmapplanner.charactertracking

import dev.evestaticmapplanner.feature.api.CharacterTrackingPriority
import dev.evestaticmapplanner.feature.api.TrackedCharacterAuthorizationState
import dev.evestaticmapplanner.feature.api.TrackedCharacterLocationStatus
import dev.evestaticmapplanner.feature.api.TrackedCharacterOnlineState
import dev.evestaticmapplanner.feature.api.TrackedCharacterSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class CharacterTrackingPriorityCoordinatorTest {
    @Test
    fun `current identity and foreground character may both be high priority`() {
        val fixture = Fixture()

        fixture.coordinator.update(1, 2, listOf(character(1), character(2), character(3)))

        assertEquals(
            listOf(1L to CharacterTrackingPriority.HIGH, 2L to CharacterTrackingPriority.HIGH),
            fixture.priorityChanges,
        )
        assertEquals(listOf(2L), fixture.refreshes)
    }

    @Test
    fun `foreground change demotes only characters leaving high set and refreshes once`() {
        val fixture = Fixture()
        val characters = listOf(character(1), character(2), character(3))
        fixture.coordinator.update(1, 2, characters)
        fixture.priorityChanges.clear()
        fixture.refreshes.clear()

        fixture.coordinator.update(1, 3, characters)
        fixture.coordinator.update(1, 3, characters)

        assertEquals(
            listOf(2L to CharacterTrackingPriority.NORMAL, 3L to CharacterTrackingPriority.HIGH),
            fixture.priorityChanges,
        )
        assertEquals(listOf(3L), fixture.refreshes)
    }

    @Test
    fun `same current identity and foreground produces one high priority update`() {
        val fixture = Fixture()

        fixture.coordinator.update(1, 1, listOf(character(1)))

        assertEquals(listOf(1L to CharacterTrackingPriority.HIGH), fixture.priorityChanges)
        assertEquals(listOf(1L), fixture.refreshes)
    }

    @Test
    fun `disabled character is normalized and never refreshed`() {
        val fixture = Fixture()
        fixture.coordinator.update(1, 2, listOf(character(1), character(2)))
        fixture.priorityChanges.clear()
        fixture.refreshes.clear()

        fixture.coordinator.update(1, 2, listOf(character(1), character(2, trackingEnabled = false)))

        assertEquals(listOf(2L to CharacterTrackingPriority.NORMAL), fixture.priorityChanges)
        assertEquals(emptyList(), fixture.refreshes)
    }

    private class Fixture {
        val priorityChanges = mutableListOf<Pair<Long, CharacterTrackingPriority>>()
        val refreshes = mutableListOf<Long>()
        val coordinator = CharacterTrackingPriorityCoordinator(
            setPriority = { id, priority -> priorityChanges += id to priority; true },
            requestRefresh = { id -> refreshes += id; true },
        )
    }

    private fun character(id: Long, trackingEnabled: Boolean = true) = TrackedCharacterSnapshot(
        id,
        "Character $id",
        TrackedCharacterAuthorizationState.CONNECTED,
        trackingEnabled,
        30_000_001,
        TrackedCharacterLocationStatus.CURRENT,
        Instant.EPOCH,
        Instant.EPOCH,
        Instant.EPOCH,
        TrackedCharacterOnlineState.UNKNOWN,
        null,
        null,
    )
}
