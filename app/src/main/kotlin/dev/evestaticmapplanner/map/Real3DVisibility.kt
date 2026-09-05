package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.Real3DStaticGeometry

/**
 * Resolves the normal-stargate subset shown by Real 3D without involving route state.
 * A neighboring region is one connected directly to the focused region by a stargate.
 */
internal object Real3DStargateVisibility {
    /** A null result means no filtering; an empty result means hide every normal stargate. */
    fun visibleConnectionKeys(
        geometry: Real3DStaticGeometry,
        focusedSystemId: Int?,
        filteringEnabled: Boolean,
    ): Set<Long>? {
        if (!filteringEnabled) return null
        val focusedRegionId = focusedSystemId
            ?.let(geometry.nodesById::get)
            ?.system
            ?.regionId
            ?: return emptySet()
        val visibleRegionIds = linkedSetOf(focusedRegionId)
        geometry.edges.forEach { edge ->
            val firstRegionId = geometry.nodesById.getValue(edge.firstSystemId).system.regionId
            val secondRegionId = geometry.nodesById.getValue(edge.secondSystemId).system.regionId
            when (focusedRegionId) {
                firstRegionId -> visibleRegionIds += secondRegionId
                secondRegionId -> visibleRegionIds += firstRegionId
            }
        }
        return geometry.edges.asSequence()
            .filter { edge ->
                geometry.nodesById.getValue(edge.firstSystemId).system.regionId in visibleRegionIds &&
                    geometry.nodesById.getValue(edge.secondSystemId).system.regionId in visibleRegionIds
            }
            .mapTo(linkedSetOf()) { edge -> real3DSystemPairKey(edge.firstSystemId, edge.secondSystemId) }
    }
}
