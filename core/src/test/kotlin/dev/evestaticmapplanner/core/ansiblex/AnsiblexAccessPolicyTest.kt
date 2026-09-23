package dev.evestaticmapplanner.core.ansiblex

import dev.evestaticmapplanner.core.identity.CurrentIdentityContext
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnsiblexAccessPolicyTest {
    @Test
    fun `matching stable alliance ID is allowed and mismatch is denied`() {
        val connection = connection(ownerAllianceId = 99_000_001)

        assertEquals(AnsiblexAccessStatus.ALLIANCE_NOT_SELECTED, AnsiblexAccessPolicy.status(connection, null))
        assertEquals(
            AnsiblexAccessStatus.ALLIANCE_MISMATCH,
            AnsiblexAccessPolicy.status(connection, CurrentIdentityContext.manual(99_000_002)),
        )
        assertEquals(
            AnsiblexAccessStatus.AVAILABLE,
            AnsiblexAccessPolicy.status(connection, CurrentIdentityContext.manual(99_000_001)),
        )
        assertTrue(AnsiblexAccessPolicy.isUsable(connection, CurrentIdentityContext.manual(99_000_001)))
        assertFalse(
            AnsiblexAccessPolicy.isUsable(
                connection.copy(enabled = false),
                CurrentIdentityContext.manual(99_000_001),
            ),
        )
    }

    @Test
    fun `unknown owner fails closed`() {
        assertEquals(
            AnsiblexAccessStatus.OWNER_UNKNOWN,
            AnsiblexAccessPolicy.status(connection(ownerAllianceId = null), CurrentIdentityContext.manual(99_000_001)),
        )
    }

    @Test
    fun `display name and ticker changes do not affect stable ID access`() {
        val connection = connection(ownerAllianceId = 99_000_001).copy(
            ownerAllianceName = "Renamed Owner",
            ownerAllianceTicker = "NEW",
        )
        val identity = CurrentIdentityContext.manual(
            allianceId = 99_000_001,
            allianceName = "Old Display Name",
            allianceTicker = "OLD",
        )

        assertEquals(AnsiblexAccessStatus.AVAILABLE, AnsiblexAccessPolicy.status(connection, identity))
    }

    private fun connection(ownerAllianceId: Long?) = AnsiblexConnection(
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
