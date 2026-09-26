package io.github.ryancontento.tincan.llm.ollama

import io.github.ryancontento.tincan.llm.BackendId
import io.github.ryancontento.tincan.llm.ChatMessage
import io.github.ryancontento.tincan.llm.ChatRequest
import io.github.ryancontento.tincan.llm.Role
import io.github.ryancontento.tincan.llm.LlmError
import io.github.ryancontento.tincan.llm.PullEvent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/* Captured from Ollama 0.34.2 with curl, like the chat fixtures. */

private const val PS_ONE_LOADED = """{"models":[{"name":"llama3.2:1b","model":"llama3.2:1b","size":1514584145,"digest":"baf6a787fdffd633537aa2eb51cfd54cb93ff08e28040095462bb63daf552878","details":{"parent_model":"","format":"gguf","family":"llama","families":["llama"],"parameter_size":"1.2B","quantization_level":"Q8_0"},"expires_at":"2026-09-26T13:36:53.4650789-04:00","size_vram":1514584145,"context_length":4096}]}"""

private const val UNLOADED = """{"model":"llama3.2:1b","created_at":"2026-09-26T17:31:54.0672826Z","response":"","done":true,"done_reason":"unload"}"""

private const val PULL_EXISTING = """{"status":"pulling manifest"}
{"status":"pulling 74701a8c35f6","digest":"sha256:74701a8c35f6c8d9a4b91f3f3497643001d63e0c7a84e085bed452548fa88d45","total":1321082688,"completed":1321082688}
{"status":"pulling 966de95ca8a6","digest":"sha256:966de95ca8a62200913e3f8bfbf84c8494536f1b94b49166851e76644e966396","total":1429,"completed":1429}
{"status":"verifying sha256 digest"}
{"status":"writing manifest"}
{"status":"success"}
"""

/** Note the 200: a pull that fails reports it inside the stream. */
private const val PULL_MISSING = """{"status":"pulling manifest"}
{"error":"pull model manifest: file does not exist"}
"""

private const val DELETE_MISSING = """{"error":"model 'no-such-model-tincan' not found"}"""

private const val TAGS_WITH_CAPABILITIES = """{"models":[{"name":"llama3.2:1b","model":"llama3.2:1b","modified_at":"2026-09-17T11:41:28.459923-04:00","size":1321098329,"digest":"baf6a787fdffd633537aa2eb51cfd54cb93ff08e28040095462bb63daf552878","details":{"parent_model":"","format":"gguf","family":"llama","families":["llama"],"parameter_size":"1.2B","quantization_level":"Q8_0","context_length":131072,"embedding_length":2048},"capabilities":["completion","tools"]}]}"""

private class Recorded(var method: HttpMethod? = null, var path: String? = null, var body: String? = null)

private fun backend(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    recorded: Recorded = Recorded(),
): RemoteOllamaBackend {
    val engine = MockEngine { request ->
        recorded.method = request.method
        recorded.path = request.url.encodedPath
        recorded.body = request.bodyText()
        respondWith(body, status)
    }
    return RemoteOllamaBackend(BackendId("test"), "http://localhost:11434", OllamaHttpClient.create(engine = engine), 0)
}

/** Ollama labels streams as NDJSON and everything else as JSON; the client's decoding depends on it. */
private fun MockRequestHandleScope.respondWith(body: String, status: HttpStatusCode): HttpResponseData {
    val type = if (body.trim().lines().size > 1) "application/x-ndjson" else "application/json; charset=utf-8"
    return respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, type))
}

private fun HttpRequestData.bodyText(): String? =
    (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()

class ModelManagementTest {

    @Test
    fun loaded_models_report_their_memory_and_expiry() = runTest {
        val loaded = backend(PS_ONE_LOADED).loadedModels().getOrThrow().single()

        assertEquals("llama3.2:1b", loaded.id)
        assertEquals(1514584145, loaded.vramBytes)
        assertEquals(4096, loaded.contextLength)
        assertTrue(loaded.expiresAt!!.startsWith("2026-09-26"))
    }

    @Test
    fun unloading_asks_for_a_zero_keep_alive() = runTest {
        val recorded = Recorded()
        backend(UNLOADED, recorded = recorded).unloadModel("llama3.2:1b").getOrThrow()

        assertEquals("/api/generate", recorded.path)
        assertTrue(recorded.body!!.contains("\"keep_alive\":0"), recorded.body)
        assertTrue(!recorded.body!!.contains("prompt"), "a prompt would load the model instead")
    }

    @Test
    fun a_pull_reports_progress_then_finishes() = runTest {
        val events = backend(PULL_EXISTING).pullModel("llama3.2:1b").toList()

        assertEquals(PullEvent.Done, events.last())
        val download = events.filterIsInstance<PullEvent.Progress>().first { it.totalBytes != null }
        assertEquals(1f, download.fraction)
        assertTrue(events.filterIsInstance<PullEvent.Progress>().any { it.status == "verifying sha256 digest" })
    }

    @Test
    fun a_pull_of_an_unknown_model_fails_with_the_servers_reason() = runTest {
        val failed = assertIs<PullEvent.Failed>(backend(PULL_MISSING).pullModel("no-such").toList().last())

        assertEquals(LlmError.Rejected("pull model manifest: file does not exist"), failed.error)
    }

    @Test
    fun a_pull_cut_off_before_success_is_not_reported_as_done() = runTest {
        val cut = PULL_EXISTING.lines().take(2).joinToString("\n")
        val last = backend(cut).pullModel("llama3.2:1b").toList().last()

        assertEquals(PullEvent.Failed(LlmError.StreamInterrupted), last)
    }

    @Test
    fun deleting_uses_the_delete_method_and_surfaces_a_refusal() = runTest {
        val recorded = Recorded()
        val result = backend(DELETE_MISSING, HttpStatusCode.NotFound, recorded).deleteModel("no-such-model-tincan")

        assertEquals(HttpMethod.Delete, recorded.method)
        assertEquals("/api/delete", recorded.path)
        val error = assertIs<OllamaException>(result.exceptionOrNull()).error
        assertEquals(LlmError.Rejected("model 'no-such-model-tincan' not found"), error)
    }

    @Test
    fun images_go_out_as_base64_and_text_only_turns_carry_no_images_field() = runTest {
        val recorded = Recorded()
        val request = ChatRequest(
            model = "llava",
            messages = listOf(
                ChatMessage(Role.USER, "earlier, no image"),
                ChatMessage(Role.USER, "what is this?", images = listOf(byteArrayOf(1, 2, 3))),
            ),
        )
        backend(COMPLETE_STREAM, recorded = recorded).chat(request).toList()

        val body = recorded.body!!
        assertTrue(body.contains("\"images\":[\"AQID\"]"), body)
        assertEquals(1, Regex("\"images\"").findAll(body).count(), body)
    }

    @Test
    fun listed_models_carry_their_capabilities() = runTest {
        val model = backend(TAGS_WITH_CAPABILITIES).listModels().getOrThrow().single()

        assertEquals(setOf("completion", "tools"), model.capabilities)
        assertEquals("1.2B", model.parameterSize)
        assertEquals(false, model.supportsImages)
    }
}
