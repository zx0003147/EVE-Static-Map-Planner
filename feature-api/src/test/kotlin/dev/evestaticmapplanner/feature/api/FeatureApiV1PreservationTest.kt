package dev.evestaticmapplanner.feature.api

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/** Repeatable controlled diff proving that V2 preserves the complete V1 source-facing JVM ABI. */
class FeatureApiV1PreservationTest {
    @Test
    fun `V1 constructors methods inheritance and enum constants remain unchanged`() {
        V1_ABI.forEach { (typeName, expected) ->
            val type = Class.forName(typeName)
            val constructors = type.declaredConstructors
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .mapTo(sortedSetOf(), ::constructorSignature)
            val methods = type.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
                .mapTo(sortedSetOf(), ::methodSignature)
                .minus(APPROVED_V2_METHOD_ADDITIONS[typeName].orEmpty())
                .toSortedSet()
            val interfaces = type.interfaces.mapTo(sortedSetOf()) { it.name }
            val enumConstants = type.enumConstants?.mapTo(sortedSetOf()) { (it as Enum<*>).name }.orEmpty()

            assertEquals(expected.constructors, constructors, "$typeName constructors changed outside the approved V2 diff")
            assertEquals(expected.methods, methods, "$typeName methods changed outside the approved V2 diff")
            assertEquals(expected.interfaces, interfaces, "$typeName interfaces changed outside the approved V2 diff")
            assertEquals(expected.enumConstants, enumConstants, "$typeName enum constants changed outside the approved V2 diff")
        }
    }

    private data class Abi(
        val constructors: Set<String> = emptySet(),
        val methods: Set<String> = emptySet(),
        val interfaces: Set<String> = emptySet(),
        val enumConstants: Set<String> = emptySet(),
    )

    private companion object {
        const val API = "dev.evestaticmapplanner.feature.api"
        const val OBJECT = "java.lang.Object"
        const val STRING = "java.lang.String"
        const val LIST = "java.util.List"

        fun abi(
            constructors: Set<String> = emptySet(),
            methods: Set<String> = emptySet(),
            interfaces: Set<String> = emptySet(),
            enumConstants: Set<String> = emptySet(),
        ) = Abi(constructors, methods, interfaces, enumConstants)

        val V1_ABI = linkedMapOf(
            "$API.CoreVersion" to abi(
                constructors = setOf("(int,int,int)"),
                methods = setOf(
                    "compareTo($API.CoreVersion):int",
                    "equals($OBJECT):boolean",
                    "getMajor():int",
                    "getMinor():int",
                    "getPatch():int",
                    "hashCode():int",
                    "toString():$STRING",
                ),
                interfaces = setOf("java.lang.Comparable"),
            ),
            "$API.FeatureApiVersion" to abi(
                constructors = setOf("($STRING,boolean)"),
                methods = setOf(
                    "equals($OBJECT):boolean",
                    "getFrozen():boolean",
                    "getIdentifier():$STRING",
                    "hashCode():int",
                    "toString():$STRING",
                ),
            ),
            "$API.FeatureApiVersions" to abi(methods = setOf("current():$API.FeatureApiVersion")),
            "$API.FeaturePackCompatibility" to abi(
                constructors = setOf("(int,$API.FeatureApiVersion,$API.CoreVersion)"),
                methods = setOf(
                    "getFeatureApiVersion():$API.FeatureApiVersion",
                    "getMinimumCoreVersion():$API.CoreVersion",
                    "getPackFormatVersion():int",
                    "isCompatibleWith($API.FeaturePackHostInfo,int):boolean",
                ),
            ),
            "$API.FeaturePackContext" to abi(methods = setOf(
                "hostInfo():$API.FeaturePackHostInfo",
                "logger():$API.FeaturePackLogger",
                "overlays():$API.OverlayRegistry",
                "storage():$API.PackStorage",
                "systemInfo():$API.SystemInfoRegistry",
            )),
            "$API.FeaturePackDescriptor" to abi(
                constructors = setOf("($API.PackId,$STRING,$API.PackVersion,$STRING)"),
                methods = setOf(
                    "getDisplayName():$STRING",
                    "getPackId():$API.PackId",
                    "getPackVersion():$API.PackVersion",
                    "getPublisher():$STRING",
                ),
            ),
            "$API.FeaturePackEntrypoint" to abi(methods = setOf(
                "descriptor():$API.FeaturePackDescriptor",
                "start($API.FeaturePackContext):$API.FeaturePackSession throws $API.FeaturePackStartupException",
            )),
            "$API.FeaturePackHostInfo" to abi(
                constructors = setOf("($API.CoreVersion,$API.FeatureApiVersion,$API.HostPlatform)"),
                methods = setOf(
                    "getCoreVersion():$API.CoreVersion",
                    "getFeatureApiVersion():$API.FeatureApiVersion",
                    "getPlatform():$API.HostPlatform",
                ),
            ),
            "$API.FeaturePackLogLevel" to enumAbi(
                "$API.FeaturePackLogLevel",
                setOf("DEBUG", "INFO", "WARN", "ERROR"),
            ),
            "$API.FeaturePackLogger" to abi(methods = setOf(
                "log($API.FeaturePackLogLevel,$STRING,java.lang.Throwable):void",
            )),
            "$API.FeaturePackSession" to abi(
                methods = setOf("close():void"),
                interfaces = setOf("java.lang.AutoCloseable"),
            ),
            "$API.FeaturePackStartupException" to abi(
                constructors = setOf("($STRING)", "($STRING,java.lang.Throwable)"),
            ),
            "$API.HostPlatform" to abi(
                constructors = setOf("($STRING,$STRING)"),
                methods = setOf("getArchitecture():$STRING", "getOperatingSystem():$STRING"),
            ),
            "$API.OverlayEntry" to abi(
                constructors = setOf("($STRING,int,$STRING,$STRING,$STRING,$API.OverlayEntryVisibility)"),
                methods = setOf(
                    "getLayerId():$STRING",
                    "getSubtitle():$STRING",
                    "getSystemId():int",
                    "getTitle():$STRING",
                    "getValue():$STRING",
                    "getVisibility():$API.OverlayEntryVisibility",
                ),
            ),
            "$API.OverlayEntryVisibility" to enumAbi(
                "$API.OverlayEntryVisibility",
                setOf("VISIBLE", "HIDDEN"),
            ),
            "$API.OverlayLayer" to abi(
                constructors = setOf("($STRING,$STRING,$STRING,int)"),
                methods = setOf(
                    "getDescription():$STRING",
                    "getId():$STRING",
                    "getName():$STRING",
                    "getPriority():int",
                ),
            ),
            "$API.OverlayLayerState" to abi(
                constructors = setOf("($API.OverlayProviderDescriptor,$API.OverlayLayer,$LIST)"),
                methods = setOf(
                    "getEntries():$LIST",
                    "getLayer():$API.OverlayLayer",
                    "getProvider():$API.OverlayProviderDescriptor",
                ),
            ),
            "$API.OverlayProvider" to abi(methods = setOf(
                "descriptor():$API.OverlayProviderDescriptor",
                "layers():$LIST",
                "snapshot():$API.OverlaySnapshot",
            )),
            "$API.OverlayProviderDescriptor" to abi(
                constructors = setOf("($STRING,$STRING,$STRING)"),
                methods = setOf("getDescription():$STRING", "getId():$STRING", "getName():$STRING"),
            ),
            "$API.OverlayRegistration" to abi(
                methods = setOf("close():void"),
                interfaces = setOf("java.lang.AutoCloseable"),
            ),
            "$API.OverlayRegistry" to abi(methods = setOf(
                "register($API.OverlayProvider):$API.OverlayRegistration",
            )),
            "$API.OverlaySnapshot" to abi(
                constructors = setOf("($LIST)"),
                methods = setOf("getEntries():$LIST"),
            ),
            "$API.OverlayState" to abi(
                constructors = setOf("($LIST)"),
                methods = setOf("getLayers():$LIST", "isEmpty():boolean"),
            ),
            "$API.PackId" to valueObjectAbi(),
            "$API.PackRelativePath" to abi(
                constructors = setOf("($STRING)"),
                methods = valueObjectMethods() + "toPath():java.nio.file.Path",
            ),
            "$API.PackStorage" to abi(methods = setOf(
                "cachePath($API.PackRelativePath):java.nio.file.Path",
                "configPath($API.PackRelativePath):java.nio.file.Path",
                "dataPath($API.PackRelativePath):java.nio.file.Path",
            )),
            "$API.PackVersion" to valueObjectAbi(),
            "$API.SystemInfoField" to abi(
                constructors = setOf("($STRING,$STRING,$STRING)"),
                methods = setOf("getKey():$STRING", "getLabel():$STRING", "getValue():$STRING"),
            ),
            "$API.SystemInfoProvider" to abi(methods = setOf(
                "descriptor():$API.SystemInfoProviderDescriptor",
                "provide(int):$API.SystemInfoSnapshot",
            )),
            "$API.SystemInfoProviderDescriptor" to abi(
                constructors = setOf("($STRING,$STRING,int)"),
                methods = setOf("getId():$STRING", "getName():$STRING", "getPriority():int"),
            ),
            "$API.SystemInfoRegistration" to abi(
                methods = setOf("close():void", "refresh():void"),
                interfaces = setOf("java.lang.AutoCloseable"),
            ),
            "$API.SystemInfoRegistry" to abi(methods = setOf(
                "register($API.SystemInfoProvider):$API.SystemInfoRegistration",
            )),
            "$API.SystemInfoSection" to abi(
                constructors = setOf("($STRING,$STRING,int,$LIST)"),
                methods = setOf(
                    "getFields():$LIST",
                    "getPriority():int",
                    "getSectionId():$STRING",
                    "getTitle():$STRING",
                ),
            ),
            "$API.SystemInfoSnapshot" to abi(
                constructors = setOf("(int,$LIST)"),
                methods = setOf("getSections():$LIST", "getSystemId():int"),
            ),
            "$API.SystemInfoState" to abi(
                constructors = setOf("(java.lang.Integer,$LIST)"),
                methods = setOf("getSections():$LIST", "getSystemId():java.lang.Integer", "isEmpty():boolean"),
            ),
        )

        val APPROVED_V2_METHOD_ADDITIONS = mapOf(
            "$API.FeaturePackContext" to setOf("capabilities():$API.FeatureCapabilityLookup"),
        )

        fun valueObjectMethods() = setOf(
            "equals($OBJECT):boolean",
            "getValue():$STRING",
            "hashCode():int",
            "toString():$STRING",
        )

        fun valueObjectAbi() = abi(constructors = setOf("($STRING)"), methods = valueObjectMethods())

        fun enumAbi(typeName: String, constants: Set<String>) = abi(
            methods = setOf(
                "getEntries():kotlin.enums.EnumEntries",
                "valueOf($STRING):$typeName",
                "values():[L$typeName;",
            ),
            enumConstants = constants,
        )

        fun constructorSignature(constructor: Constructor<*>): String =
            constructor.parameterTypes.joinToString(prefix = "(", postfix = ")", separator = ",") { it.name }

        fun methodSignature(method: Method): String = buildString {
            append(method.name)
            method.parameterTypes.joinTo(this, prefix = "(", postfix = ")", separator = ",") { it.name }
            append(':')
            append(method.returnType.name)
            if (method.exceptionTypes.isNotEmpty()) {
                method.exceptionTypes.map { it.name }.sorted().joinTo(this, prefix = " throws ", separator = ",")
            }
        }
    }
}
