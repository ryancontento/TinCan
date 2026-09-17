package io.github.ryancontento.tincan.llm.ollama

import io.github.ryancontento.tincan.llm.BackendId
import io.github.ryancontento.tincan.llm.LlmBackend

/**
 * Builds backends that share one HTTP client.
 *
 * This exists because the server URL is a setting the user can change at any
 * time, so a backend cannot be a process-wide singleton — but constructing
 * [RemoteOllamaBackend] directly on every send would spin up a fresh OkHttp
 * client, and with it a fresh connection pool and dispatcher thread pool, each
 * time. The factory keeps the expensive part shared and the cheap part
 * per-request.
 *
 * Callers still see no Ktor types.
 */
class OllamaBackendFactory(
    connectTimeoutMillis: Long = 4_000,
    socketTimeoutMillis: Long = 120_000,
) : AutoCloseable {

    private val client = OllamaHttpClient.create(
        connectTimeoutMillis = connectTimeoutMillis,
        socketTimeoutMillis = socketTimeoutMillis,
    )

    fun create(
        baseUrl: String,
        id: BackendId = BackendId("ollama"),
        modelLoadingThresholdMillis: Long = 2_500,
    ): LlmBackend = RemoteOllamaBackend(
        id = id,
        baseUrl = baseUrl,
        client = client,
        modelLoadingThresholdMillis = modelLoadingThresholdMillis,
    )

    override fun close() {
        client.close()
    }
}
