package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.core.forceprojection.ForceProjectionZone
import dev.evestaticmapplanner.core.forceprojection.ForceProjectionZoneResolver
import dev.evestaticmapplanner.core.identity.CurrentIdentityContext
import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.sovereignty.AllianceCapitalStatus
import dev.evestaticmapplanner.core.sovereignty.SovereigntyFreshness
import dev.evestaticmapplanner.feature.api.AllianceCapitalProvider
import dev.evestaticmapplanner.feature.api.AllianceCapitalRecordDto
import dev.evestaticmapplanner.feature.api.AllianceCapitalSnapshotDto
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.SovereigntyFreshnessDto
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AllianceCapitalHostTest {
    @Test
    fun `one alliance has one capital and multiple alliances resolve independently`() {
        val host = hostWith(
            available(
                AllianceCapitalRecordDto(ALLIANCE_A, CAPITAL_A),
                AllianceCapitalRecordDto(ALLIANCE_B, CAPITAL_B),
            ),
        )

        assertEquals(CAPITAL_A, host.getCapital(ALLIANCE_A).capitalSystemId)
        assertEquals(CAPITAL_B, host.getCapital(ALLIANCE_B).capitalSystemId)
        assertEquals(AllianceCapitalStatus.AVAILABLE, host.getCapital(ALLIANCE_A).status)
    }

    @Test
    fun `missing alliance capital is unknown and never guessed`() {
        val host = hostWith(available(AllianceCapitalRecordDto(ALLIANCE_A, CAPITAL_A)))

        val missing = host.getCapital(ALLIANCE_B)

        assertEquals(AllianceCapitalStatus.UNKNOWN, missing.status)
        assertNull(missing.capitalSystemId)
    }

    @Test
    fun `multiple capital observations are ambiguous and retain diagnostics`() {
        val host = hostWith(
            available(
                AllianceCapitalRecordDto(ALLIANCE_A, CAPITAL_A),
                AllianceCapitalRecordDto(ALLIANCE_A, CAPITAL_B),
            ),
        )

        val ambiguous = host.getCapital(ALLIANCE_A)

        assertEquals(AllianceCapitalStatus.AMBIGUOUS, ambiguous.status)
        assertNull(ambiguous.capitalSystemId)
        assertEquals(listOf(CAPITAL_A, CAPITAL_B), ambiguous.conflictingSystemIds)
        assertTrue(ambiguous.diagnostic.orEmpty().contains(CAPITAL_A.toString()))
    }

    @Test
    fun `no capability leaves current alliance unavailable`() {
        val host = AllianceCapitalHost()
        host.updateCurrentIdentity(CurrentIdentityContext.manual(ALLIANCE_A))

        assertFalse(host.state.value.providerAvailable)
        assertEquals(AllianceCapitalStatus.UNAVAILABLE, host.state.value.currentAlliance.capital?.status)
    }

    @Test
    fun `provider stale snapshot remains stale`() {
        val host = hostWith(
            AllianceCapitalSnapshotDto(
                records = listOf(AllianceCapitalRecordDto(ALLIANCE_A, CAPITAL_A)),
                observedAt = OBSERVED_AT,
                source = SOURCE,
                freshness = SovereigntyFreshnessDto.STALE,
                errorMessage = "cache expired",
            ),
        )

        assertEquals(SovereigntyFreshness.STALE, host.snapshot().freshness)
        assertEquals(CAPITAL_A, host.getCapital(ALLIANCE_A).capitalSystemId)
    }

    @Test
    fun `temporary provider failure retains last good snapshot as stale`() {
        val provider = MutableProvider(available(AllianceCapitalRecordDto(ALLIANCE_A, CAPITAL_A)))
        val host = AllianceCapitalHost()
        val registration = host.scopedCapability(PackId("sovereignty.pack")).register(provider)

        provider.failure = IllegalStateException("ESI offline")
        registration.requestRefresh()

        assertEquals(SovereigntyFreshness.STALE, host.snapshot().freshness)
        assertEquals(CAPITAL_A, host.getCapital(ALLIANCE_A).capitalSystemId)
        assertEquals("ESI offline", host.snapshot().errorMessage)
    }

    @Test
    fun `unavailable refresh retains last good snapshot as stale`() {
        val provider = MutableProvider(available(AllianceCapitalRecordDto(ALLIANCE_A, CAPITAL_A)))
        val host = AllianceCapitalHost()
        val registration = host.scopedCapability(PackId("sovereignty.pack")).register(provider)
        provider.value = AllianceCapitalSnapshotDto(
            records = emptyList(),
            source = SOURCE,
            freshness = SovereigntyFreshnessDto.UNAVAILABLE,
            errorMessage = "HTTP 503",
        )

        registration.requestRefresh()

        assertEquals(SovereigntyFreshness.STALE, host.snapshot().freshness)
        assertEquals(CAPITAL_A, host.getCapital(ALLIANCE_A).capitalSystemId)
        assertEquals("HTTP 503", host.snapshot().errorMessage)
    }

    @Test
    fun `switching current alliance changes capital and recomputes zone from the new capital`() {
        val host = hostWith(
            available(
                AllianceCapitalRecordDto(ALLIANCE_A, CAPITAL_A),
                AllianceCapitalRecordDto(ALLIANCE_B, CAPITAL_B),
            ),
        )
        val resolver = ForceProjectionZoneResolver(
            listOf(
                system(CAPITAL_A, 0.0),
                system(CAPITAL_B, 30.0),
                system(TARGET, 4.0),
            ),
        )

        host.updateCurrentIdentity(CurrentIdentityContext.manual(ALLIANCE_A, "Alliance A", "AAA"))
        val first = requireNotNull(host.state.value.currentAlliance.capital)
        val firstZone = resolver.resolve(ALLIANCE_A, requireNotNull(first.capitalSystemId), TARGET)

        host.updateCurrentIdentity(CurrentIdentityContext.manual(ALLIANCE_B, "Alliance B", "BBB"))
        val second = requireNotNull(host.state.value.currentAlliance.capital)
        val secondZone = resolver.resolve(ALLIANCE_B, requireNotNull(second.capitalSystemId), TARGET)

        assertEquals(CAPITAL_A, first.capitalSystemId)
        assertEquals(ForceProjectionZone.ZONE_1, firstZone.zone)
        assertEquals(CAPITAL_B, second.capitalSystemId)
        assertEquals(ForceProjectionZone.ZONE_5, secondZone.zone)
    }

    private fun hostWith(snapshot: AllianceCapitalSnapshotDto): AllianceCapitalHost = AllianceCapitalHost().also {
        it.scopedCapability(PackId("sovereignty.pack")).register(MutableProvider(snapshot))
    }

    private fun available(vararg records: AllianceCapitalRecordDto) = AllianceCapitalSnapshotDto(
        records = records.toList(),
        observedAt = OBSERVED_AT,
        source = SOURCE,
        freshness = SovereigntyFreshnessDto.AVAILABLE,
    )

    private class MutableProvider(var value: AllianceCapitalSnapshotDto) : AllianceCapitalProvider {
        var failure: Throwable? = null
        override fun snapshot(): AllianceCapitalSnapshotDto {
            failure?.let { throw it }
            return value
        }
    }

    private fun system(id: Int, xLy: Double) = SolarSystem(
        id = id,
        constellationId = 20_000_001,
        regionId = 10_000_001,
        name = "System $id",
        securityStatus = 0.0,
        securityClass = null,
        position = UniversePosition(xLy * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR, 0.0, 0.0),
        schematicPosition = SchematicPosition(0.0, 0.0),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )

    private companion object {
        const val ALLIANCE_A = 99L
        const val ALLIANCE_B = 100L
        const val CAPITAL_A = 30_000_001
        const val CAPITAL_B = 30_000_002
        const val TARGET = 30_000_003
        const val SOURCE = "Sovereignty Pack / PUBLIC_ESI"
        val OBSERVED_AT: Instant = Instant.parse("2026-10-05T00:00:00Z")
    }
}
