package dev.evestaticmapplanner.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedProtocolCommonTest {
    @Test
    fun `marker CRUD payloads round trip on every platform`() {
        val create = CreateSharedMarkerRequestDto(
            systemId = 30_004_759,
            name = "Alliance Staging",
            color = "BLUE",
            tags = listOf("staging", "ops"),
            notes = "Private note",
        )
        val encoded = SHARED_MAP_PROTOCOL_JSON.encodeToString(create)
        assertEquals(create, SHARED_MAP_PROTOCOL_JSON.decodeFromString<CreateSharedMarkerRequestDto>(encoded))

        val update = UpdateSharedMarkerRequestDto(
            expectedVersion = 7,
            name = "Alliance Staging 2",
            color = "ORANGE",
            tags = listOf("ops"),
            notes = null,
        )
        assertEquals(
            update,
            SHARED_MAP_PROTOCOL_JSON.decodeFromString<UpdateSharedMarkerRequestDto>(
                SHARED_MAP_PROTOCOL_JSON.encodeToString(update),
            ),
        )
    }

    @Test
    fun `invite and token values are redacted from DTO diagnostics`() {
        val request = ExchangeInviteRequestDto("esm_inv_secret", "Browser")
        assertFalse(request.toString().contains("esm_inv_secret"))
        assertTrue(SHARED_MAP_PROTOCOL_JSON.encodeToString(request).contains("esm_inv_secret"))

        val response = ExchangeInviteResponseDto(
            accessToken = "esm_dev_secret",
            tokenId = "01991d6a-74ce-7ef5-8735-4e15444fc980",
            expiresAt = "2026-12-01T00:00:00Z",
            user = UserDto("01991d61-745e-7b08-a716-93c039cde2e2", "Pilot"),
            workspace = WorkspaceDto(
                "01991d60-b8a2-7a20-a311-b5114b27c219",
                "Ops",
                "EDITOR",
                3,
                "01991d62-1fcb-70d0-858b-1d65f6ce3cf6",
            ),
        )
        assertFalse(response.toString().contains("esm_dev_secret"))
    }

    @Test
    fun `protocol meta retains frozen invite and permission vocabulary`() {
        val meta = MetaResponseDto("0.1.0", 1, 1, 1, listOf(SHARED_MARKERS_FEATURE), "sde-1")
        assertEquals(SHARED_MAP_PROTOCOL_VERSION, meta.protocolVersion)
        assertEquals(listOf(SHARED_MARKERS_FEATURE), meta.features)
        assertEquals(setOf("VIEWER", "EDITOR", "ADMIN"), SHARED_WORKSPACE_ROLES)
        assertEquals(setOf("RED", "ORANGE", "YELLOW", "GREEN", "BLUE", "PURPLE", "WHITE"), SHARED_MARKER_COLORS)
    }
}
