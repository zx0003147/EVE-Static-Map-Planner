package dev.evestaticmapplanner.feature.fixture

import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.FeaturePackDescriptor
import dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint
import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.FeaturePackSession
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.PackRelativePath
import dev.evestaticmapplanner.feature.api.PackVersion

class MinimalFixturePack : FeaturePackEntrypoint {
    override fun descriptor(): FeaturePackDescriptor = FeaturePackDescriptor(
        packId = PackId("fixture.pack"),
        displayName = "Minimal Fixture Pack",
        packVersion = PackVersion("0.0.1-test"),
        publisher = "EVE Static Map Planner Tests",
    )

    override fun start(context: FeaturePackContext): FeaturePackSession {
        context.hostInfo()
        context.storage().configPath(PackRelativePath("fixture.properties"))
        context.logger().log(FeaturePackLogLevel.INFO, "Fixture Pack started", null)
        return FixtureSession(context)
    }

    private class FixtureSession(
        private val context: FeaturePackContext,
    ) : FeaturePackSession {
        override fun close() {
            context.logger().log(FeaturePackLogLevel.INFO, "Fixture Pack stopped", null)
        }
    }
}
