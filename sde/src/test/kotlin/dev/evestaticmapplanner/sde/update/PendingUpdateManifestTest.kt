package dev.evestaticmapplanner.sde.update

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PendingUpdateManifestTest {
    @Test
    fun `manifest round trips and resolves only its managed candidate`() {
        val paths = ManagedStaticDataPaths(createTempDirectory("pending"))
        val manifest = UpdateTestFixtures.preparePending(paths, UpdateTestFixtures.NEW_BUILD)
        val store = PendingUpdateStore(paths)

        assertEquals(manifest, store.read())
        assertEquals(paths.stagedDatabase(manifest.stagingId), store.candidatePath(assertNotNull(store.read())))
    }

    @Test
    fun `corrupt manifest is rejected`() {
        val paths = ManagedStaticDataPaths(createTempDirectory("pending"))
        paths.initialize()
        Files.writeString(paths.pendingManifest, "{not-json")
        assertFailsWith<Exception> { PendingUpdateStore(paths).read() }
    }

    @Test
    fun `relative path must exactly match the staging id`() {
        assertFailsWith<IllegalArgumentException> {
            PendingUpdateManifest(
                targetBuild = 1,
                officialBuildIdentity = "tranquility/1/jsonl",
                sourceUrl = "https://example.test/1.zip",
                archiveSha256 = "a".repeat(64),
                archiveSize = 1,
                generatedDatabaseSha256 = "b".repeat(64),
                generatedDatabaseSize = 1,
                createdAt = "now",
                downloadedAt = "now",
                stagingId = "safe",
                stagedDatabaseRelativePath = "../escape.db",
                expectedSchemaVersion = 1,
                generatorVersion = "test",
                counts = CandidateCounts(1, 1, 1, 1),
            )
        }
    }
}
