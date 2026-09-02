package dev.evestaticmapplanner.shared.integration

import dev.evestaticmapplanner.shared.api.KtorSharedMapClient
import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.shared.auth.SecureCredentialStore
import dev.evestaticmapplanner.shared.auth.SharedCredentialKey
import dev.evestaticmapplanner.shared.model.SharedConnectionState
import dev.evestaticmapplanner.shared.model.SharedMapConfiguration
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole
import dev.evestaticmapplanner.shared.sync.SharedMapConfigurationSink
import dev.evestaticmapplanner.shared.sync.SharedMapSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealSharedMapServerIntegrationTest {
    @Test
    fun `real PostgreSQL server supports read sync recovery role and revocation semantics`() {
        val inviteText = System.getenv(INVITE_ENV) ?: return
        val serverUrl = System.getenv(SERVER_ENV) ?: return
        val docker = System.getenv(DOCKER_ENV) ?: return
        val container = System.getenv(CONTAINER_ENV) ?: return

        runBlocking {
            val raw = RawServerClient(serverUrl)
            val adminStore = MemoryCredentialStore()
            val adminSession = session(adminStore)
            var memberSession: SharedMapSession? = null
            var memberStore: MemoryCredentialStore? = null
            var containerPaused = false
            try {
                SecretValue.from(inviteText).use { adminSession.connect(serverUrl, it, "Phase 4 Integration") }
                assertEquals(SharedConnectionState.ONLINE, adminSession.state.value.connectionState)
                val adminIdentity = assertNotNull(adminSession.state.value.identity)
                val workspaceId = adminIdentity.workspace.workspaceId
                val adminKey = SharedCredentialKey(serverUrl, workspaceId)
                val adminToken = assertNotNull(adminStore.load(adminKey))
                adminToken.use { token ->
                    val created = raw.request(
                        "POST",
                        "/api/v1/workspaces/$workspaceId/markers",
                        token,
                        """{"systemId":30004759,"name":"Phase 4 marker","color":"BLUE","tags":["integration"],"notes":"private integration note"}""",
                    ).requireStatus(201)
                    val createdJson = created.json()
                    val markerId = createdJson.string("markerId")
                    assertEquals(1, createdJson.long("version"))

                    adminSession.refreshNow()
                    assertEquals(setOf(markerId), adminSession.state.value.snapshot?.markers?.keys)

                    val updated = raw.request(
                        "PATCH",
                        "/api/v1/workspaces/$workspaceId/markers/$markerId",
                        token,
                        """{"expectedVersion":1,"name":"Phase 4 marker updated","color":"ORANGE","tags":["integration","updated"],"notes":null}""",
                    ).requireStatus(200)
                    assertEquals(2, updated.json().long("version"))
                    adminSession.refreshNow()
                    assertEquals(2, adminSession.state.value.snapshot?.markers?.get(markerId)?.version)

                    raw.request(
                        "DELETE",
                        "/api/v1/workspaces/$workspaceId/markers/$markerId?expectedVersion=2",
                        token,
                    ).requireStatus(204)
                    adminSession.refreshNow()
                    assertTrue(adminSession.state.value.snapshot?.markers?.isEmpty() == true)

                    docker(docker, "pause", container)
                    containerPaused = true
                    adminSession.refreshNow()
                    assertEquals(SharedConnectionState.DEGRADED, adminSession.state.value.connectionState)
                    assertNotNull(adminSession.state.value.snapshot)
                    assertTrue(adminSession.state.value.stale)

                    docker(docker, "unpause", container)
                    containerPaused = false
                    raw.awaitHealthy()
                    adminSession.refreshNow()
                    assertEquals(SharedConnectionState.ONLINE, adminSession.state.value.connectionState)

                    val member = raw.request(
                        "POST",
                        "/api/v1/workspaces/$workspaceId/members",
                        token,
                        """{"displayName":"Phase 4 Reader","role":"EDITOR"}""",
                    ).requireStatus(201).json()
                    val memberId = member.string("memberId")
                    val memberVersion = member.long("version")
                    val invite = raw.request(
                        "POST",
                        "/api/v1/workspaces/$workspaceId/members/$memberId/invites",
                        token,
                        """{"expiresInHours":1}""",
                    ).requireStatus(201).json().string("inviteToken")

                    val activeMemberStore = MemoryCredentialStore()
                    val activeMemberSession = session(activeMemberStore)
                    memberStore = activeMemberStore
                    memberSession = activeMemberSession
                    SecretValue.from(invite).use { activeMemberSession.connect(serverUrl, it, "Phase 4 Reader") }
                    assertEquals(SharedWorkspaceRole.EDITOR, activeMemberSession.state.value.identity?.workspace?.role)

                    val viewerUpdate = raw.request(
                        "PATCH",
                        "/api/v1/workspaces/$workspaceId/members/$memberId",
                        token,
                        """{"expectedVersion":$memberVersion,"role":"VIEWER"}""",
                    ).requireStatus(200).json()
                    val viewerVersion = viewerUpdate.long("version")
                    activeMemberSession.refreshNow()
                    assertEquals(SharedWorkspaceRole.VIEWER, activeMemberSession.state.value.identity?.workspace?.role)

                    val memberKey = SharedCredentialKey(serverUrl, workspaceId)
                    activeMemberStore.load(memberKey)!!.use { viewerToken ->
                        raw.request(
                            "POST",
                            "/api/v1/workspaces/$workspaceId/markers",
                            viewerToken,
                            """{"systemId":30004759,"name":"Denied","color":"RED","tags":[],"notes":null}""",
                        ).requireStatus(403)
                    }

                    raw.request(
                        "DELETE",
                        "/api/v1/workspaces/$workspaceId/members/$memberId?expectedVersion=$viewerVersion",
                        token,
                    ).requireStatus(204)
                    activeMemberSession.refreshNow()
                    assertEquals(SharedConnectionState.FORBIDDEN, activeMemberSession.state.value.connectionState)
                    assertNull(activeMemberSession.state.value.snapshot)

                    raw.request(
                        "DELETE",
                        "/api/v1/me/devices/${adminIdentity.device.tokenId}",
                        token,
                    ).requireStatus(204)
                    adminSession.refreshNow()
                    assertEquals(SharedConnectionState.AUTH_REQUIRED, adminSession.state.value.connectionState)
                    assertNotNull(adminSession.state.value.snapshot)
                    assertTrue(adminSession.state.value.stale)
                }
            } finally {
                if (containerPaused) runCatching { docker(docker, "unpause", container) }
                memberSession?.close()
                memberStore?.close()
                adminSession.close()
                adminStore.close()
            }
        }
    }

    private fun session(store: MemoryCredentialStore): SharedMapSession = SharedMapSession(
        client = KtorSharedMapClient(),
        credentialStore = store,
        configurationSink = object : SharedMapConfigurationSink {
            override suspend fun save(configuration: SharedMapConfiguration) = Unit
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        pollingIntervalMillis = 3_600_000,
    )

    companion object {
        private const val INVITE_ENV = "SHARED_MAP_INTEGRATION_INVITE"
        private const val SERVER_ENV = "SHARED_MAP_INTEGRATION_SERVER"
        private const val DOCKER_ENV = "SHARED_MAP_INTEGRATION_DOCKER"
        private const val CONTAINER_ENV = "SHARED_MAP_INTEGRATION_CONTAINER"
    }
}

private class RawServerClient(private val origin: String) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun request(method: String, path: String, token: SecretValue, body: String? = null): RawResponse = token.useString { raw ->
        val builder = HttpRequest.newBuilder(URI.create(origin + path))
            .timeout(Duration.ofSeconds(12))
            .header("Authorization", "Bearer $raw")
            .header("X-Request-Id", UUID.randomUUID().toString())
        if (method != "GET") builder.header("Idempotency-Key", UUID.randomUUID().toString())
        val publisher = body?.let {
            builder.header("Content-Type", "application/json")
            HttpRequest.BodyPublishers.ofString(it)
        } ?: HttpRequest.BodyPublishers.noBody()
        RawResponse(client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString()))
    }

    fun awaitHealthy() {
        repeat(30) {
            val healthy = runCatching {
                val request = HttpRequest.newBuilder(URI.create("$origin/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build()
                client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
            }.getOrDefault(false)
            if (healthy) return
            Thread.sleep(500)
        }
        error("Shared Map Server did not recover")
    }
}

private class RawResponse(private val response: HttpResponse<String>) {
    fun requireStatus(expected: Int): RawResponse {
        assertEquals(expected, response.statusCode(), "Unexpected Shared Map HTTP status")
        return this
    }

    fun json(): JsonObject = Json.parseToJsonElement(response.body()).jsonObject
}

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.content.toLong()

private class MemoryCredentialStore : SecureCredentialStore, AutoCloseable {
    private val values = mutableMapOf<SharedCredentialKey, SecretValue>()

    override fun load(key: SharedCredentialKey): SecretValue? = values[key]?.copy()
    override fun save(key: SharedCredentialKey, secret: SecretValue) {
        values.put(key, secret.copy())?.close()
    }
    override fun delete(key: SharedCredentialKey) {
        values.remove(key)?.close()
    }
    override fun close() {
        values.values.forEach(SecretValue::close)
        values.clear()
    }
}

private fun docker(executable: String, vararg arguments: String) {
    val process = ProcessBuilder(listOf(executable, *arguments))
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    check(process.waitFor() == 0) { "Docker lifecycle command failed" }
}
