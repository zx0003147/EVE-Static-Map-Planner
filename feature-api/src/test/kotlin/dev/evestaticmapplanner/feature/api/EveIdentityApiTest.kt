package dev.evestaticmapplanner.feature.api

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EveIdentityApiTest {
    @Test
    fun `identity snapshot is credential free and supports characters without an alliance`() {
        val identity = EveIdentitySnapshot(
            character = EveCharacterIdentitySnapshot(90_000_001, "Pilot One"),
            corporation = EveCorporationIdentitySnapshot(98_000_001, "Example Corp", "EX"),
            alliance = null,
            fetchedAt = Instant.parse("2026-09-23T00:00:00Z"),
        )

        val snapshot = EveIdentityProviderSnapshot(listOf(identity))

        assertEquals(90_000_001, snapshot.identities.single().character.id)
        assertNull(snapshot.identities.single().alliance)
    }

    @Test
    fun `provider snapshot rejects duplicate character IDs`() {
        val identity = EveIdentitySnapshot(
            EveCharacterIdentitySnapshot(90_000_001, "Pilot One"),
            EveCorporationIdentitySnapshot(98_000_001, "Example Corp", "EX"),
            EveAllianceIdentitySnapshot(99_000_001, "Example Alliance", "EA"),
            Instant.EPOCH,
        )

        assertFailsWith<IllegalArgumentException> {
            EveIdentityProviderSnapshot(listOf(identity, identity))
        }
    }
}
