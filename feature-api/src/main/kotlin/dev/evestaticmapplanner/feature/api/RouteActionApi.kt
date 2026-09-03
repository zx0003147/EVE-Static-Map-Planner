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

/**
 * Immutable explicit navigation intent passed to a navigation-aware Route Action provider.
 *
 * Only user- or Mission-authored targets are included: ordered Waypoints followed by an optional
 * explicit Destination. The Start is retained for validation and identity, but is never a target.
 * Calculated transit systems and calculated edge types deliberately do not appear in this model.
 */
class NavigationSnapshot(
    val identity: RouteIdentity,
    val kind: RouteKind,
    val startSystemId: Int,
    waypointSystemIds: List<Int>,
    val destinationSystemId: Int?,
) {
    val waypointSystemIds: List<Int> = Collections.unmodifiableList(ArrayList(waypointSystemIds))
    val orderedTargetSystemIds: List<Int> = Collections.unmodifiableList(
        ArrayList(this.waypointSystemIds + listOfNotNull(destinationSystemId)),
    )

    init {
        require(kind == RouteKind.NORMAL || kind == RouteKind.MISSION_NORMAL) {
            "Navigation actions support only normal route intent"
        }
        require(startSystemId > 0) { "Navigation Start system ID must be positive" }
        require(this.orderedTargetSystemIds.isNotEmpty()) {
            "Navigation must contain at least one Waypoint or an explicit Destination"
        }
        require(this.orderedTargetSystemIds.all { it > 0 }) { "Navigation target system IDs must be positive" }
        (listOf(startSystemId) + this.orderedTargetSystemIds).zipWithNext().forEach { (left, right) ->
            require(left != right) { "Adjacent navigation stops must use different system IDs" }
        }
    }
}

/** Opaque Pack-owned identity for one selectable Route Action target. */
class RouteActionTargetId(val value: String) {
    init {
        RouteActionValidation.validateText("Route action target ID", value, 256)
    }

    override fun equals(other: Any?): Boolean = other is RouteActionTargetId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** One generic target exposed by a Pack for a group of Route Actions. */
class RouteActionTargetOption(
    val id: RouteActionTargetId,
    val label: String,
    val description: String? = null,
    val available: Boolean = true,
) {
    init {
        RouteActionValidation.validateText("Route action target label", label, 120)
        RouteActionValidation.validateOptionalText("Route action target description", description, 240)
    }
}

/** Immutable target choices for one selector shared by one or more Route Actions. */
class RouteActionTargetSnapshot(
    val selectorId: String,
    val label: String,
    options: List<RouteActionTargetOption>,
) {
    val options: List<RouteActionTargetOption> = Collections.unmodifiableList(ArrayList(options))

    init {
        RouteActionValidation.validateId("Route action target selector ID", selectorId)
        RouteActionValidation.validateText("Route action target selector label", label, 100)
        require(this.options.map { it.id }.distinct().size == this.options.size) {
            "Route action target IDs must be unique within a selector"
        }
    }
}

/** Minimal execution context for one Route Action invocation. */
class RouteActionContext(
    val route: RouteSnapshot,
    val targetId: RouteActionTargetId? = null,
)

/** Minimal execution context for an explicit navigation-intent action invocation. */
class NavigationActionContext(
    val navigation: NavigationSnapshot,
    val targetId: RouteActionTargetId? = null,
)

/** Stable, display-neutral metadata for one Route Action. */
class RouteActionDescriptor(
    val id: String,
    val label: String,
    val description: String?,
    supportedRouteKinds: Set<RouteKind>,
    val targetSelectorId: String? = null,
) {
    val supportedRouteKinds: Set<RouteKind> = Collections.unmodifiableSet(LinkedHashSet(supportedRouteKinds))

    init {
        RouteActionValidation.validateId("Route action ID", id)
        RouteActionValidation.validateText("Route action label", label, 100)
        RouteActionValidation.validateOptionalText("Route action description", description, 240)
        targetSelectorId?.let { RouteActionValidation.validateId("Route action target selector ID", it) }
        require(this.supportedRouteKinds.isNotEmpty()) { "Route action must support at least one route kind" }
    }
}

/** Pack-owned synchronous source of one display-neutral route action. */
interface RouteActionProvider {
    fun descriptor(): RouteActionDescriptor

    /** Returns current generic targets, or null when this action has no selectable target. */
    fun targets(): RouteActionTargetSnapshot? = null

    fun execute(context: RouteActionContext): RouteActionResult
}

/**
 * Additive Route Action extension for providers that operate on explicit navigation intent.
 *
 * Existing Route Action providers remain source- and binary-compatible. Hosts must call this
 * method only when the provider implements this interface; calculated [RouteSnapshot] execution
 * remains available through [RouteActionProvider.execute].
 */
interface NavigationRouteActionProvider : RouteActionProvider {
    fun executeNavigation(context: NavigationActionContext): RouteActionResult
}

/** Optional Host capability for registering Route Action providers. */
interface RouteActionCapability : FeatureCapability {
    fun register(provider: RouteActionProvider): RouteActionRegistration
}

/** Idempotent lifetime handle for one Route Action provider registration. */
interface RouteActionRegistration : AutoCloseable {
    /** Signals that [RouteActionProvider.targets] may have changed. */
    fun requestTargetRefresh()

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
