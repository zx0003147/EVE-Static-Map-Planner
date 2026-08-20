package dev.evestaticmapplanner

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildInfoTest {
    @Test
    fun `loads generated metadata without machine paths`() {
        val input = """
            appVersion=0.1.0
            gitCommit=abc123
            jdkVersion=25.0.4
            jdkVendor=Eclipse Adoptium
            kotlinVersion=2.3.0
            composeVersion=1.10.0
            gradleVersion=9.2.1
            targetOs=Windows
            targetArch=x64
        """.trimIndent().byteInputStream()

        val info = ApplicationBuildInfo.load(input)

        assertEquals("0.1.0", info.appVersion)
        assertEquals("abc123", info.gitCommit)
        assertEquals("x64", info.targetArch)
    }

    @Test
    fun `missing metadata has explicit fallback`() {
        val info = ApplicationBuildInfo.load(ByteArrayInputStream(byteArrayOf()))

        assertEquals("unknown", info.appVersion)
        assertEquals("unknown", info.gitCommit)
    }
}
