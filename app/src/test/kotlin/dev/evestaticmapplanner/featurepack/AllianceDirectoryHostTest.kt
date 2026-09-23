package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.core.alliance.AllianceReference
import dev.evestaticmapplanner.core.identity.EveAllianceIdentity
import dev.evestaticmapplanner.core.identity.EveCharacterIdentity
import dev.evestaticmapplanner.core.identity.EveCorporationIdentity
import dev.evestaticmapplanner.core.identity.EveIdentity
import dev.evestaticmapplanner.feature.api.AllianceDirectoryProvider
import dev.evestaticmapplanner.feature.api.AllianceDirectoryProviderSnapshot
import dev.evestaticmapplanner.feature.api.AllianceReferenceSnapshot
import dev.evestaticmapplanner.feature.api.PackId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AllianceDirectoryHostTest {
    @Test
    fun `sovereignty seed and current identity merge by stable ID`() {
        val host = AllianceDirectoryHost()
        val capability = host.scopedCapability(PackId("sovereignty.pack"))
        capability.register(object : AllianceDirectoryProvider {
            override fun snapshot() = AllianceDirectoryProviderSnapshot(
                listOf(
                    AllianceReferenceSnapshot(10, "Sovereignty Name"),
                    AllianceReferenceSnapshot(20, "Other Alliance"),
                ),
                observedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
        })
        host.updateCurrentIdentity(
            EveIdentity(
                EveCharacterIdentity(1, "Pilot"),
                EveCorporationIdentity(2, "Corporation", "CORP"),
                EveAllianceIdentity(10, "Identity Name", "ALLY"),
                Instant.parse("2026-02-01T00:00:00Z").toEpochMilli(),
            ),
        )

        val state = host.state.value
        assertEquals(setOf(10L, 20L), state.snapshot.alliancesById.keys)
        assertEquals("Identity Name", state.snapshot.alliancesById.getValue(10).name)
        assertEquals("ALLY", state.snapshot.alliancesById.getValue(10).ticker)
        assertTrue(state.snapshot.entriesById.getValue(10).sources.contains("feature-pack:sovereignty.pack"))
    }

    @Test
    fun `verified public detail enriches known ID without creating name identity`() {
        val host = AllianceDirectoryHost()
        host.addVerifiedPublicMetadata(AllianceReference(77, "Verified", "VRFY"))

        assertEquals(77L, host.state.value.snapshot.alliancesById.getValue(77).allianceId)
        assertEquals("VRFY", host.state.value.snapshot.alliancesById.getValue(77).ticker)
    }
}
