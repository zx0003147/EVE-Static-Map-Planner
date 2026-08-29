package dev.evestaticmapplanner.fixture

import dev.evestaticmapplanner.feature.api.FeatureApiVersions
import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.FeaturePackDescriptor
import dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint
import dev.evestaticmapplanner.feature.api.FeaturePackSession
import dev.evestaticmapplanner.feature.api.OverlayEntry
import dev.evestaticmapplanner.feature.api.OverlayLayer
import dev.evestaticmapplanner.feature.api.OverlayProvider
import dev.evestaticmapplanner.feature.api.OverlayProviderDescriptor
import dev.evestaticmapplanner.feature.api.OverlaySnapshot
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.PackVersion

class CoordinateConsumerPack : FeaturePackEntrypoint {
    init {
        check(FeatureApiVersions.current().identifier == "2")
        check(FeatureApiVersions.current().frozen)
    }

    override fun descriptor() = FeaturePackDescriptor(
        packId = PackId("coordinate.consumer.fixture"),
        displayName = "Coordinate Consumer Fixture",
        packVersion = PackVersion("0.0.1-test"),
        publisher = "EVE Static Map Planner Tests",
    )

    override fun start(context: FeaturePackContext): FeaturePackSession {
        context.capabilities()
        val registration = context.overlays().register(CoordinateOverlayProvider)
        return object : FeaturePackSession {
            override fun close() = registration.close()
        }
    }

    private object CoordinateOverlayProvider : OverlayProvider {
        override fun descriptor() = OverlayProviderDescriptor(
            id = "coordinate.consumer.fixture.overlay",
            name = "Coordinate Consumer Fixture",
        )

        override fun layers() = listOf(OverlayLayer("fixture", "Fixture"))

        override fun snapshot() = OverlaySnapshot(
            listOf(OverlayEntry(layerId = "fixture", systemId = 30_000_142)),
        )
    }
}
