package dev.evestaticmapplanner.featurepack

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

class FeaturePackInstalledImageTest {
    @Test
    fun `installed production launcher loads external Pack from spaced Windows paths`() {
        val configuredImage = System.getProperty(IMAGE_PROPERTY)
        assumeTrue(!configuredImage.isNullOrBlank(), "Runs only in featurePackInstalledImageTest")
        val fixture = Path.of(requireNotNull(System.getProperty(FIXTURE_PROPERTY)))
        val root = createTempDirectory("fp-2b-installed-image-")
        try {
            val installedImage = root.resolve("Program Files Style Path/EVE Static Map Planner")
            copyTree(Path.of(configuredImage), installedImage)
            val localAppData = root.resolve("User Profile With Spaces/AppData/Local")
            val packJar = localAppData.resolve(
                "EVE Static Map Planner/feature-packs/fixture.pack/pack.jar",
            )
            packJar.parent.createDirectories()
            Files.copy(fixture, packJar)
            val managerState = localAppData.resolve("EVE Static Map Planner/feature-pack-manager.properties")
            PropertiesFeaturePackManagerStateStore(managerState).save(
                mapOf(dev.evestaticmapplanner.feature.api.PackId("fixture.pack") to StoredFeaturePackState(true)),
            )
            val report = root.resolve("User Profile With Spaces/validation reports/with-pack.txt")

            launch(installedImage, localAppData, report)

            val validation = Files.readString(report)
            assertTrue(validation.contains("candidateCount=1"))
            assertTrue(validation.contains("loadedPackIds=fixture.pack"))
            assertTrue(validation.contains("Fixture Pack started"))
            assertTrue(validation.contains("Fixture Pack stopped"))
            assertTrue(validation.contains("closeFailures="))
            assertTrue(validation.contains("lifecycleClosed=true"))
            assertTrue(validation.contains("coreContinued=true"))
            assertFalse(Files.walk(installedImage).use { paths ->
                paths.anyMatch { it.fileName.toString().equals("pack.jar", ignoreCase = true) }
            })
            assertEquals(1, Files.walk(installedImage).use { paths ->
                paths.filter { Files.isDirectory(it) && it.fileName.toString().equals("runtime", true) }.count()
            })
            assertEquals(1, Files.walk(installedImage).use { paths ->
                paths.filter {
                    Files.isRegularFile(it) && it.fileName.toString() == "modules" &&
                        it.parent?.fileName?.toString() == "lib" &&
                        it.parent?.parent?.fileName?.toString().equals("runtime", true)
                }.count()
            })
            assertFixtureHasNoBundledRuntime(fixture)

            Files.delete(packJar)
            Files.delete(packJar.parent)
            val noPackReport = root.resolve("User Profile With Spaces/validation reports/no-pack.txt")
            launch(installedImage, localAppData, noPackReport)
            val noPack = Files.readString(noPackReport)
            assertTrue(noPack.contains("candidateCount=0"))
            assertTrue(noPack.contains("loadedPackIds="))
            assertTrue(noPack.contains("startupFailures="))
            assertTrue(noPack.contains("coreContinued=true"))
            assertFalse(Files.exists(localAppData.resolve("EVE Static Map Planner/feature-pack-storage")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun launch(installedImage: Path, localAppData: Path, report: Path) {
        report.parent.createDirectories()
        val process = ProcessBuilder(
            installedImage.resolve("EVE Static Map Planner.exe").toString(),
            FeaturePackRuntimeValidationArguments.OPTION,
            report.toString(),
        ).apply {
            environment()["LOCALAPPDATA"] = localAppData.toString()
        }.start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Installed launcher did not exit")
        assertEquals(0, process.exitValue())
        assertTrue(Files.isRegularFile(report), "Installed launcher did not write its validation report")
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

    private fun assertFixtureHasNoBundledRuntime(fixture: Path) {
        JarFile(fixture.toFile()).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toList()
            assertFalse(entries.any { it.startsWith("kotlin/") })
            assertFalse(entries.any { it.startsWith("dev/evestaticmapplanner/feature/api/") })
            assertFalse(entries.any { it.endsWith("java.exe", ignoreCase = true) })
        }
    }

    private companion object {
        const val IMAGE_PROPERTY = "feature.pack.installed.image"
        const val FIXTURE_PROPERTY = "feature.pack.fixture.jar"
    }
}
