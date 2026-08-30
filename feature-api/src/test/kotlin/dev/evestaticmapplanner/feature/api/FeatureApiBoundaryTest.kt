package dev.evestaticmapplanner.feature.api

import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureApiBoundaryTest {
    @Test
    fun `public API type set is explicit and exposes no forbidden signatures`() {
        val publicTypes = productionClasses()
            .filter { Modifier.isPublic(it.modifiers) }
            .toSet()

        assertEquals(EXPECTED_PUBLIC_TYPES, publicTypes.mapTo(sortedSetOf()) { it.name })

        publicTypes.forEach { type ->
            type.declaredConstructors.filter { Modifier.isPublic(it.modifiers) }.forEach { constructor ->
                constructor.genericParameterTypes.forEach(::assertAllowedType)
                constructor.genericExceptionTypes.forEach(::assertAllowedType)
            }
            type.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.forEach { method ->
                assertAllowedType(method.genericReturnType)
                method.genericParameterTypes.forEach(::assertAllowedType)
                method.genericExceptionTypes.forEach(::assertAllowedType)
            }
            type.declaredFields.filter { Modifier.isPublic(it.modifiers) }.forEach { field ->
                assertAllowedType(field.genericType)
            }
        }
    }

    @Test
    fun `fixture artifact contains implementation but no duplicated Feature API classes`() {
        val fixtureJar = Path.of(requireNotNull(System.getProperty("feature.api.fixture.jar")))
        assertTrue(Files.isRegularFile(fixtureJar), "Fixture JAR was not built: $fixtureJar")

        JarFile(fixtureJar.toFile()).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toList()
            assertEquals("2", jar.manifest.mainAttributes.getValue("EVE-Feature-API-Version"))
            assertTrue(entries.contains("dev/evestaticmapplanner/feature/fixture/MinimalFixturePack.class"))
            assertFalse(entries.any { it.startsWith("dev/evestaticmapplanner/feature/api/") })
            assertFalse(entries.any { it.startsWith("kotlin/") })
        }
    }

    private fun productionClasses(): List<Class<*>> {
        val packagePath = API_PACKAGE.replace('.', '/')
        val location = FeaturePackEntrypoint::class.java.protectionDomain.codeSource.location.toURI()
        val root = Path.of(location)
        check(Files.isDirectory(root)) { "Expected test-time Feature API classes directory, got $root" }
        val classRoot = root.resolve(packagePath)
        return Files.walk(classRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension == "class" }
                .map { classFile ->
                    val relative = root.relativize(classFile).toString()
                        .removeSuffix(".class")
                        .replace('\\', '.')
                        .replace('/', '.')
                    Class.forName(relative)
                }
                .toList()
        }
    }

    private fun assertAllowedType(type: Type) {
        when (type) {
            is Class<*> -> {
                assertTrue(FORBIDDEN_PREFIXES.none(type.name::startsWith), "Forbidden public ABI type: ${type.name}")
                if (type.isArray) assertAllowedType(type.componentType)
            }
            is ParameterizedType -> {
                assertAllowedType(type.rawType)
                type.actualTypeArguments.forEach(::assertAllowedType)
            }
            is WildcardType -> {
                type.lowerBounds.forEach(::assertAllowedType)
                type.upperBounds.forEach(::assertAllowedType)
            }
        }
    }

    private companion object {
        const val API_PACKAGE = "dev.evestaticmapplanner.feature.api"
        val EXPECTED_PUBLIC_TYPES = sortedSetOf(
            "$API_PACKAGE.CoreVersion",
            "$API_PACKAGE.FeatureApiVersion",
            "$API_PACKAGE.FeatureApiVersions",
            "$API_PACKAGE.FeatureCapability",
            "$API_PACKAGE.FeatureCapabilityId",
            "$API_PACKAGE.FeatureCapabilityKey",
            "$API_PACKAGE.FeatureCapabilityLookup",
            "$API_PACKAGE.FeatureCapabilityLookup\$Companion",
            "$API_PACKAGE.FeaturePackCompatibility",
            "$API_PACKAGE.FeaturePackContext",
            "$API_PACKAGE.FeaturePackContext\$DefaultImpls",
            "$API_PACKAGE.FeaturePackDescriptor",
            "$API_PACKAGE.FeaturePackEntrypoint",
            "$API_PACKAGE.FeaturePackHostInfo",
            "$API_PACKAGE.FeaturePackLogLevel",
            "$API_PACKAGE.FeaturePackLogger",
            "$API_PACKAGE.FeaturePackSession",
            "$API_PACKAGE.FeaturePackStartupException",
            "$API_PACKAGE.HostPlatform",
            "$API_PACKAGE.DynamicOverlayCapability",
            "$API_PACKAGE.DynamicOverlayRegistration",
            "$API_PACKAGE.OverlayEntry",
            "$API_PACKAGE.OverlayEntryVisibility",
            "$API_PACKAGE.OverlayImage",
            "$API_PACKAGE.OverlayLayer",
            "$API_PACKAGE.OverlayLayerState",
            "$API_PACKAGE.OverlayProvider",
            "$API_PACKAGE.OverlayProviderDescriptor",
            "$API_PACKAGE.OverlayRegistration",
            "$API_PACKAGE.OverlayRegistry",
            "$API_PACKAGE.OverlaySnapshot",
            "$API_PACKAGE.OverlayState",
            "$API_PACKAGE.OverlaySystemMarker",
            "$API_PACKAGE.PackControlActionDescriptor",
            "$API_PACKAGE.PackControlActionResult",
            "$API_PACKAGE.PackControlActionStatus",
            "$API_PACKAGE.PackControlCapability",
            "$API_PACKAGE.PackControlProvider",
            "$API_PACKAGE.PackControlRegistration",
            "$API_PACKAGE.PackControlSeverity",
            "$API_PACKAGE.PackControlSnapshot",
            "$API_PACKAGE.PackId",
            "$API_PACKAGE.PackRelativePath",
            "$API_PACKAGE.PackStorage",
            "$API_PACKAGE.PackVersion",
            "$API_PACKAGE.RouteActionCapability",
            "$API_PACKAGE.RouteActionContext",
            "$API_PACKAGE.RouteActionDescriptor",
            "$API_PACKAGE.RouteActionProvider",
            "$API_PACKAGE.RouteActionProvider\$DefaultImpls",
            "$API_PACKAGE.RouteActionRegistration",
            "$API_PACKAGE.RouteActionResult",
            "$API_PACKAGE.RouteActionStatus",
            "$API_PACKAGE.RouteActionTargetId",
            "$API_PACKAGE.RouteActionTargetOption",
            "$API_PACKAGE.RouteActionTargetSnapshot",
            "$API_PACKAGE.RouteIdentity",
            "$API_PACKAGE.RouteKind",
            "$API_PACKAGE.RouteSegment",
            "$API_PACKAGE.RouteSegmentKind",
            "$API_PACKAGE.RouteSnapshot",
            "$API_PACKAGE.StandardFeatureCapabilities",
            "$API_PACKAGE.SystemInfoField",
            "$API_PACKAGE.SystemInfoProvider",
            "$API_PACKAGE.SystemInfoProviderDescriptor",
            "$API_PACKAGE.SystemInfoRegistration",
            "$API_PACKAGE.SystemInfoRegistry",
            "$API_PACKAGE.SystemInfoSection",
            "$API_PACKAGE.SystemInfoSnapshot",
            "$API_PACKAGE.SystemInfoState",
        )
        val FORBIDDEN_PREFIXES = listOf(
            "androidx.",
            "dev.evestaticmapplanner.app.",
            "dev.evestaticmapplanner.control.",
            "dev.evestaticmapplanner.core.",
            "dev.evestaticmapplanner.data.",
            "dev.evestaticmapplanner.mcp.",
            "dev.evestaticmapplanner.marker.",
            "dev.evestaticmapplanner.sde.",
            "io.ktor.",
            "java.sql.",
            "java.net.http.",
            "kotlinx.coroutines.",
            "org.sqlite.",
            "org.jetbrains.compose.",
        )
    }
}
