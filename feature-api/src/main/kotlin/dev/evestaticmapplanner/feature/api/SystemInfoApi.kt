package dev.evestaticmapplanner.feature.api

/** Pack-owned identity and ordering for one source of system information. */
class SystemInfoProviderDescriptor(
    val id: String,
    val name: String,
    val priority: Int = 0,
) {
    init {
        SystemInfoValidation.validateId("System Info provider ID", id)
        SystemInfoValidation.validateText("System Info provider name", name, 100)
    }
}

/** One display-neutral value contributed to a System Info section. */
class SystemInfoField(
    val key: String,
    val label: String,
    val value: String,
) {
    init {
        SystemInfoValidation.validateId("System Info field key", key)
        SystemInfoValidation.validateText("System Info field label", label, 100)
        SystemInfoValidation.validateText("System Info field value", value, 500)
    }
}

/** A display-neutral group of fields for one selected solar system. */
class SystemInfoSection(
    val sectionId: String,
    val title: String,
    val priority: Int = 0,
    fields: List<SystemInfoField>,
) {
    val fields: List<SystemInfoField> = fields.toList()

    init {
        SystemInfoValidation.validateId("System Info section ID", sectionId)
        SystemInfoValidation.validateText("System Info section title", title, 120)
        require(this.fields.isNotEmpty()) { "System Info section must contain at least one field" }
        require(this.fields.map(SystemInfoField::key).distinct().size == this.fields.size) {
            "System Info section must not contain duplicate field keys: $sectionId"
        }
    }
}

/** Immutable information returned for one requested solar system. */
class SystemInfoSnapshot(
    val systemId: Int,
    sections: List<SystemInfoSection>,
) {
    val sections: List<SystemInfoSection> = sections.toList()

    init {
        require(systemId > 0) { "System Info snapshot system ID must be positive" }
        require(this.sections.map(SystemInfoSection::sectionId).distinct().size == this.sections.size) {
            "System Info snapshot must not contain duplicate section IDs"
        }
    }
}

/** Pack-facing, synchronous source of display-neutral information for a solar system. */
interface SystemInfoProvider {
    fun descriptor(): SystemInfoProviderDescriptor

    fun provide(systemId: Int): SystemInfoSnapshot
}

/** Host-owned registration capability scoped to the lifetime of one Pack. */
fun interface SystemInfoRegistry {
    fun register(provider: SystemInfoProvider): SystemInfoRegistration
}

/** Idempotent registration handle; [refresh] reports that provider data changed. */
interface SystemInfoRegistration : AutoCloseable {
    fun refresh()

    override fun close()
}

/** Aggregated extension information consumed by the application-owned System Info panel. */
class SystemInfoState(
    val systemId: Int?,
    sections: List<SystemInfoSection>,
) {
    val sections: List<SystemInfoSection> = sections.toList()

    init {
        require(systemId == null || systemId > 0) { "Selected System Info system ID must be positive" }
        require(systemId != null || this.sections.isEmpty()) {
            "System Info state without a selected system cannot contain sections"
        }
    }

    val isEmpty: Boolean
        get() = sections.isEmpty()
}

private object SystemInfoValidation {
    private val idSyntax = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")

    fun validateId(label: String, value: String) {
        require(value.length in 1..64) { "$label must contain between 1 and 64 characters" }
        require(idSyntax.matches(value)) {
            "$label must be lowercase ASCII alphanumeric groups separated by '.', '_', or '-'"
        }
    }

    fun validateText(label: String, value: String, maximumLength: Int) {
        require(value.isNotBlank()) { "$label must not be blank" }
        require(value == value.trim()) { "$label must not have leading or trailing whitespace" }
        require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters" }
        require(value.none(Char::isISOControl)) { "$label must not contain control characters" }
    }
}
