package dev.evestaticmapplanner.control.transport

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.UserPrincipal
import java.util.EnumSet

object LocalControlDiscoveryProtocol {
    const val DESCRIPTOR_VERSION = 1
    const val DESCRIPTOR_FILE_NAME = "active-instance.json"
    const val SESSION_KEY_FILE_NAME = "session.key"
    const val ACTIVE_LOCK_FILE_NAME = "active.lock"
    const val MAX_DESCRIPTOR_BYTES = 16 * 1024L
    const val MAX_SESSION_KEY_BYTES = 1024L
}

sealed interface LocalControlDiscoveryAcquisition {
    data class Acquired(val lease: ActiveLocalControlDiscovery) : LocalControlDiscoveryAcquisition
    data object AlreadyActive : LocalControlDiscoveryAcquisition
}

class SecureLocalControlDiscovery internal constructor(
    root: Path,
    private val aclSecurity: DiscoveryAclSecurity,
    private val atomicMove: (Path, Path) -> Unit,
) {
    constructor(root: Path) : this(root, WindowsAccountOnlyAclSecurity(), ::moveAtomically)

    val root: Path = root.toAbsolutePath().normalize()
    private val applicationRoot = requireNotNull(this.root.parent) { "Control discovery root requires a parent" }
    private val activeLock = this.root.resolve(LocalControlDiscoveryProtocol.ACTIVE_LOCK_FILE_NAME)
    private val descriptor = this.root.resolve(LocalControlDiscoveryProtocol.DESCRIPTOR_FILE_NAME)
    private val sessionKey = this.root.resolve(LocalControlDiscoveryProtocol.SESSION_KEY_FILE_NAME)

    fun acquire(): LocalControlDiscoveryAcquisition {
        ensureSecureDirectories()
        requireRegularFileOrMissing(activeLock, null)
        val channel = openLockChannel()
        var lock: FileLock? = null
        try {
            aclSecurity.secureFile(activeLock)
            lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                return LocalControlDiscoveryAcquisition.AlreadyActive
            }
            val lease = ActiveLocalControlDiscovery(
                root = root,
                descriptor = descriptor,
                sessionKey = sessionKey,
                channel = channel,
                lock = lock,
                aclSecurity = aclSecurity,
                atomicMove = atomicMove,
            )
            lease.cleanupStaleArtifacts()
            return LocalControlDiscoveryAcquisition.Acquired(lease)
        } catch (failure: Throwable) {
            runCatching { lock?.release() }
            runCatching { channel.close() }
            throw DiscoverySecurityException("Secure local control discovery could not be acquired", failure)
        }
    }

    private fun ensureSecureDirectories() {
        createOrRequireDirectory(applicationRoot)
        createOrRequireDirectory(root)
        aclSecurity.secureDirectory(root)
    }

    private fun createOrRequireDirectory(path: Path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            requireDirectory(path)
            return
        }
        try {
            Files.createDirectory(path)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            requireDirectory(path)
        }
        requireDirectory(path)
    }

    private fun openLockChannel(): FileChannel = FileChannel.open(
        activeLock,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS,
    )
}

class ActiveLocalControlDiscovery internal constructor(
    val root: Path,
    private val descriptor: Path,
    private val sessionKey: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
    private val aclSecurity: DiscoveryAclSecurity,
    private val atomicMove: (Path, Path) -> Unit,
) : AutoCloseable {
    private val lifecycleLock = Any()
    private var released = false
    private var descriptorPublished = false
    private var sessionKeyPublished = false

    internal fun cleanupStaleArtifacts() = synchronized(lifecycleLock) {
        ensureHeld()
        requireRegularFileOrMissing(descriptor, LocalControlDiscoveryProtocol.MAX_DESCRIPTOR_BYTES)
        requireRegularFileOrMissing(sessionKey, LocalControlDiscoveryProtocol.MAX_SESSION_KEY_BYTES)
        Files.deleteIfExists(descriptor)
        Files.deleteIfExists(sessionKey)
        cleanupTemporaryFiles()
    }

    fun publish(server: LocalControlServer) = synchronized(lifecycleLock) {
        ensureHeld()
        check(!descriptorPublished && !sessionKeyPublished) { "Control discovery is already published" }
        val metadata = checkNotNull(server.sessionMetadata) { "Local control server is not running" }
        check(metadata.boundAddress.hostAddress == "127.0.0.1" && metadata.port > 0)
        val secret = server.sessionCredentials().encodedSecretBytes()
        try {
            secureAtomicWrite(
                target = sessionKey,
                temporaryPrefix = SESSION_TEMP_PREFIX,
                bytes = secret,
                limit = LocalControlDiscoveryProtocol.MAX_SESSION_KEY_BYTES,
            )
            sessionKeyPublished = true
            val descriptorBytes = descriptorBytes(metadata)
            secureAtomicWrite(
                target = descriptor,
                temporaryPrefix = DESCRIPTOR_TEMP_PREFIX,
                bytes = descriptorBytes,
                limit = LocalControlDiscoveryProtocol.MAX_DESCRIPTOR_BYTES,
            )
            descriptorPublished = true
        } catch (failure: Throwable) {
            runCatching {
                requireRegularFileOrMissing(descriptor, LocalControlDiscoveryProtocol.MAX_DESCRIPTOR_BYTES)
                Files.deleteIfExists(descriptor)
            }
            runCatching {
                requireRegularFileOrMissing(sessionKey, LocalControlDiscoveryProtocol.MAX_SESSION_KEY_BYTES)
                Files.deleteIfExists(sessionKey)
            }
            descriptorPublished = false
            sessionKeyPublished = false
            runCatching { cleanupTemporaryFiles() }
            throw DiscoverySecurityException("Secure local control discovery could not be published", failure)
        } finally {
            secret.fill(0)
        }
    }

    fun unpublishDescriptor(): Unit = synchronized(lifecycleLock) {
        if (released) return
        requireRegularFileOrMissing(descriptor, LocalControlDiscoveryProtocol.MAX_DESCRIPTOR_BYTES)
        Files.deleteIfExists(descriptor)
        descriptorPublished = false
    }

    fun removeSessionKey(): Unit = synchronized(lifecycleLock) {
        if (released) return
        requireRegularFileOrMissing(sessionKey, LocalControlDiscoveryProtocol.MAX_SESSION_KEY_BYTES)
        Files.deleteIfExists(sessionKey)
        sessionKeyPublished = false
    }

    fun release(): Unit = synchronized(lifecycleLock) {
        if (released) return
        var firstFailure: Throwable? = null
        fun bestEffort(block: () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        bestEffort {
            requireRegularFileOrMissing(descriptor, LocalControlDiscoveryProtocol.MAX_DESCRIPTOR_BYTES)
            Files.deleteIfExists(descriptor)
        }
        bestEffort {
            requireRegularFileOrMissing(sessionKey, LocalControlDiscoveryProtocol.MAX_SESSION_KEY_BYTES)
            Files.deleteIfExists(sessionKey)
        }
        bestEffort(::cleanupTemporaryFiles)
        bestEffort { lock.release() }
        bestEffort { channel.close() }
        descriptorPublished = false
        sessionKeyPublished = false
        released = true
        firstFailure?.let { throw DiscoverySecurityException("Secure local control discovery cleanup failed", it) }
        Unit
    }

    override fun close() {
        release()
    }

    private fun secureAtomicWrite(target: Path, temporaryPrefix: String, bytes: ByteArray, limit: Long) {
        require(bytes.size.toLong() in 1..limit) { "Discovery file content exceeds its limit" }
        requireRegularFileOrMissing(target, limit)
        check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Discovery target already exists" }
        val temporary = Files.createTempFile(root, temporaryPrefix, TEMP_SUFFIX)
        try {
            requireRegularFile(temporary, limit)
            aclSecurity.secureFile(temporary)
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
            ).use { output ->
                var buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) output.write(buffer)
                output.force(true)
            }
            requireRegularFile(temporary, limit)
            check(Files.size(temporary) == bytes.size.toLong()) { "Discovery file write was incomplete" }
            atomicMove(temporary, target)
            requireRegularFile(target, limit)
            aclSecurity.verifyFile(target)
        } finally {
            requireRegularFileOrMissing(temporary, limit)
            Files.deleteIfExists(temporary)
        }
    }

    private fun descriptorBytes(metadata: LocalControlSessionMetadata): ByteArray {
        val process = ProcessHandle.current()
        val processStart = process.info().startInstant().orElse(null)
        return buildJsonObject {
            put("descriptorVersion", LocalControlDiscoveryProtocol.DESCRIPTOR_VERSION)
            put("protocolVersion", metadata.protocolVersion)
            put("controlApiVersion", metadata.controlApiVersion)
            put("instanceId", metadata.instanceId)
            put("pid", process.pid())
            put("processStart", processStart?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("port", metadata.port)
            put("appVersion", metadata.appVersion)
        }.toString().toByteArray(Charsets.UTF_8)
    }

    private fun cleanupTemporaryFiles() {
        Files.newDirectoryStream(root).use { entries ->
            entries.forEach { candidate ->
                val name = candidate.fileName.toString()
                val limit = when {
                    name.startsWith(DESCRIPTOR_TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX) ->
                        LocalControlDiscoveryProtocol.MAX_DESCRIPTOR_BYTES
                    name.startsWith(SESSION_TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX) ->
                        LocalControlDiscoveryProtocol.MAX_SESSION_KEY_BYTES
                    else -> null
                } ?: return@forEach
                requireRegularFile(candidate, limit)
                Files.deleteIfExists(candidate)
            }
        }
    }

    private fun ensureHeld() {
        check(!released && lock.isValid) { "Control discovery lock is not held" }
    }

    private companion object {
        const val DESCRIPTOR_TEMP_PREFIX = "active-instance-"
        const val SESSION_TEMP_PREFIX = "session-"
        const val TEMP_SUFFIX = ".tmp"
    }
}

class DiscoverySecurityException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal interface DiscoveryAclSecurity {
    fun secureDirectory(path: Path)
    fun secureFile(path: Path)
    fun verifyDirectory(path: Path)
    fun verifyFile(path: Path)
}

internal class WindowsAccountOnlyAclSecurity(
    private val principalProvider: (Path) -> UserPrincipal = ::currentWindowsAccount,
    private val viewProvider: (Path) -> AclFileAttributeView? = { path ->
        Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
    },
) : DiscoveryAclSecurity {
    override fun secureDirectory(path: Path) = secure(path, directory = true)
    override fun secureFile(path: Path) = secure(path, directory = false)
    override fun verifyDirectory(path: Path) = verify(path, directory = true)
    override fun verifyFile(path: Path) = verify(path, directory = false)

    private fun secure(path: Path, directory: Boolean) {
        val view = viewProvider(path) ?: throw DiscoverySecurityException("Windows ACL view is unavailable")
        val principal = principalProvider(path)
        val expected = expectedEntry(principal, directory)
        try {
            if (!isExact(view.acl, expected)) {
                view.setAcl(listOf(expected))
            }
        } catch (failure: Throwable) {
            throw DiscoverySecurityException("Windows ACL could not be secured", failure)
        }
        verify(path, directory)
    }

    private fun verify(path: Path, directory: Boolean) {
        val view = viewProvider(path) ?: throw DiscoverySecurityException("Windows ACL view is unavailable")
        val expected = expectedEntry(principalProvider(path), directory)
        try {
            if (!isExact(view.acl, expected)) {
                throw DiscoverySecurityException("Windows ACL verification rejected broad or unexpected access")
            }
        } catch (failure: DiscoverySecurityException) {
            throw failure
        } catch (failure: Throwable) {
            throw DiscoverySecurityException("Windows ACL could not be verified", failure)
        }
    }

    private fun expectedEntry(principal: UserPrincipal, directory: Boolean): AclEntry = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(principal)
        .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
        .apply {
            if (directory) setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
        }
        .build()

    private fun isExact(actual: List<AclEntry>, expected: AclEntry): Boolean =
        actual.size == 1 && actual.single() == expected
}

private fun currentWindowsAccount(path: Path): UserPrincipal {
    val user = System.getProperty("user.name")?.takeIf(String::isNotBlank)
        ?: throw DiscoverySecurityException("Current Windows account is unavailable")
    val domain = System.getenv("USERDOMAIN")?.takeIf(String::isNotBlank)
    val accountName = domain?.let { "$it\\$user" } ?: user
    return try {
        path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(accountName)
    } catch (failure: Throwable) {
        throw DiscoverySecurityException("Current Windows account could not be resolved", failure)
    }
}

internal fun requireDirectory(path: Path) {
    val attributes = readAttributesNoFollow(path)
    if (Files.isSymbolicLink(path) || attributes.isOther || !attributes.isDirectory) {
        throw DiscoverySecurityException("Control discovery directory has an unexpected file type")
    }
}

private fun requireRegularFileOrMissing(path: Path, maxBytes: Long?) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    requireRegularFile(path, maxBytes)
}

internal fun requireRegularFile(path: Path, maxBytes: Long?) {
    val attributes = readAttributesNoFollow(path)
    if (Files.isSymbolicLink(path) || attributes.isOther || !attributes.isRegularFile) {
        throw DiscoverySecurityException("Control discovery file has an unexpected file type")
    }
    if (maxBytes != null && attributes.size() > maxBytes) {
        throw DiscoverySecurityException("Control discovery file exceeds its size limit")
    }
}

private fun readAttributesNoFollow(path: Path): BasicFileAttributes =
    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

private fun moveAtomically(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (failure: AtomicMoveNotSupportedException) {
        throw DiscoverySecurityException("Atomic discovery publication is unavailable", failure)
    }
}
