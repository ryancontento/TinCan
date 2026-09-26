package io.github.ryancontento.tincan.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ryancontento.tincan.attach.Attachment
import io.github.ryancontento.tincan.attach.PickedFile
import io.github.ryancontento.tincan.attach.classify
import io.github.ryancontento.tincan.data.ChatRepository
import io.github.ryancontento.tincan.data.ImageAttachment
import io.github.ryancontento.tincan.data.db.AttachmentEntity
import io.github.ryancontento.tincan.data.ServerKey
import io.github.ryancontento.tincan.data.SettingsRepository
import io.github.ryancontento.tincan.data.TinCanSettings
import io.github.ryancontento.tincan.data.db.ConversationEntity
import io.github.ryancontento.tincan.data.db.MessageEntity
import io.github.ryancontento.tincan.data.db.MessageRole
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
import io.github.ryancontento.tincan.llm.ModelInfo
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
import kotlin.time.TimeSource

data class ChatUiState(
    val conversations: List<ConversationEntity> = emptyList(),
    val activeConversationId: Long? = null,
    val messages: List<MessageEntity> = emptyList(),
    /** In-flight reply, overlaid on its own row so appends recompose one bubble. */
    val streamingMessageId: Long? = null,
    val streamingText: String = "",
    val streamingThinking: String = "",
    val isGenerating: Boolean = false,
    val availableModels: List<ModelInfo> = emptyList(),
    val settings: TinCanSettings = TinCanSettings(),
    val notice: Notice? = null,
    val connection: ConnectionState = ConnectionState.UNKNOWN,
    /** Messages composed while unreachable, awaiting delivery. */
    val queuedCount: Int = 0,
    val context: ContextPlan? = null,
    val search: SearchState = SearchState(),
    /** Set once when a search hit is opened, cleared as soon as it is scrolled to. */
    val scrollToMessageId: Long? = null,
    /** Images waiting in the composer for the next send. */
    val draftImages: List<DraftImage> = emptyList(),
    /** Images already sent in the active conversation, by message. */
    val attachments: Map<Long, List<AttachmentEntity>> = emptyMap(),
) {
    /** False only when the server says so; older servers report no capabilities, so it is unknown. */
    val activeModelSeesImages: Boolean
        get() = availableModels.firstOrNull { it.id == activeModel }
            ?.let { it.capabilities.isEmpty() || it.supportsImages } ?: true

    val canContinue: Boolean
        get() = !isGenerating && messages.lastOrNull()
            ?.let { it.role == MessageRole.ASSISTANT && it.status == MessageStatus.INCOMPLETE } == true

    val activeConversation: ConversationEntity?
        get() = conversations.firstOrNull { it.id == activeConversationId }

    /** What the next send will actually use, which is not always the global choice. */
    val activeModel: String?
        get() = resolveModel(activeConversation?.defaultModelId, settings.selectedModel)

    /** Regenerating replaces the last reply, so there has to be one and nothing in flight. */
    val canRegenerate: Boolean
        get() = !isGenerating && messages.lastOrNull()?.role == MessageRole.ASSISTANT
}

class DraftImage(val name: String, val image: ImageAttachment)

data class SearchState(val term: String = "", val hits: List<SearchHit> = emptyList()) {
    val active: Boolean get() = term.isNotBlank()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository,
    private val backends: LlmBackendProvider,
    reconnectPollMillis: Long = 5_000,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

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
            // Rows still STREAMING belong to a dead process; demote before the UI sees them.
            chatRepository.recoverInterruptedMessages()
            chatRepository.redactStoredServerAddresses(settingsRepository::serverKeyFor)
        }

        observeSettings()
        observeConversations()
        observeActiveMessages()
        observeAttachments()
        observeQueue()
        observeConnection()
    }

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
                // The field saves per keystroke; wait so half-typed hostnames are not probed.
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
            // Resume where the app left off, but only once. Every later emission
            // also arrives with no selection right after New conversation, and
            // restoring then would silently drag the user back to the old thread.
            if (!restoredInitialSelection) {
                restoredInitialSelection = true
                conversations.firstOrNull()?.let { select(it.id) }
            }
        }
    }

    /**
     * [activeId] is the authoritative selection; the copy in the state is for
     * display. They are published together so the screen can never show one
     * conversation's header above another's transcript.
     */
    private fun observeActiveMessages() = viewModelScope.launch {
        activeId
            .flatMapLatest { id ->
                if (id == null) flowOf(null to emptyList()) else chatRepository.observeMessages(id).map { id to it }
            }
            .collect { (id, messages) ->
                _state.update { it.copy(activeConversationId = id, messages = messages) }
            }
    }

    /** Separate from messages: the attachments table stays quiet while a reply streams. */
    private fun observeAttachments() = viewModelScope.launch {
        activeId
            .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chatRepository.observeAttachments(id) }
            .collect { rows -> _state.update { it.copy(attachments = rows.groupBy { row -> row.messageId }) } }
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
                        status == ConnectionState.OFFLINE ->
                            connection.error.value?.toNotice(hasPartialOutput = false) ?: state.notice
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

    fun checkConnection() = connection.check()

    /** The first showing is covered by the connection coming online; this catches returns. */
    fun onShown() {
        if (connection.state.value == ConnectionState.ONLINE) refreshModels()
    }

    fun select(conversationId: Long) {
        if (_state.value.isGenerating) return   // switching mid-stream would orphan the reply
        activeId.value = conversationId
        _state.update { it.copy(notice = null) }
    }

    fun newConversation() {
        if (_state.value.isGenerating) return
        activeId.value = null
        _state.update { it.copy(notice = null) }
    }

    fun deleteConversation(id: Long) = viewModelScope.launch {
        chatRepository.deleteConversation(id)
        if (activeId.value == id) activeId.value = null
    }

    /** Not mid-reply: the stream would keep writing into a thread now pointed elsewhere. */
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
                _state.update {
                    it.copy(
                        availableModels = models,
                        notice = if (models.isEmpty()) {
                            Notice("Connected, but that server has no models pulled.", Notice.Severity.INFO)
                        } else {
                            it.notice
                        },
                    )
                }
                val current = settings.selectedModel
                if (models.isNotEmpty() && (current == null || models.none { m -> m.id == current })) {
                    settingsRepository.setSelectedModel(models.first().id)
                }
            }
            .onFailure { error -> (error as? OllamaException)?.error?.let(connection::reportUnreachable) }
    }

    /** False when nothing was sent, so the composer keeps what the user typed. */
    fun send(text: String): Boolean {
        val body = text.trim()
        val state = _state.value
        val settings = state.settings
        val model = state.activeModel ?: return false
        val images = state.draftImages.map { it.image }
        if ((body.isEmpty() && images.isEmpty()) || state.isGenerating) return false
        if (images.isNotEmpty() && !state.activeModelSeesImages) {
            _state.update {
                it.copy(notice = Notice("$model can't read images. Pick a vision model, or remove the image.", Notice.Severity.ERROR))
            }
            return false
        }
        _state.update { it.copy(draftImages = emptyList()) }

        viewModelScope.launch {
            val serverKey = settingsRepository.serverKeyFor(settings.serverUrl)
            val conversationId = ensureConversation(model, settings, serverKey)
            val userMessageId = chatRepository.appendUserMessage(conversationId, body, serverKey, images)
            chatRepository.titleFromFirstMessageIfUnset(conversationId, body.ifEmpty { "Image" })

            if (connection.isOffline) {
                // Already known down, so skip the timeout. It sends itself on reconnect.
                chatRepository.markPending(userMessageId)
                _state.update {
                    it.copy(notice = Notice("Saved. It will send when the server is back.", Notice.Severity.INFO))
                }
                return@launch
            }
            generation = launch { generate(conversationId, model, settings, userMessageId) }
        }
        return true
    }

    /**
     * Images join the draft; text files come back as blocks for the composer to insert,
     * so the user sees exactly what will be sent. Anything else is explained, not dropped silently.
     */
    fun attach(files: List<PickedFile>): List<String> {
        val results = files.map(::classify)
        val images = results.filterIsInstance<Attachment.Image>().map { DraftImage(it.name, it.image) }
        val rejected = results.filterIsInstance<Attachment.Rejected>().map { it.reason }
        _state.update {
            it.copy(
                draftImages = it.draftImages + images,
                notice = if (rejected.isEmpty()) it.notice else Notice("Not attached: ${rejected.joinToString("; ")}.", Notice.Severity.INFO),
            )
        }
        return results.filterIsInstance<Attachment.Text>().map { it.block }
    }

    fun removeDraftImage(index: Int) = _state.update {
        it.copy(draftImages = it.draftImages.filterIndexed { i, _ -> i != index })
    }

    /** Sends the queued message in the active conversation, if there is one. */
    fun deliverQueued() {
        val settings = _state.value.settings
        val model = _state.value.activeModel ?: return
        val conversationId = activeId.value ?: return
        if (_state.value.isGenerating) return

        viewModelScope.launch {
            val pending = chatRepository.oldestPendingMessage(conversationId) ?: return@launch
            generation = launch { generate(conversationId, model, settings, pending.id) }
        }
    }

    /**
     * Resumes a truncated reply. Ollama continues an assistant turn when it is
     * last in the history, so output appends to the same row.
     */
    fun continueReply() {
        val settings = _state.value.settings
        val model = _state.value.activeModel ?: return
        val conversationId = activeId.value ?: return
        if (_state.value.isGenerating) return

        viewModelScope.launch {
            val partial = chatRepository.resumableReply(conversationId) ?: return@launch
            chatRepository.resumeAssistantMessage(partial.id)
            generation = launch {
                generate(conversationId, model, settings, null, partial.id, partial.content)
            }
        }
    }

    /**
     * Throws the last reply away and asks again. Deleting it first is what makes
     * this a retry rather than a continuation — the model must not be shown the
     * answer it is being asked to replace.
     */
    fun regenerateLastReply() {
        val settings = _state.value.settings
        val model = _state.value.activeModel ?: return
        val conversationId = activeId.value ?: return
        if (_state.value.isGenerating) return

        viewModelScope.launch {
            val previous = chatRepository.lastAssistantMessage(conversationId) ?: return@launch
            chatRepository.discardMessage(previous.id)
            generation = launch {
                try {
                    generate(conversationId, model, settings, userMessageId = null)
                } finally {
                    // Deleting first is what stops the model seeing the answer it
                    // is replacing, but it also puts that answer at risk for the
                    // length of the call. If nothing came back, put it back.
                    // NonCancellable because Stop cancels this scope too.
                    withContext(NonCancellable) {
                        if (chatRepository.lastAssistantMessage(conversationId) == null) {
                            chatRepository.restoreMessage(previous)
                        }
                    }
                }
            }
        }
    }

    /**
     * Replaces a question and everything that followed it. The turns after the
     * edit answered a question that no longer exists, so they go too.
     */
    fun editAndResend(messageId: Long, text: String) {
        val body = text.trim()
        val settings = _state.value.settings
        val model = _state.value.activeModel ?: return
        val conversationId = activeId.value ?: return
        // Editing the words keeps the images; the rewrite deletes the row they hang off.
        val images = _state.value.attachments[messageId].orEmpty().map { ImageAttachment(it.mimeType, it.bytes) }
        if ((body.isEmpty() && images.isEmpty()) || _state.value.isGenerating) return

        viewModelScope.launch {
            val serverKey = settingsRepository.serverKeyFor(settings.serverUrl)
            chatRepository.truncateFrom(conversationId, messageId)
            val userMessageId = chatRepository.appendUserMessage(conversationId, body, serverKey, images)

            if (connection.isOffline) {
                chatRepository.markPending(userMessageId)
                _state.update {
                    it.copy(notice = Notice("Saved. It will send when the server is back.", Notice.Severity.INFO))
                }
                return@launch
            }
            generation = launch { generate(conversationId, model, settings, userMessageId) }
        }
    }

    fun renameConversation(id: Long, title: String) = viewModelScope.launch {
        chatRepository.renameConversation(id, title)
    }

    /** Null hands the conversation back to the global default. */
    fun setConversationSystemPrompt(id: Long, prompt: String?) = viewModelScope.launch {
        chatRepository.setSystemPrompt(id, prompt)
    }

    /** Blank or invalid text hands that setting back to the global value. */
    fun setConversationOptions(id: Long, temperature: String, numCtx: String) = viewModelScope.launch {
        chatRepository.setGenerationOptions(id, parseTemperature(temperature), parseNumCtx(numCtx))
    }

    fun setPinned(id: Long, pinned: Boolean) = viewModelScope.launch {
        chatRepository.setPinned(id, pinned)
    }

    fun search(term: String) = viewModelScope.launch {
        _state.update { it.copy(search = it.search.copy(term = term)) }
        val hits = chatRepository.search(term)
        // Discard a result that arrived after the user kept typing.
        if (_state.value.search.term == term) {
            _state.update { it.copy(search = it.search.copy(hits = hits)) }
        }
    }

    /** Written when the drag ends, not per pixel: dragging fires continuously. */
    fun setSidebarWidth(dp: Int) = viewModelScope.launch { settingsRepository.setSidebarWidth(dp) }

    /** Opens the conversation a hit belongs to and asks the transcript to scroll to it. */
    fun openSearchHit(hit: SearchHit) {
        if (_state.value.isGenerating) return
        select(hit.conversationId)
        _state.update { it.copy(scrollToMessageId = hit.messageId) }
    }

    fun scrolledToTarget() = _state.update { it.copy(scrollToMessageId = null) }

    /** Builds the file; writing it is the platform layer's job. */
    suspend fun buildExport(format: ExportFormat): ExportDocument? {
        val id = activeId.value ?: return null
        val conversation = chatRepository.conversation(id) ?: return null
        return exportConversation(conversation, chatRepository.messages(id), format, chatRepository.attachments(id))
    }

    /** A write failure (read-only folder, full disk) becomes a notice rather than a crash. */
    fun export(format: ExportFormat, saver: FileSaver) = viewModelScope.launch {
        val document = buildExport(format) ?: return@launch
        val notice = try {
            // Null path means the save dialog was dismissed, which is not worth a notice.
            saver.save(document)?.let { Notice("Exported to $it", Notice.Severity.INFO) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Notice("Could not export: ${e.message ?: e::class.simpleName}", Notice.Severity.ERROR)
        }
        notice?.let { n -> _state.update { it.copy(notice = n) } }
    }

    fun stop() {
        generation?.cancel()
        generation = null
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private suspend fun ensureConversation(
        model: String,
        settings: TinCanSettings,
        serverKey: ServerKey,
    ): Long =
        activeId.value ?: chatRepository.createConversation(
            serverKey = serverKey,
            defaultModelId = model,
            // Snapshotted, not referenced: changing the default later must not
            // rewrite how this conversation behaves.
            systemPrompt = settings.systemPrompt,
        ).also { activeId.value = it }

    private suspend fun generate(
        conversationId: Long,
        model: String,
        settings: TinCanSettings,
        userMessageId: Long?,
        resumeMessageId: Long? = null,
        resumePrefix: String = "",
    ) {
        // Read from the row, not from settings: the conversation owns its prompt and overrides.
        val conversation = chatRepository.conversation(conversationId)
        val systemPrompt = resolveSystemPrompt(
            conversationPrompt = conversation?.systemPrompt,
            globalPrompt = settings.systemPrompt,
        )
        val options = resolveOptions(conversation, settings)

        // Trim locally: Ollama drops old turns at num_ctx without telling the client.
        val plan = planContext(
            messages = chatRepository.historyFor(conversationId),
            systemPrompt = systemPrompt,
            budgetTokens = options.numCtx,
        )
        _state.update { it.copy(context = plan) }

        val assistantId = resumeMessageId
            ?: chatRepository.beginAssistantMessage(
                conversationId,
                model,
                settingsRepository.serverKeyFor(settings.serverUrl),
            )

        _state.update {
            it.copy(
                streamingMessageId = assistantId,
                streamingText = resumePrefix,
                streamingThinking = "",
                isGenerating = true,
                notice = null,
            )
        }

        val sink = StreamSink(resumePrefix)
        var stats: GenerationStats? = null
        var failure: LlmError? = null

        suspend fun publish(force: Boolean = false) {
            sink.publishIfDue(force)?.let { snapshot ->
                _state.update { it.copy(streamingText = snapshot.text, streamingThinking = snapshot.thinking) }
            }
            sink.persistIfDue(force)?.let { snapshot ->
                chatRepository.updateStreamingBody(assistantId, snapshot.text, snapshot.thinking.ifEmpty { null })
            }
        }

        val request = ChatRequest(
            model = model,
            messages = plan.messages,
            systemPrompt = systemPrompt,
            options = options,
        )

        try {
            backends.create(settings.serverUrl, modelLoadingThresholdMillis = settings.modelLoadingThresholdMillis)
                .chat(request)
                .collect { event ->
                    when (event) {
                        is ChatEvent.Token -> { sink.appendText(event.text); publish() }
                        is ChatEvent.Thinking -> { sink.appendThinking(event.text); publish() }
                        ChatEvent.ModelLoading -> _state.update {
                            it.copy(notice = Notice("Loading $model into memory…", Notice.Severity.INFO))
                        }
                        is ChatEvent.Completed -> stats = event.stats
                        is ChatEvent.Failed -> failure = event.error
                    }
                }
            publish(force = true)
            finish(assistantId, conversationId, userMessageId, sink, stats, failure, plan)
        } catch (e: CancellationException) {
            publish(force = true)
            finish(assistantId, conversationId, userMessageId, sink, null, null, plan, stopped = true)
            throw e
        }
    }

    private suspend fun finish(
        messageId: Long,
        conversationId: Long,
        userMessageId: Long?,
        sink: StreamSink,
        stats: GenerationStats?,
        failure: LlmError?,
        plan: ContextPlan,
        stopped: Boolean = false,
    ) {
        val body = sink.text
        val unreachable = failure?.isConnectivity() == true

        when {
            body.isBlank() -> {
                chatRepository.discardMessage(messageId)
                // Requeue the question only if it never reached the server.
                userMessageId?.let {
                    if (unreachable) chatRepository.markPending(it) else chatRepository.markDelivered(it)
                }
            }
            else -> {
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
        }

        _state.update {
            it.copy(
                streamingMessageId = null,
                streamingText = "",
                streamingThinking = "",
                isGenerating = false,
                notice = when {
                    failure != null -> failure.toNotice(hasPartialOutput = body.isNotBlank())
                    stopped -> Notice("Stopped.", Notice.Severity.INFO, NoticeAction.CONTINUE)
                    plan.trimmed -> Notice(
                        "Trimmed the oldest ${plan.droppedCount} message(s) to fit the context window.",
                        Notice.Severity.INFO,
                        NoticeAction.OPEN_SETTINGS,
                    )
                    else -> null
                },
            )
        }

        if (unreachable) failure?.let(connection::reportUnreachable)
    }
}

/**
 * Accumulates a streaming reply and rations updates: the screen refreshes far
 * more often than the disk, so a crash loses at most a fraction of a second.
 */
private class StreamSink(prefix: String) {
    private val body = StringBuilder(prefix)
    private val reasoning = StringBuilder()
    private var lastPublish = 0L
    private var lastPersist = 0L

    val text: String get() = body.toString()

    fun appendText(chunk: String) = body.append(chunk).let { }
    fun appendThinking(chunk: String) = reasoning.append(chunk).let { }

    fun publishIfDue(force: Boolean): Snapshot? = snapshotIfDue(force, lastPublish, UI_INTERVAL_MILLIS)
        ?.also { lastPublish = now() }

    fun persistIfDue(force: Boolean): Snapshot? = snapshotIfDue(force, lastPersist, DB_INTERVAL_MILLIS)
        ?.also { lastPersist = now() }

    private fun snapshotIfDue(force: Boolean, last: Long, interval: Long): Snapshot? =
        if (force || now() - last >= interval) Snapshot(body.toString(), reasoning.toString()) else null

    data class Snapshot(val text: String, val thinking: String)

    private companion object {
        const val UI_INTERVAL_MILLIS = 30L
        const val DB_INTERVAL_MILLIS = 500L
        val uptime = TimeSource.Monotonic.markNow()
        fun now() = uptime.elapsedNow().inWholeMilliseconds
    }
}

private const val ADDRESS_SETTLE_MILLIS = 800L
