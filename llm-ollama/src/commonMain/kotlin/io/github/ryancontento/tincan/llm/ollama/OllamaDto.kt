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
    /** Base64 image data; null is omitted, so text-only turns are unchanged on the wire. */
    val images: List<String>? = null,
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
    val capabilities: List<String> = emptyList(),
)

@Serializable
internal data class OllamaTagDetails(
    val family: String? = null,
    @SerialName("quantization_level") val quantizationLevel: String? = null,
    @SerialName("parameter_size") val parameterSize: String? = null,
    @SerialName("context_length") val contextLength: Int? = null,
)

@Serializable
internal data class OllamaPsResponse(
    val models: List<OllamaPsModel> = emptyList(),
)

@Serializable
internal data class OllamaPsModel(
    val name: String,
    val size: Long? = null,
    @SerialName("size_vram") val sizeVram: Long? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("context_length") val contextLength: Int? = null,
)

/**
 * A generate call with no prompt and keep_alive 0 is Ollama's documented way to unload.
 * No default on keepAlive: the encoder drops defaulted fields, and without it the call loads the model.
 */
@Serializable
internal data class OllamaUnloadRequest(
    val model: String,
    @SerialName("keep_alive") val keepAlive: Int,
)

@Serializable
internal data class OllamaModelRequest(
    val model: String,
    val stream: Boolean? = null,
)

/** One NDJSON line of a pull. Failures arrive in-band as `error`, on a 200. */
@Serializable
internal data class OllamaPullChunk(
    val status: String? = null,
    val total: Long? = null,
    val completed: Long? = null,
    val error: String? = null,
)

@Serializable
internal data class OllamaErrorBody(
    val error: String? = null,
)
