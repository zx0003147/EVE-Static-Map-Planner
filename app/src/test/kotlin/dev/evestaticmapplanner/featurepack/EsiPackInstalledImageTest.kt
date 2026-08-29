package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.PackId
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EsiPackInstalledImageTest {
    @Test
    fun `production launcher loads the real ESI Pack with native access enabled`() {
        val configuredImage = System.getProperty(IMAGE_PROPERTY)
        val configuredPack = System.getProperty(PACK_PROPERTY)
        assumeTrue(
            !configuredImage.isNullOrBlank() && !configuredPack.isNullOrBlank(),
            "Runs only in esiPackInstalledImageTest",
        )
        val pack = Path.of(requireNotNull(configuredPack))
        val root = createTempDirectory("esi-pack-installed-image-")
        try {
            val installedImage = root.resolve("Program Files Style Path/EVE Static Map Planner")
            copyTree(Path.of(configuredImage), installedImage)
            assertNativeAccessConfigured(installedImage)

            val localAppData = root.resolve("User Profile With Spaces/AppData/Local")
            val installedPack = localAppData.resolve(
                "EVE Static Map Planner/feature-packs/esi.pack/pack.jar",
            )
            installedPack.parent.createDirectories()
            Files.copy(pack, installedPack)
            PropertiesFeaturePackManagerStateStore(
                localAppData.resolve("EVE Static Map Planner/feature-pack-manager.properties"),
            ).save(mapOf(PackId("esi.pack") to StoredFeaturePackState(enabled = true)))
            val report = root.resolve("User Profile With Spaces/validation reports/esi-pack.txt")
            val stdout = root.resolve("launcher-stdout.txt")
            val stderr = root.resolve("launcher-stderr.txt")

            val process = ProcessBuilder(
                installedImage.resolve("EVE Static Map Planner.exe").toString(),
                FeaturePackRuntimeValidationArguments.OPTION,
                report.toString(),
            ).apply {
                environment()["LOCALAPPDATA"] = localAppData.toString()
                redirectOutput(stdout.toFile())
                redirectError(stderr.toFile())
            }.start()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Installed launcher did not exit")
            assertEquals(0, process.exitValue())

            val validation = Files.readString(report)
            assertTrue(validation.contains("candidateCount=1"))
            assertTrue(validation.contains("loadedPackIds=esi.pack"))
            assertTrue(validation.contains("packControlPackIds=esi.pack"))
            assertTrue(validation.contains("startupFailures="))
            assertTrue(validation.contains("closeFailures="))
            assertTrue(validation.contains("lifecycleClosed=true"))
            assertTrue(validation.contains("coreContinued=true"))
            val launcherOutput = Files.readString(stdout) + Files.readString(stderr)
            assertFalse(launcherOutput.contains("restricted method", ignoreCase = true))
            assertPackBoundary(pack)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun assertNativeAccessConfigured(installedImage: Path) {
        val configuration = Files.readString(
            installedImage.resolve("app/EVE Static Map Planner.cfg"),
        )
        assertEquals(
            1,
            configuration.lineSequence().count {
                it.trim() == "java-options=--enable-native-access=ALL-UNNAMED"
            },
        )
    }

    private fun assertPackBoundary(pack: Path) {
        JarFile(pack.toFile()).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toList()
            assertFalse(entries.any { it.startsWith("kotlin/") })
            assertFalse(entries.any { it.startsWith("dev/evestaticmapplanner/feature/api/") })
            assertFalse(entries.any {
                it.endsWith(".class") &&
                    it.startsWith("dev/evestaticmapplanner/") &&
                    !it.startsWith("dev/evestaticmapplanner/esi/")
            })
            assertFalse(entries.any { it.endsWith("java.exe", ignoreCase = true) })
        }
    }

    private fun copyTree(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val target = destination.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }

    private companion object {
        const val IMAGE_PROPERTY = "feature.pack.installed.image"
        const val PACK_PROPERTY = "esi.pack.jar"
    }
}
