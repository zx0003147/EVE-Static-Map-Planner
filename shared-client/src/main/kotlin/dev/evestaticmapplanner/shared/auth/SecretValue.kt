package dev.evestaticmapplanner.shared.auth

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class SecretValue private constructor(private var value: CharArray?) : AutoCloseable {
    init {
        require(value != null && value!!.isNotEmpty()) { "Secret value must not be empty" }
    }

    @Synchronized
    fun <T> useString(block: (String) -> T): T {
        val current = checkNotNull(value) { "Secret value has been cleared" }
        return block(String(current))
    }

    @Synchronized
    fun <T> useUtf8Bytes(block: (ByteArray) -> T): T {
        val current = checkNotNull(value) { "Secret value has been cleared" }
        val bytes = String(current).toByteArray(StandardCharsets.UTF_8)
        return try {
            block(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    @Synchronized
    fun copy(): SecretValue = SecretValue(checkNotNull(value) { "Secret value has been cleared" }.copyOf())

    @Synchronized
    override fun close() {
        value?.fill('\u0000')
        value = null
    }

    override fun toString(): String = REDACTED

    companion object {
        const val REDACTED = "<redacted>"

        fun from(value: String): SecretValue = SecretValue(value.toCharArray())

        fun fromUtf8(bytes: ByteArray): SecretValue {
            val decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            val chars = CharArray(decoded.remaining())
            decoded.get(chars)
            return SecretValue(chars)
        }
    }
}

object SecretValueSerializer : KSerializer<SecretValue> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SecretValue", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SecretValue) {
        value.useString(encoder::encodeString)
    }

    override fun deserialize(decoder: Decoder): SecretValue = SecretValue.from(decoder.decodeString())
}
