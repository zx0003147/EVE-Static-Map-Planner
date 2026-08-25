package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.CoreVersion
import dev.evestaticmapplanner.feature.api.FeatureApiVersions
import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.FeaturePackDescriptor
import dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint
import dev.evestaticmapplanner.feature.api.FeaturePackHostInfo
import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.FeaturePackLogger
import dev.evestaticmapplanner.feature.api.HostPlatform
import dev.evestaticmapplanner.feature.api.PackRelativePath
import dev.evestaticmapplanner.feature.api.PackStorage
import dev.evestaticmapplanner.feature.api.OverlayRegistration
import dev.evestaticmapplanner.feature.api.OverlayRegistry
import dev.evestaticmapplanner.feature.api.SystemInfoRegistration
import dev.evestaticmapplanner.feature.api.SystemInfoRegistry
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
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class LocalFeaturePackHostTest {
    @Test
    fun `discovery finds external fixture Pack and reports invalid paths`() = withTempDirectory { root ->
        val packDirectory = root.resolve("feature-packs/test-pack").createDirectories()
        Files.copy(fixtureJar, packDirectory.resolve("pack.jar"))

        val report = LocalFeaturePackHost().discover(root.resolve("feature-packs"))

        assertEquals(listOf(packDirectory.resolve("pack.jar")), report.candidates.map { it.jar })
        assertTrue(report.failures.isEmpty())

        val invalidPath = root.resolve("not-a-directory.txt").apply { writeText("invalid") }
        val invalidReport = LocalFeaturePackHost().discover(invalidPath)
        assertTrue(invalidReport.candidates.isEmpty())
        assertEquals(FeaturePackFailureKind.INVALID_DISCOVERY_PATH, invalidReport.failures.single().kind)
    }

    @Test
    fun `discovery reports Pack directory with missing jar`() = withTempDirectory { root ->
        root.resolve("feature-packs/incomplete-pack").createDirectories()

        val report = LocalFeaturePackHost().discover(root.resolve("feature-packs"))

        assertTrue(report.candidates.isEmpty())
        assertEquals(FeaturePackFailureKind.MISSING_JAR, report.failures.single().kind)
    }

    @Test
    fun `external Pack uses separate loader shared Feature API identity and full lifecycle`() =
        withTempDirectory { root ->
            val jar = copyFixtureToDevelopmentLayout(root)
            val events = mutableListOf<String>()
            var contextDescriptor: FeaturePackDescriptor? = null
            val host = LocalFeaturePackHost()
            val candidate = host.discover(root.resolve("feature-packs")).candidates.single()

            val result = host.load(candidate) { descriptor ->
                contextDescriptor = descriptor
                TestFeaturePackContext(root.resolve("storage"), events)
            }

            val loaded = assertIs<FeaturePackLoadResult.Loaded>(result).pack
            assertEquals(jar, candidate.jar)
            assertEquals("fixture.pack", loaded.descriptor.packId.value)
            assertSame(loaded.descriptor, contextDescriptor)
            assertNotSame(FeaturePackEntrypoint::class.java.classLoader, loaded.classLoader)
            assertSame(
                FeaturePackEntrypoint::class.java,
                loaded.classLoader.loadClass(FeaturePackEntrypoint::class.java.name),
            )
            assertFailsWith<ClassNotFoundException> {
                loaded.classLoader.loadClass("dev.evestaticmapplanner.core.model.SolarSystem")
            }
            assertEquals(listOf("INFO:Fixture Pack started"), events)

            assertIs<FeaturePackCloseResult.Closed>(loaded.closeSafely())
            assertEquals(
                listOf("INFO:Fixture Pack started", "INFO:Fixture Pack stopped"),
                events,
            )
            assertIs<FeaturePackCloseResult.Closed>(loaded.closeSafely())
        }

    @Test
    fun `ServiceLoader rejects zero and multiple entrypoints`() = withTempDirectory { root ->
        val host = LocalFeaturePackHost()
        val contextFactory = FeaturePackContextFactory { TestFeaturePackContext(root, mutableListOf()) }
        val noServiceJar = rewriteFixtureJar(root.resolve("no-service.jar"), null)
        val multipleServiceJar = rewriteFixtureJar(
            root.resolve("multiple-service.jar"),
            listOf(MINIMAL_PROVIDER, SECOND_PROVIDER),
        )

        val zero = host.load(candidate(noServiceJar), contextFactory)
        val multiple = host.load(candidate(multipleServiceJar), contextFactory)

        assertEquals(
            FeaturePackFailureKind.ZERO_ENTRYPOINTS,
            assertIs<FeaturePackLoadResult.Failed>(zero).failure.kind,
        )
        assertEquals(
            FeaturePackFailureKind.MULTIPLE_ENTRYPOINTS,
            assertIs<FeaturePackLoadResult.Failed>(multiple).failure.kind,
        )
    }

    @Test
    fun `invalid and startup-broken Packs return failures without stopping host`() = withTempDirectory { root ->
        val host = LocalFeaturePackHost()
        val contextFactory = FeaturePackContextFactory { TestFeaturePackContext(root, mutableListOf()) }
        val missingJar = root.resolve("missing.jar")
        val invalidJar = root.resolve("invalid.jar").apply { writeText("not a jar") }
        val startupFailureJar = rewriteFixtureJar(
            root.resolve("startup-failure.jar"),
            listOf(STARTUP_FAILURE_PROVIDER),
        )

        assertEquals(
            FeaturePackFailureKind.MISSING_JAR,
            assertIs<FeaturePackLoadResult.Failed>(host.load(candidate(missingJar), contextFactory)).failure.kind,
        )
        assertEquals(
            FeaturePackFailureKind.INVALID_JAR,
            assertIs<FeaturePackLoadResult.Failed>(host.load(candidate(invalidJar), contextFactory)).failure.kind,
        )
        assertEquals(
            FeaturePackFailureKind.STARTUP_FAILED,
            assertIs<FeaturePackLoadResult.Failed>(
                host.load(candidate(startupFailureJar), contextFactory),
            ).failure.kind,
        )

        val valid = host.load(candidate(fixtureJar), contextFactory)
        assertIs<FeaturePackLoadResult.Loaded>(valid).pack.close()
    }

    @Test
    fun `Pack close exception is contained and reported`() = withTempDirectory { root ->
        val jar = rewriteFixtureJar(root.resolve("close-failure.jar"), listOf(CLOSE_FAILURE_PROVIDER))
        val result = LocalFeaturePackHost().load(candidate(jar)) {
            TestFeaturePackContext(root, mutableListOf())
        }

        val loaded = assertIs<FeaturePackLoadResult.Loaded>(result).pack
        val closeResult = assertIs<FeaturePackCloseResult.Failed>(loaded.closeSafely())
        assertEquals(FeaturePackFailureKind.CLOSE_FAILED, closeResult.failure.kind)
        assertTrue(closeResult.failure.cause is IllegalStateException)
        loaded.close()
    }

    @Test
    fun `fixture Pack artifact contains implementation resources but no host or runtime dependencies`() {
        JarFile(fixtureJar.toFile()).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toList()
            assertTrue(entries.contains(SERVICE_ENTRY))
            assertTrue(entries.contains("dev/evestaticmapplanner/feature/fixture/MinimalFixturePack.class"))
            assertTrue(
                entries.filter { it.endsWith(".class") }
                    .all { it.startsWith("dev/evestaticmapplanner/feature/fixture/") },
            )
            val forbiddenPrefixes = listOf(
                "dev/evestaticmapplanner/feature/api/",
                "dev/evestaticmapplanner/core/",
                "dev/evestaticmapplanner/app/",
                "dev/evestaticmapplanner/mcp/",
                "dev/evestaticmapplanner/data/",
                "dev/evestaticmapplanner/map/",
                "dev/evestaticmapplanner/featurepack/",
                "androidx/compose/",
                "org/jetbrains/compose/",
                "io/modelcontextprotocol/",
                "java/sql/",
                "kotlin/",
            )
            assertFalse(entries.any { entry -> forbiddenPrefixes.any(entry::startsWith) })
            assertFalse(entries.any { it in APP_ROOT_CLASSES })
        }
    }

    private fun copyFixtureToDevelopmentLayout(root: Path): Path {
        val jar = root.resolve("feature-packs/test-pack/pack.jar")
        jar.parent.createDirectories()
        return Files.copy(fixtureJar, jar)
    }

    private fun candidate(jar: Path) = LocalFeaturePackCandidate(jar.parent, jar)

    private fun rewriteFixtureJar(destination: Path, providers: List<String>?): Path {
        JarFile(fixtureJar.toFile()).use { source ->
            JarOutputStream(Files.newOutputStream(destination)).use { output ->
                source.entries().asSequence()
                    .filterNot { it.name == SERVICE_ENTRY }
                    .forEach { sourceEntry ->
                        val targetEntry = JarEntry(sourceEntry.name).apply { time = sourceEntry.time }
                        output.putNextEntry(targetEntry)
                        if (!sourceEntry.isDirectory) {
                            source.getInputStream(sourceEntry).use { it.copyTo(output) }
                        }
                        output.closeEntry()
                    }
                if (providers != null) {
                    output.putNextEntry(JarEntry(SERVICE_ENTRY))
                    output.write((providers.joinToString("\n", postfix = "\n")).toByteArray())
                    output.closeEntry()
                }
            }
        }
        return destination
    }

    private inline fun withTempDirectory(block: (Path) -> Unit) {
        val directory = createTempDirectory("fp-2a-host-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private class TestFeaturePackContext(
        private val storageRoot: Path,
        private val events: MutableList<String>,
    ) : FeaturePackContext {
        override fun hostInfo() = FeaturePackHostInfo(
            coreVersion = CoreVersion(0, 3, 0),
            featureApiVersion = FeatureApiVersions.current(),
            platform = HostPlatform("windows", "x64"),
        )

        override fun storage(): PackStorage = TestPackStorage(storageRoot)

        override fun logger(): FeaturePackLogger = object : FeaturePackLogger {
            override fun log(level: FeaturePackLogLevel, message: String, cause: Throwable?) {
                events += "${level.name}:$message"
            }
        }

        override fun overlays(): OverlayRegistry = OverlayRegistry { NoOpOverlayRegistration }

        override fun systemInfo(): SystemInfoRegistry = SystemInfoRegistry { NoOpSystemInfoRegistration }
    }

    private object NoOpOverlayRegistration : OverlayRegistration {
        override fun close() = Unit
    }

    private object NoOpSystemInfoRegistration : SystemInfoRegistration {
        override fun refresh() = Unit
        override fun close() = Unit
    }

    private class TestPackStorage(private val root: Path) : PackStorage {
        override fun dataPath(relativePath: PackRelativePath): Path = root.resolve("data").resolve(relativePath.toPath())

        override fun configPath(relativePath: PackRelativePath): Path =
            root.resolve("config").resolve(relativePath.toPath())

        override fun cachePath(relativePath: PackRelativePath): Path =
            root.resolve("cache").resolve(relativePath.toPath())
    }

    private companion object {
        val fixtureJar: Path
            get() = Path.of(requireNotNull(System.getProperty("feature.pack.fixture.jar")))

        const val SERVICE_ENTRY =
            "META-INF/services/dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint"
        const val MINIMAL_PROVIDER = "dev.evestaticmapplanner.feature.fixture.MinimalFixturePack"
        const val SECOND_PROVIDER = "dev.evestaticmapplanner.feature.fixture.SecondFixturePack"
        const val STARTUP_FAILURE_PROVIDER =
            "dev.evestaticmapplanner.feature.fixture.StartupFailureFixturePack"
        const val CLOSE_FAILURE_PROVIDER =
            "dev.evestaticmapplanner.feature.fixture.CloseFailureFixturePack"
        val APP_ROOT_CLASSES = setOf(
            "dev/evestaticmapplanner/MainKt.class",
            "dev/evestaticmapplanner/StartupCoordinator.class",
            "dev/evestaticmapplanner/ApplicationBuildInfo.class",
        )
    }
}
