package dev.evestaticmapplanner.feature.api

import java.util.Collections

/** Pack-owned identity for one source of map overlay information. */
class OverlayProviderDescriptor(
    val id: String,
    val name: String,
    val description: String? = null,
) {
    init {
        OverlayValidation.validateId("Overlay provider ID", id)
        OverlayValidation.validateText("Overlay provider name", name, 100, optional = false)
        OverlayValidation.validateText("Overlay provider description", description, 240, optional = true)
    }
}

/** A provider-defined category of information. Rendering remains host-owned. */
class OverlayLayer(
    val id: String,
    val name: String,
    val description: String? = null,
    val priority: Int = 0,
) {
    init {
        OverlayValidation.validateId("Overlay layer ID", id)
        OverlayValidation.validateText("Overlay layer name", name, 100, optional = false)
        OverlayValidation.validateText("Overlay layer description", description, 240, optional = true)
    }
}

enum class OverlayEntryVisibility {
    VISIBLE,
    HIDDEN,
}

/** Immutable encoded image content supplied by a Pack for generic Host rendering. */
class OverlayImage(
    val mediaType: String,
    content: ByteArray,
) {
    private val bytes = content.copyOf()

    val content: ByteArray
        get() = bytes.copyOf()

    init {
        require(mediaType in SUPPORTED_MEDIA_TYPES) { "Overlay image media type is unsupported" }
        require(bytes.size in 1..MAX_IMAGE_BYTES) { "Overlay image content must be between 1 and $MAX_IMAGE_BYTES bytes" }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 1_048_576
        val SUPPORTED_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/webp")
    }
}

/** Generic, non-interactive image marker anchored to one system node. */
class OverlaySystemMarker(
    images: List<OverlayImage>,
    val overflowCount: Int = 0,
    tooltipLines: List<String> = emptyList(),
) {
    val images: List<OverlayImage> = Collections.unmodifiableList(ArrayList(images))
    val tooltipLines: List<String> = Collections.unmodifiableList(ArrayList(tooltipLines))

    init {
        require(this.images.size in 1..4) { "System marker must contain between one and four images" }
        require(overflowCount >= 0) { "System marker overflow count must not be negative" }
        require(this.tooltipLines.size <= 20) { "System marker tooltip must not exceed 20 lines" }
        this.tooltipLines.forEach {
            OverlayValidation.validateText("System marker tooltip line", it, 160, optional = false)
        }
    }
}

/** Information associated with one solar system and one provider-defined layer. */
class OverlayEntry(
    val layerId: String,
    val systemId: Int,
    val title: String? = null,
    val subtitle: String? = null,
    val value: String? = null,
    val visibility: OverlayEntryVisibility = OverlayEntryVisibility.VISIBLE,
    val systemMarker: OverlaySystemMarker? = null,
) {
    /** Preserves the complete Feature API v1 constructor ABI. */
    constructor(
        layerId: String,
        systemId: Int,
        title: String?,
        subtitle: String?,
        value: String?,
        visibility: OverlayEntryVisibility,
    ) : this(layerId, systemId, title, subtitle, value, visibility, null)

    init {
        OverlayValidation.validateId("Overlay entry layer ID", layerId)
        require(systemId > 0) { "Overlay entry system ID must be positive" }
        OverlayValidation.validateText("Overlay entry title", title, 120, optional = true)
        OverlayValidation.validateText("Overlay entry subtitle", subtitle, 160, optional = true)
        OverlayValidation.validateText("Overlay entry value", value, 240, optional = true)
    }
}

/** An immutable point-in-time view supplied synchronously by a provider. */
class OverlaySnapshot(entries: List<OverlayEntry>) {
    val entries: List<OverlayEntry> = entries.toList()
}

/**
 * Pack-facing source of overlay information.
 *
 * Providers describe data only. They do not draw, render, access coordinates,
 * or receive application implementation objects.
 */
interface OverlayProvider {
    fun descriptor(): OverlayProviderDescriptor

    fun layers(): List<OverlayLayer>

    fun snapshot(): OverlaySnapshot
}

/** Host-owned registration capability scoped to the lifetime of one Pack. */
fun interface OverlayRegistry {
    fun register(provider: OverlayProvider): OverlayRegistration
}

/** Idempotent handle for one provider registration. */
interface OverlayRegistration : AutoCloseable {
    override fun close()
}

/** One validated provider/layer contribution in the host's combined state. */
class OverlayLayerState(
    val provider: OverlayProviderDescriptor,
    val layer: OverlayLayer,
    entries: List<OverlayEntry>,
) {
    val entries: List<OverlayEntry> = entries.toList()
}

/** Combined overlay information consumed by Core-owned presentation code. */
class OverlayState(layers: List<OverlayLayerState>) {
    val layers: List<OverlayLayerState> = layers.toList()

    val isEmpty: Boolean
        get() = layers.isEmpty()
}

private object OverlayValidation {
    private val idSyntax = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")

    fun validateId(label: String, value: String) {
        require(value.length in 1..64) { "$label must contain between 1 and 64 characters" }
        require(idSyntax.matches(value)) {
            "$label must be lowercase ASCII alphanumeric groups separated by '.', '_', or '-'"
        }
    }

    fun validateText(label: String, value: String?, maximumLength: Int, optional: Boolean) {
        if (value == null) {
            require(optional) { "$label must not be null" }
            return
        }
        require(value.isNotBlank()) { "$label must not be blank" }
        require(value == value.trim()) { "$label must not have leading or trailing whitespace" }
        require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters" }
        require(value.none(Char::isISOControl)) { "$label must not contain control characters" }
    }
}
