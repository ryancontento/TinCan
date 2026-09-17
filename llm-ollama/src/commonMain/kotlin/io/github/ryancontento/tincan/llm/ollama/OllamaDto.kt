package io.github.ryancontento.tincan.llm.ollama

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Ollama native API, not the /v1 shim: the shim discards eval_count,
 * load_duration, done_reason and `thinking`. NDJSON also beats SSE to parse.
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

/** One NDJSON line. The final one has done = true plus the timing fields. */
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
    @SerialName("context_length") val contextLength: Int? = null,
)
