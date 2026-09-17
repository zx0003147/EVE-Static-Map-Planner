package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.AiCredentialResolver
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.InMemoryAiCredentialStore
import dev.evestaticmapplanner.embeddedai.OPENAI_VOICE_CREDENTIAL_REF
import dev.evestaticmapplanner.embeddedai.VoiceConfig
import dev.evestaticmapplanner.embeddedai.VoiceInputProvider
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider
import dev.evestaticmapplanner.shared.auth.SecretValue
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSettingsControllerTest {
    @Test
    fun `save persists config and independent secure voice credential`() = runTest {
        val secure = InMemoryAiCredentialStore()
        val session = InMemoryAiCredentialStore()
        val resolver = AiCredentialResolver(secure, session) { null }
        val persisted = mutableListOf<VoiceConfig>()
        val speechRoot = Files.createTempDirectory("voice-settings")
        val controller = VoiceSettingsController(
            secureStore = secure,
            sessionStore = session,
            credentialResolver = resolver,
            speechPackManager = SpeechPackManager(speechRoot = speechRoot),
            localSynthesizer = object : SpeechSynthesizer {
                override suspend fun synthesize(text: String, voice: String?, rate: Int, volume: Int) = ByteArray(0)
                override suspend fun voices() = listOf("Fixture Voice")
            },
            persistConfig = { persisted += it; Result.success(Unit) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val config = VoiceConfig(
            inputProvider = VoiceInputProvider.OPENAI,
            outputProvider = VoiceOutputProvider.LOCAL,
        )
        try {
            controller.save(config, SecretValue.from("voice-key"))
            advanceUntilIdle()

            assertEquals(listOf(config), persisted)
            assertTrue(secure.contains(OPENAI_VOICE_CREDENTIAL_REF))
            assertEquals(AiCredentialSource.SECURE_STORAGE, controller.state.value.credentialSource)
            assertEquals("Voice I/O settings saved.", controller.state.value.message)
        } finally {
            controller.close()
            secure.close()
            session.close()
            speechRoot.toFile().deleteRecursively()
        }
    }
}
