package dev.evestaticmapplanner.control.transport

import dev.evestaticmapplanner.control.MapControlService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalControlEndpointContractTest {
    @Test
    fun `Control API v3 keeps wire protocol v1`() {
        assertEquals(3, LocalControlProtocol.CONTROL_API_VERSION)
        assertEquals(1, LocalControlProtocol.PROTOCOL_VERSION)
    }

    @Test
    fun `transport methods exactly match MapControlService allowlist`() {
        val serviceMethods = MapControlService::class.java.declaredMethods
            .filterNot { it.isSynthetic || '$' in it.name }
            .map { it.name }.toSet()
        val mappedMethods = LocalControlOperation.entries.mapNotNull(LocalControlOperation::serviceMethod).toSet()

        assertEquals(serviceMethods, mappedMethods)
        assertEquals(serviceMethods.size, mappedMethods.size)
        assertEquals(1, LocalControlOperation.entries.count { it.serviceMethod == null })
        assertEquals(LocalControlOperation.HANDSHAKE, LocalControlOperation.entries.single { it.serviceMethod == null })
    }

    @Test
    fun `allowlist has no generic or forbidden capabilities`() {
        val combined = LocalControlOperation.entries.joinToString(" ") { "${it.path} ${it.serviceMethod}" }.lowercase()
        listOf(
            "invoke", "execute", "generic", "method", "class", "sql", "file", "shell", "process",
            "ansiblexmutation", "preference", "database", "mcp",
        ).forEach { forbidden -> assertFalse(combined.contains(forbidden), forbidden) }

        assertTrue(LocalControlOperation.allowedPaths.all { it.startsWith("/v1/") })
        assertEquals(LocalControlOperation.entries.size, LocalControlOperation.allowedPaths.size)
        assertEquals(11, LocalControlOperation.entries.count { !it.mutation && it.serviceMethod != null })
        assertEquals(21, LocalControlOperation.entries.count(LocalControlOperation::mutation))
        val savedMarkerOperations = LocalControlOperation.entries.filter {
            it.path.contains("saved-marker") || it.serviceMethod == "getSystemMarkers"
        }
        assertEquals(
            setOf(LocalControlOperation.SYSTEM_MARKERS, LocalControlOperation.CREATE_SAVED_MARKER),
            savedMarkerOperations.toSet(),
        )
        val wormholeOperations = LocalControlOperation.entries.filter {
            it.path.contains("wormhole") || it.serviceMethod?.contains("Wormhole") == true
        }
        assertEquals(
            setOf(LocalControlOperation.LIST_WORMHOLES, LocalControlOperation.CREATE_WORMHOLE),
            wormholeOperations.toSet(),
        )
        listOf("remove", "delete", "clear", "replace").forEach { forbidden ->
            assertFalse(wormholeOperations.any { forbidden in it.path || forbidden in it.serviceMethod.orEmpty().lowercase() })
        }
    }
}
