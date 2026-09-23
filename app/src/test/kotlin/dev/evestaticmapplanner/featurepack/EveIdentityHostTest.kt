package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.EveAllianceIdentitySnapshot
import dev.evestaticmapplanner.feature.api.EveCharacterIdentitySnapshot
import dev.evestaticmapplanner.feature.api.EveCorporationIdentitySnapshot
import dev.evestaticmapplanner.feature.api.EveIdentityProvider
import dev.evestaticmapplanner.feature.api.EveIdentityProviderSnapshot
import dev.evestaticmapplanner.feature.api.EveIdentitySnapshot
import dev.evestaticmapplanner.feature.api.PackId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EveIdentityHostTest {
    @Test
    fun `host restores preferred current identity after provider publishes it`() {
        val host = EveIdentityHost()
        host.restorePreferredCharacterId(2)
        val provider = MutableProvider(snapshot(identity(1, "Alpha"), identity(2, "Bravo")))
        val registration = host.scopedCapability(PackId("esi.pack")).register(provider)

        assertEquals(2, host.state.value.selectedCharacterId)
        assertEquals("Bravo", host.state.value.currentIdentity?.character?.name)
        assertEquals("A2", host.state.value.currentIdentity?.currentAllianceIdentifier)

        registration.close()
        assertTrue(host.state.value.identities.isEmpty())
        assertNull(host.state.value.currentIdentity)
    }

    @Test
    fun `host requires an explicit choice for multiple identities and keeps it across refresh`() {
        val host = EveIdentityHost()
        val provider = MutableProvider(snapshot(identity(1, "Alpha"), identity(2, "Bravo")))
        val registration = host.scopedCapability(PackId("esi.pack")).register(provider)

        assertNull(host.state.value.currentIdentity)
        assertFalse(host.select(3))
        assertTrue(host.select(2))

        provider.value = snapshot(identity(2, "Bravo Updated"), identity(1, "Alpha"))
        registration.requestRefresh()

        assertEquals(2, host.state.value.selectedCharacterId)
        assertEquals("Bravo Updated", host.state.value.currentIdentity?.character?.name)
    }

    private class MutableProvider(var value: EveIdentityProviderSnapshot) : EveIdentityProvider {
        override fun snapshot(): EveIdentityProviderSnapshot = value
    }

    private fun snapshot(vararg identities: EveIdentitySnapshot) = EveIdentityProviderSnapshot(identities.toList())

    private fun identity(id: Long, name: String) = EveIdentitySnapshot(
        EveCharacterIdentitySnapshot(id, name),
        EveCorporationIdentitySnapshot(100 + id, "$name Corporation", "C$id"),
        EveAllianceIdentitySnapshot(200 + id, "$name Alliance", "A$id"),
        Instant.ofEpochMilli(1_000 + id),
    )
}
