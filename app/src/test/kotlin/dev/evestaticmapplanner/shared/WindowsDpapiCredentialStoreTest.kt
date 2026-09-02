package dev.evestaticmapplanner.shared

import com.sun.jna.Platform
import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.shared.auth.SecureCredentialException
import dev.evestaticmapplanner.shared.auth.SharedCredentialKey
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsDpapiCredentialStoreTest {
    @Test
    fun `real current-user DPAPI supports round trip overwrite and delete without plaintext`() {
        assertTrue(Platform.isWindows(), "Shared Map secure-storage acceptance requires Windows")
        val root = createTempDirectory("shared-map-dpapi-")
        try {
            val store = WindowsDpapiCredentialStore(root)
            val first = "esm_dev_FIRST_RAW_TOKEN_TEST_角色"
            val second = "esm_dev_SECOND_RAW_TOKEN_TEST_角色"

            assertNull(store.load(KEY_A))
            SecretValue.from(first).use { store.save(KEY_A, it) }
            store.load(KEY_A)!!.use { loaded -> loaded.useString { assertEquals(first, it) } }
            assertFalse(Files.readAllBytes(store.pathForTesting(KEY_A)).toString(StandardCharsets.UTF_8).contains(first))

            SecretValue.from(second).use { store.save(KEY_A, it) }
            store.load(KEY_A)!!.use { loaded -> loaded.useString { assertEquals(second, it) } }
            val persisted = Files.readAllBytes(store.pathForTesting(KEY_A)).toString(StandardCharsets.UTF_8)
            assertFalse(persisted.contains(first))
            assertFalse(persisted.contains(second))

            store.delete(KEY_A)
            assertNull(store.load(KEY_A))
            assertFalse(Files.exists(store.pathForTesting(KEY_A)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `server and workspace credential identities are isolated by DPAPI entropy`() {
        val root = createTempDirectory("shared-map-dpapi-isolation-")
        try {
            val store = WindowsDpapiCredentialStore(root)
            SecretValue.from("esm_dev_isolated").use { store.save(KEY_A, it) }
            assertNull(store.load(KEY_B))
            assertTrue(store.pathForTesting(KEY_A) != store.pathForTesting(KEY_B))

            Files.createDirectories(store.pathForTesting(KEY_B).parent)
            Files.copy(store.pathForTesting(KEY_A), store.pathForTesting(KEY_B))
            val error = assertFailsWith<SecureCredentialException> { store.load(KEY_B) }
            assertFalse(error.toString().contains("esm_dev_isolated"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `corrupt DPAPI ciphertext fails closed without secret text`() {
        val root = createTempDirectory("shared-map-dpapi-corrupt-")
        try {
            val store = WindowsDpapiCredentialStore(root)
            val path = store.pathForTesting(KEY_A)
            Files.createDirectories(path.parent)
            Files.write(path, byteArrayOf(1, 2, 3, 4, 5))
            val error = assertFailsWith<SecureCredentialException> { store.load(KEY_A) }
            assertFalse(error.toString().contains("esm_dev_"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    companion object {
        private val KEY_A = SharedCredentialKey(
            "https://example.com",
            "01991d60-b8a2-7a20-a311-b5114b27c219",
        )
        private val KEY_B = SharedCredentialKey(
            "https://example.com",
            "01991d60-b8a2-7a20-a311-b5114b27c220",
        )
    }
}
