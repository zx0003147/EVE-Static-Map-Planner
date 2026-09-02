package dev.evestaticmapplanner.shared

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util
import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.shared.auth.SecureCredentialException
import dev.evestaticmapplanner.shared.auth.SecureCredentialStore
import dev.evestaticmapplanner.shared.auth.SharedCredentialKey
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
import java.util.Locale

internal class WindowsDpapiCredentialStore(
    private val applicationRoot: Path,
) : SecureCredentialStore {
    override fun load(key: SharedCredentialKey): SecretValue? {
        requireWindows()
        val path = pathFor(key)
        if (!Files.exists(path)) return null
        val ciphertext = try {
            Files.readAllBytes(path)
        } catch (error: Exception) {
            throw SecureCredentialException("The encrypted Shared Map credential could not be read.", error)
        }
        if (ciphertext.isEmpty() || ciphertext.size > MAX_CIPHERTEXT_BYTES) {
            ciphertext.fill(0)
            throw SecureCredentialException("The encrypted Shared Map credential is corrupt.")
        }
        val entropy = entropyFor(key)
        val plaintext = try {
            Crypt32Util.cryptUnprotectData(ciphertext, entropy, 0, null)
        } catch (error: Exception) {
            throw SecureCredentialException("The encrypted Shared Map credential is unreadable.", error)
        } finally {
            ciphertext.fill(0)
            entropy.fill(0)
        }
        return try {
            SecretValue.fromUtf8(plaintext)
        } catch (error: Exception) {
            throw SecureCredentialException("The encrypted Shared Map credential is corrupt.", error)
        } finally {
            plaintext.fill(0)
        }
    }

    override fun save(key: SharedCredentialKey, secret: SecretValue) {
        requireWindows()
        val entropy = entropyFor(key)
        val ciphertext = try {
            secret.useUtf8Bytes { plaintext ->
                try {
                    Crypt32Util.cryptProtectData(plaintext, entropy, 0, DESCRIPTION, null)
                } catch (error: Exception) {
                    throw SecureCredentialException("Windows could not protect the Shared Map credential.", error)
                }
            }
        } finally {
            entropy.fill(0)
        }
        try {
            writeAtomically(pathFor(key), ciphertext)
        } finally {
            ciphertext.fill(0)
        }
    }

    override fun delete(key: SharedCredentialKey) {
        try {
            Files.deleteIfExists(pathFor(key))
        } catch (error: Exception) {
            throw SecureCredentialException("The encrypted Shared Map credential could not be deleted.", error)
        }
    }

    internal fun pathForTesting(key: SharedCredentialKey): Path = pathFor(key)

    private fun pathFor(key: SharedCredentialKey): Path {
        val originHash = sha256(key.serverOrigin.toByteArray(Charsets.UTF_8)).toHex()
        return applicationRoot.toAbsolutePath().normalize()
            .resolve("credentials")
            .resolve("shared-map")
            .resolve(originHash)
            .resolve("${key.workspaceId}.dpapi")
    }

    private fun entropyFor(key: SharedCredentialKey): ByteArray = sha256(
        "$ENTROPY_CONTEXT\u0000${key.serverOrigin}\u0000${key.workspaceId}".toByteArray(Charsets.UTF_8),
    )

    private fun writeAtomically(path: Path, bytes: ByteArray) {
        val parent = path.parent ?: throw SecureCredentialException("The secure credential path is invalid.")
        try {
            Files.createDirectories(parent)
            restrictToOwner(parent)
            val temporary = Files.createTempFile(parent, ".shared-map-", ".tmp")
            try {
                restrictToOwner(temporary)
                FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                try {
                    Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
                }
                restrictToOwner(path)
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (error: SecureCredentialException) {
            throw error
        } catch (error: Exception) {
            throw SecureCredentialException("The encrypted Shared Map credential could not be stored.", error)
        }
    }

    private fun restrictToOwner(path: Path) {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            ?: throw SecureCredentialException("The credential filesystem does not support Windows access controls.")
        val owner = view.owner
        val ownerOnly = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(AclEntryPermission.entries.toSet())
            .build()
        view.acl = listOf(ownerOnly)
    }

    private fun requireWindows() {
        if (!Platform.isWindows()) {
            throw SecureCredentialException("Shared Map secure credentials require Windows DPAPI.")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    companion object {
        private const val DESCRIPTION = "EVE Static Map Planner Shared Map Device Token"
        private const val ENTROPY_CONTEXT = "EVE Static Map Planner/Shared Map/v1"
        private const val MAX_CIPHERTEXT_BYTES = 64 * 1024

        private fun sha256(input: ByteArray): ByteArray = try {
            MessageDigest.getInstance("SHA-256").digest(input)
        } finally {
            input.fill(0)
        }
    }
}
