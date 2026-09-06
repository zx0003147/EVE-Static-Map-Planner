package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.CharacterTrackingProvider
import dev.evestaticmapplanner.feature.api.CharacterTrackingSnapshot
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.TrackedCharacterAuthorizationState
import dev.evestaticmapplanner.feature.api.TrackedCharacterLocationStatus
import dev.evestaticmapplanner.feature.api.TrackedCharacterOnlineState
import dev.evestaticmapplanner.feature.api.TrackedCharacterSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CharacterTrackingHostTest {
    @Test
    fun `registration publishes typed snapshots and close removes only its Pack`() {
        val host = CharacterTrackingHost()
        val first = MutableProvider(listOf(character(2, "Bravo", 30_000_002)))
        val second = MutableProvider(listOf(character(1, "Alpha", 30_000_001)))
        val firstRegistration = host.scopedCapability(PackId("first.pack")).register(first)
        val secondRegistration = host.scopedCapability(PackId("second.pack")).register(second)

        assertEquals(listOf("Alpha", "Bravo"), host.state.value.map { it.characterName })
        first.characters = listOf(character(2, "Bravo", 30_000_003))
        firstRegistration.requestRefresh()
        assertEquals(30_000_003, host.state.value.first { it.characterId == 2L }.solarSystemId)

        firstRegistration.close()
        assertEquals(listOf("Alpha"), host.state.value.map { it.characterName })
        secondRegistration.close()
        assertTrue(host.state.value.isEmpty())
        host.close()
    }

    @Test
    fun `invalid refresh retains last good provider snapshot`() {
        val failures = mutableListOf<String>()
        val host = CharacterTrackingHost { packId, operation, _ -> failures += "$packId:$operation" }
        val provider = MutableProvider(listOf(character(1, "Alpha", 30_000_001)))
        val registration = host.scopedCapability(PackId("esi.pack")).register(provider)

        provider.failure = IllegalStateException("broken")
        registration.requestRefresh()

        assertEquals(30_000_001, host.state.value.single().solarSystemId)
        assertEquals(listOf("esi.pack:snapshot"), failures)
        registration.close()
        host.close()
    }

    private class MutableProvider(var characters: List<TrackedCharacterSnapshot>) : CharacterTrackingProvider {
        var failure: Throwable? = null
        override fun snapshot(): CharacterTrackingSnapshot {
            failure?.let { throw it }
            return CharacterTrackingSnapshot(characters)
        }
    }

    private fun character(id: Long, name: String, systemId: Int) = TrackedCharacterSnapshot(
        id,
        name,
        TrackedCharacterAuthorizationState.CONNECTED,
        true,
        systemId,
        TrackedCharacterLocationStatus.CURRENT,
        Instant.EPOCH,
        Instant.EPOCH,
        Instant.EPOCH,
        TrackedCharacterOnlineState.UNKNOWN,
        null,
        null,
    )
}
