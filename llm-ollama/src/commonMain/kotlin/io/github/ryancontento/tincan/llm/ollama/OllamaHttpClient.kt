package io.github.ryancontento.tincan.llm.ollama

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Timeout configuration is the single most common way to break streaming with
 * Ktor, so it lives in one place with the reasoning attached.
 */
internal object OllamaHttpClient {

    val json: Json = Json {
        ignoreUnknownKeys = true   // Ollama adds fields between releases
        explicitNulls = false
    }

    /**
     * @param engine injectable so tests can pass MockEngine.
     * @param connectTimeoutMillis short on purpose: a sleeping MacBook should
     *   fail fast and show "asleep", not hang the UI for 30 seconds.
     * @param socketTimeoutMillis the real guard during generation — it measures
     *   the gap *between* bytes, so a slow CPU-bound model is fine as long as
     *   it keeps producing something.
     */
    fun create(
        engine: HttpClientEngine? = null,
        connectTimeoutMillis: Long = 4_000,
        socketTimeoutMillis: Long = 120_000,
    ): HttpClient {
        val configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                // MUST be infinite. requestTimeoutMillis bounds the whole call,
                // including the streaming body, so any finite value kills long
                // generations partway through with a misleading timeout error.
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                this.connectTimeoutMillis = connectTimeoutMillis
                this.socketTimeoutMillis = socketTimeoutMillis
            }
            expectSuccess = false   // map status codes ourselves into LlmError
        }
        return if (engine != null) HttpClient(engine, configure) else HttpClient(OkHttp, configure)
    }
}
