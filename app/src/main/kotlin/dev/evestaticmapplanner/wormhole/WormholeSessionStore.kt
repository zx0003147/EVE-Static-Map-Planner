package dev.evestaticmapplanner.wormhole

import dev.evestaticmapplanner.core.wormhole.WormholeConnection
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AddWormholeResult {
    CREATED,
    ALREADY_EXISTS,
}

class WormholeSessionStore {
    private val connectionsById = linkedMapOf<String, WormholeConnection>()
    private val mutableConnections = MutableStateFlow<List<WormholeConnection>>(emptyList())

    val connections: StateFlow<List<WormholeConnection>> = mutableConnections.asStateFlow()

    @Synchronized
    fun add(firstSystemId: Int, secondSystemId: Int): AddWormholeResult {
        val connection = WormholeConnection.between(firstSystemId, secondSystemId)
        if (connection.id in connectionsById) return AddWormholeResult.ALREADY_EXISTS

        connectionsById[connection.id] = connection
        publishSnapshot()
        return AddWormholeResult.CREATED
    }

    @Synchronized
    fun remove(connectionId: String): Boolean {
        if (connectionsById.remove(connectionId) == null) return false
        publishSnapshot()
        return true
    }

    @Synchronized
    fun clear(): Int {
        val clearedCount = connectionsById.size
        if (clearedCount == 0) return 0
        connectionsById.clear()
        publishSnapshot()
        return clearedCount
    }

    @Synchronized
    fun connectionsForSystem(systemId: Int): List<WormholeConnection> {
        require(systemId > 0) { "Solar system ID must be positive" }
        return immutableSnapshot(
            connectionsById.values.filter {
                it.firstSystemId == systemId || it.secondSystemId == systemId
            },
        )
    }

    private fun publishSnapshot() {
        mutableConnections.value = immutableSnapshot(connectionsById.values)
    }

    private fun immutableSnapshot(connections: Collection<WormholeConnection>): List<WormholeConnection> =
        if (connections.isEmpty()) {
            emptyList()
        } else {
            Collections.unmodifiableList(connections.sortedWith(CONNECTION_ORDER))
        }

    private companion object {
        val CONNECTION_ORDER = compareBy<WormholeConnection>(
            WormholeConnection::firstSystemId,
            WormholeConnection::secondSystemId,
        )
    }
}
