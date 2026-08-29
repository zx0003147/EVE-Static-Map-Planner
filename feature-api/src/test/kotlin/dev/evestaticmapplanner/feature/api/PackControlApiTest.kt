package dev.evestaticmapplanner.feature.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class PackControlApiTest {
    @Test
    fun `snapshot copies actions and validates product-safe presentation`() {
        val actions = mutableListOf(PackControlActionDescriptor("connect", "Connect", null, true))
        val snapshot = PackControlSnapshot("EVE Character", "Not connected", PackControlSeverity.NORMAL, actions)
        actions.clear()

        assertEquals(listOf("connect"), snapshot.actions.map(PackControlActionDescriptor::id))
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot.actions as MutableList<PackControlActionDescriptor>).clear()
        }
        assertFailsWith<IllegalArgumentException> {
            PackControlSnapshot(" ", null, PackControlSeverity.NORMAL, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            PackControlSnapshot(
                "Status",
                null,
                PackControlSeverity.NORMAL,
                listOf(
                    PackControlActionDescriptor("same", "One", null, true),
                    PackControlActionDescriptor("same", "Two", null, true),
                ),
            )
        }
    }

    @Test
    fun `action descriptor and result reject unsafe strings`() {
        assertEquals("connect", PackControlActionDescriptor("connect", "Connect", null, true).id)
        listOf("", "UPPER", "two--parts", " leading").forEach { id ->
            assertFailsWith<IllegalArgumentException>("id=$id") {
                PackControlActionDescriptor(id, "Action", null, true)
            }
        }
        listOf("", " leading", "trailing ", "line\nbreak", "x".repeat(101)).forEach { label ->
            assertFailsWith<IllegalArgumentException>("label=$label") {
                PackControlActionDescriptor("action", label, null, true)
            }
        }
        assertNull(PackControlActionResult(PackControlActionStatus.SUCCEEDED, null).message)
        assertFailsWith<IllegalArgumentException> {
            PackControlActionResult(PackControlActionStatus.FAILED, "raw\nerror")
        }
    }

    @Test
    fun `provider capability and registration contracts are synchronously usable`() {
        val events = mutableListOf<String>()
        val provider = object : PackControlProvider {
            override fun snapshot() = PackControlSnapshot("Status", null, PackControlSeverity.NORMAL, emptyList())

            override fun invoke(actionId: String): PackControlActionResult {
                events += actionId
                return PackControlActionResult(PackControlActionStatus.SUCCEEDED, "Done")
            }
        }
        val registration = object : PackControlRegistration {
            override fun requestRefresh() {
                events += "refresh"
            }

            override fun close() {
                events += "close"
            }
        }
        val capability = object : PackControlCapability {
            override fun register(provider: PackControlProvider): PackControlRegistration {
                assertEquals("Status", provider.snapshot().primaryText)
                return registration
            }
        }

        val returned = capability.register(provider)
        assertIs<AutoCloseable>(returned)
        assertEquals(PackControlActionStatus.SUCCEEDED, provider.invoke("connect").status)
        returned.requestRefresh()
        returned.close()

        assertEquals(listOf("connect", "refresh", "close"), events)
    }
}
