package dev.evestaticmapplanner.feature.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SystemInfoApiTest {
    @Test
    fun `fixture provider returns requested data and an empty result when unavailable`() {
        val provider = fixtureProvider()

        val populated = provider.provide(30_000_142)
        val empty = provider.provide(30_000_143)

        assertEquals("fixture.system-info", provider.descriptor().id)
        assertEquals(30_000_142, populated.systemId)
        assertEquals("Fixture", populated.sections.single().title)
        assertEquals("Available", populated.sections.single().fields.single().value)
        assertEquals(30_000_143, empty.systemId)
        assertTrue(empty.sections.isEmpty())
    }

    @Test
    fun `models reject invalid identities duplicate keys and mismatched empty state`() {
        assertFailsWith<IllegalArgumentException> {
            SystemInfoProviderDescriptor("Invalid ID", "Invalid")
        }
        assertFailsWith<IllegalArgumentException> {
            SystemInfoSection(
                "duplicate",
                "Duplicate",
                fields = listOf(
                    SystemInfoField("same", "First", "One"),
                    SystemInfoField("same", "Second", "Two"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SystemInfoSnapshot(
                30_000_142,
                listOf(section("same"), section("same")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SystemInfoState(null, listOf(section("orphan")))
        }
    }

    private fun fixtureProvider() = object : SystemInfoProvider {
        override fun descriptor() = SystemInfoProviderDescriptor(
            id = "fixture.system-info",
            name = "Fixture System Info",
            priority = 10,
        )

        override fun provide(systemId: Int) = SystemInfoSnapshot(
            systemId,
            if (systemId == 30_000_142) listOf(section("fixture")) else emptyList(),
        )
    }

    private fun section(id: String) = SystemInfoSection(
        sectionId = id,
        title = "Fixture",
        fields = listOf(SystemInfoField("status", "Status", "Available")),
    )
}
