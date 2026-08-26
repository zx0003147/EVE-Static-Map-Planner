package dev.evestaticmapplanner.feature.fixture

import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.FeaturePackDescriptor
import dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint
import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.FeaturePackSession
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.PackRelativePath
import dev.evestaticmapplanner.feature.api.PackVersion
import dev.evestaticmapplanner.feature.api.OverlayEntry
import dev.evestaticmapplanner.feature.api.OverlayLayer
import dev.evestaticmapplanner.feature.api.OverlayProvider
import dev.evestaticmapplanner.feature.api.OverlayProviderDescriptor
import dev.evestaticmapplanner.feature.api.OverlayRegistration
import dev.evestaticmapplanner.feature.api.OverlaySnapshot
import dev.evestaticmapplanner.feature.api.SystemInfoField
import dev.evestaticmapplanner.feature.api.SystemInfoProvider
import dev.evestaticmapplanner.feature.api.SystemInfoProviderDescriptor
import dev.evestaticmapplanner.feature.api.SystemInfoRegistration
import dev.evestaticmapplanner.feature.api.SystemInfoSection
import dev.evestaticmapplanner.feature.api.SystemInfoSnapshot
import java.net.http.HttpClient
import java.sql.DriverManager

class MinimalFixturePack : FeaturePackEntrypoint {
    override fun descriptor(): FeaturePackDescriptor = FeaturePackDescriptor(
        packId = PackId("fixture.pack"),
        displayName = "Minimal Fixture Pack",
        packVersion = PackVersion("0.0.1-test"),
        publisher = "EVE Static Map Planner Tests",
    )

    override fun start(context: FeaturePackContext): FeaturePackSession {
        HttpClient.newBuilder()
        check(HttpClient::class.java.module.name == "java.net.http")
        check(DriverManager::class.java.module.name == "java.sql")
        val resource = javaClass.getResourceAsStream("/fixture-pack-resource.txt")
            ?.bufferedReader()
            ?.use { it.readText().trim() }
        check(resource == "fixture-resource-ok") { "Fixture Pack resource was not available through its ClassLoader" }
        context.hostInfo()
        context.storage().configPath(PackRelativePath("fixture.properties"))
        context.logger().log(FeaturePackLogLevel.INFO, "Fixture Pack started", null)
        val overlayRegistration = context.overlays().register(FixtureOverlayProvider)
        val systemInfoRegistration = context.systemInfo().register(FixtureSystemInfoProvider)
        return FixtureSession(context, overlayRegistration, systemInfoRegistration)
    }

    private class FixtureSession(
        private val context: FeaturePackContext,
        private val overlayRegistration: OverlayRegistration,
        private val systemInfoRegistration: SystemInfoRegistration,
    ) : FeaturePackSession {
        override fun close() {
            systemInfoRegistration.close()
            overlayRegistration.close()
            context.logger().log(FeaturePackLogLevel.INFO, "Fixture Pack stopped", null)
        }
    }

    private object FixtureOverlayProvider : OverlayProvider {
        override fun descriptor() = OverlayProviderDescriptor(
            id = "fixture.pack.systems",
            name = "Fixture Systems",
            description = "Static overlay fixture used by architecture tests",
        )

        override fun layers() = listOf(
            OverlayLayer(
                id = "fixture",
                name = "Fixture Layer",
                description = "A deterministic test layer",
                priority = 10,
            ),
        )

        override fun snapshot() = OverlaySnapshot(
            listOf(
                OverlayEntry(
                    layerId = "fixture",
                    systemId = 30_000_142,
                    title = "Fixture System",
                    subtitle = "OV-1 provider fixture",
                    value = "fixture",
                ),
            ),
        )
    }

    private object FixtureSystemInfoProvider : SystemInfoProvider {
        override fun descriptor() = SystemInfoProviderDescriptor(
            id = "fixture.pack.system-info",
            name = "Fixture System Info",
            priority = 10,
        )

        override fun provide(systemId: Int) = SystemInfoSnapshot(
            systemId = systemId,
            sections = if (systemId == 30_000_142) {
                listOf(
                    SystemInfoSection(
                        sectionId = "fixture",
                        title = "Fixture Information",
                        priority = 10,
                        fields = listOf(
                            SystemInfoField("source", "Source", "OV-3 fixture provider"),
                        ),
                    ),
                )
            } else {
                emptyList()
            },
        )
    }
}

class SecondFixturePack : FeaturePackEntrypoint {
    override fun descriptor(): FeaturePackDescriptor = FeaturePackDescriptor(
        packId = PackId("fixture.second"),
        displayName = "Second Fixture Pack",
        packVersion = PackVersion("0.0.1-test"),
        publisher = "EVE Static Map Planner Tests",
    )

    override fun start(context: FeaturePackContext): FeaturePackSession = object : FeaturePackSession {
        override fun close() = Unit
    }
}

class StartupFailureFixturePack : FeaturePackEntrypoint {
    override fun descriptor(): FeaturePackDescriptor = FeaturePackDescriptor(
        packId = PackId("fixture.startup-failure"),
        displayName = "Startup Failure Fixture Pack",
        packVersion = PackVersion("0.0.1-test"),
        publisher = "EVE Static Map Planner Tests",
    )

    override fun start(context: FeaturePackContext): FeaturePackSession =
        throw IllegalStateException("Deliberate fixture startup failure")
}

class CloseFailureFixturePack : FeaturePackEntrypoint {
    override fun descriptor(): FeaturePackDescriptor = FeaturePackDescriptor(
        packId = PackId("fixture.close-failure"),
        displayName = "Close Failure Fixture Pack",
        packVersion = PackVersion("0.0.1-test"),
        publisher = "EVE Static Map Planner Tests",
    )

    override fun start(context: FeaturePackContext): FeaturePackSession = object : FeaturePackSession {
        override fun close() {
            throw IllegalStateException("Deliberate fixture close failure")
        }
    }
}
