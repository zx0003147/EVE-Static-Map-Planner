package dev.evestaticmapplanner.control.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class LocalControlDiscoverySnapshot(
    val protocolVersion: Int,
    val controlApiVersion: Int,
    val instanceId: String,
    val pid: Long,
    val processStart: String?,
    val port: Int,
    val appVersion: String,
    val credentials: LocalControlSessionCredentials,
) {
    override fun toString(): String =
        "LocalControlDiscoverySnapshot(protocolVersion=$protocolVersion, controlApiVersion=$controlApiVersion, " +
            "instanceId=<redacted>, pid=$pid, processStart=<redacted>, port=<redacted>, appVersion=$appVersion, " +
            "credentials=<redacted>)"
}

internal class LocalControlDiscoveryUnavailableException : IllegalStateException("Local control discovery is unavailable")

internal class SecureLocalControlDiscoveryReader(
    root: Path,
    private val aclSecurity: DiscoveryAclSecurity = WindowsAccountOnlyAclSecurity(),
) {
    private val root = root.toAbsolutePath().normalize()
    private val descriptor = this.root.resolve(LocalControlDiscoveryProtocol.DESCRIPTOR_FILE_NAME)
    private val sessionKey = this.root.resolve(LocalControlDiscoveryProtocol.SESSION_KEY_FILE_NAME)
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    fun read(): LocalControlDiscoverySnapshot = try {
        requireDirectory(root)
        aclSecurity.verifyDirectory(root)
        val firstDescriptor = readBounded(descriptor, LocalControlDiscoveryProtocol.MAX_DESCRIPTOR_BYTES)
        val keyBytes = readBounded(sessionKey, LocalControlDiscoveryProtocol.MAX_SESSION_KEY_BYTES)
        val secondDescriptor = readBounded(descriptor, LocalControlDiscoveryProtocol.MAX_DESCRIPTOR_BYTES)
        if (!firstDescriptor.contentEquals(secondDescriptor)) unavailable()
        val parsed = parseDescriptor(firstDescriptor)
        val credentials = LocalControlSessionCredentials.parseEncoded(keyBytes)
        LocalControlDiscoverySnapshot(
            protocolVersion = parsed.protocolVersion,
            controlApiVersion = parsed.controlApiVersion,
            instanceId = parsed.instanceId,
            pid = parsed.pid,
            processStart = parsed.processStart,
            port = parsed.port,
            appVersion = parsed.appVersion,
            credentials = credentials,
        )
    } catch (_: LocalControlDiscoveryUnavailableException) {
        throw LocalControlDiscoveryUnavailableException()
    } catch (_: Throwable) {
        throw LocalControlDiscoveryUnavailableException()
    }

    private fun readBounded(path: Path, limit: Long): ByteArray {
        requireRegularFile(path, limit)
        aclSecurity.verifyFile(path)
        val size = Files.readAttributes(
            path,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).size()
        if (size !in 1..limit || size > Int.MAX_VALUE) unavailable()
        val bytes = ByteArray(size.toInt())
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) unavailable()
            }
            if (channel.read(ByteBuffer.allocate(1)) >= 0) unavailable()
        }
        requireRegularFile(path, limit)
        aclSecurity.verifyFile(path)
        return bytes
    }

    private fun parseDescriptor(bytes: ByteArray): ParsedDescriptor {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        val value = json.parseToJsonElement(text) as? JsonObject ?: unavailable()
        if (value.keys != DESCRIPTOR_FIELDS) unavailable()
        if (value.int("descriptorVersion") != LocalControlDiscoveryProtocol.DESCRIPTOR_VERSION) unavailable()
        val protocolVersion = value.int("protocolVersion").takeIf { it > 0 } ?: unavailable()
        val controlApiVersion = value.int("controlApiVersion").takeIf { it > 0 } ?: unavailable()
        val instanceId = value.string("instanceId").takeIf(::isSafeIdentifier) ?: unavailable()
        val pid = value.long("pid").takeIf { it > 0 } ?: unavailable()
        val processStart = when (val raw = value["processStart"]) {
            JsonNull -> null
            is JsonPrimitive -> raw.takeIf(JsonPrimitive::isString)?.content?.takeIf { it.length <= 80 } ?: unavailable()
            else -> unavailable()
        }
        val port = value.int("port").takeIf { it in 1..65535 } ?: unavailable()
        val appVersion = value.string("appVersion").takeIf { it.isNotBlank() && it.length <= 64 } ?: unavailable()
        return ParsedDescriptor(protocolVersion, controlApiVersion, instanceId, pid, processStart, port, appVersion)
    }

    private data class ParsedDescriptor(
        val protocolVersion: Int,
        val controlApiVersion: Int,
        val instanceId: String,
        val pid: Long,
        val processStart: String?,
        val port: Int,
        val appVersion: String,
    )

    private companion object {
        val DESCRIPTOR_FIELDS = setOf(
            "descriptorVersion",
            "protocolVersion",
            "controlApiVersion",
            "instanceId",
            "pid",
            "processStart",
            "port",
            "appVersion",
        )

        fun JsonObject.int(name: String): Int =
            (this[name] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull ?: unavailable()

        fun JsonObject.long(name: String): Long =
            (this[name] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull ?: unavailable()

        fun JsonObject.string(name: String): String =
            (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: unavailable()

        fun isSafeIdentifier(value: String): Boolean = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$").matches(value)
        fun unavailable(): Nothing = throw LocalControlDiscoveryUnavailableException()
    }
}
