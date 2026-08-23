package dev.evestaticmapplanner.packaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MsiDistributionAuditReaderTest {
    @Test
    fun `audit rows preserve empty MSI fields and table identities`() {
        val audit = MsiDistributionAuditReader.parseRows(
            """
                SUMMARY_TEMPLATE	x64;1033
                TABLE	Property
                TABLE	File
                PROPERTY	ProductName	EVE Static Map Planner
                PROPERTY	ProductVersion	0.2.0
                PROPERTY	ProductCode	{PRODUCT}
                PROPERTY	UpgradeCode	{UPGRADE}
                PROPERTY	Manufacturer	Static Map Planner Project
                FILE	file1	component1	EVE Map MCP Bridge.exe	540672
                COMPONENT	component1	{COMPONENT}	INSTALLDIR
                FEATURE	DefaultFeature		Main
                SHORTCUT	shortcut1	DesktopFolder	EVE Static Map Planner	component1	[#file1]
                REMOVE_FILE	cleanup	component1	.package	APPDIR	1
                UPGRADE	{UPGRADE}		0.2.0	JP_UPGRADABLE_FOUND
                INSTALL_SEQUENCE	JpSuppressRemoveFolderExDuringUpgrade	UPGRADINGPRODUCTCODE	52
                CUSTOM_ACTION	JpSuppressRemoveFolderExDuringUpgrade	51	RM_RF${'\t'}
                REMOVE_FOLDER_EX	remove1	component1	RM_RF	3${'\t'}
                DIRECTORY	INSTALLDIR	LocalAppDataFolder	EVE Static Map Planner
            """.trimIndent(),
        )

        assertEquals("EVE Static Map Planner", audit.productName)
        assertEquals("0.2.0", audit.productVersion)
        assertEquals("{PRODUCT}", audit.productCode)
        assertEquals("{UPGRADE}", audit.upgradeCode)
        assertEquals("x64;1033", audit.summaryTemplate)
        assertEquals(listOf("DefaultFeature", "", "Main"), audit.features.single())
        assertEquals("EVE Map MCP Bridge.exe", audit.files.single()[2])
        assertEquals("INSTALLDIR", audit.components.single()[2])
        assertTrue(audit.tables.containsAll(setOf("Property", "File")))
    }
}
