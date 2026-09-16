package dev.evestaticmapplanner.embeddedai

import ai.koog.http.client.KoogHttpClientException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.TimeoutCancellationException

enum class AiProviderErrorCode {
    NO_PROVIDER_CONFIGURED,
    NO_CREDENTIAL,
    INVALID_CREDENTIAL,
    INVALID_BASE_URL,
    MODEL_NOT_FOUND,
    MODEL_UNAVAILABLE,
    REGION_RESTRICTED,
    TOOL_CALLING_UNSUPPORTED,
    BASE_URL_UNREACHABLE,
    TIMEOUT,
    NETWORK_ERROR,
    RATE_LIMITED,
    PROVIDER_ERROR,
}

class AiProviderException(
    val code: AiProviderErrorCode,
    val safeMessage: String,
) : Exception(safeMessage) {
    override fun toString(): String = "AiProviderException(code=$code, message=$safeMessage)"
}

internal fun Throwable.toSafeProviderException(
    toolProbe: Boolean = false,
    customBaseUrl: Boolean = false,
): AiProviderException {
    if (this is AiProviderException) return this
    val chain = generateSequence(this) { it.cause }.toList()
    val http = chain.filterIsInstance<KoogHttpClientException>().firstOrNull()
    if (http != null) {
        val status = http.statusCode
        val body = http.errorBody.orEmpty().lowercase()
        return when {
            status == 401 -> providerError(AiProviderErrorCode.INVALID_CREDENTIAL, "Authentication failed.")
            status == 451 || status == 403 && body.contains("not available in your region") -> providerError(
                AiProviderErrorCode.REGION_RESTRICTED,
                "The selected model is not available from your current region.",
            )
            status == 403 -> providerError(AiProviderErrorCode.INVALID_CREDENTIAL, "Authentication failed.")
            status == 404 -> providerError(AiProviderErrorCode.MODEL_NOT_FOUND, "The selected model was not found.")
            status == 408 || status == 504 -> providerError(
                AiProviderErrorCode.TIMEOUT,
                "The provider request timed out.",
            )
            status == 429 -> providerError(
                AiProviderErrorCode.RATE_LIMITED,
                "The provider rate limit was reached. Please wait and try again.",
            )
            toolProbe && status in 400..499 -> providerError(
                AiProviderErrorCode.TOOL_CALLING_UNSUPPORTED,
                "The selected model did not accept the required tool call.",
            )
            status != null && status >= 500 -> providerError(
                AiProviderErrorCode.MODEL_UNAVAILABLE,
                "The selected model is temporarily unavailable.",
            )
            else -> providerError(AiProviderErrorCode.PROVIDER_ERROR, "The provider rejected the request.")
        }
    }
    return when {
        chain.any { it is TimeoutCancellationException || it is TimeoutException || it is SocketTimeoutException } ->
            providerError(AiProviderErrorCode.TIMEOUT, "The provider request timed out.")
        chain.any { it is ConnectException || it is UnknownHostException } ->
            if (customBaseUrl) {
                providerError(AiProviderErrorCode.BASE_URL_UNREACHABLE, "The provider Base URL could not be reached.")
            } else {
                providerError(AiProviderErrorCode.NETWORK_ERROR, "The provider could not be reached.")
            }
        toolProbe -> providerError(
            AiProviderErrorCode.TOOL_CALLING_UNSUPPORTED,
            "The selected model did not complete the required tool call.",
        )
        else -> providerError(AiProviderErrorCode.PROVIDER_ERROR, "The provider request failed.")
    }
}

private fun providerError(code: AiProviderErrorCode, message: String) = AiProviderException(code, message)
