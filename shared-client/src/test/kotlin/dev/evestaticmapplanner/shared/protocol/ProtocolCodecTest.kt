package dev.evestaticmapplanner.shared.protocol

import dev.evestaticmapplanner.shared.api.KtorSharedMapClient
import dev.evestaticmapplanner.shared.model.SharedMarkerColor
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolCodecTest {
    @Test
    fun `meta accepts future additive fields`() {
        val dto = KtorSharedMapClient.PROTOCOL_JSON.decodeFromString<MetaResponseDto>(
            """{"serverVersion":"0.1.0","protocolVersion":1,"minimumClientProtocolVersion":1,"maximumClientProtocolVersion":1,"features":["shared-markers"],"universeBuild":"sde-1","future":{"enabled":true}}""",
        )
        val meta = dto.toDomain()
        assertTrue(meta.supportsClient)
        assertTrue(meta.supportsSharedMarkers)
    }

    @Test
    fun `snapshot maps complete marker payload`() {
        val dto = KtorSharedMapClient.PROTOCOL_JSON.decodeFromString<SharedMarkerSnapshotResponseDto>(SNAPSHOT_JSON)
        val snapshot = dto.toDomain()
        assertEquals(7, snapshot.revision)
        assertEquals(setOf(MARKER_ID), snapshot.markers.keys)
        assertEquals(SharedMarkerColor.BLUE, snapshot.markers.getValue(MARKER_ID).color)
        assertEquals("private note", snapshot.markers.getValue(MARKER_ID).notes)
    }

    @Test
    fun `workspace role uses frozen enum`() {
        val workspace = WorkspaceDto(WORKSPACE_ID, "Ops", "VIEWER", 1, MEMBER_ID).toDomain()
        assertEquals(SharedWorkspaceRole.VIEWER, workspace.role)
    }
}

internal const val WORKSPACE_ID = "01991d60-b8a2-7a20-a311-b5114b27c219"
internal const val MEMBER_ID = "01991d62-1fcb-70d0-858b-1d65f6ce3cf6"
internal const val USER_ID = "01991d61-745e-7b08-a716-93c039cde2e2"
internal const val TOKEN_ID = "01991d6a-74ce-7ef5-8735-4e15444fc980"
internal const val MARKER_ID = "01991d67-5672-7514-9369-482ed563c63d"

internal val META_JSON =
    """{"serverVersion":"0.1.0","protocolVersion":1,"minimumClientProtocolVersion":1,"maximumClientProtocolVersion":1,"features":["shared-markers"],"universeBuild":"sde-1"}"""
internal val WORKSPACE_JSON =
    """{"workspaceId":"$WORKSPACE_ID","name":"Ops","role":"EDITOR","revision":7,"memberId":"$MEMBER_ID"}"""
internal val ME_JSON =
    """{"user":{"userId":"$USER_ID","displayName":"Pilot"},"workspace":$WORKSPACE_JSON,"device":{"tokenId":"$TOKEN_ID","deviceName":"Laptop","createdAt":"2026-09-01T00:00:00Z","lastUsedAt":null,"expiresAt":"2026-12-01T00:00:00Z"}}"""
internal val SNAPSHOT_JSON =
    """{"workspaceId":"$WORKSPACE_ID","revision":7,"generatedAt":"2026-09-01T00:00:00Z","markers":[{"markerId":"$MARKER_ID","workspaceId":"$WORKSPACE_ID","systemId":30004759,"name":"Staging","color":"BLUE","tags":["ops"],"notes":"private note","createdBy":{"userId":"$USER_ID","displayName":"Pilot"},"updatedBy":{"userId":"$USER_ID","displayName":"Pilot"},"createdAt":"2026-09-01T00:00:00Z","updatedAt":"2026-09-01T00:00:00Z","version":1}]}"""
