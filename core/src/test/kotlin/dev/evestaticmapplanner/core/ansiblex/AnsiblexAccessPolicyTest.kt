package dev.evestaticmapplanner.core.ansiblex

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnsiblexAccessPolicyTest {
    @Test
    fun `only enabled connection owned by selected alliance is usable`() {
        val connection = connection(ownerAllianceId = "CONDI")

        assertEquals(AnsiblexAccessStatus.ALLIANCE_NOT_SELECTED, AnsiblexAccessPolicy.status(connection, null))
        assertEquals(AnsiblexAccessStatus.ALLIANCE_MISMATCH, AnsiblexAccessPolicy.status(connection, "OTHER"))
        assertEquals(AnsiblexAccessStatus.AVAILABLE, AnsiblexAccessPolicy.status(connection, " condi "))
        assertTrue(AnsiblexAccessPolicy.isUsable(connection, "CONDI"))
        assertFalse(AnsiblexAccessPolicy.isUsable(connection.copy(enabled = false), "CONDI"))
    }

    @Test
    fun `unknown owner fails closed`() {
        assertEquals(
            AnsiblexAccessStatus.OWNER_UNKNOWN,
            AnsiblexAccessPolicy.status(connection(ownerAllianceId = null), "CONDI"),
        )
    }

    private fun connection(ownerAllianceId: String?) = AnsiblexConnection(
        id = "bridge",
        firstSystemId = 1,
        secondSystemId = 2,
        direction = AnsiblexDirection.BIDIRECTIONAL,
        displayName = null,
        notes = null,
        source = AnsiblexSource.MANUAL,
        sourceBatchId = null,
        enabled = true,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        ownerAllianceId = ownerAllianceId,
    )
}
