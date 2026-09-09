package dev.evestaticmapplanner.web

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.Promise

data class RememberedWebDeviceSession(
    val serverOrigin: String,
    val accessToken: String,
    val deviceName: String,
    val workspaceId: String,
) {
    override fun toString(): String =
        "RememberedWebDeviceSession(serverOrigin=$serverOrigin, accessToken=<redacted>, " +
            "deviceName=$deviceName, workspaceId=$workspaceId)"
}

interface WebDeviceSessionStore {
    suspend fun load(): RememberedWebDeviceSession?
    suspend fun save(session: RememberedWebDeviceSession)
    suspend fun clear()
}

object EphemeralWebDeviceSessionStore : WebDeviceSessionStore {
    override suspend fun load(): RememberedWebDeviceSession? = null
    override suspend fun save(session: RememberedWebDeviceSession) = Unit
    override suspend fun clear() = Unit
}

class IndexedDbWebDeviceSessionStore : WebDeviceSessionStore {
    override suspend fun load(): RememberedWebDeviceSession? {
        val database = openDatabase()
        return try {
            val transaction = database.transaction(STORE_NAME, "readonly")
            val request = transaction.objectStore(STORE_NAME).get(SESSION_KEY)
            val record = requestResult(request) ?: return null
            require((record.schemaVersion as? Number)?.toInt() == SCHEMA_VERSION) {
                "Remembered device session schema is invalid."
            }
            RememberedWebDeviceSession(
                serverOrigin = record.serverOrigin as? String
                    ?: throw IllegalStateException("Remembered device session origin is invalid."),
                accessToken = record.accessToken as? String
                    ?: throw IllegalStateException("Remembered device session token is invalid."),
                deviceName = record.deviceName as? String
                    ?: throw IllegalStateException("Remembered device session device name is invalid."),
                workspaceId = record.workspaceId as? String
                    ?: throw IllegalStateException("Remembered device session Workspace is invalid."),
            )
        } finally {
            database.close()
        }
    }

    override suspend fun save(session: RememberedWebDeviceSession) {
        val record = js("({})")
        record.schemaVersion = SCHEMA_VERSION
        record.serverOrigin = session.serverOrigin
        record.accessToken = session.accessToken
        record.deviceName = session.deviceName
        record.workspaceId = session.workspaceId
        writeTransaction { store -> store.put(record, SESSION_KEY) }
    }

    override suspend fun clear() {
        writeTransaction { store -> store.delete(SESSION_KEY) }
    }

    private suspend fun openDatabase(): dynamic = Promise<dynamic> { resolve, reject ->
        val indexedDb = window.asDynamic().indexedDB
        if (indexedDb == null) {
            reject(IllegalStateException("IndexedDB is unavailable."))
        } else {
            val request = indexedDb.open(DATABASE_NAME, DATABASE_VERSION)
            request.onupgradeneeded = {
                val database = request.result
                if (!(database.objectStoreNames.contains(STORE_NAME) as Boolean)) {
                    database.createObjectStore(STORE_NAME)
                }
            }
            request.onsuccess = { resolve(request.result) }
            request.onerror = { reject(IllegalStateException("IndexedDB could not be opened.")) }
            request.onblocked = { reject(IllegalStateException("IndexedDB upgrade is blocked.")) }
        }
    }.await()

    private suspend fun requestResult(request: dynamic): dynamic = Promise<dynamic> { resolve, reject ->
        request.onsuccess = { resolve(request.result) }
        request.onerror = { reject(IllegalStateException("IndexedDB read failed.")) }
    }.await()

    private suspend fun writeTransaction(operation: (dynamic) -> Unit) {
        val database = openDatabase()
        try {
            Promise<Unit> { resolve, reject ->
                val transaction = database.transaction(STORE_NAME, "readwrite")
                transaction.oncomplete = { resolve(Unit) }
                transaction.onerror = { reject(IllegalStateException("IndexedDB write failed.")) }
                transaction.onabort = { reject(IllegalStateException("IndexedDB write was aborted.")) }
                operation(transaction.objectStore(STORE_NAME))
            }.await()
        } finally {
            database.close()
        }
    }

    private companion object {
        const val DATABASE_NAME = "eve-static-map-planner"
        const val DATABASE_VERSION = 1
        const val STORE_NAME = "device-sessions"
        const val SESSION_KEY = "current"
        const val SCHEMA_VERSION = 1
    }
}
