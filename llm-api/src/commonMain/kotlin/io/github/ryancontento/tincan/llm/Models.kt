package io.github.ryancontento.tincan.llm

/** Identifies a configured endpoint, e.g. "local" or "macbook". */
@JvmInline
value class BackendId(val value: String)

enum class Role { USER, ASSISTANT, SYSTEM }

data class ChatMessage(
    val role: Role,
    val content: String,
)

data class ModelInfo(
    val id: String,
    val displayName: String = id,
    val sizeBytes: Long? = null,
    val family: String? = null,
    val quantization: String? = null,
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val systemPrompt: String? = null,
    val options: GenerationOptions = GenerationOptions(),
)

data class GenerationOptions(
    val temperature: Float? = null,
    /**
     * Ollama silently truncates history at num_ctx and tells you nothing about
     * it. Setting this explicitly is the only way to know what the server is
     * actually doing with a long conversation.
     */
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
            val nanos = evalDurationNanos ?: return null
            if (nanos <= 0L) return null
            return tokens * 1_000_000_000f / nanos
        }
}

sealed interface BackendHealth {
    data class Available(val roundTripMillis: Long) : BackendHealth
    data class Unavailable(val error: LlmError) : BackendHealth
}
