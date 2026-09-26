package io.github.ryancontento.tincan.llm

/** Identifies a configured endpoint, e.g. "local" or "macbook". */
@JvmInline
value class BackendId(val value: String)

enum class Role { USER, ASSISTANT, SYSTEM }

data class ChatMessage(
    val role: Role,
    val content: String,
    /** Raw image bytes for vision models. Resent every turn: the server keeps no state. */
    val images: List<ByteArray> = emptyList(),
)

data class ModelInfo(
    val id: String,
    val displayName: String = id,
    val sizeBytes: Long? = null,
    val family: String? = null,
    val quantization: String? = null,
    /** What the model itself supports, which is not what Ollama necessarily allocates. */
    val contextLength: Int? = null,
    /** e.g. "8B". */
    val parameterSize: String? = null,
    /** Ollama's capability tags, e.g. "completion", "vision", "tools". Empty on older servers. */
    val capabilities: Set<String> = emptySet(),
) {
    val supportsImages: Boolean get() = "vision" in capabilities
}

data class LoadedModel(
    val id: String,
    val sizeBytes: Long?,
    /** Below [sizeBytes] means part of it spilled to system memory and runs slower. */
    val vramBytes: Long?,
    /** ISO-8601 time the server will unload it on its own. */
    val expiresAt: String?,
    /** The context the server actually allocated for it. */
    val contextLength: Int?,
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val systemPrompt: String? = null,
    val options: GenerationOptions = GenerationOptions(),
)

data class GenerationOptions(
    val temperature: Float? = null,
    /** Ollama silently truncates history at num_ctx; setting it is the only way to know the real window. */
    val numCtx: Int? = null,
    /** How long the server keeps the model resident after this request. */
    val keepAlive: String? = null,
)

data class GenerationStats(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalDurationNanos: Long? = null,
    val loadDurationNanos: Long? = null,
    val evalDurationNanos: Long? = null,
    val doneReason: String? = null,
) {
    /** Generation speed, excluding prompt processing and model load time. */
    val tokensPerSecond: Float?
        get() {
            val tokens = completionTokens ?: return null
            val nanos = evalDurationNanos?.takeIf { it > 0L } ?: return null
            return tokens * 1_000_000_000f / nanos
        }
}

sealed interface BackendHealth {
    data class Available(val roundTripMillis: Long) : BackendHealth
    data class Unavailable(val error: LlmError) : BackendHealth
}
