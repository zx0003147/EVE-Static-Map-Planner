package dev.evestaticmapplanner.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SharedServerUrlTest {
    @Test
    fun `canonicalizes secure origins and default ports`() {
        assertEquals("https://example.com", SharedServerUrl.parse(" HTTPS://EXAMPLE.COM:443/ ").origin)
        assertEquals("https://example.com:8443", SharedServerUrl.parse("https://example.com:8443").origin)
    }

    @Test
    fun `allows localhost plain HTTP only`() {
        assertEquals("http://localhost:8080", SharedServerUrl.parse("http://LOCALHOST:8080/").origin)
        assertEquals("http://127.0.0.1:8080", SharedServerUrl.parse("http://127.0.0.1:8080").origin)
        assertFailsWith<IllegalArgumentException> { SharedServerUrl.parse("http://example.com") }
        assertFailsWith<IllegalArgumentException> { SharedServerUrl.parse("http://10.0.0.1:8080") }
    }

    @Test
    fun `rejects non-origin and credential-bearing URLs`() {
        listOf(
            "https://example.com/api",
            "https://example.com?token=x",
            "https://user@example.com",
            "ftp://example.com",
        ).forEach { value -> assertFailsWith<IllegalArgumentException>(value) { SharedServerUrl.parse(value) } }
    }
}
