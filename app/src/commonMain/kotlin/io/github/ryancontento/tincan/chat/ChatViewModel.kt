package io.github.ryancontento.tincan.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ryancontento.tincan.data.SettingsRepository
import io.github.ryancontento.tincan.data.TinCanSettings
import io.github.ryancontento.tincan.llm.ChatEvent
import io.github.ryancontento.tincan.llm.ChatMessage
import io.github.ryancontento.tincan.llm.ChatRequest
import io.github.ryancontento.tincan.llm.GenerationStats
import io.github.ryancontento.tincan.llm.LlmError
import io.github.ryancontento.tincan.llm.ModelInfo
import io.github.ryancontento.tincan.llm.Role
import io.github.ryancontento.tincan.llm.ollama.OllamaBackendFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

data class UiMessage(
    val id: Long,
    val role: Role,
    val content: String,
    val modelId: String? = null,
    val stats: GenerationStats? = null,
    val incomplete: Boolean = false,
)

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    /**
     * The in-flight reply, kept out of [messages] so appending to it only
     * recomposes the streaming bubble instead of the whole list.
     */
    val streaming: String? = null,
    val isGenerating: Boolean = false,
    val availableModels: List<ModelInfo> = emptyList(),
    val settings: TinCanSettings = TinCanSettings(),
    val notice: Notice? = null,
)

/** A transient message about connectivity or generation, shown above the composer. */
data class Notice(val text: String, val severity: Severity) {
    enum class Severity { INFO, ERROR }
}

class ChatViewModel(
    private val settingsRepository: SettingsRepository,
    private val backends: OllamaBackendFactory,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var generation: Job? = null
    private var nextId = 0L

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val urlChanged = settings.serverUrl != _state.value.settings.serverUrl
                _state.update { it.copy(settings = settings) }
                if (urlChanged || _state.value.availableModels.isEmpty()) refreshModels()
            }
        }
    }

    fun selectModel(id: String) {
        viewModelScope.launch { settingsRepository.setSelectedModel(id) }
    }

    fun refreshModels() {
        viewModelScope.launch {
            val settings = _state.value.settings
            backend(settings).listModels()
                .onSuccess { models ->
                    _state.update { state ->
                        state.copy(
                            availableModels = models,
                            notice = if (models.isEmpty()) {
                                Notice("Connected, but no models are pulled on that server.", Notice.Severity.INFO)
                            } else {
                                null
                            },
                        )
                    }
                    // Only auto-select when nothing is chosen, or when the chosen
                    // model is gone from this server — never override a live choice.
                    val current = settings.selectedModel
                    if (models.isNotEmpty() && (current == null || models.none { it.id == current })) {
                        settingsRepository.setSelectedModel(models.first().id)
                    }
                }
                .onFailure { error ->
                    val llmError = (error as? io.github.ryancontento.tincan.llm.ollama.OllamaException)?.error
                    _state.update {
                        it.copy(
                            availableModels = emptyList(),
                            notice = Notice(llmError?.describe() ?: "Could not reach ${settings.serverUrl}.", Notice.Severity.ERROR),
                        )
                    }
                }
        }
    }

    fun send(text: String) {
        val body = text.trim()
        val settings = _state.value.settings
        val model = settings.selectedModel
        if (body.isEmpty() || model == null || _state.value.isGenerating) return

        _state.update {
            it.copy(
                messages = it.messages + UiMessage(nextId++, Role.USER, body),
                streaming = "",
                isGenerating = true,
                notice = null,
            )
        }

        val history = _state.value.messages.map { ChatMessage(it.role, it.content) }

        generation = viewModelScope.launch {
            val buffer = StringBuilder()
            var lastPublish = 0L
            var stats: GenerationStats? = null
            var failure: LlmError? = null

            fun publish(force: Boolean = false) {
                val now = uptime.elapsedNow().inWholeMilliseconds
                if (force || now - lastPublish >= PUBLISH_INTERVAL_MILLIS) {
                    lastPublish = now
                    _state.update { it.copy(streaming = buffer.toString()) }
                }
            }

            val request = ChatRequest(
                model = model,
                messages = history,
                systemPrompt = settings.systemPrompt.takeIf { it.isNotBlank() },
                options = settings.toGenerationOptions(),
            )

            backend(settings).chat(request).collect { event ->
                when (event) {
                    is ChatEvent.Token -> { buffer.append(event.text); publish() }
                    is ChatEvent.Thinking -> Unit      // rendered at M4
                    ChatEvent.ModelLoading -> _state.update {
                        it.copy(notice = Notice("Loading $model into memory…", Notice.Severity.INFO))
                    }
                    is ChatEvent.Completed -> {
                        stats = event.stats
                        _state.update { it.copy(notice = null) }
                    }
                    is ChatEvent.Failed -> failure = event.error
                }
            }
            publish(force = true)

            finish(buffer.toString(), model, stats, failure)
        }
    }

    /** Cancels generation, keeping whatever already arrived as a clean partial message. */
    fun stop() {
        val partial = _state.value.streaming.orEmpty()
        generation?.cancel()
        generation = null
        finish(
            body = partial,
            model = _state.value.settings.selectedModel,
            stats = null,
            failure = null,
            stopped = true,
        )
    }

    private fun finish(
        body: String,
        model: String?,
        stats: GenerationStats?,
        failure: LlmError?,
        stopped: Boolean = false,
    ) {
        _state.update { state ->
            val appended = if (body.isNotEmpty()) {
                state.messages + UiMessage(
                    id = nextId++,
                    role = Role.ASSISTANT,
                    content = body,
                    modelId = model,
                    stats = stats,
                    incomplete = stopped || failure != null,
                )
            } else {
                state.messages
            }
            state.copy(
                messages = appended,
                streaming = null,
                isGenerating = false,
                notice = when {
                    failure != null -> Notice(failure.describe(), Notice.Severity.ERROR)
                    stopped -> Notice("Stopped.", Notice.Severity.INFO)
                    else -> state.notice
                },
            )
        }
    }

    private fun backend(settings: TinCanSettings) = backends.create(
        baseUrl = settings.serverUrl,
        modelLoadingThresholdMillis = settings.modelLoadingThresholdMillis,
    )

    private companion object {
        const val PUBLISH_INTERVAL_MILLIS = 30L
        val uptime = TimeSource.Monotonic.markNow()
    }
}

internal fun LlmError.describe(): String = when (this) {
    LlmError.Unreachable ->
        "Nothing answered. If that address is the MacBook, it is probably asleep."
    LlmError.ConnectionRefused ->
        "That machine is reachable, but Ollama is not running on that port."
    is LlmError.ModelNotFound ->
        "The server does not have \"$model\" pulled."
    LlmError.StreamInterrupted ->
        "The connection dropped mid-reply. What arrived is kept below."
    is LlmError.Server ->
        "The server returned $code."
    is LlmError.Unknown ->
        "Unexpected failure: ${cause.message ?: cause::class.simpleName}"
}
