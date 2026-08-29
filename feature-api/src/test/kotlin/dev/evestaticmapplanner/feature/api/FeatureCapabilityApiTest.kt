package dev.evestaticmapplanner.feature.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class FeatureCapabilityApiTest {
    @Test
    fun `capability ID accepts canonical syntax`() {
        listOf("a", "dynamic-overlay", "route_action", "vendor.capability2").forEach { value ->
            assertEquals(value, FeatureCapabilityId(value).value)
        }
    }

    @Test
    fun `capability ID rejects non-canonical syntax`() {
        listOf(
            "",
            "A",
            " leading",
            "trailing ",
            "-leading",
            "trailing-",
            "two--parts",
            "two._parts",
            "white space",
            "line\nbreak",
            "a".repeat(65),
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>("value=$value") { FeatureCapabilityId(value) }
        }
    }

    @Test
    fun `empty lookup is stateless typed and always absent`() {
        val lookup = FeatureCapabilityLookup.empty()

        assertSame(lookup, FeatureCapabilityLookup.empty())
        assertNull(lookup.find(StandardFeatureCapabilities.DYNAMIC_OVERLAY))
        assertNull(lookup.find(StandardFeatureCapabilities.ROUTE_ACTION))
        assertNull(lookup.find(StandardFeatureCapabilities.PACK_CONTROLS))
    }

    @Test
    fun `capability keys retain both ID and expected Java type`() {
        val first = FeatureCapabilityKey(FeatureCapabilityId("test"), FirstCapability::class.java)
        val same = FeatureCapabilityKey(FeatureCapabilityId("test"), FirstCapability::class.java)
        val otherType = FeatureCapabilityKey(FeatureCapabilityId("test"), SecondCapability::class.java)

        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertEquals(FirstCapability::class.java, first.type)
        kotlin.test.assertNotEquals<Any?>(first, otherType)
    }

    @Test
    fun `standard capability keys have frozen IDs and types`() {
        assertEquals(FeatureCapabilityId("dynamic-overlay"), StandardFeatureCapabilities.DYNAMIC_OVERLAY.id)
        assertEquals(DynamicOverlayCapability::class.java, StandardFeatureCapabilities.DYNAMIC_OVERLAY.type)
        assertEquals(FeatureCapabilityId("route-action"), StandardFeatureCapabilities.ROUTE_ACTION.id)
        assertEquals(RouteActionCapability::class.java, StandardFeatureCapabilities.ROUTE_ACTION.type)
        assertEquals(FeatureCapabilityId("pack-controls"), StandardFeatureCapabilities.PACK_CONTROLS.id)
        assertEquals(PackControlCapability::class.java, StandardFeatureCapabilities.PACK_CONTROLS.type)
    }

    private interface FirstCapability : FeatureCapability

    private interface SecondCapability : FeatureCapability
}
