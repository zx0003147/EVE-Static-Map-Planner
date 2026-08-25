package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.SystemInfoField
import dev.evestaticmapplanner.feature.api.SystemInfoProvider
import dev.evestaticmapplanner.feature.api.SystemInfoProviderDescriptor
import dev.evestaticmapplanner.feature.api.SystemInfoSection
import dev.evestaticmapplanner.feature.api.SystemInfoSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SystemInfoHostTest {
    @Test
    fun `register and unregister update the selected system without polling`() {
        val host = SystemInfoHost()
        host.request(SYSTEM_ID)
        val registry = host.scopedRegistry(PackId("test.pack"))

        val registration = registry.register(provider("test.provider", 10, "status", 20))

        assertEquals(SYSTEM_ID, host.state.value.systemId)
        assertEquals(listOf("status"), host.state.value.sections.map { it.sectionId })

        registration.close()
        assertTrue(host.state.value.sections.isEmpty())
    }

    @Test
    fun `multiple providers merge in deterministic provider then section priority order`() {
        val host = SystemInfoHost()
        val first = host.scopedRegistry(PackId("first.pack"))
        val second = host.scopedRegistry(PackId("second.pack"))

        first.register(provider("first.provider", 10, "first-low", 5, "first-high" to 20))
        second.register(provider("second.provider", 50, "second", -10))

        val state = host.request(SYSTEM_ID)

        assertEquals(listOf("second", "first-high", "first-low"), state.sections.map { it.sectionId })
    }

    @Test
    fun `registration refresh publishes changed provider state only on explicit invalidation`() {
        var value = "Initial"
        val host = SystemInfoHost()
        host.request(SYSTEM_ID)
        val registration = host.scopedRegistry(PackId("test.pack")).register(
            provider("test.provider", 0, "status", 0) { value },
        )
        assertEquals("Initial", host.state.value.sections.single().fields.single().value)

        value = "Changed"
        assertEquals("Initial", host.state.value.sections.single().fields.single().value)
        registration.refresh()

        assertEquals("Changed", host.state.value.sections.single().fields.single().value)
    }

    @Test
    fun `closing Pack registry removes all providers and rejects later registration`() {
        val host = SystemInfoHost()
        host.request(SYSTEM_ID)
        val registry = host.scopedRegistry(PackId("test.pack"))
        registry.register(provider("test.one", 0, "one", 0))
        registry.register(provider("test.two", 0, "two", 0))

        registry.close()

        assertTrue(host.state.value.isEmpty)
        assertFailsWith<IllegalStateException> {
            registry.register(provider("test.three", 0, "three", 0))
        }
    }

    @Test
    fun `broken provider is isolated while healthy providers still contribute`() {
        val failures = mutableListOf<String>()
        val host = SystemInfoHost { providerId, _ -> failures += providerId }
        val broken = host.scopedRegistry(PackId("broken.pack"))
        val healthy = host.scopedRegistry(PackId("healthy.pack"))
        broken.register(object : SystemInfoProvider {
            override fun descriptor() = SystemInfoProviderDescriptor("broken.provider", "Broken")
            override fun provide(systemId: Int): SystemInfoSnapshot = error("Deliberate provider failure")
        })
        healthy.register(provider("healthy.provider", 0, "healthy", 0))

        val state = host.request(SYSTEM_ID)

        assertEquals(listOf("healthy"), state.sections.map { it.sectionId })
        assertTrue(failures.isNotEmpty())
        assertTrue(failures.all { it == "broken.provider" })
    }

    private fun provider(
        providerId: String,
        providerPriority: Int,
        sectionId: String,
        sectionPriority: Int,
        vararg additionalSections: Pair<String, Int>,
        value: () -> String = { "Available" },
    ) = object : SystemInfoProvider {
        override fun descriptor() = SystemInfoProviderDescriptor(providerId, providerId, providerPriority)

        override fun provide(systemId: Int) = SystemInfoSnapshot(
            systemId,
            listOf(section(sectionId, sectionPriority, value())) +
                additionalSections.map { (id, priority) -> section(id, priority, value()) },
        )
    }

    private fun section(id: String, priority: Int, value: String) = SystemInfoSection(
        sectionId = id,
        title = id,
        priority = priority,
        fields = listOf(SystemInfoField("status", "Status", value)),
    )

    private companion object {
        const val SYSTEM_ID = 30_000_142
    }
}
