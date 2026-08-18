package dev.evestaticmapplanner.sde.update

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagedStaticDataCleanerTest {
    @Test
    fun `cleaner removes parts and orphan staging but retains pending staging`() {
        val paths = ManagedStaticDataPaths(createTempDirectory("cleaner"))
        val pending = UpdateTestFixtures.preparePending(paths, UpdateTestFixtures.NEW_BUILD)
        val orphan = paths.stagingRoot("orphan")
        Files.createDirectories(orphan)
        Files.writeString(orphan.resolve("file"), "orphan")
        val part = paths.downloadsDirectory.resolve("download.zip.part")
        Files.writeString(part, "partial")

        ManagedStaticDataCleaner(paths).cleanOrphans()

        assertFalse(Files.exists(part))
        assertFalse(Files.exists(orphan))
        assertTrue(Files.isRegularFile(paths.stagedDatabase(pending.stagingId)))
    }
}
