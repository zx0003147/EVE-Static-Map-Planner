package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.SolarSystemDetails
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.core.repository.UniverseRepository
import dev.evestaticmapplanner.core.sovereignty.SovereigntyFreshness
import dev.evestaticmapplanner.core.sovereignty.SovereigntySnapshot
import dev.evestaticmapplanner.core.sovereignty.SovereigntyStatus
import dev.evestaticmapplanner.core.sovereignty.SystemOwnerKind
import dev.evestaticmapplanner.core.sovereignty.SystemOwnership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepositorySystemReadPortTest {
    @Test
    fun `system info consumes Core sovereignty snapshot`() = runBlocking {
        val port = RepositorySystemReadPort(
            searchRepository = object : SystemSearchRepository {
                override fun searchSystems(query: String, limit: Int) = emptyList<SolarSystem>()
            },
            universeRepository = TestUniverseRepository,
            sovereigntySnapshotProvider = {
                SovereigntySnapshot(
                    systemsById = mapOf(SYSTEM.id to OWNERSHIP),
                    observedAtEpochMillis = 1_000,
                    source = "Sovereignty Pack",
                    freshness = SovereigntyFreshness.AVAILABLE,
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val sovereignty = requireNotNull(port.getSystemInfo(SYSTEM.id)?.sovereignty)

        assertEquals("ALLIANCE", sovereignty.ownerKind)
        assertEquals(99L, sovereignty.allianceId)
        assertEquals("Alliance", sovereignty.allianceName)
        assertEquals("AVAILABLE", sovereignty.freshness)
    }

    @Test
    fun `system info reports unavailable Core sovereignty without a Pack`() = runBlocking {
        val port = RepositorySystemReadPort(
            searchRepository = object : SystemSearchRepository {
                override fun searchSystems(query: String, limit: Int) = emptyList<SolarSystem>()
            },
            universeRepository = TestUniverseRepository,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val sovereignty = requireNotNull(port.getSystemInfo(SYSTEM.id)?.sovereignty)

        assertEquals("UNKNOWN", sovereignty.ownerKind)
        assertEquals("UNAVAILABLE", sovereignty.freshness)
        assertNull(sovereignty.allianceId)
    }

    private object TestUniverseRepository : UniverseRepository {
        override fun getRegion(id: Int) = REGION.takeIf { it.id == id }
        override fun getConstellation(id: Int) = CONSTELLATION.takeIf { it.id == id }
        override fun getSystem(id: Int) = SYSTEM.takeIf { it.id == id }
        override fun findSystemByName(name: String) = SYSTEM.takeIf { it.name == name }
        override fun getSystemDetails(id: Int) = SYSTEM.takeIf { it.id == id }?.let {
            SolarSystemDetails(it, REGION, CONSTELLATION, emptyList())
        }
    }

    private companion object {
        val POSITION = UniversePosition(1.0, 2.0, 3.0)
        val REGION = Region(10_000_001, "Region", POSITION, null)
        val CONSTELLATION = Constellation(20_000_001, REGION.id, "Constellation", POSITION, null)
        val SYSTEM = SolarSystem(
            id = 30_000_001,
            constellationId = CONSTELLATION.id,
            regionId = REGION.id,
            name = "System",
            securityStatus = 0.0,
            securityClass = null,
            position = POSITION,
            schematicPosition = null,
            radius = 1.0,
            factionId = null,
            wormholeClassId = null,
        )
        val OWNERSHIP = SystemOwnership(
            systemId = SYSTEM.id,
            ownerKind = SystemOwnerKind.ALLIANCE,
            allianceId = 99,
            allianceName = "Alliance",
            sovereigntyStatus = SovereigntyStatus.CLAIMED,
            observedAtEpochMillis = 1_000,
            source = "Sovereignty Pack",
            freshness = SovereigntyFreshness.AVAILABLE,
        )
    }
}
