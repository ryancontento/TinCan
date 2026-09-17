package io.github.ryancontento.tincan.llm

import kotlinx.coroutines.flow.Flow

/**
 * The seam. Implementations live in their own module and know nothing about
 * the UI or the database.
 *
 * A local Ollama and a remote one are the same implementation with different
 * base URLs — "run it locally" is a setting, not a feature.
 */
interface LlmBackend {
    val id: BackendId

    /** Cheap liveness check, short timeout. */
    suspend fun probe(): BackendHealth

    /** Failure is not fatal; the UI falls back to a free-text model field. */
    suspend fun listModels(): Result<List<ModelInfo>>

    /**
     * Streams a reply. Always completes normally — failures arrive as a terminal
     * [ChatEvent.Failed], so callers have one path and partial output is already
     * persisted. Cancelling leaves a clean partial message.
     */
    fun chat(request: ChatRequest): Flow<ChatEvent>
}

sealed interface ChatEvent {
    /** An incremental chunk, not necessarily a whole token. */
    data class Token(val text: String) : ChatEvent

    /** Reasoning trace from models that emit one. */
    data class Thinking(val text: String) : ChatEvent

    /**
     * No wire signal exists — Ollama reports load_duration only at the end — so
     * this is inferred from time-to-first-token.
     */
    data object ModelLoading : ChatEvent

    data class Completed(val stats: GenerationStats) : ChatEvent

    data class Failed(val error: LlmError) : ChatEvent
}

sealed interface LlmError {
    /** Asleep, off, or unreachable. */
    data object Unreachable : LlmError

    /** Host is up but nothing is listening. */
    data object ConnectionRefused : LlmError

    data class ModelNotFound(val model: String) : LlmError

    /** Socket died mid-generation; partial output is still valid. */
    data object StreamInterrupted : LlmError

    data class Server(val code: Int, val body: String?) : LlmError

    data class Unknown(val cause: Throwable) : LlmError
}

/** Supplies backends per address, so callers depend on this rather than a concrete factory. */
interface LlmBackendProvider {
    fun create(
        baseUrl: String,
        id: BackendId = BackendId("ollama"),
        modelLoadingThresholdMillis: Long = 2_500,
    ): LlmBackend
}
