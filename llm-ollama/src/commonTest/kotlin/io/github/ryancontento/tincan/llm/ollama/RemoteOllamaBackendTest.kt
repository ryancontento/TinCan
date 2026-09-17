package io.github.ryancontento.tincan.llm.ollama

import io.github.ryancontento.tincan.llm.BackendId
import io.github.ryancontento.tincan.llm.ChatEvent
import io.github.ryancontento.tincan.llm.ChatMessage
import io.github.ryancontento.tincan.llm.ChatRequest
import io.github.ryancontento.tincan.llm.LlmError
import io.github.ryancontento.tincan.llm.Role
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.writer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Fixtures below are real output captured from Ollama 0.34.1 with
 * `curl http://localhost:11434/api/chat`, not hand-written. Hand-written
 * fixtures test your idea of the protocol rather than the protocol.
 */
internal const val COMPLETE_STREAM = """{"model":"llama3.2:1b","created_at":"2026-09-17T15:42:59.5670387Z","message":{"role":"assistant","content":"Hello"},"done":false}
{"model":"llama3.2:1b","created_at":"2026-09-17T15:42:59.7001801Z","message":{"role":"assistant","content":"."},"done":false}
{"model":"llama3.2:1b","created_at":"2026-09-17T15:42:59.7420965Z","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","total_duration":30258609300,"load_duration":19906358300,"prompt_eval_count":29,"prompt_eval_cached_count":0,"prompt_eval_duration":10175903000,"eval_count":3,"eval_duration":173639000}
"""

/** Same stream cut off mid-generation, as happens when the server sleeps. */
private const val TRUNCATED_STREAM = """{"model":"llama3.2:1b","created_at":"2026-09-17T15:42:59.5670387Z","message":{"role":"assistant","content":"Hello"},"done":false}
"""

/** A corrupt line between two good ones must not discard the good output. */
private const val MALFORMED_LINE_STREAM = """{"model":"llama3.2:1b","message":{"role":"assistant","content":"Hel"},"done":false}
{"model":"llama3.2:1b","message":{"role":"ass
{"model":"llama3.2:1b","message":{"role":"assistant","content":"lo"},"done":false}
{"model":"llama3.2:1b","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","eval_count":2,"eval_duration":1000000000}
"""

private fun backendReturning(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    modelLoadingThresholdMillis: Long = 0,   // 0 disables; runTest fast-forwards any real delay
): RemoteOllamaBackend {
    val engine = MockEngine {
        respond(
            content = ByteReadChannel(body),
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/x-ndjson"),
        )
    }
    return RemoteOllamaBackend(
        id = BackendId("test"),
        baseUrl = "http://localhost:11434",
        client = OllamaHttpClient.create(engine = engine),
        modelLoadingThresholdMillis = modelLoadingThresholdMillis,
    )
}

internal fun request() = ChatRequest(
    model = "llama3.2:1b",
    messages = listOf(ChatMessage(Role.USER, "Say exactly: hello")),
)

class RemoteOllamaBackendTest {

    @Test
    fun emits_tokens_then_completes_with_stats() = runTest {
        val events = backendReturning(COMPLETE_STREAM).chat(request()).toList()

        val text = events.filterIsInstance<ChatEvent.Token>().joinToString("") { it.text }
        assertEquals("Hello.", text)

        val completed = assertIs<ChatEvent.Completed>(events.last())
        assertEquals("stop", completed.stats.doneReason)
        assertEquals(29, completed.stats.promptTokens)
        assertEquals(3, completed.stats.completionTokens)
        assertEquals(19_906_358_300, completed.stats.loadDurationNanos)
    }

    @Test
    fun computes_tokens_per_second_from_eval_fields_only() = runTest {
        val events = backendReturning(COMPLETE_STREAM).chat(request()).toList()
        val stats = assertIs<ChatEvent.Completed>(events.last()).stats

        // 3 tokens in 173_639_000ns is ~17.3 tok/s. Crucially this must exclude
        // the 19.9s of load time, or a cold start would report ~0.1 tok/s and
        // look like the model is broken.
        val rate = stats.tokensPerSecond!!
        assertTrue(rate > 15f && rate < 20f, "expected ~17 tok/s, got $rate")
    }

    @Test
    fun truncated_stream_reports_interrupted_but_keeps_partial_output() = runTest {
        val events = backendReturning(TRUNCATED_STREAM).chat(request()).toList()

        assertEquals("Hello", events.filterIsInstance<ChatEvent.Token>().joinToString("") { it.text })
        val failed = assertIs<ChatEvent.Failed>(events.last())
        assertEquals(LlmError.StreamInterrupted, failed.error)
    }

    @Test
    fun malformed_line_is_skipped_without_losing_surrounding_tokens() = runTest {
        val events = backendReturning(MALFORMED_LINE_STREAM).chat(request()).toList()

        assertEquals("Hello", events.filterIsInstance<ChatEvent.Token>().joinToString("") { it.text })
        assertIs<ChatEvent.Completed>(events.last())
    }

    @Test
    fun http_error_maps_to_server_error_and_flow_completes_normally() = runTest {
        val events = backendReturning("upstream exploded", HttpStatusCode.InternalServerError)
            .chat(request()).toList()

        val failed = assertIs<ChatEvent.Failed>(events.single())
        val error = assertIs<LlmError.Server>(failed.error)
        assertEquals(500, error.code)
    }
}
