package dev.evestaticmapplanner.sde.update

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeSdeArchiveExtractorTest {
    @Test
    fun `extracts only four required JSONL entries`() {
        val root = createTempDirectory("extract")
        val archive = zip(root.resolve("valid.zip"), validEntries() + ("ignored.jsonl" to "ignored"))
        val output = root.resolve("out")
        val result = SafeSdeArchiveExtractor().extract(archive, output)

        assertTrue(result.extractedBytes > 0)
        assertEquals(SafeSdeArchiveExtractor.REQUIRED_NAMES, Files.list(output).use { it.map { p -> p.fileName.toString() }.toList().toSet() })
        assertFalse(Files.exists(output.resolve("ignored.jsonl")))
    }

    @Test
    fun `missing and duplicate required entries are rejected`() {
        val root = createTempDirectory("extract")
        val missing = zip(root.resolve("missing.zip"), validEntries().filterNot { it.first == "mapStargates.jsonl" })
        assertFailsWith<SdeArchiveSecurityException> { SafeSdeArchiveExtractor().extract(missing, root.resolve("missing")) }

        val duplicate = zip(root.resolve("duplicate.zip"), validEntries() + ("nested/mapRegions.jsonl" to "duplicate"))
        assertFailsWith<SdeArchiveSecurityException> { SafeSdeArchiveExtractor().extract(duplicate, root.resolve("duplicate")) }
    }

    @Test
    fun `zip slip absolute UNC drive and backslash entries are rejected`() {
        val unsafe = listOf("../evil", "/absolute", "C:/drive", "//server/share", "nested\\evil")
        unsafe.forEachIndexed { index, name ->
            val root = createTempDirectory("unsafe-$index")
            val archive = zip(root.resolve("unsafe.zip"), validEntries() + (name to "evil"))
            assertFailsWith<SdeArchiveSecurityException> {
                SafeSdeArchiveExtractor().extract(archive, root.resolve("out"))
            }
            assertFalse(Files.exists(root.resolve("evil")))
        }
    }

    @Test
    fun `corrupt ZIP and extraction size limit are rejected`() {
        val root = createTempDirectory("extract")
        val corrupt = root.resolve("corrupt.zip")
        Files.write(corrupt, byteArrayOf(1, 2, 3, 4))
        assertFailsWith<Exception> { SafeSdeArchiveExtractor().extract(corrupt, root.resolve("corrupt")) }

        val valid = zip(root.resolve("large.zip"), validEntries())
        assertFailsWith<SdeArchiveSecurityException> {
            SafeSdeArchiveExtractor(maxRequiredEntryBytes = 10).extract(valid, root.resolve("large"))
        }
    }

    @Test
    fun `CRC-altered required entry is rejected`() {
        val root = createTempDirectory("crc")
        val archive = root.resolve("crc.zip")
        storedZip(archive, validEntries())
        val bytes = Files.readAllBytes(archive)
        val needle = validEntries().first().second.toByteArray().copyOfRange(0, 24)
        val offset = bytes.indexOf(needle)
        check(offset >= 0)
        bytes[offset] = (bytes[offset].toInt() xor 0x01).toByte()
        Files.write(archive, bytes)

        assertFailsWith<Exception> { SafeSdeArchiveExtractor().extract(archive, root.resolve("out")) }
    }

    internal fun validEntries(): List<Pair<String, String>> = SafeSdeArchiveExtractor.REQUIRED_NAMES.map { name ->
        val resource = UpdateTestFixtures.sourceDirectory().resolve(name)
        name to Files.readString(resource)
    }

    internal fun zip(path: Path, entries: List<Pair<String, String>>): Path {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return path
    }

    private fun storedZip(path: Path, entries: List<Pair<String, String>>) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (name, content) ->
                val data = content.toByteArray()
                val crc = CRC32().apply { update(data) }
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = data.size.toLong()
                    compressedSize = data.size.toLong()
                    this.crc = crc.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        outer@ for (index in 0..size - needle.size) {
            for (offset in needle.indices) if (this[index + offset] != needle[offset]) continue@outer
            return index
        }
        return -1
    }
}
