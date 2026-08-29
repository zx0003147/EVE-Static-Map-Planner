package dev.evestaticmapplanner.feature.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DynamicOverlayApiTest {
    @Test
    fun `dynamic overlay capability registers provider and exposes refresh registration`() {
        val events = mutableListOf<String>()
        val registration = object : DynamicOverlayRegistration {
            override fun requestRefresh() {
                events += "refresh"
            }

            override fun close() {
                events += "close"
            }
        }
        val capability = object : DynamicOverlayCapability {
            override fun register(provider: OverlayProvider): DynamicOverlayRegistration {
                assertEquals("dynamic.test", provider.descriptor().id)
                return registration
            }
        }

        val returned = capability.register(TestProvider)
        assertIs<OverlayRegistration>(returned)
        returned.requestRefresh()
        returned.close()

        assertEquals(listOf("refresh", "close"), events)
    }

    private object TestProvider : OverlayProvider {
        override fun descriptor() = OverlayProviderDescriptor("dynamic.test", "Dynamic Test")

        override fun layers() = listOf(OverlayLayer("test", "Test"))

        override fun snapshot() = OverlaySnapshot(emptyList())
    }
}
