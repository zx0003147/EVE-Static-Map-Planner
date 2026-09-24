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
    fun `persisted A remains selected when B publishes before A`() {
        val diagnostics = mutableListOf<EveIdentitySelectionDiagnostic>()
        val host = EveIdentityHost(diagnosticSink = diagnostics::add)
        host.restorePreferredCharacterId(1)
        val provider = MutableProvider(snapshot(true, identity(2, "Bravo")))
        val registration = host.scopedCapability(PackId("esi.pack")).register(provider)
        host.completeInitialProviderRegistration()

        assertEquals(1L, host.state.value.selectedCharacterId)
        assertNull(host.state.value.currentIdentity)
        assertEquals(EveIdentitySelectionAvailability.LOADING, host.state.value.selectionAvailability)
        val unavailable = diagnostics.last { it.reason == EveIdentitySelectionReason.SELECTED_UNAVAILABLE }
        assertFalse(unavailable.selectionChanged)
        assertEquals(PackId("esi.pack"), unavailable.packId)
        assertTrue(unavailable.refreshing)
        assertTrue(unavailable.providerRevision > 0)

        provider.value = snapshot(
            false,
            identity(1, "Alpha"),
            identity(2, "Bravo"),
        )
        registration.requestRefresh()

        assertEquals(1L, host.state.value.selectedCharacterId)
        assertEquals("Alpha", host.state.value.currentIdentity?.character?.name)
        assertEquals(EveIdentitySelectionAvailability.AVAILABLE, host.state.value.selectionAvailability)
        assertTrue(diagnostics.none { it.newCharacterId == 2L && it.selectionChanged })
    }

    @Test
    fun `persisted A remains selected when A publishes before B`() {
        val host = EveIdentityHost()
        host.restorePreferredCharacterId(1)
        val provider = MutableProvider(snapshot(true, identity(1, "Alpha")))
        val registration = host.scopedCapability(PackId("esi.pack")).register(provider)
        host.completeInitialProviderRegistration()

        assertEquals(1L, host.state.value.selectedCharacterId)
        assertEquals("Alpha", host.state.value.currentIdentity?.character?.name)

        provider.value = snapshot(
            false,
            identity(1, "Alpha"),
            identity(2, "Bravo"),
        )
        registration.requestRefresh()

        assertEquals(1L, host.state.value.selectedCharacterId)
        assertEquals("Alpha", host.state.value.currentIdentity?.character?.name)
    }

    @Test
    fun `selection is stable across repeated snapshot order changes`() {
        val host = EveIdentityHost()
        val provider = MutableProvider(snapshot(false, identity(1, "Alpha"), identity(2, "Bravo")))
        val registration = host.scopedCapability(PackId("esi.pack")).register(provider)
        host.completeInitialProviderRegistration()
        assertTrue(host.select(1))
        val selectionRevision = host.state.value.selectionRevision
        val selectedIdentity = host.state.value.currentIdentity

        listOf(
            snapshot(false, identity(2, "Bravo"), identity(1, "Alpha")),
            snapshot(false, identity(1, "Alpha"), identity(2, "Bravo")),
            snapshot(false, identity(2, "Bravo Updated"), identity(1, "Alpha")),
        ).forEach { next ->
            provider.value = next
            registration.requestRefresh()
            assertEquals(1L, host.state.value.selectedCharacterId)
            assertEquals(selectedIdentity, host.state.value.currentIdentity)
            assertEquals(selectionRevision, host.state.value.selectionRevision)
        }
    }

    @Test
    fun `selected identity remains unavailable instead of falling back after disconnect`() {
        val host = EveIdentityHost()
        host.restorePreferredCharacterId(1)
        val provider = MutableProvider(snapshot(false, identity(1, "Alpha"), identity(2, "Bravo")))
        val registration = host.scopedCapability(PackId("esi.pack")).register(provider)
        host.completeInitialProviderRegistration()

        provider.value = snapshot(false, identity(2, "Bravo"))
        registration.requestRefresh()

        assertEquals(1L, host.state.value.selectedCharacterId)
        assertNull(host.state.value.currentIdentity)
        assertEquals(EveIdentitySelectionAvailability.UNAVAILABLE, host.state.value.selectionAvailability)
    }

    @Test
    fun `progressive multi-character restoration never auto selects the transient singleton`() {
        val host = EveIdentityHost()
        val provider = MutableProvider(snapshot(true, identity(1, "Alpha")))
        val registration = host.scopedCapability(PackId("esi.pack")).register(provider)
        host.completeInitialProviderRegistration()

        assertNull(host.state.value.selectedCharacterId)

        provider.value = snapshot(false, identity(1, "Alpha"), identity(2, "Bravo"))
        registration.requestRefresh()

        assertNull(host.state.value.selectedCharacterId)
        assertNull(host.state.value.currentIdentity)

        provider.value = snapshot(false, identity(2, "Bravo"))
        registration.requestRefresh()
        assertNull(host.state.value.selectedCharacterId)
    }

    @Test
    fun `stable initial singleton auto selects exactly once`() {
        val diagnostics = mutableListOf<EveIdentitySelectionDiagnostic>()
        val host = EveIdentityHost(diagnosticSink = diagnostics::add)
        val provider = MutableProvider(snapshot(true, identity(1, "Alpha")))
        val registration = host.scopedCapability(PackId("esi.pack")).register(provider)
        host.completeInitialProviderRegistration()
        assertNull(host.state.value.selectedCharacterId)

        provider.value = snapshot(false, identity(1, "Alpha"))
        registration.requestRefresh()

        assertEquals(1L, host.state.value.selectedCharacterId)
        assertEquals(EveIdentitySelectionReason.AUTO_SELECT_SINGLE_STABLE, host.state.value.lastSelectionReason)
        assertEquals(
            1,
            diagnostics.count {
                it.selectionChanged && it.reason == EveIdentitySelectionReason.AUTO_SELECT_SINGLE_STABLE
            },
        )

        provider.value = snapshot(false, identity(2, "Bravo"))
        registration.requestRefresh()
        assertEquals(1L, host.state.value.selectedCharacterId)
        assertNull(host.state.value.currentIdentity)
    }

    @Test
    fun `explicit user selection changes the authoritative identity and records its reason`() {
        val diagnostics = mutableListOf<EveIdentitySelectionDiagnostic>()
        val host = EveIdentityHost(diagnosticSink = diagnostics::add)
        val provider = MutableProvider(snapshot(false, identity(1, "Alpha"), identity(2, "Bravo")))
        host.scopedCapability(PackId("esi.pack")).register(provider)
        host.completeInitialProviderRegistration()

        assertNull(host.state.value.currentIdentity)
        assertFalse(host.select(3))
        assertTrue(host.select(2))

        assertEquals(2L, host.state.value.selectedCharacterId)
        assertEquals("Bravo", host.state.value.currentIdentity?.character?.name)
        assertEquals(EveIdentitySelectionReason.USER_SELECT, host.state.value.lastSelectionReason)
        assertEquals(
            EveIdentitySelectionReason.USER_SELECT,
            diagnostics.single { it.selectionChanged }.reason,
        )
    }

    @Test
    fun `explicitly selected B is restored before identities on restart`() {
        val restarted = EveIdentityHost()
        restarted.restorePreferredCharacterId(2)
        val provider = MutableProvider(snapshot(true, identity(1, "Alpha")))
        val registration = restarted.scopedCapability(PackId("esi.pack")).register(provider)
        restarted.completeInitialProviderRegistration()

        assertEquals(2L, restarted.state.value.selectedCharacterId)
        assertNull(restarted.state.value.currentIdentity)

        provider.value = snapshot(false, identity(1, "Alpha"), identity(2, "Bravo"))
        registration.requestRefresh()

        assertEquals(2L, restarted.state.value.selectedCharacterId)
        assertEquals("Bravo", restarted.state.value.currentIdentity?.character?.name)
        assertEquals(EveIdentitySelectionReason.RESTORE_PERSISTED, restarted.state.value.lastSelectionReason)
    }

    private class MutableProvider(var value: EveIdentityProviderSnapshot) : EveIdentityProvider {
        override fun snapshot(): EveIdentityProviderSnapshot = value
    }

    private fun snapshot(
        refreshing: Boolean,
        vararg identities: EveIdentitySnapshot,
    ) = EveIdentityProviderSnapshot(identities.toList(), refreshing = refreshing)

    private fun identity(id: Long, name: String) = EveIdentitySnapshot(
        EveCharacterIdentitySnapshot(id, name),
        EveCorporationIdentitySnapshot(100 + id, "$name Corporation", "C$id"),
        EveAllianceIdentitySnapshot(200 + id, "$name Alliance", "A$id"),
        Instant.ofEpochMilli(1_000 + id),
    )
}
