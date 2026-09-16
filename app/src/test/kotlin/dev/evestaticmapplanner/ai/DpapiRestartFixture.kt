package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.AiCredentialRef
import dev.evestaticmapplanner.shared.auth.SecretValue
import java.nio.file.Path

object DpapiRestartFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        val store = WindowsDpapiAiCredentialStore(Path.of(args[0]))
        when (args[1]) {
            "write" -> {
                SecretValue.from(OPENROUTER_SECRET).use { store.save(OPENROUTER, it) }
                SecretValue.from(ANTHROPIC_SECRET).use { store.save(ANTHROPIC, it) }
            }
            "verify-and-delete-anthropic" -> {
                checkSecret(OPENROUTER_SECRET, store.load(OPENROUTER))
                checkSecret(ANTHROPIC_SECRET, store.load(ANTHROPIC))
                store.delete(ANTHROPIC)
            }
            else -> error("Unknown fixture action")
        }
    }

    private fun checkSecret(expected: String, actual: SecretValue?) {
        val secret = checkNotNull(actual)
        secret.use { value -> value.useString { check(it == expected) } }
    }

    private val OPENROUTER = AiCredentialRef("openrouter")
    private val ANTHROPIC = AiCredentialRef("anthropic")
    private const val OPENROUTER_SECRET = "fixture-openrouter-key"
    private const val ANTHROPIC_SECRET = "fixture-anthropic-key"
}
