package dev.evestaticmapplanner.packaging

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JpackageComponentGuidNamespaceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `implements the RFC UUIDv5 DNS test vector`() {
        val namespaceDns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        assertEquals(
            UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2"),
            JpackageComponentGuidNamespace.uuidV5(namespaceDns, "www.example.com"),
        )
    }

    @Test
    fun `matches the fixed Planner component example`() {
        assertEquals(
            "{6BB2A4A0-0571-5AF9-A20B-BA5D12B6FDC0}",
            JpackageComponentGuidNamespace.namespaceComponentGuid(
                PLANNER_NAMESPACE,
                "{0566E653-E9AE-32AD-8448-1AC3F202A898}",
            ),
        )
    }

    @Test
    fun `canonicalizes GUID case and braces`() {
        assertEquals(
            "0566e653-e9ae-32ad-8448-1ac3f202a898",
            JpackageComponentGuidNamespace.canonicalGuid("{0566E653-E9AE-32AD-8448-1AC3F202A898}"),
        )
        assertFailsWith<IllegalArgumentException> {
            JpackageComponentGuidNamespace.canonicalGuid("{0566E653-E9AE-32AD-8448-1AC3F202A898")
        }
    }

    @Test
    fun `is deterministic and separates namespaces and component identities`() {
        val original = "{0566E653-E9AE-32AD-8448-1AC3F202A898}"
        val first = JpackageComponentGuidNamespace.namespaceComponentGuid(PLANNER_NAMESPACE, original)
        val second = JpackageComponentGuidNamespace.namespaceComponentGuid(PLANNER_NAMESPACE, original)
        assertEquals(first, second)
        assertNotEquals(
            first,
            JpackageComponentGuidNamespace.namespaceComponentGuid(
                UUID.fromString("9C2B2F7E-5F3D-4A2C-8B7E-1F3C4D5E6F70"),
                original,
            ),
        )
        assertNotEquals(
            first,
            JpackageComponentGuidNamespace.namespaceComponentGuid(
                PLANNER_NAMESPACE,
                "{5C97B751-F669-30AA-9F5D-3C54EAE3BF2D}",
            ),
        )
    }

    @Test
    fun `transforms GUIDs and replaces generated package metadata with exact install cleanup`() {
        val source = writeFixture("source.wxf")
        val output = temporaryDirectory.resolve("output.wxf")
        val result = JpackageComponentGuidNamespace.transform(source, PROBE_COMPONENTS, output, PLANNER_NAMESPACE)

        assertEquals(3, result.componentCount)
        assertEquals(2, result.explicitGuidCount)
        assertEquals(1, result.addedGuidCount)
        assertEquals(3, result.mappings.values.map { it.namespacedGuid }.toSet().size)
        assertEquals("cfiled4b06b31fb623c598059562884b108f1", result.legacyPackageCleanupComponentId)
        assertTrue(output.readText().contains("Guid=\"{6BB2A4A0-0571-5AF9-A20B-BA5D12B6FDC0}\""))
        assertTrue(output.readText().contains("Id=\"crm_rf48431aecbd69377ea800d62616352f20\""))
        assertTrue(!output.readText().contains("Source=\"config\\.package\""))
        assertTrue(output.readText().contains("Id=\"JpRemoveLegacyPackageMetadata\""))
        assertTrue(output.readText().contains("Name=\".package\""))
        assertTrue(output.readText().contains("On=\"install\""))
        assertTrue(!output.readText().contains("Name=\"*\""))
        assertTrue(!output.readText().contains("Name=\"*.*\""))
        JpackageComponentGuidNamespace.assertOnlyExpectedChanges(source, output)
        JpackageComponentGuidNamespace.verifyFinalComponents(
            result.mappings.mapValues { it.value.namespacedGuid },
            result,
        )
    }

    @Test
    fun `produces deterministic transformed output`() {
        val source = writeFixture("source.wxf")
        val first = temporaryDirectory.resolve("first.wxf")
        val second = temporaryDirectory.resolve("second.wxf")
        val firstResult = JpackageComponentGuidNamespace.transform(source, PROBE_COMPONENTS, first, PLANNER_NAMESPACE)
        val secondResult = JpackageComponentGuidNamespace.transform(source, PROBE_COMPONENTS, second, PLANNER_NAMESPACE)
        assertEquals(firstResult, secondResult)
        assertTrue(Files.readAllBytes(first).contentEquals(Files.readAllBytes(second)))
    }

    @Test
    fun `removes only additional launcher shortcuts from the generated bundle`() {
        val source = temporaryDirectory.resolve("shortcuts-source.wxf")
        Files.writeString(source, SHORTCUT_FIXTURE)
        val output = temporaryDirectory.resolve("shortcuts-output.wxf")
        val shortcutProbe = PROBE_COMPONENTS + mapOf(
            "mcpMenu" to "{11111111-1111-3111-8111-111111111111}",
            "mcpDesktop" to "{22222222-2222-3222-8222-222222222222}",
        )

        val result = JpackageComponentGuidNamespace.transform(
            source,
            shortcutProbe,
            output,
            PLANNER_NAMESPACE,
            excludedShortcutLauncherNames = setOf("EVE Map MCP Bridge"),
        )

        assertEquals(setOf("mcpMenu", "mcpDesktop"), result.removedShortcutComponentIds)
        assertEquals(PROBE_COMPONENTS.keys, result.mappings.keys)
        assertTrue(!output.readText().contains("EVE Map MCP Bridge"))
        assertTrue(output.readText().contains("EVE Static Map Planner"))
        JpackageComponentGuidNamespace.assertOnlyExpectedChanges(
            source,
            output,
            excludedShortcutLauncherNames = setOf("EVE Map MCP Bridge"),
        )
    }

    @Test
    fun `fails closed on missing unknown duplicate and many-to-one mappings`() {
        val source = writeFixture("source.wxf")
        assertFailsWith<IllegalArgumentException> {
            JpackageComponentGuidNamespace.transform(
                source,
                PROBE_COMPONENTS - "cfile0566e653e9ae32ad84481ac3f202a898",
                temporaryDirectory.resolve("missing.wxf"),
                PLANNER_NAMESPACE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            JpackageComponentGuidNamespace.transform(
                source,
                PROBE_COMPONENTS + ("unknown" to "{11111111-1111-3111-8111-111111111111}"),
                temporaryDirectory.resolve("unknown.wxf"),
                PLANNER_NAMESPACE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MsiComponentTableReader.parseRows(
                "componentA\t{0566E653-E9AE-32AD-8448-1AC3F202A898}\n" +
                    "componentA\t{5C97B751-F669-30AA-9F5D-3C54EAE3BF2D}\n",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MsiComponentTableReader.parseRows(
                "componentA\t{0566E653-E9AE-32AD-8448-1AC3F202A898}\n" +
                    "componentB\t{0566E653-E9AE-32AD-8448-1AC3F202A898}\n",
            )
        }
    }

    @Test
    fun `fails closed on schema drift explicit GUID mismatch and semantic changes`() {
        val wrongSchema = temporaryDirectory.resolve("wrong-schema.wxf")
        Files.writeString(wrongSchema, "<Wix xmlns=\"urn:not-wix4\"><Component Id=\"x\"/></Wix>")
        assertFailsWith<IllegalArgumentException> {
            JpackageComponentGuidNamespace.transform(
                wrongSchema,
                mapOf("x" to "{0566E653-E9AE-32AD-8448-1AC3F202A898}"),
                temporaryDirectory.resolve("wrong-output.wxf"),
                PLANNER_NAMESPACE,
            )
        }

        val source = writeFixture("source.wxf")
        assertFailsWith<IllegalArgumentException> {
            JpackageComponentGuidNamespace.transform(
                source,
                PROBE_COMPONENTS + (
                    "cfile0566e653e9ae32ad84481ac3f202a898" to
                        "{11111111-1111-3111-8111-111111111111}"
                ),
                temporaryDirectory.resolve("mismatch.wxf"),
                PLANNER_NAMESPACE,
            )
        }

        val changed = temporaryDirectory.resolve("changed.wxf")
        Files.writeString(changed, source.readText().replace("runtime\\bin\\server\\jvm.dll", "runtime\\bin\\server\\other.dll"))
        assertFailsWith<IllegalArgumentException> {
            JpackageComponentGuidNamespace.assertOnlyExpectedChanges(source, changed)
        }
    }

    @Test
    fun `fails closed when generated package metadata is missing or leaves the app directory`() {
        val missing = temporaryDirectory.resolve("missing-package.wxf")
        Files.writeString(missing, FIXTURE.replace("config\\.package", "config\\other.txt"))
        assertFailsWith<IllegalArgumentException> {
            JpackageComponentGuidNamespace.transform(
                missing,
                PROBE_COMPONENTS,
                temporaryDirectory.resolve("missing-package-output.wxf"),
                PLANNER_NAMESPACE,
            )
        }

        val wrongDirectory = temporaryDirectory.resolve("wrong-directory.wxf")
        Files.writeString(wrongDirectory, FIXTURE.replace("Name=\"app\"", "Name=\"data\""))
        assertFailsWith<IllegalArgumentException> {
            JpackageComponentGuidNamespace.transform(
                wrongDirectory,
                PROBE_COMPONENTS,
                temporaryDirectory.resolve("wrong-directory-output.wxf"),
                PLANNER_NAMESPACE,
            )
        }
    }

    private fun writeFixture(name: String): Path {
        val path = temporaryDirectory.resolve(name)
        Files.writeString(path, FIXTURE)
        return path
    }

    private companion object {
        val PLANNER_NAMESPACE: UUID = UUID.fromString("502B9850-A5B0-4922-BB20-AC7FEBA590DC")

        val PROBE_COMPONENTS = mapOf(
            "cfile0566e653e9ae32ad84481ac3f202a898" to "{0566E653-E9AE-32AD-8448-1AC3F202A898}",
            "cfiled4b06b31fb623c598059562884b108f1" to "{D4B06B31-FB62-3C59-8059-562884B108F1}",
            "crm_rf48431aecbd69377ea800d62616352f20" to "{9D11BBF8-01D1-5B9D-9A13-B9E82360CDD9}",
        )

        val FIXTURE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Wix xmlns="http://wixtoolset.org/schemas/v4/wxs"
                 xmlns:util="http://wixtoolset.org/schemas/v4/wxs/util">
              <Fragment>
                <DirectoryRef Id="INSTALLDIR">
                  <Component Bitness="always64" Guid="{0566e653-e9ae-32ad-8448-1ac3f202a898}" Id="cfile0566e653e9ae32ad84481ac3f202a898">
                    <File Id="file0566e653e9ae32ad84481ac3f202a898" Source="runtime\bin\server\jvm.dll"/>
                  </Component>
                  <Directory Id="dir40f84b56068d341da01a768174eb5e59" Name="app"/>
                </DirectoryRef>
                <DirectoryRef Id="dir40f84b56068d341da01a768174eb5e59">
                  <Component Bitness="always64" Guid="{d4b06b31-fb62-3c59-8059-562884b108f1}" Id="cfiled4b06b31fb623c598059562884b108f1">
                    <RegistryKey Root="HKCU" Key="Software\Planner\0.1.1">
                      <RegistryValue Type="string" KeyPath="yes" Name="ProductCode" Value="[ProductCode]"/>
                    </RegistryKey>
                    <RemoveFolder Id="rm98442446b28b37358e47a8bc03b785fd_62" On="uninstall"/>
                    <File Id="filed4b06b31fb623c598059562884b108f1" Source="config\.package"/>
                  </Component>
                </DirectoryRef>
                <DirectoryRef Id="INSTALLDIR">
                  <Component Id="crm_rf48431aecbd69377ea800d62616352f20">
                    <RegistryKey Root="HKCU" Key="Software\Planner">
                      <RegistryValue Type="string" KeyPath="yes" Name="Path" Value="[INSTALLDIR]"/>
                    </RegistryKey>
                    <util:RemoveFolderEx On="uninstall" Property="RM_RF48431AECBD69377EA800D62616352F20"/>
                  </Component>
                </DirectoryRef>
                <ComponentGroup Id="Files">
                  <ComponentRef Id="cfile0566e653e9ae32ad84481ac3f202a898"/>
                  <ComponentRef Id="cfiled4b06b31fb623c598059562884b108f1"/>
                  <ComponentRef Id="crm_rf48431aecbd69377ea800d62616352f20"/>
                </ComponentGroup>
              </Fragment>
            </Wix>
        """.trimIndent()

        val SHORTCUT_FIXTURE = FIXTURE.replace(
            """<ComponentGroup Id="Files">""",
            """
                <DirectoryRef Id="ProgramMenuPlanner">
                  <Component Bitness="always64" Guid="{11111111-1111-3111-8111-111111111111}" Id="mcpMenu" Condition="JP_INSTALL_STARTMENU_SHORTCUT">
                    <RegistryKey Root="HKCU" Key="Software\Planner\0.2.0"><RegistryValue Type="string" KeyPath="yes" Name="ProductCode" Value="[ProductCode]"/></RegistryKey>
                    <RemoveFolder Id="mcpMenuFolder" On="uninstall"/>
                    <Shortcut Id="mcpMenuShortcut" Name="EVE Map MCP Bridge" Target="[#mcpExe]"/>
                  </Component>
                </DirectoryRef>
                <StandardDirectory Id="DesktopFolder">
                  <Component Bitness="always64" Guid="{22222222-2222-3222-8222-222222222222}" Id="mcpDesktop" Condition="JP_INSTALL_DESKTOP_SHORTCUT">
                    <RegistryKey Root="HKCU" Key="Software\Planner\0.2.0"><RegistryValue Type="string" KeyPath="yes" Name="ProductCode" Value="[ProductCode]"/></RegistryKey>
                    <Shortcut Id="mcpDesktopShortcut" Name="EVE Map MCP Bridge" Target="[#mcpExe]"/>
                  </Component>
                </StandardDirectory>
                <ComponentGroup Id="Shortcuts">
                  <ComponentRef Id="mainMenu"/>
                  <ComponentRef Id="mainDesktop"/>
                  <ComponentRef Id="mcpMenu"/>
                  <ComponentRef Id="mcpDesktop"/>
                </ComponentGroup>
                <Shortcut Name="EVE Static Map Planner"/>
                <ComponentGroup Id="Files">""".trimIndent(),
        )
    }
}
