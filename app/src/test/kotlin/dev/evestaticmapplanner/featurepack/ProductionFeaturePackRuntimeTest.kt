package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint
import dev.evestaticmapplanner.feature.api.PackId
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductionFeaturePackRuntimeTest {
    @Test
    fun `production directory uses the user-writable Windows local application data root`() =
        withTempDirectory("User Profile With Spaces") { localAppData ->
            val resolved = ProductionFeaturePackDirectories.resolve(
                environment = mapOf("LOCALAPPDATA" to localAppData.toString()),
                osName = "Windows 11",
                userHome = localAppData.resolve("ignored-home"),
            )

            assertEquals(
                localAppData.resolve("EVE Static Map Planner/feature-packs").toAbsolutePath().normalize(),
                resolved,
            )
            resolved.createDirectories()
            val probe = resolved.resolve("write-probe.txt")
            probe.writeText("writable")
            assertEquals("writable", Files.readString(probe))
        }

    @Test
    fun `missing production directory is a clean no-Pack startup with no worker threads or storage`() =
        withTempDirectory("fp-2b-no-pack-") { applicationRoot ->
            val packRoot = applicationRoot.resolve("feature-packs")
            val storageRoot = applicationRoot.resolve("feature-pack-storage")
            val threadsBefore = liveNonDaemonThreadIds()
            val featureWorkerThreadsBefore = liveFeatureWorkerThreadNames()
            var classLoaderCreations = 0
            val host = LocalFeaturePackHost(
                FeaturePackEntrypoint::class.java.classLoader,
                FeaturePackClassLoaderFactory { jarUrl, parent ->
                    classLoaderCreations += 1
                    URLClassLoader(arrayOf(jarUrl), parent)
                },
            )

            val runtime = ProductionFeaturePackRuntime.start(packRoot, applicationRoot, host = host)
            val close = runtime.closeSafely()

            assertTrue(runtime.startReport.candidates.isEmpty())
            assertTrue(runtime.startReport.loadedPackIds.isEmpty())
            assertTrue(runtime.startReport.failures.isEmpty())
            assertTrue(close.failures.isEmpty())
            assertFalse(Files.exists(packRoot))
            assertFalse(Files.exists(storageRoot))
            assertEquals(0, classLoaderCreations)
            assertEquals(threadsBefore, liveNonDaemonThreadIds())
            assertEquals(featureWorkerThreadsBefore, liveFeatureWorkerThreadNames())
            assertTrue(runtime.routeActionHost.state.value.isEmpty())
            assertTrue(runtime.packControlHost.state.value.isEmpty())
        }

    @Test
    fun `startup does not scan or load installed Packs when none are enabled`() =
        withTempDirectory("fp-3-disabled-pack-") { applicationRoot ->
            val packRoot = applicationRoot.resolve("feature-packs")
            Files.copy(fixtureJar, packRoot.resolve("fixture.pack/pack.jar").also { it.parent.createDirectories() })
            val events = mutableListOf<String>()

            val runtime = ProductionFeaturePackRuntime.start(packRoot, applicationRoot, events::add)

            assertTrue(runtime.startReport.candidates.isEmpty())
            assertTrue(runtime.startReport.loadedPackIds.isEmpty())
            assertFalse(runtime.manager.state.value.initialized)
            assertTrue(events.isEmpty())
            assertTrue(runtime.closeSafely().failures.isEmpty())
        }

    @Test
    fun `enabling Pack registers System Info provider and disabling Pack removes it`() =
        withTempDirectory("ov-3-system-info-lifecycle-") { applicationRoot ->
            val packRoot = applicationRoot.resolve("feature-packs")
            Files.copy(fixtureJar, packRoot.resolve("fixture.pack/pack.jar").also { it.parent.createDirectories() })
            val runtime = ProductionFeaturePackRuntime.start(packRoot, applicationRoot)
            val packId = PackId("fixture.pack")

            assertTrue(runtime.systemInfoHost.request(30_000_142).isEmpty)

            assertTrue(runtime.manager.setEnabled(packId, true).isSuccess)
            assertEquals(
                listOf("fixture"),
                runtime.systemInfoHost.request(30_000_142).sections.map { it.sectionId },
            )

            assertTrue(runtime.manager.setEnabled(packId, false).isSuccess)
            assertTrue(runtime.systemInfoHost.state.value.isEmpty)
            assertTrue(runtime.closeSafely().failures.isEmpty())
        }

    @Test
    fun `production runtime isolates broken Packs and continues through valid lifecycle`() =
        withTempDirectory("fp-2b-failure-isolation-") { applicationRoot ->
            val packRoot = applicationRoot.resolve("feature-packs")
            Files.copy(fixtureJar, packRoot.resolve("fixture.pack/pack.jar").also { it.parent.createDirectories() })
            packRoot.resolve("missing-pack").createDirectories()
            packRoot.resolve("corrupted-pack/pack.jar").also {
                it.parent.createDirectories()
                it.writeText("not a jar")
            }
            rewriteFixtureJar(
                packRoot.resolve("invalid-service/pack.jar").also { it.parent.createDirectories() },
                listOf("missing.fixture.Provider"),
                "invalid-service",
            )
            rewriteFixtureJar(
                packRoot.resolve("fixture.startup-failure/pack.jar").also { it.parent.createDirectories() },
                listOf(STARTUP_FAILURE_PROVIDER),
                "fixture.startup-failure",
            )
            val events = mutableListOf<String>()
            PropertiesFeaturePackManagerStateStore(applicationRoot.resolve("feature-pack-manager.properties")).save(
                mapOf(
                    PackId("fixture.pack") to StoredFeaturePackState(enabled = true),
                    PackId("invalid-service") to StoredFeaturePackState(enabled = true),
                    PackId("fixture.startup-failure") to StoredFeaturePackState(enabled = true),
                ),
            )

            val runtime = ProductionFeaturePackRuntime.start(packRoot, applicationRoot, events::add)
            val close = runtime.closeSafely()

            assertEquals(listOf("fixture.pack"), runtime.startReport.loadedPackIds.map { it.value })
            assertTrue(runtime.startReport.failures.map { it.kind }.containsAll(setOf(
                FeaturePackFailureKind.MISSING_JAR,
                FeaturePackFailureKind.INVALID_DESCRIPTOR,
                FeaturePackFailureKind.SERVICE_LOADING_FAILED,
                FeaturePackFailureKind.STARTUP_FAILED,
            )))
            assertEquals(
                listOf("INFO:fixture.pack:Fixture Pack started", "INFO:fixture.pack:Fixture Pack stopped"),
                events,
            )
            assertTrue(close.failures.isEmpty())
        }

    private fun rewriteFixtureJar(destination: Path, providers: List<String>, packId: String) {
        JarFile(fixtureJar.toFile()).use { source ->
            val manifest = source.manifest.apply {
                mainAttributes.putValue(FeaturePackJarManifest.PACK_ID, packId)
            }
            JarOutputStream(Files.newOutputStream(destination), manifest).use { output ->
                source.entries().asSequence()
                    .filterNot { it.name == SERVICE_ENTRY || it.name.equals("META-INF/MANIFEST.MF", true) }
                    .forEach { sourceEntry ->
                        output.putNextEntry(JarEntry(sourceEntry.name).apply { time = sourceEntry.time })
                        if (!sourceEntry.isDirectory) source.getInputStream(sourceEntry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                output.putNextEntry(JarEntry(SERVICE_ENTRY))
                output.write(providers.joinToString("\n", postfix = "\n").toByteArray())
                output.closeEntry()
            }
        }
    }

    private fun liveNonDaemonThreadIds(): Set<Long> = Thread.getAllStackTraces().keys
        .filter { it.isAlive && !it.isDaemon }
        .map(Thread::threadId)
        .toSet()

    private fun liveFeatureWorkerThreadNames(): Set<String> = Thread.getAllStackTraces().keys
        .filter {
            it.isAlive && (
                it.name.startsWith("feature-overlay-refresh") ||
                    it.name.startsWith("feature-route-action") ||
                    it.name.startsWith("feature-pack-control")
                )
        }
        .map(Thread::getName)
        .toSet()

    private inline fun withTempDirectory(prefix: String, block: (Path) -> Unit) {
        val directory = createTempDirectory(prefix)
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private companion object {
        val fixtureJar: Path
            get() = Path.of(requireNotNull(System.getProperty("feature.pack.fixture.jar")))
        const val SERVICE_ENTRY =
            "META-INF/services/dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint"
        const val STARTUP_FAILURE_PROVIDER =
            "dev.evestaticmapplanner.feature.fixture.StartupFailureFixturePack"
    }
}
