package dev.evestaticmapplanner.packaging

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LauncherRuntimeCompatibilityTest {
    @Test
    fun `accepts audited local JDK runtime`() {
        assertTrue(LauncherRuntimeCompatibility.isSupported("25.0.4+7"))
    }

    @Test
    fun `accepts audited GitHub Actions JDK runtime`() {
        assertTrue(LauncherRuntimeCompatibility.isSupported("25.0.4.1+1-LTS"))
    }

    @Test
    fun `accepts build metadata changes within the audited JDK family`() {
        assertTrue(LauncherRuntimeCompatibility.isSupported("25.0.4+11-LTS"))
        assertTrue(LauncherRuntimeCompatibility.isSupported("25.0.4.2+3"))
    }

    @Test
    fun `rejects unsupported JDK versions and prereleases`() {
        listOf(
            "25.0.3+9",
            "25.0.5+1",
            "25.1.0+1",
            "26+1",
            "25.0.4-ea+1",
            "not-a-java-version",
        ).forEach { runtimeVersion ->
            assertFalse(
                LauncherRuntimeCompatibility.isSupported(runtimeVersion),
                "Unexpectedly accepted $runtimeVersion",
            )
        }
    }

    @Test
    fun `rejection reports the supported family and actual runtime`() {
        val failure = assertFailsWith<IllegalStateException> {
            LauncherRuntimeCompatibility.requireSupported("25.0.5+1")
        }

        assertTrue(failure.message.orEmpty().contains("GA JDK 25.0.4 family"))
        assertTrue(failure.message.orEmpty().contains("25.0.5+1"))
    }
}
