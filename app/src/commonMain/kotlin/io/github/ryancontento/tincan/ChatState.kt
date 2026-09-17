package io.github.ryancontento.tincan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ryancontento.tincan.llm.BackendId
import io.github.ryancontento.tincan.llm.ChatEvent
import io.github.ryancontento.tincan.llm.ChatMessage
import io.github.ryancontento.tincan.llm.ChatRequest
import io.github.ryancontento.tincan.llm.GenerationStats
import io.github.ryancontento.tincan.llm.LlmError
import io.github.ryancontento.tincan.llm.ModelInfo
import io.github.ryancontento.tincan.llm.Role
import io.github.ryancontento.tincan.llm.ollama.RemoteOllamaBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

data class UiMessage(
    val id: Long,
    val role: Role,
    val content: String,
    val modelId: String? = null,
    val stats: GenerationStats? = null,
    val error: LlmError? = null,
    val incomplete: Boolean = false,
)

/**
 * Deliberately a plain state holder, not an androidx ViewModel yet. It moves
 * behind one at M2 when Koin and lifecycle-viewmodel land; keeping it free of
 * framework types now means that move is a rename.
 */
class ChatState(private val scope: CoroutineScope) {

    var serverUrl by mutableStateOf(DEFAULT_URL)
    var selectedModel by mutableStateOf<String?>(null)
    var availableModels by mutableStateOf<List<ModelInfo>>(emptyList())
    var draft by mutableStateOf("")
    var status by mutableStateOf<String?>(null)

    val messages = mutableStateListOf<UiMessage>()

    /**
     * The in-flight reply is held apart from [messages] on purpose: appending to
     * it recomposes only the streaming bubble, not the whole list. Merged into
     * [messages] once the stream ends.
     */
    var streaming by mutableStateOf<String?>(null)
        private set

    var isGenerating by mutableStateOf(false)
        private set

    private var job: Job? = null
    private var nextId = 0L

    private fun backend() = RemoteOllamaBackend(
        id = BackendId("local"),
        baseUrl = serverUrl,
    )

    fun refreshModels() {
        scope.launch {
            status = "Contacting $serverUrl…"
            backend().listModels()
                .onSuccess { models ->
                    availableModels = models
                    if (selectedModel == null) selectedModel = models.firstOrNull()?.id
                    status = if (models.isEmpty()) "Connected, but no models are pulled." else null
                }
                .onFailure { status = "Could not reach the server: ${it.message}" }
        }
    }

    fun send() {
        val text = draft.trim()
        val model = selectedModel
        if (text.isEmpty() || model == null || isGenerating) return

        messages += UiMessage(nextId++, Role.USER, text)
        draft = ""
        isGenerating = true
        streaming = ""
        status = null

        val history = messages.filter { it.error == null }.map { ChatMessage(it.role, it.content) }

        job = scope.launch {
            // Tokens accumulate here and are published to Compose on a cadence.
            // Publishing every token would recompose faster than the display
            // refreshes, which is where streaming chat UIs usually jank.
            val buffer = StringBuilder()
            var lastPublish = 0L
            var stats: GenerationStats? = null
            var failure: LlmError? = null

            fun publish(force: Boolean = false) {
                val now = nowMillis()
                if (force || now - lastPublish >= PUBLISH_INTERVAL_MILLIS) {
                    streaming = buffer.toString()
                    lastPublish = now
                }
            }

            backend().chat(ChatRequest(model = model, messages = history)).collect { event ->
                when (event) {
                    is ChatEvent.Token -> { buffer.append(event.text); publish() }
                    is ChatEvent.Thinking -> Unit          // rendered at M4
                    ChatEvent.ModelLoading -> status = "Loading $model into memory…"
                    is ChatEvent.Completed -> { stats = event.stats; status = null }
                    is ChatEvent.Failed -> failure = event.error
                }
            }
            publish(force = true)

            val body = buffer.toString()
            if (body.isNotEmpty() || failure == null) {
                messages += UiMessage(
                    id = nextId++,
                    role = Role.ASSISTANT,
                    content = body,
                    modelId = model,
                    stats = stats,
                    incomplete = failure != null,
                )
            }
            failure?.let { status = it.describe() }

            streaming = null
            isGenerating = false
        }
    }

    fun stop() {
        job?.cancel()
        // Cancelling must leave a clean partial message, never a corrupt one.
        streaming?.takeIf { it.isNotEmpty() }?.let { partial ->
            messages += UiMessage(
                id = nextId++,
                role = Role.ASSISTANT,
                content = partial,
                modelId = selectedModel,
                incomplete = true,
            )
        }
        streaming = null
        isGenerating = false
        status = "Stopped."
    }

    private companion object {
        const val DEFAULT_URL = "http://localhost:11434"
        const val PUBLISH_INTERVAL_MILLIS = 30L
    }
}

private val uptime = TimeSource.Monotonic.markNow()
private fun nowMillis(): Long = uptime.elapsedNow().inWholeMilliseconds

private fun LlmError.describe(): String = when (this) {
    LlmError.Unreachable ->
        "Nothing answered. If this is the MacBook, it is probably asleep."
    LlmError.ConnectionRefused ->
        "The machine is reachable but Ollama is not running on that port."
    is LlmError.ModelNotFound ->
        "The server does not have \"$model\" pulled."
    LlmError.StreamInterrupted ->
        "The connection dropped mid-reply. What arrived is kept below."
    is LlmError.Server ->
        "The server returned $code."
    is LlmError.Unknown ->
        "Unexpected failure: ${cause.message ?: cause::class.simpleName}"
}
