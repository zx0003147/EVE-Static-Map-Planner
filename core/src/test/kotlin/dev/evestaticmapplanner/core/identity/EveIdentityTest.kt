package dev.evestaticmapplanner.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EveIdentityTest {
    @Test
    fun `single identity is selected automatically and exposes canonical alliance ticker`() {
        val identity = identity(2, "Pilot", "condi")

        val state = CurrentIdentitySelection.reconcile(listOf(identity), selectedCharacterId = null)

        assertEquals(2, state.selectedCharacterId)
        assertEquals(identity, state.currentIdentity)
        assertEquals("CONDI", state.currentIdentity?.currentAllianceIdentifier)
    }

    @Test
    fun `multiple identities require a choice unless the prior or preferred choice is available`() {
        val alpha = identity(1, "Alpha", null)
        val bravo = identity(2, "Bravo", "BETA")

        assertNull(CurrentIdentitySelection.reconcile(listOf(bravo, alpha), null).currentIdentity)
        assertEquals(
            bravo,
            CurrentIdentitySelection.reconcile(listOf(bravo, alpha), null, preferredCharacterId = 2).currentIdentity,
        )
        assertEquals(
            alpha,
            CurrentIdentitySelection.reconcile(listOf(bravo, alpha), selectedCharacterId = 1).currentIdentity,
        )
    }

    @Test
    fun `selection rejects an identity that is not available`() {
        val state = CurrentIdentitySelection.reconcile(listOf(identity(1, "Alpha", null)), null)

        assertFailsWith<IllegalArgumentException> { CurrentIdentitySelection.select(state, 2) }
    }

    private fun identity(characterId: Long, name: String, allianceTicker: String?) = EveIdentity(
        character = EveCharacterIdentity(characterId, name),
        corporation = EveCorporationIdentity(10 + characterId, "Corporation $name", "C$characterId"),
        alliance = allianceTicker?.let {
            EveAllianceIdentity(20 + characterId, "Alliance $name", it)
        },
        fetchedAtEpochMillis = 1_795_000_000_000,
    )
}
