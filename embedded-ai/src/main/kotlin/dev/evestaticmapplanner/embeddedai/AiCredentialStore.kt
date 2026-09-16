package dev.evestaticmapplanner.embeddedai

import dev.evestaticmapplanner.shared.auth.SecretValue

interface AiCredentialStore {
    fun contains(reference: AiCredentialRef): Boolean
    fun load(reference: AiCredentialRef): SecretValue?
    fun save(reference: AiCredentialRef, secret: SecretValue)
    fun delete(reference: AiCredentialRef)
}

class AiCredentialStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

class InMemoryAiCredentialStore : AiCredentialStore, AutoCloseable {
    private val values = mutableMapOf<AiCredentialRef, SecretValue>()

    @Synchronized
    override fun contains(reference: AiCredentialRef): Boolean = values.containsKey(reference)

    @Synchronized
    override fun load(reference: AiCredentialRef): SecretValue? = values[reference]?.copy()

    @Synchronized
    override fun save(reference: AiCredentialRef, secret: SecretValue) {
        values.remove(reference)?.close()
        values[reference] = secret.copy()
    }

    @Synchronized
    override fun delete(reference: AiCredentialRef) {
        values.remove(reference)?.close()
    }

    @Synchronized
    override fun close() {
        values.values.forEach(SecretValue::close)
        values.clear()
    }
}

object UnavailableAiCredentialStore : AiCredentialStore {
    override fun contains(reference: AiCredentialRef): Boolean = false
    override fun load(reference: AiCredentialRef): SecretValue? = null
    override fun save(reference: AiCredentialRef, secret: SecretValue) {
        throw AiCredentialStoreException("OS-backed secure credential storage is unavailable.")
    }
    override fun delete(reference: AiCredentialRef) = Unit
}

enum class AiCredentialSource(val displayName: String) {
    SECURE_STORAGE("Secure storage"),
    SESSION_ONLY("This session only"),
    ENVIRONMENT("Environment variable"),
}

class ResolvedAiCredential(
    private val secret: SecretValue,
    val source: AiCredentialSource,
) : AutoCloseable {
    fun <T> useSecret(block: (SecretValue) -> T): T = block(secret)
    override fun close() = secret.close()
    override fun toString(): String = "ResolvedAiCredential(source=$source, secret=${SecretValue.REDACTED})"
}

class AiCredentialResolver(
    private val secureStore: AiCredentialStore,
    private val sessionStore: AiCredentialStore,
    private val environment: (String) -> String? = System::getenv,
) {
    fun resolve(config: AiProviderConfig): ResolvedAiCredential? {
        val reference = config.credentialRef
        if (reference != null) {
            sessionStore.load(reference)?.let { return ResolvedAiCredential(it, AiCredentialSource.SESSION_ONLY) }
            secureStore.load(reference)?.let { return ResolvedAiCredential(it, AiCredentialSource.SECURE_STORAGE) }
        }
        val environmentSecret = environment(config.providerType.environmentVariable)?.trim().orEmpty()
        return environmentSecret.takeIf(String::isNotEmpty)?.let {
            ResolvedAiCredential(SecretValue.from(it), AiCredentialSource.ENVIRONMENT)
        }
    }

    fun source(config: AiProviderConfig): AiCredentialSource? {
        return source(config.providerType, config.credentialRef)
    }

    fun source(
        providerType: AiProviderType,
        reference: AiCredentialRef? = AiCredentialRef.forProvider(providerType),
    ): AiCredentialSource? {
        if (reference != null) {
            if (sessionStore.contains(reference)) return AiCredentialSource.SESSION_ONLY
            if (secureStore.contains(reference)) return AiCredentialSource.SECURE_STORAGE
        }
        return AiCredentialSource.ENVIRONMENT.takeIf {
            !environment(providerType.environmentVariable).isNullOrBlank()
        }
    }
}
