package dev.evestaticmapplanner.core.wormhole

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WormholeConnectionTest {
    @Test
    fun `canonicalizes endpoints into ascending order`() {
        val connection = WormholeConnection.between(30_000_003, 30_000_001)

        assertEquals(30_000_001, connection.firstSystemId)
        assertEquals(30_000_003, connection.secondSystemId)
    }

    @Test
    fun `uses a stable canonical ID`() {
        assertEquals(
            "wormhole:30000001:30000003",
            WormholeConnection.between(30_000_003, 30_000_001).id,
        )
    }

    @Test
    fun `rejects non-positive system IDs`() {
        assertFailsWith<IllegalArgumentException> { WormholeConnection.between(0, 30_000_001) }
        assertFailsWith<IllegalArgumentException> { WormholeConnection.between(30_000_001, -1) }
    }

    @Test
    fun `rejects self-loops`() {
        assertFailsWith<IllegalArgumentException> {
            WormholeConnection.between(30_000_001, 30_000_001)
        }
    }

    @Test
    fun `reversed endpoints have the same identity`() {
        assertEquals(
            WormholeConnection.between(30_000_001, 30_000_003),
            WormholeConnection.between(30_000_003, 30_000_001),
        )
    }
}
