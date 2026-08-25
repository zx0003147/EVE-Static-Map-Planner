package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint
import dev.evestaticmapplanner.feature.api.PackId
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class SovereigntyPackIntegrationTest {
    @Test
    fun `external Pack appears in manager and ServiceLoader constructs its production entrypoint`() =
        withTempDirectory { root ->
            val packRoot = root.resolve("feature-packs")
            val destination = packRoot.resolve("sovereignty.pack/pack.jar")
            destination.parent.createDirectories()
            Files.copy(sovereigntyPackJar, destination)
            val manager = FeaturePackManager(
                packRoot = packRoot,
                registry = LocalFeaturePackRegistry(
                    packRoot,
                    PropertiesFeaturePackManagerStateStore(root.resolve("feature-pack-manager.properties")),
                ),
                contextFactory = FeaturePackContextFactory {
                    error("Construction-only regression must not start the production Pack")
                },
            )

            val installed = manager.refresh().packs.single()
            assertEquals(PackId("sovereignty.pack"), installed.packId)
            assertEquals("Sovereignty Pack", installed.displayName)
            assertEquals(FeaturePackRuntimeState.DISABLED, manager.state.value.packs.single().runtimeState)

            URLClassLoader(
                arrayOf(destination.toUri().toURL()),
                FeaturePackEntrypoint::class.java.classLoader,
            ).use { classLoader ->
                val entrypoint = ServiceLoader.load(FeaturePackEntrypoint::class.java, classLoader).single()
                assertEquals(
                    "dev.evestaticmapplanner.sovereignty.SovereigntyFeaturePack",
                    entrypoint.javaClass.name,
                )
                assertEquals(installed.packId, entrypoint.descriptor().packId)
                assertEquals(installed.displayName, entrypoint.descriptor().displayName)
            }
        }

    private inline fun withTempDirectory(block: (Path) -> Unit) {
        val root = createTempDirectory("sv-3c-1-integration-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        val sovereigntyPackJar: Path
            get() = Path.of(requireNotNull(System.getProperty("sovereignty.pack.fixture.jar")))
    }
}
