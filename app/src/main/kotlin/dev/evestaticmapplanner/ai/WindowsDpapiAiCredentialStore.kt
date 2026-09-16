package dev.evestaticmapplanner.ai

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util
import dev.evestaticmapplanner.embeddedai.AiCredentialRef
import dev.evestaticmapplanner.embeddedai.AiCredentialStore
import dev.evestaticmapplanner.embeddedai.AiCredentialStoreException
import dev.evestaticmapplanner.shared.auth.SecretValue
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.security.MessageDigest

internal class WindowsDpapiAiCredentialStore(
    private val applicationRoot: Path,
) : AiCredentialStore {
    override fun contains(reference: AiCredentialRef): Boolean = Files.isRegularFile(pathFor(reference))

    override fun load(reference: AiCredentialRef): SecretValue? {
        requireWindows()
        val path = pathFor(reference)
        if (!Files.isRegularFile(path)) return null
        val ciphertext = try {
            Files.readAllBytes(path)
        } catch (error: Exception) {
            throw AiCredentialStoreException("The encrypted AI credential could not be read.", error)
        }
        if (ciphertext.isEmpty() || ciphertext.size > MAX_CIPHERTEXT_BYTES) {
            ciphertext.fill(0)
            throw AiCredentialStoreException("The encrypted AI credential is corrupt.")
        }
        val entropy = entropyFor(reference)
        val plaintext = try {
            Crypt32Util.cryptUnprotectData(ciphertext, entropy, 0, null)
        } catch (error: Exception) {
            throw AiCredentialStoreException("The encrypted AI credential is unreadable.", error)
        } finally {
            ciphertext.fill(0)
            entropy.fill(0)
        }
        return try {
            SecretValue.fromUtf8(plaintext)
        } catch (error: Exception) {
            throw AiCredentialStoreException("The encrypted AI credential is corrupt.", error)
        } finally {
            plaintext.fill(0)
        }
    }

    override fun save(reference: AiCredentialRef, secret: SecretValue) {
        requireWindows()
        val entropy = entropyFor(reference)
        val ciphertext = try {
            secret.useUtf8Bytes { plaintext ->
                try {
                    Crypt32Util.cryptProtectData(plaintext, entropy, 0, DESCRIPTION, null)
                } catch (error: Exception) {
                    throw AiCredentialStoreException("Windows could not protect the AI credential.", error)
                }
            }
        } finally {
            entropy.fill(0)
        }
        try {
            writeAtomically(pathFor(reference), ciphertext)
        } finally {
            ciphertext.fill(0)
        }
    }

    override fun delete(reference: AiCredentialRef) {
        try {
            Files.deleteIfExists(pathFor(reference))
        } catch (error: Exception) {
            throw AiCredentialStoreException("The encrypted AI credential could not be deleted.", error)
        }
    }

    internal fun pathForTesting(reference: AiCredentialRef): Path = pathFor(reference)

    private fun pathFor(reference: AiCredentialRef): Path = applicationRoot.toAbsolutePath().normalize()
        .resolve("credentials")
        .resolve("embedded-ai")
        .resolve("${reference.value}.dpapi")

    private fun entropyFor(reference: AiCredentialRef): ByteArray = sha256(
        "$ENTROPY_CONTEXT\u0000${reference.value}".toByteArray(Charsets.UTF_8),
    )

    private fun writeAtomically(path: Path, bytes: ByteArray) {
        val parent = path.parent ?: throw AiCredentialStoreException("The secure credential path is invalid.")
        try {
            Files.createDirectories(parent)
            restrictToOwner(parent)
            val temporary = Files.createTempFile(parent, ".embedded-ai-", ".tmp")
            try {
                restrictToOwner(temporary)
                FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
                }
                restrictToOwner(path)
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (error: AiCredentialStoreException) {
            throw error
        } catch (error: Exception) {
            throw AiCredentialStoreException("The encrypted AI credential could not be stored.", error)
        }
    }

    private fun restrictToOwner(path: Path) {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            ?: throw AiCredentialStoreException("The credential filesystem does not support Windows access controls.")
        val ownerOnly = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(view.owner)
            .setPermissions(AclEntryPermission.entries.toSet())
            .build()
        view.acl = listOf(ownerOnly)
    }

    private fun requireWindows() {
        if (!Platform.isWindows()) throw AiCredentialStoreException("AI secure credentials require Windows DPAPI.")
    }

    companion object {
        private const val DESCRIPTION = "EVE Static Map Planner AI Provider Credential"
        private const val ENTROPY_CONTEXT = "EVE Static Map Planner/Embedded AI/v1"
        private const val MAX_CIPHERTEXT_BYTES = 64 * 1024

        private fun sha256(input: ByteArray): ByteArray = try {
            MessageDigest.getInstance("SHA-256").digest(input)
        } finally {
            input.fill(0)
        }
    }
}
