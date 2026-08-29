package dev.evestaticmapplanner.feature.api

import java.util.Collections

/** Generic emphasis for a Pack-owned status presentation. */
enum class PackControlSeverity {
    NORMAL,
    WARNING,
    ERROR,
}

/** Display-neutral metadata for one action exposed by a Pack control provider. */
class PackControlActionDescriptor(
    val id: String,
    val label: String,
    val description: String?,
    val enabled: Boolean,
) {
    init {
        PackControlValidation.validateId("Pack control action ID", id)
        PackControlValidation.validateText("Pack control action label", label, 100)
        PackControlValidation.validateOptionalText("Pack control action description", description, 240)
    }
}

/** Cheap immutable presentation snapshot. Providers must not perform I/O while producing it. */
class PackControlSnapshot(
    val primaryText: String,
    val secondaryText: String?,
    val severity: PackControlSeverity,
    actions: List<PackControlActionDescriptor>,
) {
    val actions: List<PackControlActionDescriptor> = Collections.unmodifiableList(ArrayList(actions))

    init {
        PackControlValidation.validateText("Pack control primary text", primaryText, 120)
        PackControlValidation.validateOptionalText("Pack control secondary text", secondaryText, 240)
        require(this.actions.map(PackControlActionDescriptor::id).distinct().size == this.actions.size) {
            "Pack control action IDs must be unique within a snapshot"
        }
        require(this.actions.size <= MAX_ACTIONS) { "Pack controls must not expose more than $MAX_ACTIONS actions" }
    }

    private companion object {
        const val MAX_ACTIONS = 12
    }
}

enum class PackControlActionStatus {
    SUCCEEDED,
    REJECTED,
    FAILED,
}

/** Safe user-facing outcome of one Pack control action. */
class PackControlActionResult(
    val status: PackControlActionStatus,
    val message: String?,
) {
    init {
        PackControlValidation.validateOptionalText("Pack control action result message", message, 500)
    }
}

/** Pack-owned synchronous source of status and actions. Host executes actions off the UI thread. */
interface PackControlProvider {
    fun snapshot(): PackControlSnapshot

    fun invoke(actionId: String): PackControlActionResult
}

/** Optional Host capability for registering one generic control provider for a Pack. */
interface PackControlCapability : FeatureCapability {
    fun register(provider: PackControlProvider): PackControlRegistration
}

/** Idempotent lifetime handle that can refresh only its own provider presentation. */
interface PackControlRegistration : AutoCloseable {
    fun requestRefresh()

    override fun close()
}

private object PackControlValidation {
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

    fun validateOptionalText(label: String, value: String?, maximumLength: Int) {
        if (value != null) validateText(label, value, maximumLength)
    }
}
