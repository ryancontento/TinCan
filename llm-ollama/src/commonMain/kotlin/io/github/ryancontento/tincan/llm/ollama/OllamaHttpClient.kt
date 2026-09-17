package io.github.ryancontento.tincan.llm.ollama

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Timeouts live in one place; they are the usual way Ktor streaming breaks. */
internal object OllamaHttpClient {

    val json: Json = Json {
        ignoreUnknownKeys = true   // Ollama adds fields between releases
        explicitNulls = false
    }

    /**
     * @param connectTimeoutMillis short so a sleeping machine fails fast.
     * @param socketTimeoutMillis gap between bytes, so slow models are fine.
     */
    fun create(
        engine: HttpClientEngine? = null,
        connectTimeoutMillis: Long = 4_000,
        socketTimeoutMillis: Long = 120_000,
    ): HttpClient {
        val configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                // Must be infinite: this bounds the whole call including the
                // streamed body, so any finite value kills long generations.
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                this.connectTimeoutMillis = connectTimeoutMillis
                this.socketTimeoutMillis = socketTimeoutMillis
            }
            expectSuccess = false   // map status codes ourselves into LlmError
        }
        return if (engine != null) HttpClient(engine, configure) else HttpClient(OkHttp, configure)
    }
}
