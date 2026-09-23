package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.FeatureCapability
import dev.evestaticmapplanner.feature.api.FeatureCapabilityKey
import dev.evestaticmapplanner.feature.api.FeatureCapabilityLookup
import dev.evestaticmapplanner.feature.api.StandardFeatureCapabilities

/** Exact ID-and-type lookup over only the capability objects owned by one Pack context. */
internal class PackFeatureCapabilityLookup(
    private val dynamicOverlay: ScopedDynamicOverlayCapability,
    private val routeAction: ScopedRouteActionCapability,
    private val packControls: ScopedPackControlCapability,
    private val characterTracking: ScopedCharacterTrackingCapability,
    private val eveIdentity: ScopedEveIdentityCapability,
    private val allianceDirectory: ScopedAllianceDirectoryCapability,
) : FeatureCapabilityLookup, AutoCloseable {
    override fun <T : FeatureCapability> find(key: FeatureCapabilityKey<T>): T? {
        val capability: FeatureCapability = when (key) {
            StandardFeatureCapabilities.DYNAMIC_OVERLAY -> dynamicOverlay
            StandardFeatureCapabilities.ROUTE_ACTION -> routeAction
            StandardFeatureCapabilities.PACK_CONTROLS -> packControls
            StandardFeatureCapabilities.CHARACTER_TRACKING -> characterTracking
            StandardFeatureCapabilities.EVE_IDENTITY -> eveIdentity
            StandardFeatureCapabilities.ALLIANCE_DIRECTORY -> allianceDirectory
            else -> return null
        }
        return key.type.takeIf { it.isInstance(capability) }?.cast(capability)
    }

    override fun close() {
        allianceDirectory.close()
        eveIdentity.close()
        characterTracking.close()
        packControls.close()
        routeAction.close()
        dynamicOverlay.close()
    }
}
