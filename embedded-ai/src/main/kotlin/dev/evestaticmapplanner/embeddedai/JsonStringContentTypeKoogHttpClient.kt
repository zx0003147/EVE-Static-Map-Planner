package dev.evestaticmapplanner.embeddedai

import ai.koog.http.client.KoogHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/**
 * Koog's OpenAI-compatible clients serialize request objects before calling the HTTP backend.
 * JavaKoogHttpClient otherwise infers text/plain from that String even though its contents are JSON.
 */
internal class JsonStringContentTypeKoogHttpClientFactory(
    private val delegate: KoogHttpClient.Factory,
) : KoogHttpClient.Factory {
    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient = JsonStringContentTypeKoogHttpClient(
        delegate.create(
            clientName = clientName,
            baseUrl = baseUrl,
            headers = headers,
            queryParameters = queryParameters,
            requestTimeoutMillis = requestTimeoutMillis,
            connectTimeoutMillis = connectTimeoutMillis,
            socketTimeoutMillis = socketTimeoutMillis,
            json = json,
        ),
    )
}

private class JsonStringContentTypeKoogHttpClient(
    private val delegate: KoogHttpClient,
) : KoogHttpClient {
    override val clientName: String
        get() = delegate.clientName

    override suspend fun <R : Any> get(
        path: String,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = delegate.get(path, responseType, parameters, headers)

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = delegate.post(
        path,
        requestBody,
        requestBodyType,
        responseType,
        parameters,
        headers.withJsonContentType(requestBodyType),
    )

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> = delegate.sse(
        path,
        requestBody,
        requestBodyType,
        dataFilter,
        decodeStreamingResponse,
        processStreamingChunk,
        parameters,
        headers.withJsonContentType(requestBodyType),
    )

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> = delegate.lines(
        path,
        requestBody,
        requestBodyType,
        parameters,
        headers.withJsonContentType(requestBodyType),
    )

    override fun close() = delegate.close()
}

private fun <T : Any> Map<String, String>.withJsonContentType(requestBodyType: KClass<T>): Map<String, String> {
    if (requestBodyType != String::class) return this
    return filterKeys { !it.equals(CONTENT_TYPE, ignoreCase = true) } + (CONTENT_TYPE to APPLICATION_JSON)
}

private const val CONTENT_TYPE = "Content-Type"
private const val APPLICATION_JSON = "application/json"
