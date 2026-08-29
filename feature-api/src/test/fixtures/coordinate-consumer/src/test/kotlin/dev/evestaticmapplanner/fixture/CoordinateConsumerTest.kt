package dev.evestaticmapplanner.fixture

import dev.evestaticmapplanner.feature.api.FeatureApiVersions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoordinateConsumerTest {
    @Test
    fun `published Feature API is available on the independent test classpath`() {
        val version = FeatureApiVersions.current()

        assertEquals("2", version.identifier)
        assertTrue(version.frozen)
    }
}
