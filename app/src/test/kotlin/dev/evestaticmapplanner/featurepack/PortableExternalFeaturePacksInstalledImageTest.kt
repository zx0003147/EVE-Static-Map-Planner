package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.PackId
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortableExternalFeaturePacksInstalledImageTest {
    @Test
    fun `extracted Portable launcher loads ESI and Sovereignty Packs from LocalAppData`() {
        val configuredImage = System.getProperty(IMAGE_PROPERTY)
        val configuredEsiPack = System.getProperty(ESI_PACK_PROPERTY)
        val configuredSovereigntyPack = System.getProperty(SOVEREIGNTY_PACK_PROPERTY)
        assumeTrue(
            !configuredImage.isNullOrBlank() &&
                !configuredEsiPack.isNullOrBlank() &&
                !configuredSovereigntyPack.isNullOrBlank(),
            "Runs only in portableExternalFeaturePacksInstalledImageTest",
        )

        val root = createTempDirectory("portable-external-packs-")
        try {
            val localAppData = root.resolve("Fresh User/AppData/Local")
            val applicationRoot = localAppData.resolve("EVE Static Map Planner")
            copyPack(configuredEsiPack, applicationRoot.resolve("feature-packs/esi.pack/pack.jar"))
            copyPack(
                configuredSovereigntyPack,
                applicationRoot.resolve("feature-packs/sovereignty.pack/pack.jar"),
            )
            val sovereigntyCache = applicationRoot.resolve(
                "feature-pack-storage/sovereignty.pack/cache/public-esi-lkg.json",
            )
            sovereigntyCache.parent.createDirectories()
            Files.writeString(sovereigntyCache, VALID_PUBLIC_ESI_CACHE)
            PropertiesFeaturePackManagerStateStore(
                applicationRoot.resolve("feature-pack-manager.properties"),
            ).save(
                mapOf(
                    PackId("esi.pack") to StoredFeaturePackState(enabled = true),
                    PackId("sovereignty.pack") to StoredFeaturePackState(enabled = true),
                ),
            )
            val report = root.resolve("reports/portable-packs.txt")
            report.parent.createDirectories()
            val stdout = root.resolve("stdout.txt")
            val stderr = root.resolve("stderr.txt")
            val process = ProcessBuilder(
                Path.of(configuredImage).resolve("EVE Static Map Planner.exe").toString(),
                FeaturePackRuntimeValidationArguments.OPTION,
                report.toString(),
            ).apply {
                directory(root.resolve("Unrelated Working Directory").createDirectories().toFile())
                environment()["LOCALAPPDATA"] = localAppData.toString()
                environment().remove("JAVA_HOME")
                environment().remove("JDK_HOME")
                redirectOutput(stdout.toFile())
                redirectError(stderr.toFile())
            }.start()

            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Portable launcher did not exit")
            assertEquals(0, process.exitValue())
            val validation = Files.readString(report)
            assertTrue(validation.contains("candidateCount=2"))
            assertEquals(
                setOf("esi.pack", "sovereignty.pack"),
                validation.lineSequence().single { it.startsWith("loadedPackIds=") }
                    .substringAfter('=').split(',').filter(String::isNotBlank).toSet(),
            )
            assertTrue(validation.contains("packControlPackIds=esi.pack"))
            assertTrue(validation.contains("startupFailures="))
            assertTrue(validation.contains("closeFailures="))
            val output = Files.readString(stdout) + Files.readString(stderr)
            assertFalse(output.contains("restricted method", ignoreCase = true))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun copyPack(source: String, destination: Path) {
        destination.parent.createDirectories()
        Files.copy(Path.of(source), destination)
    }

    private companion object {
        const val IMAGE_PROPERTY = "feature.pack.installed.image"
        const val ESI_PACK_PROPERTY = "esi.pack.jar"
        const val SOVEREIGNTY_PACK_PROPERTY = "sovereignty.pack.jar"
        val VALID_PUBLIC_ESI_CACHE = """
            {
              "formatVersion": 2,
              "source": "PUBLIC_ESI",
              "records": [
                {"systemId": 30004759, "allianceId": 99000001, "allianceName": "Cached Alliance", "corporationName": null, "sovereigntyStatus": "Claimed"}
              ]
            }
        """.trimIndent()
    }
}
