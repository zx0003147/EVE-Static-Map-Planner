package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.OverlayEntry
import dev.evestaticmapplanner.feature.api.OverlayLayer
import dev.evestaticmapplanner.feature.api.OverlayProvider
import dev.evestaticmapplanner.feature.api.OverlayProviderDescriptor
import dev.evestaticmapplanner.feature.api.OverlaySnapshot
import dev.evestaticmapplanner.feature.api.PackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureOverlayHostTest {
    @Test
    fun `no provider exposes empty state without background work`() {
        val host = FeatureOverlayHost()

        assertTrue(host.state.value.isEmpty)
        assertTrue(host.refresh().isEmpty)
    }

    @Test
    fun `register exposes provider layer and snapshot while unregister removes it`() {
        val host = FeatureOverlayHost()
        val registry = host.scopedRegistry(PackId("test.pack"))

        val registration = registry.register(provider("test.provider", "status", 20, 30_000_142))

        val layerState = host.state.value.layers.single()
        assertEquals("test.provider", layerState.provider.id)
        assertEquals("status", layerState.layer.id)
        assertEquals(30_000_142, layerState.entries.single().systemId)

        registration.close()
        assertTrue(host.state.value.isEmpty)
    }

    @Test
    fun `host combines providers deterministically by layer priority`() {
        val host = FeatureOverlayHost()
        val first = host.scopedRegistry(PackId("first.pack"))
        val second = host.scopedRegistry(PackId("second.pack"))

        first.register(provider("first.provider", "high", 50, 30_000_001))
        second.register(provider("second.provider", "low", -10, 30_000_002))

        assertEquals(listOf("low", "high"), host.state.value.layers.map { it.layer.id })
        assertEquals(setOf(30_000_001, 30_000_002), host.state.value.layers
            .flatMap { it.entries }
            .mapTo(mutableSetOf(), OverlayEntry::systemId))
    }

    @Test
    fun `closing Pack-scoped registry removes all of its providers`() {
        val host = FeatureOverlayHost()
        val registry = host.scopedRegistry(PackId("test.pack"))
        registry.register(provider("test.one", "one", 0, 30_000_001))
        registry.register(provider("test.two", "two", 0, 30_000_002))

        registry.close()

        assertTrue(host.state.value.isEmpty)
        assertFailsWith<IllegalStateException> {
            registry.register(provider("test.three", "three", 0, 30_000_003))
        }
    }

    @Test
    fun `host rejects snapshot entries for undeclared layers`() {
        val host = FeatureOverlayHost()
        val registry = host.scopedRegistry(PackId("test.pack"))
        val invalid = object : OverlayProvider {
            override fun descriptor() = OverlayProviderDescriptor("invalid.provider", "Invalid")
            override fun layers() = listOf(OverlayLayer("declared", "Declared"))
            override fun snapshot() = OverlaySnapshot(listOf(OverlayEntry("missing", 30_000_001)))
        }

        assertFailsWith<IllegalArgumentException> { registry.register(invalid) }
        assertTrue(host.state.value.isEmpty)
    }

    private fun provider(
        providerId: String,
        layerId: String,
        priority: Int,
        systemId: Int,
    ) = object : OverlayProvider {
        override fun descriptor() = OverlayProviderDescriptor(providerId, providerId)
        override fun layers() = listOf(OverlayLayer(layerId, layerId, priority = priority))
        override fun snapshot() = OverlaySnapshot(listOf(OverlayEntry(layerId, systemId)))
    }
}
