package io.github.ryancontento.tincan.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ryancontento.tincan.data.ChatRepository
import io.github.ryancontento.tincan.data.SettingsRepository
import io.github.ryancontento.tincan.data.TinCanSettings
import io.github.ryancontento.tincan.data.db.ConversationEntity
import io.github.ryancontento.tincan.data.db.MessageEntity
import io.github.ryancontento.tincan.data.db.MessageStatus
import io.github.ryancontento.tincan.llm.BackendHealth
import io.github.ryancontento.tincan.llm.ChatEvent
import io.github.ryancontento.tincan.llm.ChatRequest
import io.github.ryancontento.tincan.llm.GenerationStats
import io.github.ryancontento.tincan.llm.LlmError
import io.github.ryancontento.tincan.llm.ModelInfo
import io.github.ryancontento.tincan.llm.LlmBackendProvider
import io.github.ryancontento.tincan.llm.ollama.OllamaException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** What the server is currently believed to be doing. */
enum class ConnectionState { UNKNOWN, CHECKING, ONLINE, OFFLINE }

/** The one thing most worth offering alongside a message. */
enum class NoticeAction { RETRY, CONTINUE, OPEN_SETTINGS }

data class Notice(
    val text: String,
    val severity: Severity,
    val action: NoticeAction? = null,
) {
    enum class Severity { INFO, ERROR }
}

data class ChatUiState(
    val conversations: List<ConversationEntity> = emptyList(),
    val activeConversationId: Long? = null,
    val messages: List<MessageEntity> = emptyList(),
    val streamingMessageId: Long? = null,
    val streamingText: String = "",
    val isGenerating: Boolean = false,
    val availableModels: List<ModelInfo> = emptyList(),
    val settings: TinCanSettings = TinCanSettings(),
    val notice: Notice? = null,
    val connection: ConnectionState = ConnectionState.UNKNOWN,
    val connectionDetail: String? = null,
    /** Messages composed while the server was unreachable, awaiting delivery. */
    val queuedCount: Int = 0,
) {
    val canContinue: Boolean
        get() = !isGenerating &&
            messages.lastOrNull()?.let {
                it.role == io.github.ryancontento.tincan.data.db.MessageRole.ASSISTANT &&
                    it.status == MessageStatus.INCOMPLETE
            } == true
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository,
    private val backends: LlmBackendProvider,
    /** How often to re-probe while offline. Lowered by tests so they stay fast. */
    private val reconnectPollMillis: Long = 5_000,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val activeId = MutableStateFlow<Long?>(null)
    private var generation: Job? = null
    private var reconnectWatch: Job? = null

    init {
        viewModelScope.launch { chatRepository.recoverInterruptedMessages() }

        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val urlChanged = settings.serverUrl != _state.value.settings.serverUrl
                _state.update { it.copy(settings = settings) }
                if (urlChanged) {
                    // A different address is a different server; nothing known
                    // about the old one carries over.
                    _state.update { it.copy(connection = ConnectionState.UNKNOWN, availableModels = emptyList()) }
                    checkConnection()
                } else if (_state.value.connection == ConnectionState.UNKNOWN) {
                    checkConnection()
                }
            }
        }

        viewModelScope.launch {
            chatRepository.observeConversations().collect { conversations ->
                _state.update { it.copy(conversations = conversations) }
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

        viewModelScope.launch {
            chatRepository.observeConversationsWithPendingMessages().collect { ids ->
                _state.update { it.copy(queuedCount = ids.size) }
            }
        }
    }

    // ---- connectivity -------------------------------------------------------

    /**
     * Probes the server. Uses probe() rather than listModels() because the
     * question is only "is anything alive", which stays cheap and short.
     */
    fun checkConnection() {
        viewModelScope.launch {
            _state.update { it.copy(connection = ConnectionState.CHECKING) }
            when (val health = backends.create(_state.value.settings.serverUrl).probe()) {
                is BackendHealth.Available -> onServerReachable()
                is BackendHealth.Unavailable -> goOffline(health.error)
            }
        }
    }

    private suspend fun onServerReachable() {
        val wasOffline = _state.value.connection != ConnectionState.ONLINE
        reconnectWatch?.cancel()
        reconnectWatch = null
        _state.update {
            it.copy(
                connection = ConnectionState.ONLINE,
                connectionDetail = null,
                notice = if (it.notice?.severity == Notice.Severity.ERROR) null else it.notice,
            )
        }
        if (wasOffline) {
            if (_state.value.availableModels.isEmpty()) refreshModels()
            deliverQueued()
        }
    }

    private fun goOffline(error: LlmError) {
        _state.update {
            it.copy(
                connection = ConnectionState.OFFLINE,
                connectionDetail = error.describe(),
                notice = Notice(error.describe(), Notice.Severity.ERROR, NoticeAction.RETRY),
            )
        }
        watchForReconnect()
    }

    /**
     * Polls while offline so a queued message goes out on its own once the
     * machine wakes, instead of waiting for the user to notice and retry.
     * Stops as soon as the server answers.
     */
    private fun watchForReconnect() {
        if (reconnectWatch?.isActive == true) return
        reconnectWatch = viewModelScope.launch {
            while (isActive && _state.value.connection == ConnectionState.OFFLINE) {
                delay(reconnectPollMillis)
                val health = backends.create(_state.value.settings.serverUrl).probe()
                if (health is BackendHealth.Available) {
                    onServerReachable()
                    return@launch
                }
            }
        }
    }

    // ---- conversations ------------------------------------------------------

    fun select(conversationId: Long) {
        if (_state.value.isGenerating) return
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
            backends.create(settings.serverUrl).listModels()
                .onSuccess { models ->
                    _state.update { it.copy(availableModels = models) }
                    if (models.isEmpty()) {
                        _state.update {
                            it.copy(
                                notice = Notice(
                                    "Connected, but that server has no models pulled.",
                                    Notice.Severity.INFO,
                                ),
                            )
                        }
                    }
                    val current = settings.selectedModel
                    if (models.isNotEmpty() && (current == null || models.none { m -> m.id == current })) {
                        settingsRepository.setSelectedModel(models.first().id)
                    }
                }
                .onFailure { error ->
                    (error as? OllamaException)?.error?.let { goOffline(it) }
                }
        }
    }

    // ---- sending ------------------------------------------------------------

    fun send(text: String) {
        val body = text.trim()
        val settings = _state.value.settings
        val model = settings.selectedModel
        if (body.isEmpty() || model == null || _state.value.isGenerating) return

        viewModelScope.launch {
            val backendId = settings.serverUrl
            val conversationId = ensureConversation(model, settings, backendId)
            val userMessageId = chatRepository.appendUserMessage(conversationId, body, backendId)
            chatRepository.titleFromFirstMessageIfUnset(conversationId, body)

            if (_state.value.connection == ConnectionState.OFFLINE) {
                // Already known to be down, so do not spend four seconds finding
                // out again. The message is safe in the database and goes out
                // when the reconnect watch succeeds.
                chatRepository.markPending(userMessageId)
                _state.update {
                    it.copy(
                        notice = Notice(
                            "Saved. It will send itself when the server is back.",
                            Notice.Severity.INFO,
                        ),
                    )
                }
                watchForReconnect()
                return@launch
            }

            generation = launch { runGeneration(conversationId, model, settings, userMessageId) }
        }
    }

    /** Sends the queued message in the active conversation, if there is one. */
    fun deliverQueued() {
        val settings = _state.value.settings
        val model = settings.selectedModel ?: return
        val conversationId = _state.value.activeConversationId ?: return
        if (_state.value.isGenerating) return

        viewModelScope.launch {
            val pending = chatRepository.oldestPendingMessage(conversationId) ?: return@launch
            generation = launch { runGeneration(conversationId, model, settings, pending.id) }
        }
    }

    /**
     * Resumes a reply that stopped short.
     *
     * Ollama continues an assistant turn when the last message in the history
     * is the assistant's, so the partial text is sent back and the new output
     * appends to the same row — no duplicate bubble, no lost prefix.
     */
    fun continueReply() {
        val settings = _state.value.settings
        val model = settings.selectedModel ?: return
        val conversationId = _state.value.activeConversationId ?: return
        if (_state.value.isGenerating) return

        viewModelScope.launch {
            val partial = chatRepository.resumableReply(conversationId) ?: return@launch
            chatRepository.resumeAssistantMessage(partial.id)
            generation = launch {
                runGeneration(
                    conversationId = conversationId,
                    model = model,
                    settings = settings,
                    userMessageId = null,
                    resumeMessageId = partial.id,
                    resumePrefix = partial.content,
                )
            }
        }
    }

    fun stop() {
        generation?.cancel()
        generation = null
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // ---- generation ---------------------------------------------------------

    private suspend fun ensureConversation(
        model: String,
        settings: TinCanSettings,
        backendId: String,
    ): Long = _state.value.activeConversationId ?: chatRepository.createConversation(
        backendId = backendId,
        defaultModelId = model,
        systemPrompt = settings.systemPrompt.takeIf { it.isNotBlank() },
    ).also {
        activeId.value = it
        _state.update { s -> s.copy(activeConversationId = it) }
    }

    private suspend fun runGeneration(
        conversationId: Long,
        model: String,
        settings: TinCanSettings,
        userMessageId: Long?,
        resumeMessageId: Long? = null,
        resumePrefix: String = "",
    ) {
        val history = chatRepository.historyFor(conversationId)
        val assistantId = resumeMessageId
            ?: chatRepository.beginAssistantMessage(conversationId, model, settings.serverUrl)

        _state.update {
            it.copy(
                streamingMessageId = assistantId,
                streamingText = resumePrefix,
                isGenerating = true,
                notice = null,
            )
        }

        // Seeded with what is already there so a resumed reply keeps its prefix
        // and the database always holds the whole message, never just the tail.
        val buffer = StringBuilder(resumePrefix)
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
            backends.create(settings.serverUrl, modelLoadingThresholdMillis = settings.modelLoadingThresholdMillis)
                .chat(request)
                .collect { event ->
                    when (event) {
                        is ChatEvent.Token -> { buffer.append(event.text); publish() }
                        is ChatEvent.Thinking -> thinking.append(event.text)
                        ChatEvent.ModelLoading -> _state.update {
                            it.copy(notice = Notice("Loading $model into memory…", Notice.Severity.INFO))
                        }
                        is ChatEvent.Completed -> { stats = event.stats; _state.update { s -> s.copy(notice = null) } }
                        is ChatEvent.Failed -> failure = event.error
                    }
                }
            finish(assistantId, conversationId, userMessageId, buffer.toString(), thinking.toString(), stats, failure)
        } catch (e: CancellationException) {
            finish(
                assistantId, conversationId, userMessageId,
                buffer.toString(), thinking.toString(),
                stats = null, failure = null, stopped = true,
            )
            throw e
        }
    }

    private suspend fun finish(
        messageId: Long,
        conversationId: Long,
        userMessageId: Long?,
        body: String,
        thinking: String,
        stats: GenerationStats?,
        failure: LlmError?,
        stopped: Boolean = false,
    ) {
        val unreachable = failure != null && failure.isConnectivity()

        if (unreachable && body.isBlank()) {
            // Nothing was delivered. Put the user's message back in the queue so
            // it goes out later, and leave no empty reply behind.
            chatRepository.discardMessage(messageId)
            userMessageId?.let { chatRepository.markPending(it) }
        } else if (body.isBlank()) {
            chatRepository.discardMessage(messageId)
            userMessageId?.let { chatRepository.markDelivered(it) }
        } else {
            chatRepository.updateStreamingBody(messageId, body, thinking.takeIf { it.isNotEmpty() })
            chatRepository.finishAssistantMessage(
                messageId = messageId,
                conversationId = conversationId,
                content = body,
                status = if (failure != null || stopped) MessageStatus.INCOMPLETE else MessageStatus.COMPLETE,
                errorCode = failure?.let { it::class.simpleName },
                stats = stats,
            )
            userMessageId?.let { chatRepository.markDelivered(it) }
        }

        _state.update {
            it.copy(
                streamingMessageId = null,
                streamingText = "",
                isGenerating = false,
                notice = when {
                    failure != null -> failure.toNotice(hasPartialOutput = body.isNotBlank())
                    stopped -> Notice("Stopped.", Notice.Severity.INFO, NoticeAction.CONTINUE)
                    else -> null
                },
            )
        }

        if (unreachable) goOffline(failure)
    }

    private companion object {
        const val UI_PUBLISH_INTERVAL_MILLIS = 30L
        const val DB_WRITE_INTERVAL_MILLIS = 500L
        val uptime = TimeSource.Monotonic.markNow()
    }
}

/** Errors that mean the server is not there, as opposed to it refusing this request. */
internal fun LlmError.isConnectivity(): Boolean =
    this == LlmError.Unreachable || this == LlmError.ConnectionRefused

/**
 * Each failure gets the one action most likely to fix it. A dropped stream
 * offers Continue rather than Retry, because the reply so far is worth keeping
 * and starting over would throw it away.
 */
internal fun LlmError.toNotice(hasPartialOutput: Boolean): Notice = when (this) {
    LlmError.Unreachable ->
        Notice(describe(), Notice.Severity.ERROR, NoticeAction.RETRY)
    LlmError.ConnectionRefused ->
        Notice(describe(), Notice.Severity.ERROR, NoticeAction.RETRY)
    is LlmError.ModelNotFound ->
        Notice(describe(), Notice.Severity.ERROR, NoticeAction.OPEN_SETTINGS)
    LlmError.StreamInterrupted ->
        Notice(
            describe(),
            Notice.Severity.ERROR,
            if (hasPartialOutput) NoticeAction.CONTINUE else NoticeAction.RETRY,
        )
    is LlmError.Server -> Notice(describe(), Notice.Severity.ERROR, NoticeAction.RETRY)
    is LlmError.Unknown -> Notice(describe(), Notice.Severity.ERROR, NoticeAction.RETRY)
}

internal fun LlmError.describe(): String = when (this) {
    LlmError.Unreachable ->
        "Nothing answered at that address. If it is the MacBook, it is probably asleep."
    LlmError.ConnectionRefused ->
        "That machine answered, but Ollama is not running on that port."
    is LlmError.ModelNotFound ->
        "That server does not have \"$model\" pulled."
    LlmError.StreamInterrupted ->
        "The connection dropped mid-reply. What arrived is kept below."
    is LlmError.Server ->
        "The server returned $code."
    is LlmError.Unknown ->
        "Unexpected failure: ${cause.message ?: cause::class.simpleName}"
}
