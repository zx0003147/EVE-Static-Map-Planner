package dev.evestaticmapplanner.ai

import com.sun.jna.Platform
import dev.evestaticmapplanner.embeddedai.AiCredentialRef
import dev.evestaticmapplanner.shared.auth.SecretValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class WindowsDpapiAiCredentialStoreTest {
    @Test
    fun `DPAPI store saves loads replaces and deletes without plaintext`() {
        assumeTrue(Platform.isWindows())
        val root = createTempDirectory("ai-dpapi-")
        val store = WindowsDpapiAiCredentialStore(root)
        val reference = AiCredentialRef("openrouter")
        val first = "SECRET_SHOULD_NEVER_APPEAR_12345"
        val second = "replacement-secret-value"
        try {
            SecretValue.from(first).use { store.save(reference, it) }
            assertTrue(store.contains(reference))
            assertSecretEquals(first, store.load(reference))
            val encrypted = Files.readAllBytes(store.pathForTesting(reference))
            assertFalse(String(encrypted, Charsets.UTF_8).contains(first))

            SecretValue.from(second).use { store.save(reference, it) }
            assertSecretEquals(second, store.load(reference))

            store.delete(reference)
            assertFalse(store.contains(reference))
            assertNull(store.load(reference))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `DPAPI credentials remain isolated across fresh JVM processes`() {
        assumeTrue(Platform.isWindows())
        val root = createTempDirectory("ai-dpapi-restart-")
        val store = WindowsDpapiAiCredentialStore(root)
        val openRouter = AiCredentialRef("openrouter")
        val anthropic = AiCredentialRef("anthropic")
        try {
            runFixtureProcess(root, "write")
            assertTrue(store.contains(openRouter))
            assertTrue(store.contains(anthropic))

            runFixtureProcess(root, "verify-and-delete-anthropic")
            assertTrue(store.contains(openRouter))
            assertFalse(store.contains(anthropic))
            assertSecretEquals("fixture-openrouter-key", store.load(openRouter))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

private fun runFixtureProcess(root: Path, action: String) {
    val java = Path.of(System.getProperty("java.home"), "bin", "java.exe")
    val process = ProcessBuilder(
        java.toString(),
        "-cp",
        System.getProperty("java.class.path"),
        DpapiRestartFixture::class.qualifiedName,
        root.toString(),
        action,
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor() == 0) { "DPAPI restart fixture failed: $output" }
}

private fun assertSecretEquals(expected: String, actual: SecretValue?) {
    val secret = checkNotNull(actual)
    secret.use { value -> value.useString { assertTrue(it == expected) } }
}
