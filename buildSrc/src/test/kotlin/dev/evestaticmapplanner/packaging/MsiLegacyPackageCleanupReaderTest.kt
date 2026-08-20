package dev.evestaticmapplanner.packaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MsiLegacyPackageCleanupReaderTest {
    @Test
    fun `parses exact app directory install cleanup`() {
        val state = MsiLegacyPackageCleanupReader.parseRows(
            "FILE_COUNT\t0\n" +
                "REMOVE_FILE\tJpRemoveLegacyPackageMetadata\tcfile123\tshort.pac|.package\tdirApp\tshort|app\t1\n",
        )

        assertEquals(0, state.packageFileCount)
        assertEquals(
            MsiLegacyPackageCleanupRow(
                fileKey = "JpRemoveLegacyPackageMetadata",
                componentId = "cfile123",
                fileName = "short.pac|.package",
                directoryId = "dirApp",
                directoryName = "short|app",
                installMode = 1,
            ),
            state.cleanupRows.single(),
        )
    }

    @Test
    fun `fails closed on malformed or missing cleanup output`() {
        assertFailsWith<IllegalArgumentException> {
            MsiLegacyPackageCleanupReader.parseRows("REMOVE_FILE\ttoo\tfew\n")
        }
        assertFailsWith<IllegalArgumentException> {
            MsiLegacyPackageCleanupReader.parseRows("FILE_COUNT\t0\nFILE_COUNT\t1\n")
        }
    }
}
