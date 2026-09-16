package dev.evestaticmapplanner.embeddedai

import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

class AiProviderErrorMappingTest {
    @Test
    fun `DNS failure maps custom provider to Base URL unreachable`() {
        val error = UnknownHostException("private-hostname").toSafeProviderException(customBaseUrl = true)

        assertEquals(AiProviderErrorCode.BASE_URL_UNREACHABLE, error.code)
        assertEquals("The provider Base URL could not be reached.", error.safeMessage)
    }

    @Test
    fun `connection refusal maps custom provider to Base URL unreachable`() {
        val error = ConnectException("private-address").toSafeProviderException(customBaseUrl = true)

        assertEquals(AiProviderErrorCode.BASE_URL_UNREACHABLE, error.code)
        assertEquals("The provider Base URL could not be reached.", error.safeMessage)
    }
}
