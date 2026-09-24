package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.core.sovereignty.SovereigntyFreshness
import dev.evestaticmapplanner.core.sovereignty.SystemOwnerKind
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.SovereigntyFreshnessDto
import dev.evestaticmapplanner.feature.api.SovereigntyOwnerKindDto
import dev.evestaticmapplanner.feature.api.SovereigntyProvider
import dev.evestaticmapplanner.feature.api.SovereigntySnapshotDto
import dev.evestaticmapplanner.feature.api.SovereigntyStatusDto
import dev.evestaticmapplanner.feature.api.SystemOwnershipDto
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SovereigntyHostTest {
    @Test
    fun `registration publishes typed ownership and supports system query`() {
        val host = SovereigntyHost()
        val provider = MutableProvider(available("Old Name"))

        host.scopedCapability(PackId("sovereignty.pack")).register(provider)

        val ownership = host.getOwnership(30_000_001)
        assertEquals(SystemOwnerKind.ALLIANCE, ownership?.ownerKind)
        assertEquals(99, ownership?.allianceId)
        assertEquals("Old Name", ownership?.allianceName)
        assertTrue(host.state.value.providerAvailable)
    }

    @Test
    fun `same stable ID survives display name refresh`() {
        val host = SovereigntyHost()
        val provider = MutableProvider(available("Old Name"))
        val registration = host.scopedCapability(PackId("sovereignty.pack")).register(provider)

        provider.value = available("New Name")
        registration.requestRefresh()

        assertEquals(99, host.getOwnership(30_000_001)?.allianceId)
        assertEquals("New Name", host.getOwnership(30_000_001)?.allianceName)
    }

    @Test
    fun `provider failure retains last good as stale`() {
        val failures = mutableListOf<String>()
        val host = SovereigntyHost { _, operation, _ -> failures += operation }
        val provider = MutableProvider(available("Alliance"))
        val registration = host.scopedCapability(PackId("sovereignty.pack")).register(provider)

        provider.failure = IllegalStateException("offline")
        registration.requestRefresh()

        assertEquals(SovereigntyFreshness.STALE, host.state.value.snapshot.freshness)
        assertEquals(99, host.getOwnership(30_000_001)?.allianceId)
        assertEquals("offline", host.state.value.snapshot.errorMessage)
        assertEquals(listOf("snapshot"), failures)
    }

    @Test
    fun `provider removal makes sovereignty unavailable and discards last good publication`() {
        val host = SovereigntyHost()
        val capability = host.scopedCapability(PackId("sovereignty.pack"))
        val registration = capability.register(MutableProvider(available("Alliance")))

        registration.close()

        assertFalse(host.state.value.providerAvailable)
        assertEquals(SovereigntyFreshness.UNAVAILABLE, host.state.value.snapshot.freshness)
        assertNull(host.getOwnership(30_000_001))
    }

    @Test
    fun `provider can re-register after removal and restores available ownership`() {
        val host = SovereigntyHost()
        val capability = host.scopedCapability(PackId("sovereignty.pack"))
        val first = capability.register(MutableProvider(available("First Alliance")))

        first.close()
        assertEquals(SovereigntyFreshness.UNAVAILABLE, host.snapshot().freshness)

        capability.register(MutableProvider(available("Restored Alliance")))

        assertTrue(host.state.value.providerAvailable)
        assertEquals(SovereigntyFreshness.AVAILABLE, host.snapshot().freshness)
        assertEquals("Restored Alliance", host.getOwnership(30_000_001)?.allianceName)
    }

    @Test
    fun `provider unavailable without last good remains unavailable`() {
        val host = SovereigntyHost()
        host.scopedCapability(PackId("sovereignty.pack")).register(
            MutableProvider(
                SovereigntySnapshotDto(
                    emptyList(),
                    source = "Sovereignty Pack",
                    freshness = SovereigntyFreshnessDto.UNAVAILABLE,
                    errorMessage = "No cache",
                ),
            ),
        )

        assertEquals(SovereigntyFreshness.UNAVAILABLE, host.state.value.snapshot.freshness)
        assertEquals("No cache", host.state.value.snapshot.errorMessage)
    }

    private class MutableProvider(var value: SovereigntySnapshotDto) : SovereigntyProvider {
        var failure: Throwable? = null
        override fun snapshot(): SovereigntySnapshotDto {
            failure?.let { throw it }
            return value
        }
    }

    private fun available(name: String) = SovereigntySnapshotDto(
        systems = listOf(
            SystemOwnershipDto(
                systemId = 30_000_001,
                ownerKind = SovereigntyOwnerKindDto.ALLIANCE,
                allianceId = 99,
                allianceName = name,
                sovereigntyStatus = SovereigntyStatusDto.CLAIMED,
            ),
        ),
        observedAt = Instant.parse("2026-09-24T00:00:00Z"),
        source = "Sovereignty Pack",
        freshness = SovereigntyFreshnessDto.AVAILABLE,
    )
}
