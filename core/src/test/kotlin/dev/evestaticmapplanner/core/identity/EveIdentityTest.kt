package dev.evestaticmapplanner.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EveIdentityTest {
    @Test
    fun `reconcile updates available identities without inventing a selection`() {
        val identity = identity(2, "Pilot", "condi")

        val state = CurrentIdentitySelection.reconcile(listOf(identity), selectedCharacterId = null)

        assertNull(state.selectedCharacterId)
        assertNull(state.currentIdentity)
        assertNull(state.currentAllianceId)
    }

    @Test
    fun `reconcile retains the authoritative selection across list order changes`() {
        val alpha = identity(1, "Alpha", null)
        val bravo = identity(2, "Bravo", "BETA")

        assertNull(CurrentIdentitySelection.reconcile(listOf(bravo, alpha), null).currentIdentity)
        assertEquals(
            bravo,
            CurrentIdentitySelection.reconcile(listOf(alpha, bravo), selectedCharacterId = 2).currentIdentity,
        )
        assertEquals(
            alpha,
            CurrentIdentitySelection.reconcile(listOf(bravo, alpha), selectedCharacterId = 1).currentIdentity,
        )
    }

    @Test
    fun `selected identity remains authoritative while unavailable`() {
        val bravo = identity(2, "Bravo", "BETA")

        val state = CurrentIdentitySelection.reconcile(listOf(bravo), selectedCharacterId = 1)

        assertEquals(1, state.selectedCharacterId)
        assertNull(state.currentIdentity)
        assertNull(state.currentAllianceId)
    }

    @Test
    fun `selection rejects an identity that is not available`() {
        val state = CurrentIdentitySelection.reconcile(listOf(identity(1, "Alpha", null)), selectedCharacterId = 1)

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
