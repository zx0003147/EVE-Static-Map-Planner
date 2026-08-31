package dev.evestaticmapplanner.core.wormhole

class WormholeConnection private constructor(
    val firstSystemId: Int,
    val secondSystemId: Int,
) {
    val id: String = "wormhole:$firstSystemId:$secondSystemId"

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WormholeConnection &&
            firstSystemId == other.firstSystemId &&
            secondSystemId == other.secondSystemId

    override fun hashCode(): Int = 31 * firstSystemId + secondSystemId

    override fun toString(): String =
        "WormholeConnection(id=$id, firstSystemId=$firstSystemId, secondSystemId=$secondSystemId)"

    companion object {
        fun between(firstSystemId: Int, secondSystemId: Int): WormholeConnection {
            require(firstSystemId > 0 && secondSystemId > 0) { "Solar system IDs must be positive" }
            require(firstSystemId != secondSystemId) { "Wormhole connection cannot be a self-loop" }
            return WormholeConnection(
                firstSystemId = minOf(firstSystemId, secondSystemId),
                secondSystemId = maxOf(firstSystemId, secondSystemId),
            )
        }
    }
}
