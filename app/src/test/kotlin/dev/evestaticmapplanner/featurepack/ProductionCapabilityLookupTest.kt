package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.FeatureCapabilityId
import dev.evestaticmapplanner.feature.api.FeatureCapabilityKey
import dev.evestaticmapplanner.feature.api.FeaturePackDescriptor
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.PackVersion
import dev.evestaticmapplanner.feature.api.RouteActionCapability
import dev.evestaticmapplanner.feature.api.RouteActionContext
import dev.evestaticmapplanner.feature.api.RouteActionDescriptor
import dev.evestaticmapplanner.feature.api.RouteActionProvider
import dev.evestaticmapplanner.feature.api.RouteActionResult
import dev.evestaticmapplanner.feature.api.RouteActionStatus
import dev.evestaticmapplanner.feature.api.RouteKind
import dev.evestaticmapplanner.feature.api.StandardFeatureCapabilities
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class ProductionCapabilityLookupTest {
    @Test
    fun `production context finds all V2 standard capabilities and rejects unknown or wrong type keys`() {
        val root = Files.createTempDirectory("production-capability-")
        val overlayHost = FeatureOverlayHost()
        val routeActionHost = RouteActionHost()
        val packControlHost = PackControlHost()
        val context = ProductionFeaturePackRuntime.productionContextFactory(
            root,
            {},
            overlayHost,
            SystemInfoHost(),
            routeActionHost,
            packControlHost,
        ).create(descriptor("test.pack"))
        try {
            assertNotNull(context.capabilities().find(StandardFeatureCapabilities.DYNAMIC_OVERLAY))
            assertNotNull(context.capabilities().find(StandardFeatureCapabilities.ROUTE_ACTION))
            assertNotNull(context.capabilities().find(StandardFeatureCapabilities.PACK_CONTROLS))
            assertNull(context.capabilities().find(FeatureCapabilityKey(
                FeatureCapabilityId("unknown"),
                RouteActionCapability::class.java,
            )))
            assertNull(context.capabilities().find(FeatureCapabilityKey(
                FeatureCapabilityId("dynamic-overlay"),
                RouteActionCapability::class.java,
            )))
        } finally {
            (context as FeaturePackContextLifecycle).closeHostResources()
            routeActionHost.close()
            packControlHost.close()
            overlayHost.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `capability objects and registered resources remain Pack scoped`() {
        val overlayHost = FeatureOverlayHost()
        val routeActionHost = RouteActionHost()
        val packControlHost = PackControlHost()
        try {
            val first = PackFeatureCapabilityLookup(
                overlayHost.scopedDynamicCapability(PackId("first.pack")),
                routeActionHost.scopedCapability(PackId("first.pack")),
                packControlHost.scopedCapability(PackId("first.pack")),
            )
            val second = PackFeatureCapabilityLookup(
                overlayHost.scopedDynamicCapability(PackId("second.pack")),
                routeActionHost.scopedCapability(PackId("second.pack")),
                packControlHost.scopedCapability(PackId("second.pack")),
            )
            val firstActions = assertNotNull(first.find(StandardFeatureCapabilities.ROUTE_ACTION))
            val secondActions = assertNotNull(second.find(StandardFeatureCapabilities.ROUTE_ACTION))
            assertNotSame(firstActions, secondActions)
            firstActions.register(provider("same"))
            secondActions.register(provider("same"))
            assertEquals(setOf("first.pack", "second.pack"), routeActionHost.state.value.map { it.key.packId.value }.toSet())

            first.close()

            assertEquals(listOf("second.pack"), routeActionHost.state.value.map { it.key.packId.value })
            second.close()
            assertEquals(emptyList(), routeActionHost.state.value)
        } finally {
            routeActionHost.close()
            packControlHost.close()
            overlayHost.close()
        }
    }

    private fun descriptor(id: String) = FeaturePackDescriptor(PackId(id), id, PackVersion("1.0.0"), "Tests")

    private fun provider(id: String) = object : RouteActionProvider {
        override fun descriptor() = RouteActionDescriptor(id, "Action", null, setOf(RouteKind.NORMAL))
        override fun execute(context: RouteActionContext) = RouteActionResult(RouteActionStatus.SUCCEEDED, null)
    }
}
