package io.github.ryancontento.tincan.llm.ollama

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire types for Ollama's native API.
 *
 * Deliberately NOT the OpenAI-compatible /v1 shim: the native endpoint returns
 * eval_count/eval_duration (tokens per second), load_duration, done_reason and
 * a separate `thinking` field for reasoning models. The shim discards all of
 * it, and NDJSON is simpler to parse than SSE besides — every line is one
 * complete JSON object, with no data: prefix, no [DONE] sentinel and no
 * multi-line event framing.
 */

@Serializable
internal data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = true,
    val options: OllamaOptions? = null,
    @SerialName("keep_alive") val keepAlive: String? = null,
)

@Serializable
internal data class OllamaMessage(
    val role: String,
    val content: String,
)

@Serializable
internal data class OllamaOptions(
    val temperature: Float? = null,
    @SerialName("num_ctx") val numCtx: Int? = null,
)

/**
 * One NDJSON line. Streaming lines carry [message] with `done = false`; the
 * final line carries `done = true` plus the timing fields.
 */
@Serializable
internal data class OllamaChatChunk(
    val model: String? = null,
    val message: OllamaChunkMessage? = null,
    val done: Boolean = false,
    @SerialName("done_reason") val doneReason: String? = null,
    @SerialName("total_duration") val totalDuration: Long? = null,
    @SerialName("load_duration") val loadDuration: Long? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    @SerialName("eval_duration") val evalDuration: Long? = null,
)

@Serializable
internal data class OllamaChunkMessage(
    val role: String? = null,
    val content: String? = null,
    val thinking: String? = null,
)

@Serializable
internal data class OllamaTagsResponse(
    val models: List<OllamaTag> = emptyList(),
)

@Serializable
internal data class OllamaTag(
    val name: String,
    val model: String? = null,
    val size: Long? = null,
    val details: OllamaTagDetails? = null,
)

@Serializable
internal data class OllamaTagDetails(
    val family: String? = null,
    @SerialName("quantization_level") val quantizationLevel: String? = null,
    @SerialName("parameter_size") val parameterSize: String? = null,
)
