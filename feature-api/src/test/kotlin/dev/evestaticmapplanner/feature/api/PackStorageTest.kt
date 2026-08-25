package dev.evestaticmapplanner.feature.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PackStorageTest {
    @Test
    fun `portable nested relative paths are accepted`() {
        val path = PackRelativePath("profiles/default-v1.json")

        assertEquals("profiles/default-v1.json", path.value)
        assertEquals(path.value, path.toPath().toString().replace('\\', '/'))
    }

    @Test
    fun `absolute traversal ambiguous and invalid paths are rejected`() {
        listOf(
            "", "/root", "C:/root", "C:\\root", "../escape", "safe/../escape", ".",
            "safe//file", "safe/", "safe\\file", "safe file", "数据/file", "con/file", "safe/aux.txt",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { PackRelativePath(value) }
        }
    }
}
