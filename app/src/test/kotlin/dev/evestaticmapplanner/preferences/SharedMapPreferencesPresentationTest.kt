package dev.evestaticmapplanner.preferences

import dev.evestaticmapplanner.shared.model.SharedConnectionState
import dev.evestaticmapplanner.shared.model.SharedMapState
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedMapPreferencesPresentationTest {
    @Test
    fun `all frozen connection states have distinct user-facing labels`() {
        val labels = SharedConnectionState.entries.associateWith(::sharedMapStatusLabel)
        assertEquals(SharedConnectionState.entries.size, labels.values.toSet().size)
        assertEquals("Connected", labels.getValue(SharedConnectionState.ONLINE))
        assertEquals("Authentication required", labels.getValue(SharedConnectionState.AUTH_REQUIRED))
        assertEquals("Access removed", labels.getValue(SharedConnectionState.FORBIDDEN))
        assertEquals("Incompatible server", labels.getValue(SharedConnectionState.PROTOCOL_UNSUPPORTED))
    }

    @Test
    fun `configured local disconnect is distinct from never configured`() {
        assertEquals("Not configured", sharedMapStatusLabel(SharedMapState()))
        assertEquals(
            "Disconnected",
            sharedMapStatusLabel(SharedMapState(serverUrl = "https://example.com")),
        )
    }
}
