package dev.evestaticmapplanner.core.marker

import java.util.Locale

@JvmInline
value class SavedMarkerChildType private constructor(val key: String) {
    init {
        require(TYPE_KEY_PATTERN.matches(key)) {
            "Saved marker child type must be a lowercase semantic key of 1 to $MAX_TYPE_KEY_LENGTH characters"
        }
    }

    companion object {
        fun of(key: String): SavedMarkerChildType = SavedMarkerChildType(
            key.trim().lowercase(Locale.ROOT),
        )

        val STAGING = of("staging")
        val RALLY = of("rally")
        val DANGER = of("danger")
        val LOGISTICS = of("logistics")
        val HOME = of("home")
        val BACKUP = of("backup")
        val INDUSTRIAL = of("industrial")
        val STRATEGIC = of("strategic")
        val KEEPSTAR = of("keepstar")

        val supportedTypes: List<SavedMarkerChildType> = listOf(
            STAGING,
            RALLY,
            DANGER,
            LOGISTICS,
            HOME,
            BACKUP,
            INDUSTRIAL,
            STRATEGIC,
            KEEPSTAR,
        )

        private val supportedKeys = supportedTypes.mapTo(linkedSetOf()) { it.key }

        fun normalizeSupported(types: Iterable<SavedMarkerChildType>): List<SavedMarkerChildType> {
            val normalized = linkedMapOf<String, SavedMarkerChildType>()
            types.forEach { type ->
                require(type.key in supportedKeys) { "Unsupported saved marker tag: ${type.key}" }
                normalized.putIfAbsent(type.key, type)
            }
            return normalized.values.toList()
        }
    }
}

@ConsistentCopyVisibility
data class SavedMarkerChild private constructor(
    val id: String,
    val parentSystemId: Int,
    val type: SavedMarkerChildType,
    val orderIndex: Int,
) {
    init {
        require(id.isNotBlank() && id == id.trim()) { "Saved marker child ID must not be blank or padded" }
        require(parentSystemId > 0) { "Saved marker child parent system ID must be positive" }
        require(orderIndex >= 0) { "Saved marker child order index cannot be negative" }
    }

    companion object {
        fun create(
            id: String,
            parentSystemId: Int,
            type: SavedMarkerChildType,
            orderIndex: Int,
        ): SavedMarkerChild = SavedMarkerChild(id, parentSystemId, type, orderIndex)
    }
}

private const val MAX_TYPE_KEY_LENGTH = 64
private val TYPE_KEY_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,${MAX_TYPE_KEY_LENGTH - 1}}")
