package dev.evestaticmapplanner.shared.auth

import dev.evestaticmapplanner.shared.api.SharedMapError
import dev.evestaticmapplanner.shared.api.SharedMapException
import dev.evestaticmapplanner.shared.protocol.ExchangeInviteRequestDto
import dev.evestaticmapplanner.shared.protocol.ExchangeInviteResponseDto
import dev.evestaticmapplanner.shared.protocol.UserDto
import dev.evestaticmapplanner.shared.protocol.WorkspaceDto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretRedactionTest {
    @Test
    fun `secret wrappers and auth DTO strings never reveal raw credentials`() {
        val inviteText = "esm_inv_RAW_SECRET_TEST_MARKER"
        val tokenText = "esm_dev_RAW_SECRET_TEST_MARKER"
        SecretValue.from(inviteText).use { invite ->
            val text = ExchangeInviteRequestDto(invite, "Laptop").toString()
            assertTrue(text.contains(SecretValue.REDACTED))
            assertFalse(text.contains(inviteText))
        }
        SecretValue.from(tokenText).use { token ->
            val text = ExchangeInviteResponseDto(
                token,
                "01991d6a-74ce-7ef5-8735-4e15444fc980",
                "2026-12-01T00:00:00Z",
                UserDto("01991d61-745e-7b08-a716-93c039cde2e2", "Pilot"),
                WorkspaceDto(
                    "01991d60-b8a2-7a20-a311-b5114b27c219",
                    "Ops",
                    "VIEWER",
                    1,
                    "01991d62-1fcb-70d0-858b-1d65f6ce3cf6",
                ),
            ).toString()
            assertTrue(text.contains(SecretValue.REDACTED))
            assertFalse(text.contains(tokenText))
        }
    }

    @Test
    fun `domain failures contain only safe mapped text`() {
        val tokenText = "esm_dev_RAW_SECRET_TEST_MARKER"
        val failure = SharedMapException(SharedMapError.Authentication("Authentication is required"))
        assertFalse(failure.toString().contains(tokenText))
        assertFalse(failure.stackTraceToString().contains(tokenText))
    }
}
