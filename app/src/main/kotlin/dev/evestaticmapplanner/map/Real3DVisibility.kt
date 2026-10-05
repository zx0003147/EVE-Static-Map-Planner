package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.Real3DStaticGeometry

internal data class Real3DRegionVisibility(
    /** Null means semantic labels are not restricted to a region subset. */
    val detailRegionIds: Set<Int>?,
    /** Null means normal Stargates are unfiltered; an empty set hides every normal Stargate. */
    val visibleConnectionKeys: Set<Long>?,
) {
    companion object {
        /** Returns the anchor system's region and regions connected to it directly by a Stargate. */
        fun detailRegionIds(
            geometry: Real3DStaticGeometry,
            anchorSystemId: Int?,
        ): Set<Int>? {
            val anchorRegionId = anchorSystemId
                ?.let(geometry.nodesById::get)
                ?.system
                ?.regionId
                ?: return null
            val detailRegionIds = linkedSetOf(anchorRegionId)
            geometry.edges.forEach { edge ->
                val firstRegionId = geometry.nodesById.getValue(edge.firstSystemId).system.regionId
                val secondRegionId = geometry.nodesById.getValue(edge.secondSystemId).system.regionId
                when (anchorRegionId) {
                    firstRegionId -> detailRegionIds += secondRegionId
                    secondRegionId -> detailRegionIds += firstRegionId
                }
            }
            return detailRegionIds
        }
    }
}

/**
 * Resolves the normal-stargate subset shown by Real 3D without involving route state.
 * A neighboring region is one connected directly to the focused region by a stargate.
 */
internal object Real3DStargateVisibility {
    fun resolve(
        geometry: Real3DStaticGeometry,
        focusedSystemId: Int?,
        filteringEnabled: Boolean,
    ): Real3DRegionVisibility {
        if (!filteringEnabled) return Real3DRegionVisibility(
            detailRegionIds = null,
            visibleConnectionKeys = null,
        )
        val detailRegionIds = Real3DRegionVisibility.detailRegionIds(geometry, focusedSystemId)
            ?: return Real3DRegionVisibility(
                detailRegionIds = null,
                visibleConnectionKeys = emptySet(),
            )
        val visibleConnectionKeys = geometry.edges.asSequence()
            .filter { edge ->
                geometry.nodesById.getValue(edge.firstSystemId).system.regionId in detailRegionIds &&
                    geometry.nodesById.getValue(edge.secondSystemId).system.regionId in detailRegionIds
            }
            .mapTo(linkedSetOf()) { edge -> real3DSystemPairKey(edge.firstSystemId, edge.secondSystemId) }
        return Real3DRegionVisibility(detailRegionIds, visibleConnectionKeys)
    }

    /** A null result means no filtering; an empty result means hide every normal stargate. */
    fun visibleConnectionKeys(
        geometry: Real3DStaticGeometry,
        focusedSystemId: Int?,
        filteringEnabled: Boolean,
    ): Set<Long>? = resolve(geometry, focusedSystemId, filteringEnabled).visibleConnectionKeys

}
