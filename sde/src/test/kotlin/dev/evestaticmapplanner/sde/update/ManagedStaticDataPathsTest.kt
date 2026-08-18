package dev.evestaticmapplanner.sde.update

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagedStaticDataPathsTest {
    @Test
    fun `managed paths stay under the supplied root`() {
        val root = createTempDirectory("managed-paths")
        val paths = ManagedStaticDataPaths(root)
        paths.initialize()

        assertEquals(root.toAbsolutePath().normalize().resolve("data/static.db"), paths.activeDatabase)
        assertTrue(Files.isDirectory(paths.stagingDirectory))
        assertTrue(paths.stagedDatabase("build-1").startsWith(paths.root))
    }

    @Test
    fun `unsafe identifiers are rejected`() {
        val paths = ManagedStaticDataPaths(createTempDirectory("managed-paths"))
        assertFailsWith<IllegalArgumentException> { paths.stagingRoot("../escape") }
        assertFailsWith<IllegalArgumentException> { paths.stagingRoot("C:\\escape") }
    }

    @Test
    fun `initialization does not create an empty static database`() {
        val paths = ManagedStaticDataPaths(createTempDirectory("managed-paths"))
        paths.initialize()
        assertFalse(Files.exists(paths.activeDatabase))
    }
}
