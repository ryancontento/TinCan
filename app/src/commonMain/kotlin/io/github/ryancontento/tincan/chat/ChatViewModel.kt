package io.github.ryancontento.tincan.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ryancontento.tincan.data.ChatRepository
import io.github.ryancontento.tincan.data.SettingsRepository
import io.github.ryancontento.tincan.data.TinCanSettings
import io.github.ryancontento.tincan.data.db.ConversationEntity
import io.github.ryancontento.tincan.data.db.MessageEntity
import io.github.ryancontento.tincan.data.db.MessageStatus
import io.github.ryancontento.tincan.llm.ChatEvent
import io.github.ryancontento.tincan.llm.ChatRequest
import io.github.ryancontento.tincan.llm.GenerationStats
import io.github.ryancontento.tincan.llm.LlmError
import io.github.ryancontento.tincan.llm.ModelInfo
import io.github.ryancontento.tincan.llm.ollama.OllamaBackendFactory
import io.github.ryancontento.tincan.llm.ollama.OllamaException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

data class Notice(val text: String, val severity: Severity) {
    enum class Severity { INFO, ERROR }
}

data class ChatUiState(
    val conversations: List<ConversationEntity> = emptyList(),
    val activeConversationId: Long? = null,
    /** Persisted history for the active conversation, straight from Room. */
    val messages: List<MessageEntity> = emptyList(),
    /**
     * The in-flight reply is tracked separately from [messages] and overlaid on
     * the matching row when rendering. Appending to it therefore recomposes one
     * bubble, and the database is spared a write per token.
     */
    val streamingMessageId: Long? = null,
    val streamingText: String = "",
    val isGenerating: Boolean = false,
    val availableModels: List<ModelInfo> = emptyList(),
    val settings: TinCanSettings = TinCanSettings(),
    val notice: Notice? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository,
    private val backends: OllamaBackendFactory,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val activeId = MutableStateFlow<Long?>(null)
    private var generation: Job? = null

    init {
        // Anything left STREAMING belonged to a process that is gone. Demote it
        // before the UI ever sees it, or a crash leaves a reply that renders as
        // perpetually in progress.
        viewModelScope.launch { chatRepository.recoverInterruptedMessages() }

        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val urlChanged = settings.serverUrl != _state.value.settings.serverUrl
                _state.update { it.copy(settings = settings) }
                if (urlChanged || _state.value.availableModels.isEmpty()) refreshModels()
            }
        }

        viewModelScope.launch {
            chatRepository.observeConversations().collect { conversations ->
                _state.update { it.copy(conversations = conversations) }
                // Open the most recent conversation on launch so the app resumes
                // where it was rather than on an empty screen.
                if (activeId.value == null) conversations.firstOrNull()?.let { select(it.id) }
            }
        }

        viewModelScope.launch {
            activeId
                .flatMapLatest { id ->
                    if (id == null) flowOf(emptyList()) else chatRepository.observeMessages(id)
                }
                .collect { messages -> _state.update { it.copy(messages = messages) } }
        }
    }

    fun select(conversationId: Long) {
        if (_state.value.isGenerating) return   // switching mid-stream would orphan the reply
        activeId.value = conversationId
        _state.update { it.copy(activeConversationId = conversationId, notice = null) }
    }

    fun newConversation() {
        if (_state.value.isGenerating) return
        activeId.value = null
        _state.update { it.copy(activeConversationId = null, messages = emptyList(), notice = null) }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            chatRepository.deleteConversation(id)
            if (activeId.value == id) {
                activeId.value = null
                _state.update { it.copy(activeConversationId = null, messages = emptyList()) }
            }
        }
    }

    fun selectModel(id: String) {
        viewModelScope.launch {
            settingsRepository.setSelectedModel(id)
            _state.value.activeConversationId?.let { chatRepository.setDefaultModel(it, id) }
        }
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
                    val current = settings.selectedModel
                    if (models.isNotEmpty() && (current == null || models.none { it.id == current })) {
                        settingsRepository.setSelectedModel(models.first().id)
                    }
                }
                .onFailure { error ->
                    val mapped = (error as? OllamaException)?.error
                    _state.update {
                        it.copy(
                            availableModels = emptyList(),
                            notice = Notice(
                                mapped?.describe() ?: "Could not reach ${settings.serverUrl}.",
                                Notice.Severity.ERROR,
                            ),
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

        generation = viewModelScope.launch {
            val backendId = settings.serverUrl
            val conversationId = _state.value.activeConversationId
                ?: chatRepository.createConversation(
                    backendId = backendId,
                    defaultModelId = model,
                    systemPrompt = settings.systemPrompt.takeIf { it.isNotBlank() },
                ).also {
                    activeId.value = it
                    _state.update { s -> s.copy(activeConversationId = it) }
                }

            chatRepository.appendUserMessage(conversationId, body, backendId)
            chatRepository.titleFromFirstMessageIfUnset(conversationId, body)

            // History is read back from the database rather than from UI state,
            // so what the model sees is exactly what was persisted.
            val history = chatRepository.historyFor(conversationId)

            val assistantId = chatRepository.beginAssistantMessage(conversationId, model, backendId)
            _state.update {
                it.copy(
                    streamingMessageId = assistantId,
                    streamingText = "",
                    isGenerating = true,
                    notice = null,
                )
            }

            val buffer = StringBuilder()
            val thinking = StringBuilder()
            var lastUiPublish = 0L
            var lastDbWrite = 0L
            var stats: GenerationStats? = null
            var failure: LlmError? = null

            suspend fun publish(force: Boolean = false) {
                val now = uptime.elapsedNow().inWholeMilliseconds
                if (force || now - lastUiPublish >= UI_PUBLISH_INTERVAL_MILLIS) {
                    lastUiPublish = now
                    _state.update { it.copy(streamingText = buffer.toString()) }
                }
                // The database is written far less often than the screen. A disk
                // write per token would be wasteful; this bounds what a crash
                // can lose to roughly half a second of output.
                if (force || now - lastDbWrite >= DB_WRITE_INTERVAL_MILLIS) {
                    lastDbWrite = now
                    chatRepository.updateStreamingBody(
                        assistantId,
                        buffer.toString(),
                        thinking.toString().takeIf { it.isNotEmpty() },
                    )
                }
            }

            val request = ChatRequest(
                model = model,
                messages = history,
                systemPrompt = settings.systemPrompt.takeIf { it.isNotBlank() },
                options = settings.toGenerationOptions(),
            )

            try {
                backend(settings).chat(request).collect { event ->
                    when (event) {
                        is ChatEvent.Token -> { buffer.append(event.text); publish() }
                        is ChatEvent.Thinking -> thinking.append(event.text)
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
                finish(assistantId, conversationId, buffer.toString(), thinking.toString(), stats, failure)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                // Stop was pressed. Whatever arrived is kept and marked
                // incomplete, never left half-written or discarded.
                finish(
                    assistantId, conversationId, buffer.toString(), thinking.toString(),
                    stats = null, failure = null, stopped = true,
                )
                throw e
            }
        }
    }

    fun stop() {
        generation?.cancel()
        generation = null
    }

    private suspend fun finish(
        messageId: Long,
        conversationId: Long,
        body: String,
        thinking: String,
        stats: GenerationStats?,
        failure: LlmError?,
        stopped: Boolean = false,
    ) {
        if (body.isBlank()) {
            // Nothing arrived, so leave no empty bubble behind.
            chatRepository.discardMessage(messageId)
        } else {
            chatRepository.updateStreamingBody(messageId, body, thinking.takeIf { it.isNotEmpty() })
            chatRepository.finishAssistantMessage(
                messageId = messageId,
                conversationId = conversationId,
                content = body,
                status = when {
                    failure != null -> MessageStatus.INCOMPLETE
                    stopped -> MessageStatus.INCOMPLETE
                    else -> MessageStatus.COMPLETE
                },
                errorCode = failure?.let { it::class.simpleName },
                stats = stats,
            )
        }

        _state.update {
            it.copy(
                streamingMessageId = null,
                streamingText = "",
                isGenerating = false,
                notice = when {
                    failure != null -> Notice(failure.describe(), Notice.Severity.ERROR)
                    stopped -> Notice("Stopped.", Notice.Severity.INFO)
                    else -> it.notice
                },
            )
        }
    }

    private fun backend(settings: TinCanSettings) = backends.create(
        baseUrl = settings.serverUrl,
        modelLoadingThresholdMillis = settings.modelLoadingThresholdMillis,
    )

    private companion object {
        const val UI_PUBLISH_INTERVAL_MILLIS = 30L
        const val DB_WRITE_INTERVAL_MILLIS = 500L
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
