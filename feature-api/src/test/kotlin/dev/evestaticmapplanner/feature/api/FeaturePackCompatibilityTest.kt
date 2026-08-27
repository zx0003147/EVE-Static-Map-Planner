package dev.evestaticmapplanner.feature.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeaturePackCompatibilityTest {
    private val platform = HostPlatform("windows", "x64")

    @Test
    fun `current API identity is canonical frozen v1`() {
        val version = FeatureApiVersions.current()

        assertTrue(version.identifier == "1")
        assertTrue(version.frozen)
    }

    @Test
    fun `compatibility uses exact format and API plus minimum Core version`() {
        val apiVersion = FeatureApiVersions.current()
        val requirement = FeaturePackCompatibility(
            packFormatVersion = 1,
            featureApiVersion = apiVersion,
            minimumCoreVersion = CoreVersion(0, 3, 0),
        )

        assertTrue(requirement.isCompatibleWith(host(CoreVersion(0, 3, 0), apiVersion), 1))
        assertTrue(requirement.isCompatibleWith(host(CoreVersion(0, 4, 0), apiVersion), 1))
        assertFalse(requirement.isCompatibleWith(host(CoreVersion(0, 2, 9), apiVersion), 1))
        assertFalse(requirement.isCompatibleWith(host(CoreVersion(0, 3, 0), FeatureApiVersion("other", false)), 1))
        assertFalse(requirement.isCompatibleWith(host(CoreVersion(0, 3, 0), apiVersion), 2))
    }

    private fun host(coreVersion: CoreVersion, apiVersion: FeatureApiVersion) = FeaturePackHostInfo(
        coreVersion = coreVersion,
        featureApiVersion = apiVersion,
        platform = platform,
    )
}
