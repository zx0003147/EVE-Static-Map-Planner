package dev.evestaticmapplanner.featurepack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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
import java.net.http.HttpClient
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.jar.Attributes
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
import kotlin.test.assertNull
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
            assertSame(String::class.java, loaded.classLoader.loadClass(String::class.java.name))
            assertSame(HttpClient::class.java, loaded.classLoader.loadClass(HttpClient::class.java.name))
            assertSame(HttpServer::class.java, loaded.classLoader.loadClass(HttpServer::class.java.name))
            assertSame(HttpExchange::class.java, loaded.classLoader.loadClass(HttpExchange::class.java.name))
            assertSame(DriverManager::class.java, loaded.classLoader.loadClass(DriverManager::class.java.name))
            assertSame(Unit::class.java, loaded.classLoader.loadClass(Unit::class.java.name))
            assertFailsWith<ClassNotFoundException> {
                loaded.classLoader.loadClass("com.sun.jna.Native")
            }
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
    fun `down-level Feature API is rejected before ClassLoader creation or Pack initialization`() =
        withTempDirectory { root ->
            val downLevelPack = rewriteFixtureJar(
                root.resolve("down-level-pack.jar"),
                listOf(COMPATIBILITY_PROBE_PROVIDER),
                mapOf(FeaturePackJarManifest.FEATURE_API_VERSION to "1"),
            )
            var classLoaderCreations = 0
            val host = LocalFeaturePackHost(
                FeaturePackEntrypoint::class.java.classLoader,
                FeaturePackClassLoaderFactory { jarUrl, parent ->
                    classLoaderCreations += 1
                    URLClassLoader(arrayOf(jarUrl), parent)
                },
            )
            val previousStatic = System.getProperty(STATIC_PROBE_PROPERTY)
            val previousConstructor = System.getProperty(CONSTRUCTOR_PROBE_PROPERTY)
            System.clearProperty(STATIC_PROBE_PROPERTY)
            System.clearProperty(CONSTRUCTOR_PROBE_PROPERTY)
            try {
                val result = host.load(candidate(downLevelPack)) {
                    error("An incompatible Pack must not receive a context")
                }

                val failure = assertIs<FeaturePackLoadResult.Failed>(result).failure
                assertEquals(FeaturePackFailureKind.INCOMPATIBLE_FEATURE_API, failure.kind)
                assertTrue(failure.message.contains("requires Feature API 1"))
                assertTrue(failure.message.contains("provides Feature API 2"))
                assertEquals(0, classLoaderCreations)
                assertNull(System.getProperty(STATIC_PROBE_PROPERTY))
                assertNull(System.getProperty(CONSTRUCTOR_PROBE_PROPERTY))
            } finally {
                restoreSystemProperty(STATIC_PROBE_PROPERTY, previousStatic)
                restoreSystemProperty(CONSTRUCTOR_PROBE_PROPERTY, previousConstructor)
            }
        }

    @Test
    fun `missing and invalid Feature API declarations are rejected as metadata before loading`() =
        withTempDirectory { root ->
            val invalidValues = listOf<String?>(null, "", "0", "-1", "not-an-integer", "2147483648", "01")
            invalidValues.forEachIndexed { index, value ->
                val jar = rewriteFixtureJar(
                    root.resolve("invalid-api-$index.jar"),
                    listOf(MINIMAL_PROVIDER),
                    mapOf(FeaturePackJarManifest.FEATURE_API_VERSION to value),
                )

                val failure = assertIs<FeaturePackLoadResult.Failed>(
                    LocalFeaturePackHost().load(candidate(jar)) {
                        error("Invalid compatibility metadata must not create a context")
                    },
                ).failure

                assertEquals(FeaturePackFailureKind.INVALID_DESCRIPTOR, failure.kind, "value=$value")
                assertTrue(failure.message.contains(FeaturePackJarManifest.FEATURE_API_VERSION), "value=$value")
            }
        }

    @Test
    fun `multiple Packs retain private class identity and share only the intentional parent boundary`() =
        withTempDirectory { root ->
            val secondJar = rewriteFixtureJar(root.resolve("second-pack.jar"), listOf(SECOND_PROVIDER))
            val host = LocalFeaturePackHost()
            val contextFactory = FeaturePackContextFactory {
                TestFeaturePackContext(root.resolve("storage"), mutableListOf())
            }

            val first = assertIs<FeaturePackLoadResult.Loaded>(
                host.load(candidate(fixtureJar), contextFactory),
            ).pack
            val second = assertIs<FeaturePackLoadResult.Loaded>(
                host.load(candidate(secondJar), contextFactory),
            ).pack
            try {
                assertNotSame(first.classLoader, second.classLoader)
                assertNotSame(
                    first.classLoader.loadClass(MINIMAL_PROVIDER),
                    second.classLoader.loadClass(MINIMAL_PROVIDER),
                )
                assertSame(
                    FeaturePackEntrypoint::class.java,
                    first.classLoader.loadClass(FeaturePackEntrypoint::class.java.name),
                )
                assertSame(
                    FeaturePackEntrypoint::class.java,
                    second.classLoader.loadClass(FeaturePackEntrypoint::class.java.name),
                )
                assertFailsWith<ClassNotFoundException> {
                    first.classLoader.loadClass("dev.evestaticmapplanner.core.model.SolarSystem")
                }
                assertFailsWith<ClassNotFoundException> {
                    second.classLoader.loadClass("dev.evestaticmapplanner.core.model.SolarSystem")
                }
            } finally {
                assertIs<FeaturePackCloseResult.Closed>(second.closeSafely())
                assertIs<FeaturePackCloseResult.Closed>(first.closeSafely())
            }
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
            assertEquals("2", jar.manifest.mainAttributes.getValue(FeaturePackJarManifest.FEATURE_API_VERSION))
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

    private fun rewriteFixtureJar(
        destination: Path,
        providers: List<String>?,
        manifestAttributes: Map<String, String?> = emptyMap(),
    ): Path {
        JarFile(fixtureJar.toFile()).use { source ->
            val manifest = source.manifest.apply {
                manifestAttributes.forEach { (name, value) ->
                    if (value == null) {
                        mainAttributes.remove(Attributes.Name(name))
                    } else {
                        mainAttributes.putValue(name, value)
                    }
                }
            }
            JarOutputStream(Files.newOutputStream(destination), manifest).use { output ->
                source.entries().asSequence()
                    .filterNot { it.name == SERVICE_ENTRY || it.name.equals("META-INF/MANIFEST.MF", true) }
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

    private fun restoreSystemProperty(name: String, value: String?) {
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
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
        const val COMPATIBILITY_PROBE_PROVIDER =
            "dev.evestaticmapplanner.feature.fixture.CompatibilityInitializationProbePack"
        const val STATIC_PROBE_PROPERTY = "feature.pack.compatibility.probe.static"
        const val CONSTRUCTOR_PROBE_PROPERTY = "feature.pack.compatibility.probe.constructor"
        val APP_ROOT_CLASSES = setOf(
            "dev/evestaticmapplanner/MainKt.class",
            "dev/evestaticmapplanner/StartupCoordinator.class",
            "dev/evestaticmapplanner/ApplicationBuildInfo.class",
        )
    }
}
