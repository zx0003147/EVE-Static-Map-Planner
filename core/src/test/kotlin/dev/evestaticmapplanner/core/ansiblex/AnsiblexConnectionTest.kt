package dev.evestaticmapplanner.core.ansiblex

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnsiblexConnectionTest {
    @Test
    fun `bidirectional connection preserves primary direction independently of endpoint ordering`() {
        val connection = connection(
            direction = AnsiblexDirection.BIDIRECTIONAL,
            primaryFromSystemId = 20,
            primaryToSystemId = 10,
        )

        assertEquals(10, connection.firstSystemId)
        assertEquals(20, connection.secondSystemId)
        assertEquals(20, connection.primaryFromSystemId)
        assertEquals(10, connection.primaryToSystemId)
    }

    @Test
    fun `legacy connection without primary metadata remains explicitly unavailable`() {
        val connection = connection(direction = AnsiblexDirection.BIDIRECTIONAL)

        assertEquals(null, connection.primaryFromSystemId)
        assertEquals(null, connection.primaryToSystemId)
    }

    @Test
    fun `primary direction must be complete and use the connection endpoints`() {
        assertFailsWith<IllegalArgumentException> {
            connection(
                direction = AnsiblexDirection.BIDIRECTIONAL,
                primaryFromSystemId = 10,
                primaryToSystemId = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            connection(
                direction = AnsiblexDirection.BIDIRECTIONAL,
                primaryFromSystemId = 10,
                primaryToSystemId = 30,
            )
        }
    }

    private fun connection(
        direction: AnsiblexDirection,
        primaryFromSystemId: Int? = null,
        primaryToSystemId: Int? = null,
    ) = AnsiblexConnection(
        id = "test",
        firstSystemId = 10,
        secondSystemId = 20,
        direction = direction,
        displayName = null,
        notes = null,
        source = AnsiblexSource.MANUAL,
        sourceBatchId = null,
        enabled = true,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        primaryFromSystemId = primaryFromSystemId,
        primaryToSystemId = primaryToSystemId,
    )
}
