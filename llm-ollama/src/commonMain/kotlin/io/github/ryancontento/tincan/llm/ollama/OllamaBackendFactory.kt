package io.github.ryancontento.tincan.llm.ollama

import io.github.ryancontento.tincan.llm.BackendId
import io.github.ryancontento.tincan.llm.LlmBackend
import io.github.ryancontento.tincan.llm.LlmBackendProvider

/** Backends share one HTTP client: the URL is a setting so backends can't be singletons, but a client each leaks pools. */
class OllamaBackendFactory(
    connectTimeoutMillis: Long = 4_000,
    socketTimeoutMillis: Long = 120_000,
) : LlmBackendProvider, AutoCloseable {

    private val client = OllamaHttpClient.create(
        connectTimeoutMillis = connectTimeoutMillis,
        socketTimeoutMillis = socketTimeoutMillis,
    )

    override fun create(
        baseUrl: String,
        id: BackendId,
        modelLoadingThresholdMillis: Long,
    ): LlmBackend = RemoteOllamaBackend(
        id = id,
        baseUrl = baseUrl,
        client = client,
        modelLoadingThresholdMillis = modelLoadingThresholdMillis,
    )

    override fun close() = client.close()
}
