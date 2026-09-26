package io.github.ryancontento.tincan.llm.ollama

import io.github.ryancontento.tincan.llm.BackendId
import io.github.ryancontento.tincan.llm.ChatEvent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real clock, hence desktopTest: Ktor runs the body on its own dispatcher, so runTest's virtual time
 * cannot order the mock writer against the first-token watcher (it flaked ~50%). Margins are 6x or more.
 */
class ModelLoadingInferenceTest {

    private fun slowFirstByte(
        scope: CoroutineScope,
        body: String,
        firstByteDelayMillis: Long,
        thresholdMillis: Long,
    ): RemoteOllamaBackend {
        val engine = MockEngine {
            respond(
                content = scope.writer(Dispatchers.Default) {
                    delay(firstByteDelayMillis)
                    channel.writeStringUtf8(body)
                }.channel,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/x-ndjson"),
            )
        }
        return RemoteOllamaBackend(
            id = BackendId("test"),
            baseUrl = "http://localhost:11434",
            client = OllamaHttpClient.create(engine = engine),
            modelLoadingThresholdMillis = thresholdMillis,
        )
    }

    @Test
    fun reports_model_loading_when_the_first_token_is_slow() = runBlocking {
        // 600ms of silence against a 100ms threshold: a 6x margin.
        val backend = slowFirstByte(this, COMPLETE_STREAM, firstByteDelayMillis = 600, thresholdMillis = 100)
        val events = backend.chat(request()).toList()

        assertTrue(
            events.any { it is ChatEvent.ModelLoading },
            "expected ModelLoading, got ${events.map { it::class.simpleName }}",
        )
        // Otherwise the UI would announce loading after the reply had started.
        val loadingAt = events.indexOfFirst { it is ChatEvent.ModelLoading }
        val firstTokenAt = events.indexOfFirst { it is ChatEvent.Token }
        assertTrue(loadingAt < firstTokenAt, "ModelLoading must precede the first token")
    }

    @Test
    fun stays_quiet_when_the_first_token_arrives_promptly() = runBlocking {
        // 50ms to first byte against an 800ms threshold: a 16x margin.
        val backend = slowFirstByte(this, COMPLETE_STREAM, firstByteDelayMillis = 50, thresholdMillis = 800)
        val events = backend.chat(request()).toList()

        assertTrue(
            events.none { it is ChatEvent.ModelLoading },
            "a fast reply must not claim the model is loading",
        )
    }

    @Test
    fun does_not_report_model_loading_when_the_server_returns_an_error() = runBlocking {
        // Guards a past bug; deterministic because the watcher only starts after a successful response.
        val engine = MockEngine {
            respond(
                content = "upstream exploded",
                status = HttpStatusCode.InternalServerError,
            )
        }
        val backend = RemoteOllamaBackend(
            id = BackendId("test"),
            baseUrl = "http://localhost:11434",
            client = OllamaHttpClient.create(engine = engine),
            modelLoadingThresholdMillis = 1,
        )

        val events = backend.chat(request()).toList()
        assertTrue(
            events.none { it is ChatEvent.ModelLoading },
            "a server answering with a 500 is plainly not loading weights",
        )
    }
}
