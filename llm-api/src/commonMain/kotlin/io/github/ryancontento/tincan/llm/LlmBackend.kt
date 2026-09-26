package io.github.ryancontento.tincan.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Knows nothing of UI or storage. Local and remote Ollama are one implementation with different base URLs. */
interface LlmBackend {
    val id: BackendId

    /** Cheap liveness check, short timeout. */
    suspend fun probe(): BackendHealth

    /** Failure is not fatal; the UI falls back to a free-text model field. */
    suspend fun listModels(): Result<List<ModelInfo>>

    /** Always completes normally: failure is a terminal [ChatEvent.Failed], so partial output survives. */
    fun chat(request: ChatRequest): Flow<ChatEvent>

    // Model management has defaults so a backend without it still satisfies the interface.

    /** Includes how much of each model sits on the GPU. */
    suspend fun loadedModels(): Result<List<LoadedModel>> = Result.failure(UnsupportedOperationException())

    /** Frees the memory now rather than when keep_alive runs out. */
    suspend fun unloadModel(model: String): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** Like [chat], always completes normally; failure is a terminal [PullEvent.Failed]. */
    fun pullModel(model: String): Flow<PullEvent> =
        flowOf(PullEvent.Failed(LlmError.Rejected("This server cannot pull models.")))

    suspend fun deleteModel(model: String): Result<Unit> = Result.failure(UnsupportedOperationException())
}

sealed interface PullEvent {
    /** Download steps carry byte counts; the rest ("verifying", "writing manifest") do not. */
    data class Progress(val status: String, val completedBytes: Long?, val totalBytes: Long?) : PullEvent {
        val fraction: Float?
            get() {
                val total = totalBytes?.takeIf { it > 0 } ?: return null
                val done = completedBytes ?: return null
                return (done.toFloat() / total).coerceIn(0f, 1f)
            }
    }

    data object Done : PullEvent

    data class Failed(val error: LlmError) : PullEvent
}

sealed interface ChatEvent {
    /** An incremental chunk, not necessarily a whole token. */
    data class Token(val text: String) : ChatEvent

    /** Reasoning trace from models that emit one. */
    data class Thinking(val text: String) : ChatEvent

    /** Inferred from time-to-first-token; Ollama only reports load_duration at the end. */
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

    /** The server refused with its own reason, e.g. no such model in the library. */
    data class Rejected(val message: String) : LlmError

    data class Unknown(val cause: Throwable) : LlmError
}

/** Lets callers create backends per address without depending on a concrete factory. */
interface LlmBackendProvider {
    fun create(
        baseUrl: String,
        id: BackendId = BackendId("ollama"),
        modelLoadingThresholdMillis: Long = 2_500,
    ): LlmBackend
}
