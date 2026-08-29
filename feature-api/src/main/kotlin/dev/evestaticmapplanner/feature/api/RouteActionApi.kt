package dev.evestaticmapplanner.feature.api

import java.util.Collections

/** Route categories a Pack may explicitly support. */
enum class RouteKind {
    NORMAL,
    CAPITAL,
    MISSION_NORMAL,
    MISSION_CAPITAL,
}

/** Display-neutral route segment categories exposed by the Feature API. */
enum class RouteSegmentKind {
    STARGATE,
    ANSIBLEX,
    CAPITAL_JUMP,
}

/** Opaque Host-owned identity for one immutable route snapshot. */
class RouteIdentity(val value: String) {
    init {
        RouteActionValidation.validateText("Route identity", value, 256)
    }

    override fun equals(other: Any?): Boolean = other is RouteIdentity && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** One ordered connection in an immutable route snapshot. */
class RouteSegment(
    val fromSystemId: Int,
    val toSystemId: Int,
    val kind: RouteSegmentKind,
    val distanceLy: Double?,
) {
    init {
        require(fromSystemId > 0) { "Route segment source system ID must be positive" }
        require(toSystemId > 0) { "Route segment destination system ID must be positive" }
        require(fromSystemId != toSystemId) { "Route segment systems must be different" }
        require(distanceLy == null || (distanceLy.isFinite() && distanceLy >= 0.0)) {
            "Route segment distance must be null or a finite non-negative value"
        }
    }
}

/**
 * Immutable, Host-independent route data passed to a Route Action provider.
 *
 * The snapshot never retains a Host route object or the mutable collection instances supplied by
 * its caller. A source-equals-destination route is represented by one system and no segments.
 */
class RouteSnapshot(
    val identity: RouteIdentity,
    val kind: RouteKind,
    val sourceSystemId: Int,
    val destinationSystemId: Int,
    orderedSystemIds: List<Int>,
    orderedSegments: List<RouteSegment>,
) {
    val orderedSystemIds: List<Int> = Collections.unmodifiableList(ArrayList(orderedSystemIds))
    val orderedSegments: List<RouteSegment> = Collections.unmodifiableList(ArrayList(orderedSegments))

    init {
        require(sourceSystemId > 0) { "Route source system ID must be positive" }
        require(destinationSystemId > 0) { "Route destination system ID must be positive" }
        require(this.orderedSystemIds.isNotEmpty()) { "Route must contain at least one system" }
        require(this.orderedSystemIds.all { it > 0 }) { "Route system IDs must be positive" }
        require(this.orderedSystemIds.first() == sourceSystemId) {
            "First route system must equal the source system"
        }
        require(this.orderedSystemIds.last() == destinationSystemId) {
            "Last route system must equal the destination system"
        }
        require(this.orderedSegments.size == this.orderedSystemIds.size - 1) {
            "Route segment count must equal system count minus one"
        }
        this.orderedSegments.forEachIndexed { index, segment ->
            require(segment.fromSystemId == this.orderedSystemIds[index]) {
                "Route segment source does not match system order at index $index"
            }
            require(segment.toSystemId == this.orderedSystemIds[index + 1]) {
                "Route segment destination does not match system order at index $index"
            }
        }
    }
}

/** Minimal execution context for one Route Action invocation. */
class RouteActionContext(val route: RouteSnapshot)

/** Stable, display-neutral metadata for one Route Action. */
class RouteActionDescriptor(
    val id: String,
    val label: String,
    val description: String?,
    supportedRouteKinds: Set<RouteKind>,
) {
    val supportedRouteKinds: Set<RouteKind> = Collections.unmodifiableSet(LinkedHashSet(supportedRouteKinds))

    init {
        RouteActionValidation.validateId("Route action ID", id)
        RouteActionValidation.validateText("Route action label", label, 100)
        RouteActionValidation.validateOptionalText("Route action description", description, 240)
        require(this.supportedRouteKinds.isNotEmpty()) { "Route action must support at least one route kind" }
    }
}

/** Pack-owned synchronous source of one display-neutral route action. */
interface RouteActionProvider {
    fun descriptor(): RouteActionDescriptor

    fun execute(context: RouteActionContext): RouteActionResult
}

/** Optional Host capability for registering Route Action providers. */
interface RouteActionCapability : FeatureCapability {
    fun register(provider: RouteActionProvider): RouteActionRegistration
}

/** Idempotent lifetime handle for one Route Action provider registration. */
interface RouteActionRegistration : AutoCloseable {
    override fun close()
}

enum class RouteActionStatus {
    SUCCEEDED,
    REJECTED,
    FAILED,
}

/** Display-neutral result returned synchronously by a Route Action provider. */
class RouteActionResult(
    val status: RouteActionStatus,
    val message: String?,
) {
    init {
        RouteActionValidation.validateOptionalText("Route action result message", message, 500)
    }
}

private object RouteActionValidation {
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
