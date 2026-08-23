package dev.evestaticmapplanner.control.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.UserPrincipal
import java.util.Comparator
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecureLocalControlDiscoveryTest {
    @Test
    fun `publication is descriptor-last consistent owner-only and fully unpublished`() = withDiscoveryRoot { root ->
        val discovery = SecureLocalControlDiscovery(root)
        val lease = assertIs<LocalControlDiscoveryAcquisition.Acquired>(discovery.acquire()).lease
        val server = LocalControlServer(StubMapControlService(), "0.1.2")
        val metadata = server.start()
        try {
            lease.publish(server)
            val descriptorPath = root.resolve(LocalControlDiscoveryProtocol.DESCRIPTOR_FILE_NAME)
            val keyPath = root.resolve(LocalControlDiscoveryProtocol.SESSION_KEY_FILE_NAME)
            val lockPath = root.resolve(LocalControlDiscoveryProtocol.ACTIVE_LOCK_FILE_NAME)
            val descriptor = Json.parseToJsonElement(Files.readString(descriptorPath)).jsonObject

            assertEquals(LocalControlDiscoveryProtocol.DESCRIPTOR_VERSION, descriptor.getValue("descriptorVersion").jsonPrimitive.content.toInt())
            assertEquals(metadata.protocolVersion, descriptor.getValue("protocolVersion").jsonPrimitive.content.toInt())
            assertEquals(metadata.controlApiVersion, descriptor.getValue("controlApiVersion").jsonPrimitive.content.toInt())
            assertEquals(metadata.instanceId, descriptor.getValue("instanceId").jsonPrimitive.content)
            assertEquals(metadata.port, descriptor.getValue("port").jsonPrimitive.content.toInt())
            assertEquals("0.1.2", descriptor.getValue("appVersion").jsonPrimitive.content)
            assertTrue(descriptor.getValue("pid").jsonPrimitive.content.toLong() > 0)
            assertTrue(Files.size(descriptorPath) <= LocalControlDiscoveryProtocol.MAX_DESCRIPTOR_BYTES)
            assertTrue(Files.size(keyPath) in 1..LocalControlDiscoveryProtocol.MAX_SESSION_KEY_BYTES)
            assertFalse(Files.readString(keyPath).startsWith("Bearer "))
            val handshake = LocalControlTestClient(server).handshake("discovery-handshake")
            assertEquals(200, handshake.status)
            assertTrue(handshake.body.contains("\"instanceId\":\"${metadata.instanceId}\""))
            assertTrue(handshake.body.contains("\"protocolVersion\":${metadata.protocolVersion}"))
            assertTrue(handshake.body.contains("\"controlApiVersion\":${metadata.controlApiVersion}"))
            listOf(root, lockPath, descriptorPath, keyPath).forEach(::assertAccountOnlyAcl)

            lease.unpublishDescriptor()
            assertFalse(Files.exists(descriptorPath, LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.exists(keyPath, LinkOption.NOFOLLOW_LINKS))
            server.stop()
            lease.removeSessionKey()
            assertFalse(Files.exists(keyPath, LinkOption.NOFOLLOW_LINKS))
        } finally {
            server.stop()
            lease.release()
        }

        val reacquired = assertIs<LocalControlDiscoveryAcquisition.Acquired>(discovery.acquire()).lease
        reacquired.release()
    }

    @Test
    fun `second instance cannot touch active discovery and can acquire after release`() = withDiscoveryRoot { root ->
        val first = assertIs<LocalControlDiscoveryAcquisition.Acquired>(SecureLocalControlDiscovery(root).acquire()).lease
        val server = LocalControlServer(StubMapControlService(), "0.1.2")
        server.start()
        first.publish(server)
        val descriptorPath = root.resolve(LocalControlDiscoveryProtocol.DESCRIPTOR_FILE_NAME)
        val keyPath = root.resolve(LocalControlDiscoveryProtocol.SESSION_KEY_FILE_NAME)
        val descriptorBefore = Files.readAllBytes(descriptorPath)
        val keyBefore = Files.readAllBytes(keyPath)
        try {
            assertEquals(
                LocalControlDiscoveryAcquisition.AlreadyActive,
                SecureLocalControlDiscovery(root).acquire(),
            )
            assertTrue(descriptorBefore.contentEquals(Files.readAllBytes(descriptorPath)))
            assertTrue(keyBefore.contentEquals(Files.readAllBytes(keyPath)))
        } finally {
            first.unpublishDescriptor()
            server.stop()
            first.removeSessionKey()
            first.release()
        }

        val second = assertIs<LocalControlDiscoveryAcquisition.Acquired>(SecureLocalControlDiscovery(root).acquire()).lease
        second.release()
    }

    @Test
    fun `stale artifacts are removed only after lock and replaced by fresh identity and key`() = withDiscoveryRoot { root ->
        val bootstrap = assertIs<LocalControlDiscoveryAcquisition.Acquired>(SecureLocalControlDiscovery(root).acquire()).lease
        bootstrap.release()
        val oldDescriptor = root.resolve(LocalControlDiscoveryProtocol.DESCRIPTOR_FILE_NAME)
        val oldKey = root.resolve(LocalControlDiscoveryProtocol.SESSION_KEY_FILE_NAME)
        Files.writeString(oldDescriptor, "{\"instanceId\":\"old\"}")
        Files.writeString(oldKey, "old-secret")

        val lease = assertIs<LocalControlDiscoveryAcquisition.Acquired>(SecureLocalControlDiscovery(root).acquire()).lease
        assertFalse(Files.exists(oldDescriptor, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(oldKey, LinkOption.NOFOLLOW_LINKS))
        val server = LocalControlServer(StubMapControlService(), "0.1.2")
        val metadata = server.start()
        try {
            lease.publish(server)
            assertNotEquals("old-secret", Files.readString(oldKey))
            assertTrue(Files.readString(oldDescriptor).contains(metadata.instanceId))
        } finally {
            lease.unpublishDescriptor()
            server.stop()
            lease.removeSessionKey()
            lease.release()
        }
    }

    @Test
    fun `failed descriptor atomic move leaves no partially published session`() = withDiscoveryRoot { root ->
        var moves = 0
        val discovery = SecureLocalControlDiscovery(
            root,
            WindowsAccountOnlyAclSecurity(),
        ) { source, target ->
            moves += 1
            if (moves == 2) error("simulated descriptor move failure")
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        }
        val lease = assertIs<LocalControlDiscoveryAcquisition.Acquired>(discovery.acquire()).lease
        val server = LocalControlServer(StubMapControlService(), "0.1.2")
        server.start()
        try {
            assertFailsWith<DiscoverySecurityException> { lease.publish(server) }
            assertFalse(Files.exists(root.resolve(LocalControlDiscoveryProtocol.DESCRIPTOR_FILE_NAME), LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(root.resolve(LocalControlDiscoveryProtocol.SESSION_KEY_FILE_NAME), LinkOption.NOFOLLOW_LINKS))
            Files.list(root).use { entries ->
                assertFalse(entries.anyMatch { it.fileName.toString().endsWith(".tmp") })
            }
        } finally {
            server.stop()
            lease.release()
        }
    }

    @Test
    fun `oversized unexpected and symbolic-link stale paths fail closed`() = withDiscoveryRoot { root ->
        val bootstrap = assertIs<LocalControlDiscoveryAcquisition.Acquired>(SecureLocalControlDiscovery(root).acquire()).lease
        bootstrap.release()
        val descriptor = root.resolve(LocalControlDiscoveryProtocol.DESCRIPTOR_FILE_NAME)
        Files.write(descriptor, ByteArray(LocalControlDiscoveryProtocol.MAX_DESCRIPTOR_BYTES.toInt() + 1))
        assertFailsWith<DiscoverySecurityException> { SecureLocalControlDiscovery(root).acquire() }
        assertTrue(Files.exists(descriptor, LinkOption.NOFOLLOW_LINKS))
        Files.delete(descriptor)

        val key = root.resolve(LocalControlDiscoveryProtocol.SESSION_KEY_FILE_NAME)
        Files.createDirectory(key)
        assertFailsWith<DiscoverySecurityException> { SecureLocalControlDiscovery(root).acquire() }
        assertTrue(Files.isDirectory(key, LinkOption.NOFOLLOW_LINKS))
        Files.delete(key)

        val target = root.resolve("link-target")
        Files.writeString(target, "do-not-touch")
        Files.createSymbolicLink(key, target.fileName)
        assertFailsWith<DiscoverySecurityException> { SecureLocalControlDiscovery(root).acquire() }
        assertTrue(Files.isSymbolicLink(key))
        assertEquals("do-not-touch", Files.readString(target))
    }

    @Test
    fun `ACL unsupported broad and set failures are rejected`() = withDiscoveryRoot { root ->
        val principal = NamedPrincipal("DOMAIN\\current")
        val everyone = NamedPrincipal("Everyone")
        val broadEntry = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(everyone)
            .setPermissions(AclEntryPermission.READ_DATA)
            .build()

        assertFailsWith<DiscoverySecurityException> {
            WindowsAccountOnlyAclSecurity({ principal }) { null }.secureFile(root)
        }
        assertFailsWith<DiscoverySecurityException> {
            WindowsAccountOnlyAclSecurity({ principal }) { FakeAclView(principal, listOf(broadEntry), failSet = true) }
                .secureFile(root)
        }
        assertFailsWith<DiscoverySecurityException> {
            WindowsAccountOnlyAclSecurity({ principal }) { FakeAclView(principal, listOf(broadEntry)) }
                .verifyFile(root)
        }
    }

    @Test
    fun `credentials string representation never reveals bearer material`() {
        val credentials = LocalControlSessionCredentials.generate(java.security.SecureRandom())
        val authorization = credentials.authorizationHeaderValue()
        val rendered = credentials.toString()

        assertEquals("LocalControlSessionCredentials(<redacted>)", rendered)
        assertFalse(rendered.contains(authorization.removePrefix("Bearer ")))
        assertFalse(rendered.contains("Bearer"))
        credentials.invalidate()
    }
}

private data class NamedPrincipal(private val value: String) : UserPrincipal {
    override fun getName(): String = value
}

private class FakeAclView(
    private var owner: UserPrincipal,
    private var entries: List<AclEntry>,
    private val failSet: Boolean = false,
) : AclFileAttributeView {
    override fun name(): String = "acl"
    override fun getOwner(): UserPrincipal = owner
    override fun setOwner(owner: UserPrincipal) {
        this.owner = owner
    }
    override fun getAcl(): List<AclEntry> = entries
    override fun setAcl(acl: List<AclEntry>) {
        if (failSet) error("simulated ACL set failure")
        entries = acl
    }
}

private fun assertAccountOnlyAcl(path: Path) {
    val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
    val acl = requireNotNull(view).acl
    assertEquals(1, acl.size, path.toString())
    assertEquals(AclEntryType.ALLOW, acl.single().type())
    val principal = acl.single().principal().name.lowercase()
    listOf("everyone", "authenticated users", "builtin\\users").forEach {
        assertFalse(principal.endsWith(it), "$path unexpectedly grants $principal")
    }
    assertTrue(AclEntryPermission.READ_DATA in acl.single().permissions())
}

private inline fun withDiscoveryRoot(block: (Path) -> Unit) {
    val temporary = createTempDirectory("control-discovery-test-")
    val root = temporary.resolve("EVE Static Map Planner").resolve("control")
    try {
        block(root)
    } finally {
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(temporary).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
