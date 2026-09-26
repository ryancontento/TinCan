package io.github.ryancontento.tincan.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ryancontento.tincan.attach.Attachment
import io.github.ryancontento.tincan.attach.PickedFile
import io.github.ryancontento.tincan.attach.classify
import io.github.ryancontento.tincan.data.ChatRepository
import io.github.ryancontento.tincan.data.ImageAttachment
import io.github.ryancontento.tincan.data.SettingsRepository
import io.github.ryancontento.tincan.data.TinCanSettings
import io.github.ryancontento.tincan.data.db.MessageEntity
import io.github.ryancontento.tincan.data.db.MessageStatus
import io.github.ryancontento.tincan.data.db.SearchHit
import io.github.ryancontento.tincan.export.ExportDocument
import io.github.ryancontento.tincan.export.ExportFormat
import io.github.ryancontento.tincan.export.FileSaver
import io.github.ryancontento.tincan.export.exportConversation
import io.github.ryancontento.tincan.llm.ChatEvent
import io.github.ryancontento.tincan.llm.ChatRequest
import io.github.ryancontento.tincan.llm.ContextPlan
import io.github.ryancontento.tincan.llm.GenerationStats
import io.github.ryancontento.tincan.llm.LlmBackendProvider
import io.github.ryancontento.tincan.llm.LlmError
import io.github.ryancontento.tincan.llm.ollama.OllamaException
import io.github.ryancontento.tincan.llm.planContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository,
    private val backends: LlmBackendProvider,
    reconnectPollMillis: Long = 5_000,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** The real selection. State gets a copy published with its messages, so header and transcript never disagree. */
    private val activeId = MutableStateFlow<Long?>(null)
    private var generation: Job? = null
    private var pendingCheck: Job? = null
    private var restoredInitialSelection = false

    private val connection = ConnectionMonitor(
        scope = viewModelScope,
        backends = backends,
        serverUrl = { _state.value.settings.serverUrl },
        pollMillis = reconnectPollMillis,
    )

    init {
        viewModelScope.launch {
            chatRepository.recoverInterruptedMessages()
            chatRepository.redactStoredServerAddresses(settingsRepository::serverKeyFor)
        }
        observeSettings()
        observeConversations()
        observeActiveConversation()
        observeQueue()
        observeConnection()
    }

    // region Observing

    private fun observeSettings() = viewModelScope.launch {
        var initial = true
        settingsRepository.settings.collect { settings ->
            val urlChanged = settings.serverUrl != _state.value.settings.serverUrl
            _state.update { it.copy(settings = settings) }
            if (urlChanged) {
                connection.reset()
                _state.update { it.copy(availableModels = emptyList()) }
            }
            if (connection.state.value == ConnectionState.UNKNOWN) {
                pendingCheck?.cancel()
                // The address field saves per keystroke; waiting keeps half-typed hostnames from being probed.
                pendingCheck = if (urlChanged && !initial) {
                    viewModelScope.launch { delay(ADDRESS_SETTLE_MILLIS); connection.check() }
                } else {
                    connection.check()
                    null
                }
            }
            initial = false
        }
    }

    private fun observeConversations() = viewModelScope.launch {
        chatRepository.observeConversations().collect { conversations ->
            _state.update { it.copy(conversations = conversations) }
            // Once only: later emissions after New conversation would drag the user back.
            if (!restoredInitialSelection) {
                restoredInitialSelection = true
                conversations.firstOrNull()?.let { select(it.id) }
            }
        }
    }

    private fun observeActiveConversation() {
        viewModelScope.launch {
            activeId
                .flatMapLatest { id -> if (id == null) flowOf(null to emptyList()) else chatRepository.observeMessages(id).map { id to it } }
                .collect { (id, messages) -> _state.update { it.copy(activeConversationId = id, messages = messages) } }
        }
        // A separate query, so a streaming reply's frequent writes never re-read the image blobs.
        viewModelScope.launch {
            activeId
                .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chatRepository.observeAttachments(id) }
                .collect { rows -> _state.update { it.copy(attachments = rows.groupBy { row -> row.messageId }) } }
        }
    }

    private fun observeQueue() = viewModelScope.launch {
        chatRepository.observeConversationsWithPendingMessages().collect { ids ->
            _state.update { it.copy(queuedCount = ids.size) }
        }
    }

    private fun observeConnection() = viewModelScope.launch {
        connection.state.collect { status ->
            _state.update { state ->
                state.copy(
                    connection = status,
                    notice = when {
                        status == ConnectionState.OFFLINE -> connection.error.value?.toNotice(hasPartialOutput = false) ?: state.notice
                        // A stale error must not outlive the condition behind it.
                        status == ConnectionState.ONLINE && state.notice?.severity == Notice.Severity.ERROR -> null
                        else -> state.notice
                    },
                )
            }
            if (status == ConnectionState.ONLINE) {
                if (_state.value.availableModels.isEmpty()) refreshModels()
                deliverQueued()
            }
        }
    }

    // endregion

    // region Navigation and selection

    fun checkConnection() = connection.check()

    /** Catches a return from the Models screen; the first showing is covered by the connection coming online. */
    fun onShown() {
        if (connection.state.value == ConnectionState.ONLINE) refreshModels()
    }

    /** Refused mid-reply, which would orphan the stream. */
    fun select(conversationId: Long) {
        if (_state.value.isGenerating) return
        activeId.value = conversationId
        notify(null)
    }

    fun newConversation() {
        if (_state.value.isGenerating) return
        activeId.value = null
        notify(null)
    }

    fun deleteConversation(id: Long) = viewModelScope.launch {
        chatRepository.deleteConversation(id)
        if (activeId.value == id) activeId.value = null
    }

    fun selectServer(url: String) {
        if (_state.value.isGenerating) return
        viewModelScope.launch { settingsRepository.setServerUrl(url) }
    }

    fun selectModel(id: String) = viewModelScope.launch {
        settingsRepository.setSelectedModel(id)
        activeId.value?.let { chatRepository.setDefaultModel(it, id) }
    }

    fun refreshModels() = viewModelScope.launch {
        val settings = _state.value.settings
        backends.create(settings.serverUrl).listModels()
            .onSuccess { models ->
                if (models.isEmpty()) notify(Notice("Connected, but that server has no models pulled.", Notice.Severity.INFO))
                _state.update { it.copy(availableModels = models) }
                if (models.isNotEmpty() && models.none { it.id == settings.selectedModel }) {
                    settingsRepository.setSelectedModel(models.first().id)
                }
            }
            .onFailure { error -> (error as? OllamaException)?.error?.let(connection::reportUnreachable) }
    }

    fun openSearchHit(hit: SearchHit) {
        if (_state.value.isGenerating) return
        select(hit.conversationId)
        _state.update { it.copy(scrollToMessageId = hit.messageId) }
    }

    fun scrolledToTarget() = _state.update { it.copy(scrollToMessageId = null) }

    fun search(term: String) = viewModelScope.launch {
        _state.update { it.copy(search = it.search.copy(term = term)) }
        val hits = chatRepository.search(term)
        // Drop a result that arrived after the user kept typing.
        if (_state.value.search.term == term) _state.update { it.copy(search = it.search.copy(hits = hits)) }
    }

    // endregion

    // region Composing

    /** False when nothing was sent, so the composer keeps what the user typed. */
    fun send(text: String): Boolean {
        val body = text.trim()
        val state = _state.value
        val model = state.activeModel ?: return false
        val images = state.draftImages.map { it.image }
        if ((body.isEmpty() && images.isEmpty()) || state.isGenerating) return false
        if (images.isNotEmpty() && !state.activeModelSeesImages) {
            notify(Notice("$model can't read images. Pick a vision model, or remove the image.", Notice.Severity.ERROR))
            return false
        }
        _state.update { it.copy(draftImages = emptyList()) }

        viewModelScope.launch {
            val settings = state.settings
            val serverKey = settingsRepository.serverKeyFor(settings.serverUrl)
            val conversationId = activeId.value ?: chatRepository.createConversation(
                serverKey = serverKey,
                defaultModelId = model,
                // A copy, so changing the default later cannot rewrite this conversation.
                systemPrompt = settings.systemPrompt,
            ).also { activeId.value = it }
            val questionId = chatRepository.appendUserMessage(conversationId, body, serverKey, images)
            chatRepository.titleFromFirstMessageIfUnset(conversationId, body.ifEmpty { "Image" })
            sendOrQueue(Turn(conversationId, model, settings), questionId)
        }
        return true
    }

    /** Images join the draft; text files come back as blocks for the composer; the rest is explained. */
    fun attach(files: List<PickedFile>): List<String> {
        val results = files.map(::classify)
        val rejected = results.filterIsInstance<Attachment.Rejected>().map { it.reason }
        _state.update { state ->
            state.copy(draftImages = state.draftImages + results.filterIsInstance<Attachment.Image>().map { DraftImage(it.name, it.image) })
        }
        if (rejected.isNotEmpty()) notify(Notice("Not attached: ${rejected.joinToString("; ")}.", Notice.Severity.INFO))
        return results.filterIsInstance<Attachment.Text>().map { it.block }
    }

    fun removeDraftImage(index: Int) = _state.update {
        it.copy(draftImages = it.draftImages.filterIndexed { i, _ -> i != index })
    }

    /** Replaces a question and every turn after it, since they answered something no longer asked. */
    fun editAndResend(messageId: Long, text: String) {
        val turn = nextTurn() ?: return
        val body = text.trim()
        // The images hang off the row the rewrite deletes, so they are carried across.
        val images = _state.value.attachments[messageId].orEmpty().map { ImageAttachment(it.mimeType, it.bytes) }
        if (body.isEmpty() && images.isEmpty()) return

        viewModelScope.launch {
            chatRepository.truncateFrom(turn.conversationId, messageId)
            val serverKey = settingsRepository.serverKeyFor(turn.settings.serverUrl)
            sendOrQueue(turn, chatRepository.appendUserMessage(turn.conversationId, body, serverKey, images))
        }
    }

    // endregion

    // region Replies

    fun deliverQueued() {
        val turn = nextTurn() ?: return
        viewModelScope.launch {
            val pending = chatRepository.oldestPendingMessage(turn.conversationId) ?: return@launch
            startGeneration(turn, pending.id)
        }
    }

    /** Ollama continues an assistant turn that is last in the history, so output appends to the same row. */
    fun continueReply() {
        val turn = nextTurn() ?: return
        viewModelScope.launch {
            val partial = chatRepository.resumableReply(turn.conversationId) ?: return@launch
            chatRepository.resumeAssistantMessage(partial.id)
            startGeneration(turn, questionId = null, resume = partial)
        }
    }

    /**
     * Deletes the last reply first, so the model is not shown the answer it is replacing, and
     * puts it back if nothing new arrives.
     */
    fun regenerateLastReply() {
        val turn = nextTurn() ?: return
        viewModelScope.launch {
            val previous = chatRepository.lastAssistantMessage(turn.conversationId) ?: return@launch
            chatRepository.discardMessage(previous.id)
            generation = viewModelScope.launch {
                try {
                    generate(turn, questionId = null)
                } finally {
                    withContext(NonCancellable) {
                        if (chatRepository.lastAssistantMessage(turn.conversationId) == null) chatRepository.restoreMessage(previous)
                    }
                }
            }
        }
    }

    fun stop() {
        generation?.cancel()
        generation = null
    }

    // endregion

    // region Conversation settings

    fun renameConversation(id: Long, title: String) = viewModelScope.launch { chatRepository.renameConversation(id, title) }

    /** Null hands the conversation back to the global default. */
    fun setConversationSystemPrompt(id: Long, prompt: String?) = viewModelScope.launch { chatRepository.setSystemPrompt(id, prompt) }

    /** Blank or invalid text hands that option back to the global value. */
    fun setConversationOptions(id: Long, temperature: String, numCtx: String) = viewModelScope.launch {
        chatRepository.setGenerationOptions(id, parseTemperature(temperature), parseNumCtx(numCtx))
    }

    fun setPinned(id: Long, pinned: Boolean) = viewModelScope.launch { chatRepository.setPinned(id, pinned) }

    /** Saved when a drag ends, not per pixel. */
    fun setSidebarWidth(dp: Int) = viewModelScope.launch { settingsRepository.setSidebarWidth(dp) }

    // endregion

    // region Export and notices

    suspend fun buildExport(format: ExportFormat): ExportDocument? {
        val id = activeId.value ?: return null
        val conversation = chatRepository.conversation(id) ?: return null
        return exportConversation(conversation, chatRepository.messages(id), format, chatRepository.attachments(id))
    }

    /** A failed write becomes a notice, not a crash. A dismissed dialog is not worth one. */
    fun export(format: ExportFormat, saver: FileSaver) = viewModelScope.launch {
        val document = buildExport(format) ?: return@launch
        try {
            saver.save(document)?.let { notify(Notice("Exported to $it", Notice.Severity.INFO)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notify(Notice("Could not export: ${e.message ?: e::class.simpleName}", Notice.Severity.ERROR))
        }
    }

    fun dismissNotice() = notify(null)

    private fun notify(notice: Notice?) = _state.update { it.copy(notice = notice) }

    // endregion

    // region Generation

    /** Everything a request to the model needs. */
    private data class Turn(val conversationId: Long, val model: String, val settings: TinCanSettings)

    /** Null while a reply is already streaming, or with no conversation or model to use. */
    private fun nextTurn(): Turn? {
        val state = _state.value
        if (state.isGenerating) return null
        return Turn(activeId.value ?: return null, state.activeModel ?: return null, state.settings)
    }

    /** Already known to be down: queue without waiting out a timeout, and it sends itself on reconnect. */
    private suspend fun sendOrQueue(turn: Turn, questionId: Long) {
        if (connection.isOffline) {
            chatRepository.markPending(questionId)
            notify(Notice("Saved. It will send when the server is back.", Notice.Severity.INFO))
        } else {
            startGeneration(turn, questionId)
        }
    }

    private fun startGeneration(turn: Turn, questionId: Long?, resume: MessageEntity? = null) {
        generation = viewModelScope.launch { generate(turn, questionId, resume) }
    }

    private sealed interface Outcome {
        data class Completed(val stats: GenerationStats?) : Outcome
        data class Failed(val error: LlmError) : Outcome
        data object Stopped : Outcome
    }

    private suspend fun generate(turn: Turn, questionId: Long?, resume: MessageEntity? = null) {
        val (conversationId, model, settings) = turn
        // The conversation row owns its prompt and overrides, not the live settings.
        val conversation = chatRepository.conversation(conversationId)
        val systemPrompt = resolveSystemPrompt(conversation?.systemPrompt, settings.systemPrompt)
        val options = resolveOptions(conversation, settings)
        // Trimmed here because Ollama drops old turns at num_ctx without saying so.
        val plan = planContext(chatRepository.historyFor(conversationId), systemPrompt, options.numCtx)

        val replyId = resume?.id
            ?: chatRepository.beginAssistantMessage(conversationId, model, settingsRepository.serverKeyFor(settings.serverUrl))
        val sink = StreamSink(resume?.content.orEmpty())
        _state.update {
            it.copy(
                context = plan,
                streamingMessageId = replyId,
                streamingText = sink.text,
                streamingThinking = "",
                isGenerating = true,
                notice = null,
            )
        }

        suspend fun flush(force: Boolean = false) {
            sink.publishIfDue(force)?.let { s -> _state.update { it.copy(streamingText = s.text, streamingThinking = s.thinking) } }
            sink.persistIfDue(force)?.let { s -> chatRepository.updateStreamingBody(replyId, s.text, s.thinking.ifEmpty { null }) }
        }

        var outcome: Outcome = Outcome.Completed(stats = null)
        try {
            backends.create(settings.serverUrl, modelLoadingThresholdMillis = settings.modelLoadingThresholdMillis)
                .chat(ChatRequest(model, plan.messages, systemPrompt, options))
                .collect { event ->
                    when (event) {
                        is ChatEvent.Token -> { sink.appendText(event.text); flush() }
                        is ChatEvent.Thinking -> { sink.appendThinking(event.text); flush() }
                        ChatEvent.ModelLoading -> notify(Notice("Loading $model into memory…", Notice.Severity.INFO))
                        is ChatEvent.Completed -> outcome = Outcome.Completed(event.stats)
                        is ChatEvent.Failed -> outcome = Outcome.Failed(event.error)
                    }
                }
        } catch (e: CancellationException) {
            outcome = Outcome.Stopped
            throw e
        } finally {
            // Stop cancels this coroutine; the partial reply still has to be saved and closed off.
            withContext(NonCancellable) {
                flush(force = true)
                finish(conversationId, replyId, questionId, sink.text, outcome, plan)
            }
        }
    }

    private suspend fun finish(
        conversationId: Long,
        replyId: Long,
        questionId: Long?,
        text: String,
        outcome: Outcome,
        plan: ContextPlan,
    ) {
        val failure = (outcome as? Outcome.Failed)?.error
        val neverArrived = failure?.isConnectivity() == true

        if (text.isBlank()) {
            // No empty bubble; and the question is requeued only if it never reached the server.
            chatRepository.discardMessage(replyId)
            questionId?.let { if (neverArrived) chatRepository.markPending(it) else chatRepository.markDelivered(it) }
        } else {
            chatRepository.finishAssistantMessage(
                messageId = replyId,
                conversationId = conversationId,
                content = text,
                status = if (outcome is Outcome.Completed) MessageStatus.COMPLETE else MessageStatus.INCOMPLETE,
                errorCode = failure?.let { it::class.simpleName },
                stats = (outcome as? Outcome.Completed)?.stats,
            )
            questionId?.let { chatRepository.markDelivered(it) }
        }

        _state.update {
            it.copy(
                streamingMessageId = null,
                streamingText = "",
                streamingThinking = "",
                isGenerating = false,
                notice = when {
                    failure != null -> failure.toNotice(hasPartialOutput = text.isNotBlank())
                    outcome == Outcome.Stopped -> Notice("Stopped.", Notice.Severity.INFO, NoticeAction.CONTINUE)
                    plan.trimmed -> Notice(
                        "Trimmed the oldest ${plan.droppedCount} message(s) to fit the context window.",
                        Notice.Severity.INFO,
                        NoticeAction.OPEN_SETTINGS,
                    )
                    else -> null
                },
            )
        }
        if (neverArrived) failure?.let(connection::reportUnreachable)
    }

    // endregion
}

private const val ADDRESS_SETTLE_MILLIS = 800L
