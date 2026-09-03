package dev.evestaticmapplanner.feature.api

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Proves that a provider binary compiled against the Feature API 2.0 Route Action ABI runs on 2.1. */
class FeatureApiV2AdditiveCompatibilityTest {
    @Test
    fun `legacy 2_0 route action provider loads and works without navigation capability`() {
        val root = Files.createTempDirectory("feature-api-v2-compatibility-")
        try {
            val baselineSources = root.resolve("baseline-sources").createDirectories()
            val baselineClasses = root.resolve("baseline-classes").createDirectories()
            val providerSources = root.resolve("provider-sources").createDirectories()
            val providerClasses = root.resolve("provider-classes").createDirectories()

            writeBaseline20RouteActionApi(baselineSources)
            compileJava(baselineSources, baselineClasses)

            providerSources.resolve("LegacyRouteActionProvider.java").writeText(LEGACY_PROVIDER_SOURCE)
            compileJava(providerSources, providerClasses, baselineClasses)

            URLClassLoader(
                arrayOf(providerClasses.toUri().toURL()),
                RouteActionProvider::class.java.classLoader,
            ).use { classLoader ->
                val provider = classLoader
                    .loadClass("legacy.v2.LegacyRouteActionProvider")
                    .getDeclaredConstructor()
                    .newInstance()

                assertTrue(provider is RouteActionProvider)
                assertFalse(provider is NavigationRouteActionProvider)
                assertEquals("legacy-v2-action", provider.descriptor().id)
                assertNull(provider.targets())

                val route = RouteSnapshot(
                    RouteIdentity("legacy-v2-route"),
                    RouteKind.NORMAL,
                    30_000_001,
                    30_000_001,
                    listOf(30_000_001),
                    emptyList(),
                )
                val result = provider.execute(RouteActionContext(route))
                assertEquals(RouteActionStatus.SUCCEEDED, result.status)
                assertEquals("legacy-provider-ok", result.message)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun writeBaseline20RouteActionApi(sourceRoot: Path) {
        BASELINE_20_SOURCES.forEach { (name, source) -> sourceRoot.resolve(name).writeText(source) }
    }

    private fun compileJava(sourceRoot: Path, outputRoot: Path, classpath: Path? = null) {
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) {
            "A JDK compiler is required for the Feature API 2.0 binary compatibility test"
        }
        val sourceFiles = Files.list(sourceRoot).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".java") }
                .map(Path::toString)
                .sorted()
                .toList()
        }
        val arguments = buildList {
            addAll(listOf("--release", "17", "-d", outputRoot.toString()))
            classpath?.let { addAll(listOf("-classpath", it.toString())) }
            addAll(sourceFiles)
        }
        assertEquals(0, compiler.run(null, null, null, *arguments.toTypedArray()))
    }

    private companion object {
        const val API_PACKAGE = "dev.evestaticmapplanner.feature.api"

        val BASELINE_20_SOURCES = mapOf(
            "RouteActionProvider.java" to """
                package $API_PACKAGE;
                public interface RouteActionProvider {
                    RouteActionDescriptor descriptor();
                    default RouteActionTargetSnapshot targets() { return null; }
                    RouteActionResult execute(RouteActionContext context);
                }
            """.trimIndent(),
            "RouteActionDescriptor.java" to """
                package $API_PACKAGE;
                import java.util.Set;
                public final class RouteActionDescriptor {
                    public RouteActionDescriptor(
                        String id,
                        String label,
                        String description,
                        Set<? extends RouteKind> supportedRouteKinds,
                        String targetSelectorId
                    ) {}
                }
            """.trimIndent(),
            "RouteActionTargetSnapshot.java" to """
                package $API_PACKAGE;
                public final class RouteActionTargetSnapshot {}
            """.trimIndent(),
            "RouteActionContext.java" to """
                package $API_PACKAGE;
                public final class RouteActionContext {}
            """.trimIndent(),
            "RouteActionResult.java" to """
                package $API_PACKAGE;
                public final class RouteActionResult {
                    public RouteActionResult(RouteActionStatus status, String message) {}
                }
            """.trimIndent(),
            "RouteActionStatus.java" to """
                package $API_PACKAGE;
                public enum RouteActionStatus { SUCCEEDED, REJECTED, FAILED }
            """.trimIndent(),
            "RouteKind.java" to """
                package $API_PACKAGE;
                public enum RouteKind { NORMAL, CAPITAL, MISSION_NORMAL, MISSION_CAPITAL }
            """.trimIndent(),
        )

        val LEGACY_PROVIDER_SOURCE = """
            package legacy.v2;

            import dev.evestaticmapplanner.feature.api.RouteActionContext;
            import dev.evestaticmapplanner.feature.api.RouteActionDescriptor;
            import dev.evestaticmapplanner.feature.api.RouteActionProvider;
            import dev.evestaticmapplanner.feature.api.RouteActionResult;
            import dev.evestaticmapplanner.feature.api.RouteActionStatus;
            import dev.evestaticmapplanner.feature.api.RouteKind;
            import java.util.Set;

            public final class LegacyRouteActionProvider implements RouteActionProvider {
                @Override
                public RouteActionDescriptor descriptor() {
                    return new RouteActionDescriptor(
                        "legacy-v2-action",
                        "Legacy v2 action",
                        null,
                        Set.of(RouteKind.NORMAL),
                        null
                    );
                }

                @Override
                public RouteActionResult execute(RouteActionContext context) {
                    return new RouteActionResult(RouteActionStatus.SUCCEEDED, "legacy-provider-ok");
                }
            }
        """.trimIndent()
    }
}
