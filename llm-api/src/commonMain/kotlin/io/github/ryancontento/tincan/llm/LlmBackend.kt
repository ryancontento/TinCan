package io.github.ryancontento.tincan.llm

import kotlinx.coroutines.flow.Flow

/**
 * The seam. Every implementation lives in its own module and knows nothing
 * about the UI, the database, or any other backend.
 *
 * A local Ollama and the MacBook are the *same* implementation with different
 * base URLs — "run it locally" is a settings value on desktop, not a feature.
 */
interface LlmBackend {
    val id: BackendId

    /** Cheap liveness check. Runs often, on a short timeout. */
    suspend fun probe(): BackendHealth

    /** Runs rarely. Failure here is not fatal — the UI falls back to a free-text model field. */
    suspend fun listModels(): Result<List<ModelInfo>>

    /**
     * Streams a response. The flow always completes normally: failures arrive
     * as a terminal [ChatEvent.Failed] rather than an exception, so callers have
     * one code path and any partial output is already persisted when the error
     * lands.
     *
     * Cancelling the flow stops generation and must leave a clean partial
     * message, not a corrupt one.
     */
    fun chat(request: ChatRequest): Flow<ChatEvent>
}

sealed interface ChatEvent {
    /** An incremental chunk of the reply. Not necessarily a whole token. */
    data class Token(val text: String) : ChatEvent

    /** Reasoning trace from models that emit one. Rendered collapsed. */
    data class Thinking(val text: String) : ChatEvent

    /**
     * The server is loading weights into memory.
     *
     * This has no wire signal — Ollama reports load_duration only in the final
     * message, which is useless for a live state. It is inferred from
     * time-to-first-token, which is why the threshold must be configurable:
     * what is correct for the Metal-accelerated M1 Pro will misfire constantly
     * against CPU inference on localhost.
     */
    data object ModelLoading : ChatEvent

    data class Completed(val stats: GenerationStats) : ChatEvent

    data class Failed(val error: LlmError) : ChatEvent
}

sealed interface LlmError {
    /** Connect timeout — the machine is asleep, off, or unreachable. */
    data object Unreachable : LlmError

    /** Host answered but nothing is listening — Ollama isn't running. */
    data object ConnectionRefused : LlmError

    data class ModelNotFound(val model: String) : LlmError

    /** Socket died mid-generation. Partial output is still valid. */
    data object StreamInterrupted : LlmError

    data class Server(val code: Int, val body: String?) : LlmError

    data class Unknown(val cause: Throwable) : LlmError
}

/**
 * Supplies backends for a given address.
 *
 * Exists so callers depend on an interface rather than a concrete factory.
 * Without it the failure-path state machine — offline, queue, reconnect,
 * deliver — could only be exercised by actually unplugging a network, which is
 * precisely the behaviour most worth testing and least convenient to reproduce.
 */
interface LlmBackendProvider {
    fun create(
        baseUrl: String,
        id: BackendId = BackendId("ollama"),
        modelLoadingThresholdMillis: Long = 2_500,
    ): LlmBackend
}
