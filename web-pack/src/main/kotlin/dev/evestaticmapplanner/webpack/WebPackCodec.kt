package dev.evestaticmapplanner.webpack

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object WebPackCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun encodePack(document: WebPackDocument): ByteArray {
        WebPackValidator.validate(document)
        return gzip(json.encodeToString(document).encodeToByteArray())
    }

    fun decodePack(compressed: ByteArray): WebPackDocument {
        val text = gunzip(compressed).decodeToString()
        requireSupportedSchema(text, "Web Pack")
        return json.decodeFromString<WebPackDocument>(text).also(WebPackValidator::validate)
    }

    fun encodeManifest(manifest: WebPackManifest): ByteArray {
        WebPackValidator.validate(manifest)
        return json.encodeToString(manifest).encodeToByteArray()
    }

    fun decodeManifest(bytes: ByteArray): WebPackManifest {
        val text = bytes.decodeToString()
        requireSupportedSchema(text, "Web Pack manifest")
        return json.decodeFromString<WebPackManifest>(text).also(WebPackValidator::validate)
    }

    internal fun encodePayload(payload: WebPackPayload): ByteArray {
        WebPackValidator.validate(payload)
        return json.encodeToString(payload).encodeToByteArray()
    }

    private fun requireSupportedSchema(text: String, subject: String) {
        val schemaVersion = runCatching {
            json.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.content?.toInt()
        }.getOrNull() ?: throw WebPackValidationException("$subject schemaVersion is missing or invalid")
        WebPackValidator.requireSupportedSchema(schemaVersion, subject)
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
        output.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readAllBytes() }
}
