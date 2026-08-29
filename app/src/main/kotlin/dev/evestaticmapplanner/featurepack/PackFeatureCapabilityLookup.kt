package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.FeatureCapability
import dev.evestaticmapplanner.feature.api.FeatureCapabilityKey
import dev.evestaticmapplanner.feature.api.FeatureCapabilityLookup
import dev.evestaticmapplanner.feature.api.StandardFeatureCapabilities

/** Exact ID-and-type lookup over only the two capability objects owned by one Pack context. */
internal class PackFeatureCapabilityLookup(
    private val dynamicOverlay: ScopedDynamicOverlayCapability,
    private val routeAction: ScopedRouteActionCapability,
) : FeatureCapabilityLookup, AutoCloseable {
    override fun <T : FeatureCapability> find(key: FeatureCapabilityKey<T>): T? {
        val capability: FeatureCapability = when (key) {
            StandardFeatureCapabilities.DYNAMIC_OVERLAY -> dynamicOverlay
            StandardFeatureCapabilities.ROUTE_ACTION -> routeAction
            else -> return null
        }
        return key.type.takeIf { it.isInstance(capability) }?.cast(capability)
    }

    override fun close() {
        routeAction.close()
        dynamicOverlay.close()
    }
}
