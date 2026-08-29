package dev.evestaticmapplanner.feature.api

/** Marker interface for narrow optional Host capabilities approved by the Feature API. */
interface FeatureCapability

/** Canonical identity for one approved Feature API capability. */
class FeatureCapabilityId(val value: String) {
    init {
        require(value.length in 1..64) { "Feature capability ID must contain between 1 and 64 characters" }
        require(ID_SYNTAX.matches(value)) {
            "Feature capability ID must be lowercase ASCII alphanumeric groups separated by '.', '_', or '-'"
        }
    }

    override fun equals(other: Any?): Boolean = other is FeatureCapabilityId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    private companion object {
        val ID_SYNTAX = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
    }
}

/** A capability identity paired with the Java type expected across the Pack ClassLoader boundary. */
class FeatureCapabilityKey<T : FeatureCapability>(
    val id: FeatureCapabilityId,
    val type: Class<T>,
) {
    init {
        require(FeatureCapability::class.java.isAssignableFrom(type)) {
            "Feature capability type must implement FeatureCapability"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is FeatureCapabilityKey<*> && id == other.id && type == other.type

    override fun hashCode(): Int = 31 * id.hashCode() + type.hashCode()

    override fun toString(): String = "${id.value}:${type.name}"
}

/** Pack-facing lookup for optional, explicitly approved Feature API capabilities. */
interface FeatureCapabilityLookup {
    /** Returns a capability only when both its canonical ID and expected Java type match. */
    fun <T : FeatureCapability> find(key: FeatureCapabilityKey<T>): T?

    companion object {
        /** Returns a stateless, thread-safe lookup that never exposes a capability. */
        fun empty(): FeatureCapabilityLookup = EmptyFeatureCapabilityLookup
    }
}

/** Standard capability keys defined by Feature API runtime contract 2. */
object StandardFeatureCapabilities {
    val DYNAMIC_OVERLAY = FeatureCapabilityKey(
        FeatureCapabilityId("dynamic-overlay"),
        DynamicOverlayCapability::class.java,
    )

    val ROUTE_ACTION = FeatureCapabilityKey(
        FeatureCapabilityId("route-action"),
        RouteActionCapability::class.java,
    )

    val PACK_CONTROLS = FeatureCapabilityKey(
        FeatureCapabilityId("pack-controls"),
        PackControlCapability::class.java,
    )
}

private object EmptyFeatureCapabilityLookup : FeatureCapabilityLookup {
    override fun <T : FeatureCapability> find(key: FeatureCapabilityKey<T>): T? = null
}
